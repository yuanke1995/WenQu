package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.models.ModelInfo;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Token 用量观测中间件（对应参考实现 {@code agents/middlewares/token_usage.py}）。
 *
 * <p>每次主模型调用后估算近似上下文占用，并持久化 Provider 返回的实际
 * usage_metadata，按配置模型分桶累计到 Run 与 Thread 两级；快照写入
 * LangGraph state 供状态面板读取，便于展示与核对真实账单。
 *
 * <h3>承载点</h3>
 * <ul>
 *   <li>{@code before_agent}（Run 入口重置 Run 级用量、剔除已禁用 Provider 历史桶）→
 *       挂到 {@code AgentHook.beforeAgent}（其返回的 {@code Map} 会被框架合并回 state）。</li>
 *   <li>{@code wrap_model_call}（每次模型调用后构建用量快照）→ {@code ModelInterceptor.interceptModel}。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li><b>usage 元数据来源</b>：参考实现从响应 {@code AIMessage.usage_metadata} 读
 *       {@code input_tokens/output_tokens/total_tokens/input_token_details/output_token_details}；
 *       Spring AI 的 usage 在 {@link ChatResponse#getMetadata()}{@code .getUsage()}（
 *       {@link Usage#getPromptTokens()} / {@link #getCompletionTokens()} / {@link #getTotalTokens()}），
 *       详细的 {@code input_token_details}（缓存命中明细）走 {@link Usage#getNativeUsage()}
 *       （Provider 原生对象）。见 {@link #usageFromResponse}。</li>
 *   <li>{@code request.state / request.messages / request.model / request.runtime} →
 *       本工程 {@link ModelRequest} 无这些字段；快照构建所需的 state / messages / model /
 *       run_id 等由调用方经 {@link ModelRequest#getContext()} 传入（键见 {@link #CONTEXT_KEY}）。
 *       context 中间件已在 request context 里放了 {@code context}，本类据此取 run_id 等。</li>
 *   <li>{@code model_cache.get_model_info} → {@link ModelProviderCache#getModelInfo}。</li>
 *   <li>{@code count_tokens_approximately} → 本工程近似计数 {@link #countTokensApproximately}。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>Command 状态写回</b>：参考实现 {@code wrap_model_call} 返回
 *       {@code ExtendedModelResponse(command=Command(update={"token_usage": snapshot}))}，
 *       由框架把快照写回 LangGraph state；本工程 {@code ModelInterceptor} 无此能力
 *       （interceptor 只返回 {@link ModelResponse}）。因此本类把快照构建抽成<b>纯函数</b>
 *       {@link #buildSnapshot}，由调用方（AgentHook.afterModel / 图节点）负责持久化到 state；
 *       {@code interceptModel} 会把快照放进 {@link ModelRequest#getContext()} 的
 *       {@link #SNAPSHOT_KEY}，供后续取用。</li>
 *   <li><b>同步链路</b>：只保留同步 {@code interceptModel}，等价于参考实现同步 {@code wrap_model_call}。</li>
 * </ol>
 */
public class TokenUsageMiddleware extends ModelInterceptor {

    /** request context 里承载 {@code BaseContext} 的键（与 {@code ContextAwareInterceptor} 一致）。 */
    public static final String CONTEXT_KEY = "context";

    /** request context 里本类写入最新快照的键（供调用方持久化到 state）。 */
    public static final String SNAPSHOT_KEY = "__token_usage_snapshot__";

    /** 不采集用量、且要剔除历史用量桶的 Provider（与参考实现逐字一致）。 */
    public static final Set<String> TOKEN_USAGE_PROVIDER_BLACKLIST = Set.of("siliconflow-cn", "siliconflow");

    public static final Map<String, Integer> ZERO_TOTAL = Map.of("input_tokens", 0, "output_tokens", 0, "total_tokens", 0);

    /** 快照里保留的上下文估算字段（与参考实现逐字一致）。 */
    static final Set<String> TOKEN_USAGE_CONTEXT_FIELDS = Set.of(
            "state_message_count", "state_message_count_before_call",
            "state_messages_tokens", "state_messages_tokens_before_call",
            "llm_message_count", "llm_messages_tokens",
            "llm_content_message_count", "llm_content_message_tokens",
            "llm_tool_message_count", "llm_tool_message_tokens",
            "llm_input_tokens", "next_llm_input_tokens",
            "system_tokens", "tools_tokens", "tool_count",
            "context_window", "context_usage_ratio", "remaining_context_tokens",
            "summary_active", "summary_message_tokens",
            "summary_trigger_tokens", "summary_pressure_ratio",
            "counter", "estimate", "measured_at");

    /** 运行时上下文的摘要触发阈值（KB），读取它换算成 token 数。 */
    static final String SUMMARY_THRESHOLD_KEY = "summary_threshold";

    private final ModelProviderCache modelCache;

    public TokenUsageMiddleware(ModelProviderCache modelCache) {
        this.modelCache = modelCache;
    }

    @Override
    public String getName() {
        return "token_usage";
    }

    /**
     * 对应参考实现 {@code wrap_model_call}：调用 handler 后构建用量快照，放入 request context
     * 的 {@link #SNAPSHOT_KEY}，供调用方（AgentHook.afterModel / 图节点）持久化到 state。
     *
     * <p>能力差异：参考实现把快照经 {@code Command(update=...)} 直接写回 LangGraph state；
     * 本工程 {@code ModelInterceptor} 无此能力，故经 context 传递，由构图方负责落 state。
     */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        Map<String, Object> snapshot = buildSnapshotFromRequest(request, response);
        if (snapshot != null) {
            Map<String, Object> context = new LinkedHashMap<>(request.getContext());
            context.put(SNAPSHOT_KEY, snapshot);
            request = ModelRequest.builder(request).context(context).build();
        }
        return response;
    }

    /**
     * 从 request context 里取构建快照所需的输入，调用 {@link #buildSnapshot}。
     *
     * <p>本工程 {@link ModelRequest} 无 state/messages/model/runtime 字段，这些由
     * 构图方放进 request context（键约定见 {@link #CONTEXT_KEY}）。若 context 缺关键输入
     * 则返回 null（无法构建，静默跳过，等价于参考实现拿不到 state 时的降级）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSnapshotFromRequest(ModelRequest request, ModelResponse response) {
        Map<String, Object> context = request.getContext();
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object ctx = context.get(CONTEXT_KEY);
        if (!(ctx instanceof Map<?, ?> ctxMap)) {
            return null;
        }
        Map<String, Object> c = asStringKeyedMap(ctxMap);
        List<Object> stateMessages = c.get("messages") instanceof List
                ? (List<Object>) c.get("messages") : List.of();
        List<Object> llmMessages = request.getMessages() == null ? List.of() : new ArrayList<>(request.getMessages());
        Integer contextWindow = modelContextWindow(asStringKeyedMap(c.get("model_profile")));
        String configuredSpec = c.get("model") instanceof String ? (String) c.get("model") : null;
        Integer summaryThresholdKb = safeInt(c.get(SUMMARY_THRESHOLD_KEY));
        String runId = String.valueOf(c.getOrDefault("run_id", ""));
        Map<String, Object> prevSnapshot = asStringKeyedMap(c.get("token_usage"));
        Map<String, Object> usage = usageFromResponse(response.getChatResponse());
        Map<String, String> identity = modelIdentity(
                asStringMap(c.get("model_metadata")),
                configuredSpec,
                null, null);
        return buildSnapshot(
                stateMessages, llmMessages,
                request.getSystemMessage(),
                request.getTools(),
                contextWindow, configuredSpec, summaryThresholdKb, runId,
                prevSnapshot, usage, identity);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return result;
    }

    /**
     * 对应参考实现 {@code before_agent}：Run 入口重置 Run 级用量、保留 v2 线程累计、
     * 剔除已禁用 Provider 的历史桶。返回的 {@code Map} 由 AgentHook 合并回 state。
     */
    public Map<String, Object> beforeAgent(Map<String, Object> previousState, String runId) {
        Map<String, Object> previous = previousState == null || !(previousState.get("token_usage") instanceof Map)
                ? Map.of()
                : asStringKeyedMap(previousState.get("token_usage"));
        boolean sameRun = String.valueOf(previous.get("current_run_id")).equals(runId)
                && previous.get("run") instanceof Map;
        Map<String, Object> contextFields = new LinkedHashMap<>();
        for (String key : TOKEN_USAGE_CONTEXT_FIELDS) {
            if (previous.containsKey(key)) {
                contextFields.put(key, previous.get(key));
            }
        }
        Object latest = sameRun ? previous.get("latest") : null;
        if (latest instanceof Map latestMap) {
            if (isBlacklistedBucket(latestMap)) {
                latest = null;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(contextFields);
        result.put("current_run_id", runId);
        result.put("latest", latest);
        result.put("run", sameRun ? withoutBlacklistedProviders(asMap(previous.get("run"))) : emptyAggregate());
        result.put("thread", withoutBlacklistedProviders(asMap(previous.get("thread"))));
        return Map.of("token_usage", result);
    }

    /** 最新桶是否属于已禁用 Provider（其 model 元数据或 bucket_key 前缀命中黑名单）。 */
    private static boolean isBlacklistedBucket(Map<?, ?> latest) {
        Object model = latest.get("model");
        if (model instanceof Map<?, ?> modelMap) {
            Object providerId = modelMap.get("provider_id");
            if (providerId instanceof String pid && TOKEN_USAGE_PROVIDER_BLACKLIST.contains(pid)) {
                return true;
            }
        }
        Object bucketKey = latest.get("bucket_key");
        if (bucketKey instanceof String bk && TOKEN_USAGE_PROVIDER_BLACKLIST.contains(bk.split(":", 2)[0])) {
            return true;
        }
        return false;
    }

    /**
     * 对应参考实现 {@code wrap_model_call} 里的快照构建（纯函数）。
     *
     * <p>根据单次模型请求与响应构建完整用量快照。参数均取自调用方（request context 里的
     * state / messages / model / runtime 信息），返回值即 {@code TokenUsagePayload}。
     *
     * @param stateMessages         LangGraph state 里的消息列表（对应 {@code request.state["messages"]}）
     * @param llmMessages          本次模型调用的消息列表（对应 {@code request.messages}）
     * @param systemMessage        系统消息（可为 null）
     * @param tools                本次工具名列表（对应 {@code request.tools}）
     * @param contextWindow        模型上下文窗口（对应 {@code _model_context_window(request.model)}）
     * @param configuredModelSpec  运行时配置的 model spec（对应 {@code runtime.context.model}）
     * @param summaryThresholdKb   摘要触发阈值 KB（对应 {@code runtime.context.summary_threshold}）
     * @param runId                当前 run id（对应 {@code runtime.context.run_id}）
     * @param previousSnapshot     上次的 token_usage 快照（state 里带的）
     * @param usage                本次实际 usage（已从响应提取，见 {@link #usageFromResponse}）
     * @param identity             模型身份（见 {@link #modelIdentity}）
     */
    public Map<String, Object> buildSnapshot(
            List<Object> stateMessages,
            List<Object> llmMessages,
            Message systemMessage,
            List<String> tools,
            Integer contextWindow,
            String configuredModelSpec,
            Integer summaryThresholdKb,
            String runId,
            Map<String, Object> previousSnapshot,
            Map<String, Object> usage,
            Map<String, String> identity) {
        List<Object> stateMessagesSafe = stateMessages == null ? List.of() : stateMessages;
        List<Object> llmMessagesSafe = llmMessages == null ? List.of() : llmMessages;
        List<Object> systemMessages = systemMessage == null ? List.of() : List.of((Object) systemMessage);
        List<String> toolsSafe = tools == null ? List.of() : tools;
        List<Object> responseMessages = List.of();

        int stateTokensBeforeCall = countTokensApproximately(stateMessagesSafe);
        List<Object> nextStateMessages = new ArrayList<>(stateMessagesSafe);
        nextStateMessages.addAll(responseMessages);
        int stateMessagesTokens = countTokensApproximately(nextStateMessages);
        int llmMessagesTokens = countTokensApproximately(llmMessagesSafe);
        int systemTokens = countTokensApproximately(systemMessages);
        int toolsTokens = toolsSafe.isEmpty() ? 0 : countToolsTokens(toolsSafe);
        int llmInputTokens = countTokensApproximately(systemMessages, llmMessagesSafe, null, toolsSafe);
        int nextLlmInputTokens = countTokensApproximately(systemMessages, llmMessagesSafe, responseMessages, toolsSafe);

        Integer effectiveWindow = contextWindow;
        Double contextUsageRatio = null;
        Integer remainingContextTokens = null;
        if (effectiveWindow != null && effectiveWindow > 0) {
            contextUsageRatio = Math.min(1.0, Math.round(llmInputTokens * 10000.0 / effectiveWindow) / 10000.0);
            remainingContextTokens = Math.max(effectiveWindow - llmInputTokens, 0);
        }

        Object first = llmMessagesSafe.isEmpty() ? null : llmMessagesSafe.get(0);
        Message summaryMessage = isSummaryMessage(first) ? (Message) first : null;
        List<Object> llmToolMessages = new ArrayList<>();
        List<Object> llmContentMessages = new ArrayList<>();
        for (Object message : llmMessagesSafe) {
            if (isToolMessage(message)) {
                llmToolMessages.add(message);
            } else if (message != summaryMessage && !isSummaryMessage(message)) {
                llmContentMessages.add(message);
            }
        }
        Integer summaryTriggerTokens = summaryTriggerTokens(summaryThresholdKb);
        Double summaryPressureRatio = null;
        if (summaryTriggerTokens != null && summaryTriggerTokens > 0) {
            summaryPressureRatio = Math.round(nextLlmInputTokens * 10000.0 / summaryTriggerTokens) / 10000.0;
        }
        Map<String, Object> prevSnapshot = previousSnapshot == null ? Map.of() : previousSnapshot;

        Map<String, Object> previousRun = prevSnapshot.get("run") instanceof Map
                && String.valueOf(prevSnapshot.get("current_run_id")).equals(runId)
                ? asStringKeyedMap(prevSnapshot.get("run"))
                : null;
        String measuredAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> runUsage = withoutBlacklistedProviders(previousRun != null ? previousRun : emptyAggregate());
        Map<String, Object> threadUsage = withoutBlacklistedProviders(asMap(prevSnapshot.get("thread")));

        Map<String, Object> latestUsage = null;
        if (!TOKEN_USAGE_PROVIDER_BLACKLIST.contains(identity.get("provider_id"))) {
            String[] bucket = bucketKey(identity, configuredModelSpec);
            String bucketKey = bucket[0];
            String identitySource = bucket[1];
            runUsage = addCallToAggregate(runUsage, bucketKey, identitySource, identity, usage);
            threadUsage = addCallToAggregate(threadUsage, bucketKey, identitySource, identity, usage);
            Integer cacheReadTokens = cacheReadTokens(usage);
            int inputTokens = usageInputTokens(usage);
            Map<String, Object> latest = new LinkedHashMap<>();
            latest.put("bucket_key", bucketKey);
            latest.put("model", identity);
            latest.put("usage", usage == null ? Map.of() : usage);
            latest.put("uncached_input_tokens", cacheReadTokens != null ? Math.max(inputTokens - cacheReadTokens, 0) : null);
            latest.put("cache_hit_ratio", cacheReadTokens != null ? ratio(cacheReadTokens, inputTokens) : null);
            latest.put("measured_at", measuredAt);
            latestUsage = latest;
        } else {
            runUsage = addUnavailableCall(runUsage);
            threadUsage = addUnavailableCall(threadUsage);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state_message_count", nextStateMessages.size());
        snapshot.put("state_message_count_before_call", stateMessagesSafe.size());
        snapshot.put("state_messages_tokens", stateMessagesTokens);
        snapshot.put("state_messages_tokens_before_call", stateTokensBeforeCall);
        snapshot.put("llm_message_count", llmMessagesSafe.size());
        snapshot.put("llm_messages_tokens", llmMessagesTokens);
        snapshot.put("llm_content_message_count", llmContentMessages.size());
        snapshot.put("llm_content_message_tokens", countTokensApproximately(llmContentMessages));
        snapshot.put("llm_tool_message_count", llmToolMessages.size());
        snapshot.put("llm_tool_message_tokens", countTokensApproximately(llmToolMessages));
        snapshot.put("llm_input_tokens", llmInputTokens);
        snapshot.put("next_llm_input_tokens", nextLlmInputTokens);
        snapshot.put("system_tokens", systemTokens);
        snapshot.put("tools_tokens", toolsTokens);
        snapshot.put("tool_count", toolsSafe.size());
        snapshot.put("context_window", effectiveWindow);
        snapshot.put("context_usage_ratio", contextUsageRatio);
        snapshot.put("remaining_context_tokens", remainingContextTokens);
        snapshot.put("summary_active", summaryMessage != null);
        snapshot.put("summary_message_tokens", summaryMessage != null ? countTokensApproximately(List.of((Object) summaryMessage)) : 0);
        snapshot.put("summary_trigger_tokens", summaryTriggerTokens);
        snapshot.put("summary_pressure_ratio", summaryPressureRatio);
        snapshot.put("current_run_id", runId);
        snapshot.put("latest", latestUsage);
        snapshot.put("run", runUsage);
        snapshot.put("thread", threadUsage);
        snapshot.put("counter", "wenqu.count_tokens_approximately");
        snapshot.put("estimate", true);
        snapshot.put("measured_at", measuredAt);
        return snapshot;
    }

    /** 从框架 {@link ChatResponse} 提取 usage（必要替换：参考实现读 AIMessage.usage_metadata）。 */
    public static Map<String, Object> usageFromResponse(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return null;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (usage.getPromptTokens() != null) {
            result.put("input_tokens", usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            result.put("output_tokens", usage.getCompletionTokens());
        }
        if (usage.getTotalTokens() != null) {
            result.put("total_tokens", usage.getTotalTokens());
        }
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof Map<?, ?> map) {
            result.putAll(asStringKeyedMap(map));
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 解析模型身份（对应参考实现 {@code _model_identity}）。
     *
     * <p>本工程无 {@code request.model.metadata}，改为由调用方传入模型元数据（provider_id /
     * provider_type / model_id / model_spec），缺失时回退到 {@link ModelProviderCache#getModelInfo}。
     */
    public Map<String, String> modelIdentity(
            Map<String, String> modelMetadata,
            String configuredSpec,
            String responseModelId,
            String responseProviderType) {
        Map<String, String> identity = new LinkedHashMap<>();
        String providerId = modelMetadata != null ? modelMetadata.get("provider_id") : null;
        String providerType = modelMetadata != null ? modelMetadata.get("provider_type") : null;
        String configuredModelId = modelMetadata != null ? modelMetadata.get("model_id") : null;
        String configuredModelSpec = modelMetadata != null ? modelMetadata.get("model_spec") : null;
        if (notBlank(providerId)) identity.put("provider_id", providerId);
        if (notBlank(providerType)) identity.put("provider_type", providerType);
        if (notBlank(configuredModelId)) identity.put("configured_model_id", configuredModelId);
        if (notBlank(configuredModelSpec)) identity.put("configured_model_spec", configuredModelSpec);

        String spec = stripToNull(configuredSpec);
        if (identity.isEmpty() && spec != null) {
            ModelInfo info = modelCache != null ? modelCache.getModelInfo(spec) : null;
            if (info != null) {
                identity.put("provider_id", info.providerId());
                identity.put("provider_type", info.providerType());
                identity.put("configured_model_id", info.modelId());
                identity.put("configured_model_spec", info.spec());
            } else {
                identity.put("configured_model_spec", spec);
            }
        } else if (spec != null) {
            identity.put("configured_model_spec", spec);
        }

        if (notBlank(responseModelId)) {
            identity.put("response_model_id", responseModelId);
        }
        if (!identity.containsKey("provider_type") && notBlank(responseProviderType)) {
            identity.put("provider_type", responseProviderType);
        }
        return identity;
    }

    // ==================== 纯函数（逐字对齐参考实现 token_usage.py） ====================

    /** {@code _safe_int}：bool/非整数值返回 null。 */
    static Integer safeInt(Object value) {
        if (value instanceof Boolean) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return Math.toIntExact(l);
        }
        if (value instanceof Float f && f == Math.floor(f)) {
            return f.intValue();
        }
        if (value instanceof Double d && d == Math.floor(d)) {
            return d.intValue();
        }
        return null;
    }

    /** {@code _ratio}：分母为 0 返回 null。 */
    static Double ratio(Integer numerator, int denominator) {
        if (denominator <= 0 || numerator == null) {
            return null;
        }
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    /** {@code _empty_aggregate}：v2 聚合初始结构。 */
    static Map<String, Object> emptyAggregate() {
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("schema_version", 2);
        agg.put("model_call_count", 0);
        agg.put("usage_reported_call_count", 0);
        agg.put("usage_unavailable_call_count", 0);
        agg.put("complete", false);
        agg.put("models", new LinkedHashMap<String, Object>());
        agg.put("total", new LinkedHashMap<>(ZERO_TOTAL));
        return agg;
    }

    /** {@code _aggregate_from_state}：校验并规整聚合结构，schema_version 不匹配回退空聚合。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> aggregateFromState(Object value) {
        if (!(value instanceof Map) || !Integer.valueOf(2).equals(safeInt(((Map<?, ?>) value).get("schema_version")))) {
            return emptyAggregate();
        }
        Map<?, ?> src = (Map<?, ?>) value;
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("schema_version", 2);
        agg.put("model_call_count", safeIntOrZero(src.get("model_call_count")));
        agg.put("usage_reported_call_count", safeIntOrZero(src.get("usage_reported_call_count")));
        agg.put("usage_unavailable_call_count", safeIntOrZero(src.get("usage_unavailable_call_count")));
        agg.put("complete", Boolean.TRUE.equals(src.get("complete")));
        Map<String, Object> models = new LinkedHashMap<>();
        Object rawModels = src.get("models");
        if (rawModels instanceof Map<?, ?> modelsMap) {
            for (Map.Entry<?, ?> e : modelsMap.entrySet()) {
                if (e.getValue() instanceof Map) {
                    models.put(String.valueOf(e.getKey()), asStringKeyedMap(e.getValue()));
                }
            }
        }
        agg.put("models", models);
        agg.put("total", src.get("total") instanceof Map ? asStringKeyedMap(src.get("total")) : new LinkedHashMap<>(ZERO_TOTAL));
        return agg;
    }

    /** {@code _recompute_aggregate_totals}：按当前 models 重算聚合级计数和 total。 */
    @SuppressWarnings("unchecked")
    static void recomputeAggregateTotals(Map<String, Object> aggregate) {
        Map<String, Object> models = (Map<String, Object>) aggregate.get("models");
        int modelCallCount = 0;
        int usageReportedCallCount = 0;
        for (Object bucketObj : models.values()) {
            if (!(bucketObj instanceof Map)) {
                continue;
            }
            Map<?, ?> bucket = (Map<?, ?>) bucketObj;
            modelCallCount += safeIntOrZero(bucket.get("model_call_count"));
            usageReportedCallCount += safeIntOrZero(bucket.get("usage_reported_call_count"));
        }
        int usageUnavailableCallCount = safeIntOrZero(aggregate.get("usage_unavailable_call_count"));
        aggregate.put("model_call_count", modelCallCount + usageUnavailableCallCount);
        aggregate.put("usage_reported_call_count", usageReportedCallCount);
        aggregate.put("complete", (modelCallCount + usageUnavailableCallCount) > 0
                && usageReportedCallCount == (modelCallCount + usageUnavailableCallCount));
        Map<String, Integer> total = new LinkedHashMap<>(ZERO_TOTAL);
        for (Object bucketObj : models.values()) {
            if (!(bucketObj instanceof Map)) {
                continue;
            }
            Object bucketUsage = ((Map<?, ?>) bucketObj).get("usage");
            if (!(bucketUsage instanceof Map)) {
                continue;
            }
            Map<?, ?> usage = (Map<?, ?>) bucketUsage;
            for (String key : total.keySet()) {
                total.put(key, total.get(key) + safeIntOrZero(usage.get(key)));
            }
        }
        aggregate.put("total", total);
    }

    /** {@code _add_unavailable_call}：记录一次无可信 Provider usage 的模型调用。 */
    static Map<String, Object> addUnavailableCall(Map<String, Object> aggregate) {
        Map<String, Object> result = aggregateFromState(aggregate);
        result.put("usage_unavailable_call_count", safeIntOrZero(result.get("usage_unavailable_call_count")) + 1);
        recomputeAggregateTotals(result);
        return result;
    }

    /** {@code _without_blacklisted_providers}：剔除已禁用 Provider 的错误用量桶。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutBlacklistedProviders(Map<String, Object> aggregate) {
        Map<String, Object> result = aggregateFromState(aggregate);
        Map<String, Object> models = (Map<String, Object>) result.get("models");
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : models.entrySet()) {
            Object bucket = e.getValue();
            Object model = bucket instanceof Map ? ((Map<?, ?>) bucket).get("model") : null;
            boolean blacklisted = model instanceof Map
                    && ((Map<?, ?>) model).get("provider_id") instanceof String pid
                    && TOKEN_USAGE_PROVIDER_BLACKLIST.contains(pid);
            if (!blacklisted) {
                filtered.put(e.getKey(), bucket);
            }
        }
        result.put("models", filtered);
        recomputeAggregateTotals(result);
        return result;
    }

    /**
     * {@code _bucket_key}：按优先级取分桶 key —— 配置 model spec &gt; 响应 model id &gt; 适配器兜底。
     */
    static String[] bucketKey(Map<String, String> identity, String configuredSpec) {
        String configuredModelSpec = identity.get("configured_model_spec");
        if (notBlank(configuredModelSpec)) {
            return new String[]{configuredModelSpec, "configured_metadata"};
        }
        String responseModelId = identity.get("response_model_id");
        String providerType = identity.getOrDefault("provider_type", "unknown");
        if (notBlank(responseModelId)) {
            return new String[]{"response:" + providerType + ":" + responseModelId, "response_metadata"};
        }
        String modelId = stripToNull(configuredSpec);
        if (modelId == null) {
            modelId = "unknown";
        }
        return new String[]{"unattributed:TokenUsageMiddleware:" + modelId, "adapter_fallback"};
    }

    /** {@code _add_call_to_aggregate}：把一次调用 usage 累加进分桶，更新缓存命中统计与聚合总计。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> addCallToAggregate(
            Map<String, Object> aggregate,
            String bucketKey,
            String identitySource,
            Map<String, String> identity,
            Map<String, Object> usage) {
        Map<String, Object> result = aggregateFromState(aggregate);
        Map<String, Object> models = (Map<String, Object>) result.get("models");
        Map<String, Object> previousBucket = models.get(bucketKey) instanceof Map
                ? asStringKeyedMap(models.get(bucketKey)) : new LinkedHashMap<>();
        Map<String, Object> usageIncrement = usageForAccumulation(usage);
        Object prevUsageObj = previousBucket.get("usage");
        Map<String, Object> previousUsage = prevUsageObj instanceof Map
                ? usageForAccumulation(prevUsageObj) : null;
        Map<String, Object> cumulativeUsage = usageIncrement != null && previousUsage != null
                ? addUsage(previousUsage, usageIncrement)
                : (usageIncrement != null ? usageIncrement : previousUsage);

        Map<String, Object> modelIdentity = previousBucket.get("model") instanceof Map
                ? asStringKeyedMap(previousBucket.get("model")) : new LinkedHashMap<>();
        for (Map.Entry<String, String> e : identity.entrySet()) {
            if (!"response_model_id".equals(e.getKey())) {
                modelIdentity.put(e.getKey(), e.getValue());
            }
        }
        modelIdentity.put("identity_source", identitySource);
        List<String> responseModelIds = modelIdentity.get("response_model_ids") instanceof List
                ? new ArrayList<>((List<String>) modelIdentity.get("response_model_ids")) : new ArrayList<>();
        String responseModelId = identity.get("response_model_id");
        if (notBlank(responseModelId) && !responseModelIds.contains(responseModelId)) {
            responseModelIds.add(responseModelId);
        }
        modelIdentity.put("response_model_ids", responseModelIds);

        Integer cacheRead = cacheReadTokens(usage);
        boolean cacheObserved = cacheRead != null;
        int cacheObservedCalls = safeIntOrZero(previousBucket.get("cache_observed_call_count")) + (cacheObserved ? 1 : 0);
        int cacheHitCalls = safeIntOrZero(previousBucket.get("cache_hit_call_count")) + (cacheRead != null && cacheRead > 0 ? 1 : 0);
        int observedInput = safeIntOrZero(previousBucket.get("cache_observed_input_tokens")) + (cacheObserved ? usageInputTokens(usage) : 0);
        int cacheReadInput = safeIntOrZero(previousBucket.get("cache_read_input_tokens")) + (cacheRead != null ? cacheRead : 0);

        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("model", modelIdentity);
        bucket.put("usage", cumulativeUsage != null ? cumulativeUsage : Map.of());
        bucket.put("model_call_count", safeIntOrZero(previousBucket.get("model_call_count")) + 1);
        bucket.put("usage_reported_call_count", safeIntOrZero(previousBucket.get("usage_reported_call_count")) + (usageIncrement != null ? 1 : 0));
        bucket.put("cache_observed_call_count", cacheObservedCalls);
        bucket.put("cache_hit_call_count", cacheHitCalls);
        bucket.put("cache_observed_input_tokens", observedInput);
        bucket.put("cache_read_input_tokens", cacheReadInput);
        bucket.put("uncached_input_tokens", cacheObservedCalls > 0 ? Math.max(observedInput - cacheReadInput, 0) : null);
        bucket.put("cache_hit_ratio", ratio(cacheReadInput, observedInput));
        bucket.put("cache_request_hit_ratio", ratio(cacheHitCalls, cacheObservedCalls));
        models.put(bucketKey, bucket);

        recomputeAggregateTotals(result);
        return result;
    }

    /** {@code _model_context_window}：读取模型配置上下文窗口，未配置/非法返回 null。 */
    static Integer modelContextWindow(Map<String, Object> modelProfile) {
        if (modelProfile == null) {
            return null;
        }
        Object maxInput = modelProfile.get("max_input_tokens");
        Integer value = safeInt(maxInput);
        return value != null && value > 0 ? value : null;
    }

    /** {@code _summary_trigger_tokens}：阈值 KB × 1024 换算为 token 数。 */
    static Integer summaryTriggerTokens(Integer thresholdKb) {
        if (thresholdKb == null || thresholdKb <= 0) {
            return null;
        }
        return thresholdKb * 1024;
    }

    /** {@code _is_summary_message}：摘要中间件产生的消息（additional_kwargs.lc_source == "summarization"）。 */
    static boolean isSummaryMessage(Object message) {
        if (message instanceof Message springMessage) {
            Object lc = springMessage.getMetadata() == null ? null : springMessage.getMetadata().get("lc_source");
            return "summarization".equals(lc);
        }
        return false;
    }

    /** {@code _is_tool_message}：消息是否为工具消息。 */
    static boolean isToolMessage(Object message) {
        if (message instanceof Message springMessage) {
            return springMessage.getMessageType() != null
                    && "tool".equalsIgnoreCase(springMessage.getMessageType().getValue());
        }
        return false;
    }

    /** {@code _usage_for_accumulation}：保留可累计数值字段，忽略 Provider 非数值扩展元数据。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> usageForAccumulation(Object usage) {
        if (!(usage instanceof Map)) {
            return null;
        }
        Map<?, ?> src = (Map<?, ?>) usage;
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : src.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Integer i && !(value instanceof Boolean)) {
                normalized.put(String.valueOf(e.getKey()), i);
            } else if (value instanceof Long l) {
                normalized.put(String.valueOf(e.getKey()), Math.toIntExact(l));
            } else if (value instanceof Map) {
                Map<String, Object> details = new LinkedHashMap<>();
                for (Map.Entry<?, ?> de : ((Map<?, ?>) value).entrySet()) {
                    Object dv = de.getValue();
                    if (dv instanceof Integer i && !(dv instanceof Boolean)) {
                        details.put(String.valueOf(de.getKey()), i);
                    } else if (dv instanceof Long l) {
                        details.put(String.valueOf(de.getKey()), Math.toIntExact(l));
                    }
                }
                if (!details.isEmpty()) {
                    normalized.put(String.valueOf(e.getKey()), details);
                }
            }
        }
        if (!(normalized.get("input_tokens") instanceof Integer)
                || !(normalized.get("output_tokens") instanceof Integer)
                || !(normalized.get("total_tokens") instanceof Integer)) {
            return null;
        }
        return normalized;
    }

    /** {@code _usage_input_tokens}：读取 usage 的 input_tokens，缺失/非法返回 0。 */
    static int usageInputTokens(Map<String, Object> usage) {
        if (usage == null) {
            return 0;
        }
        Object value = usage.get("input_tokens");
        return value instanceof Integer && !(value instanceof Boolean) ? (Integer) value : 0;
    }

    /** {@code _cache_read_tokens}：从 input_token_details 读缓存命中 token，未观测返回 null。 */
    static Integer cacheReadTokens(Map<String, Object> usage) {
        Object details = usage == null ? null : usage.get("input_token_details");
        if (!(details instanceof Map)) {
            return null;
        }
        Map<?, ?> detailsMap = (Map<?, ?>) details;
        for (String key : new String[]{"cache_read", "priority_cache_read", "flex_cache_read"}) {
            if (!detailsMap.containsKey(key)) {
                continue;
            }
            Object value = detailsMap.get(key);
            if (value instanceof Integer && !(value instanceof Boolean)) {
                return (Integer) value;
            }
            if (value instanceof Long l) {
                return Math.toIntExact(l);
            }
            return null;
        }
        return null;
    }

    /** {@code langchain add_usage} 的数值逐字段累加（含 input/output/total 与明细）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> addUsage(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> result = new LinkedHashMap<>(a);
        for (Map.Entry<String, Object> e : b.entrySet()) {
            Object prev = result.get(e.getKey());
            Object value = e.getValue();
            if (prev instanceof Integer pi && value instanceof Integer vi) {
                result.put(e.getKey(), pi + vi);
            } else if (prev instanceof Map pm && value instanceof Map vm) {
                result.put(e.getKey(), addUsage(asStringKeyedMap(pm), asStringKeyedMap(vm)));
            } else {
                result.put(e.getKey(), value);
            }
        }
        return result;
    }

    // ==================== 辅助 ====================

    /** 近似 token 计数（能力差异：参考实现用 langchain count_tokens_approximately）。 */
    static int countTokensApproximately(List<Object> messages) {
        return countTokensApproximately(messages, null, null, null);
    }

    /** 多段合并计数（system + messages + [response]，必要时叠加工具 schema）。 */
    static int countTokensApproximately(
            List<Object> systemMessages,
            List<Object> llmMessages,
            List<Object> responseMessages,
            List<String> tools) {
        List<Object> all = new ArrayList<>();
        if (systemMessages != null) all.addAll(systemMessages);
        if (llmMessages != null) all.addAll(llmMessages);
        if (responseMessages != null) all.addAll(responseMessages);
        int total = 0;
        for (Object message : all) {
            total += approximateMessageTokens(message);
        }
        if (tools != null) {
            for (String tool : tools) {
                total += Math.max(1, tool.length() / 4);
            }
        }
        return total;
    }

    /** 工具 schema token 计数。 */
    static int countToolsTokens(List<String> tools) {
        int total = 0;
        for (String tool : tools) {
            total += Math.max(1, tool.length() / 4);
        }
        return total;
    }

    /** 单条消息近似 token：按文本长度 / 4（中文按字符近似，足够用于比例/压力估算）。 */
    private static int approximateMessageTokens(Object message) {
        String text = null;
        if (message instanceof Message m) {
            text = m.getText();
        } else if (message instanceof Map<?, ?> map) {
            Object content = map.get("content");
            text = content instanceof String ? (String) content : String.valueOf(content);
        } else if (message != null) {
            text = message.toString();
        }
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return result;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return asStringKeyedMap(value);
        }
        return Map.of();
    }

    private static int safeIntOrZero(Object value) {
        Integer v = safeInt(value);
        return v != null ? v : 0;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripToNull(String s) {
        if (s == null) {
            return null;
        }
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}