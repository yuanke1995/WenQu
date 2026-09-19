package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.ToolApproval;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.common.SseUtils;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.AgentRunRequest;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.AgentRunOutputRepository;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.AgentRunRequestRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.service.InputMessageService.AgentRunInputMessage;
import com.alibaba.fastjson2.JSON;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AgentRun 生命周期服务。
 *
 * <p>由参考实现的 {@code services/agent_run_service.py} 逐函数翻译。本模块own持久 {@code AgentRun}
 * 契约：校验 run 作用域、落库输入消息、创建 run 行、投递 worker 执行、流式输出 run 事件、
 * 读取最终结果与请求取消。来源特有的编排（chat / agent-call / 评估 / 子智能体）留在各自调用方，
 * 各调用方先把自身请求形状翻译成本模块的公开 run API。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code get_arq_pool().enqueue_job("process_agent_run", run_id, _job_id=f"run:{run_id}")} →
 *       {@link ArqPort} 端口（与 {@link TaskQueueService.ArqPort} 同口径：ARQ 是 Python 专用队列，
 *       本工程以端口承载同一唤醒语义；未装配时抛出，与参考实现"Redis 不可达即投递失败"同型）。</li>
 *   <li>{@code pg_manager.get_async_session_context()} 自管会话 → {@link TransactionTemplate}
 *       （参考实现显式 {@code commit()} 的位置改用事务模板，保持同一提交边界）。</li>
 *   <li>异步生成器 {@code async def … yield} → 回调 sink（{@code Consumer<String>}），
 *       调用方线程顺序消费，SSE 帧语义等价。</li>
 *   <li>{@code asyncio.sleep} 轮询 → {@link Thread#sleep}（阻塞式；由承担该次流式输出的线程执行）。</li>
 *   <li>{@code random.uniform} 抖动 → {@link ThreadLocalRandom}。</li>
 *   <li>品牌前缀必要替换：参考实现内部 SSE custom 事件名 {@code "yuxi.agent_state"} →
 *       {@code "wenqu.agent_state"}（生产者 {@code run_worker} 与消费者本模块同处后端，
 *       参考前端**不消费**该字面量——已核查 {@code web/src} 无引用，故为纯品牌替换）。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li>{@code agent_manager.get_agent(backend_id)} 依赖 {@code agents/buildin} 的 chatbot/subagent
 *       后端（引擎面，未照搬）；本工程 {@link AgentManager} 保留语义但当前无内置后端可注册，
 *       故所有需要 {@code agent_backend} 的路径一律走到既有的 404 分支
 *       （「智能体后端 X 不存在」），与参考实现在后端缺失时的业务表现一致。</li>
 * </ul>
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    public static final int RUN_PROGRESS_RECENT_EVENT_SCAN_LIMIT = 100;
    public static final int RUN_PROGRESS_MESSAGE_LIMIT = 3;
    public static final int RUN_PROGRESS_CONTENT_MAX_CHARS = 800;
    public static final double RUN_SSE_ACTIVE_POLL_SECONDS = 0.1;
    public static final double RUN_SSE_SHORT_IDLE_MAX_POLL_SECONDS = 1.0;
    public static final double RUN_SSE_LONG_IDLE_AFTER_SECONDS = 120.0;
    public static final double RUN_SSE_LONG_IDLE_MAX_POLL_SECONDS = 4.0;
    public static final double RUN_SSE_STATUS_POLL_SECONDS = 5.0;
    public static final double RUN_SSE_POLL_JITTER_RATIO = 0.2;

    /** 本模块内部 SSE custom 事件名（品牌前缀已替换，见类注释）。 */
    public static final String AGENT_STATE_EVENT_NAME = "wenqu.agent_state";

    /** worker 唤醒端口（对应 ARQ 的 {@code enqueue_job("process_agent_run", run_id)}）。 */
    public interface ArqPort {
        void enqueueProcessAgentRun(String runId) throws Exception;
    }

    /** 等待结束但 run 尚未进入终态（对应 {@code AgentRunWaitTimeout}）。 */
    public static class AgentRunWaitTimeout extends RuntimeException {
        private final transient Map<String, Object> result;

        public AgentRunWaitTimeout(Map<String, Object> result) {
            super("agent run "
                    + firstNonNull(result, "agent_run_id", "run_id")
                    + " is still "
                    + (result.get("status") == null ? "unknown" : result.get("status"))
                    + " after waiting");
            this.result = result;
        }

        public Map<String, Object> getResult() {
            return result;
        }

        private static Object firstNonNull(Map<String, Object> result, String... keys) {
            for (String key : keys) {
                if (result != null && result.get(key) != null) {
                    return result.get(key);
                }
            }
            return "";
        }
    }

    /** run 创建前置校验后的数据库作用域（对应 {@code AgentRunCreationScope}）。 */
    public record AgentRunCreationScope(
            Conversation conversation,
            Agent agentItem,
            BaseAgent agentBackend,
            AgentRun existingRun,
            AgentRun parentRun) {}

    private final AgentRepository agentRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunRequestRepository requestRepository;
    private final AgentRunOutputRepository outputRepository;
    private final ConversationRepository conversationRepository;
    private final MessageMapper messageMapper;
    private final AgentManager agentManager;
    private final RunQueueService runQueueService;
    private final LangfuseService langfuseService;
    private final OptionsService optionsService;
    private final ModelProviderCache modelProviderCache;
    private final ObjectProvider<ArqPort> arqPort;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate nestedTransactionTemplate;

    public AgentRunService(
            AgentRepository agentRepository,
            AgentRunRepository runRepository,
            AgentRunRequestRepository requestRepository,
            AgentRunOutputRepository outputRepository,
            ConversationRepository conversationRepository,
            MessageMapper messageMapper,
            AgentManager agentManager,
            RunQueueService runQueueService,
            LangfuseService langfuseService,
            OptionsService optionsService,
            ModelProviderCache modelProviderCache,
            ObjectProvider<ArqPort> arqPort,
            PlatformTransactionManager transactionManager) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.outputRepository = outputRepository;
        this.conversationRepository = conversationRepository;
        this.messageMapper = messageMapper;
        this.agentManager = agentManager;
        this.runQueueService = runQueueService;
        this.langfuseService = langfuseService;
        this.optionsService = optionsService;
        this.modelProviderCache = modelProviderCache;
        this.arqPort = arqPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // =========================================================================
    // === 模型 / 审批模式解析 ===
    // =========================================================================

    /**
     * 用 Agent 配置的 context 片段实例化并填充运行上下文
     * （对应 {@code load_agent_run_context}）。
     */
    public com.wisesoft.wenqu.agents.BaseContext loadAgentRunContext(Agent agentItem, BaseAgent agentBackend) {
        com.wisesoft.wenqu.agents.BaseContext context = newContext(agentBackend);
        Map<String, Object> configJson = agentItem == null ? null : parseJsonObject(agentItem.getConfigJson());
        Object configContext = configJson == null ? null : configJson.get("context");
        if (configContext instanceof Map<?, ?> map) {
            context.updateConfig(toStringKeyMap(map));
        }
        return context;
    }

    /** 实例化后端声明的 context schema（对应 {@code agent_backend.context_schema()}）。 */
    private com.wisesoft.wenqu.agents.BaseContext newContext(BaseAgent agentBackend) {
        Class<? extends com.wisesoft.wenqu.agents.BaseContext> schema =
                agentBackend == null ? com.wisesoft.wenqu.agents.BaseContext.class : agentBackend.resolveContextSchema();
        try {
            return schema.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Context schema 实例化失败: " + schema.getName(), error);
        }
    }

    /** 按请求、Agent 配置、系统默认的顺序解析并校验聊天模型（对应 {@code resolve_agent_run_model_spec}）。 */
    public String resolveAgentRunModelSpec(String requestedModel, String configuredModel) {
        String modelSpec = null;
        for (String candidate : new String[] {requestedModel, configuredModel}) {
            if (candidate != null && !candidate.strip().isEmpty()) {
                modelSpec = candidate.strip();
                break;
            }
        }
        if (modelSpec == null) {
            Object defaultModel = optionsService.get(OptionsService.SYSTEM_OPTIONS).get("default_model");
            modelSpec = defaultModel == null ? "" : String.valueOf(defaultModel).strip();
        }
        var info = modelProviderCache.getModelInfo(modelSpec);
        if (info == null || !"chat".equals(info.modelType())) {
            throw ApiHttpException.unprocessable("未找到可用聊天模型: '" + modelSpec + "'");
        }
        return modelSpec;
    }

    /** 解析本次 run 的工具审批模式（对应 {@code resolve_agent_run_tool_approval_mode}）。 */
    public String resolveAgentRunToolApprovalMode(String requestedMode, String configuredMode) {
        String source = requestedMode != null
                ? requestedMode
                : (configuredMode == null || configuredMode.isEmpty()
                        ? ToolApproval.DEFAULT_TOOL_APPROVAL_MODE
                        : configuredMode);
        try {
            return ToolApproval.normalizeToolApprovalMode(source);
        } catch (IllegalArgumentException error) {
            throw ApiHttpException.unprocessable(error.getMessage());
        }
    }

    /** 一次性解析 model_spec 与 tool_approval_mode（对应 {@code resolve_agent_run_config}）。 */
    public String[] resolveAgentRunConfig(
            String modelSpec, String toolApprovalMode, Agent agentItem, BaseAgent agentBackend) {
        com.wisesoft.wenqu.agents.BaseContext context = loadAgentRunContext(agentItem, agentBackend);
        String resolvedModelSpec = resolveAgentRunModelSpec(modelSpec, context.getString("model"));
        String resolvedToolApprovalMode =
                resolveAgentRunToolApprovalMode(toolApprovalMode, context.getString("tool_approval_mode"));
        return new String[] {resolvedModelSpec, resolvedToolApprovalMode};
    }

    /** run 的 HTTP 响应投影（对应 {@code _build_run_response}）。 */
    public static Map<String, Object> buildRunResponse(AgentRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", run.getId());
        data.put("thread_id", run.getConversationThreadId());
        data.put("status", run.getStatus());
        data.put("request_id", run.getRequestId());
        data.put("stream_url", "/api/agent/runs/" + run.getId() + "/events");
        return data;
    }

    /** 校验 resume 载荷（对应 {@code _validate_resume_input}）。 */
    public static void validateResumeInput(Object resume) {
        if (!(resume instanceof Map<?, ?> map) || !map.containsKey("decisions")) {
            return;
        }
        Object decisions = map.get("decisions");
        if (!(decisions instanceof List<?> list) || list.isEmpty()) {
            throw ApiHttpException.unprocessable("decisions 必须是非空数组");
        }
        for (Object decision : list) {
            if (!(decision instanceof Map<?, ?> decisionMap)) {
                throw ApiHttpException.unprocessable("decision.type 只支持 approve 或 reject");
            }
            Object type = decisionMap.get("type");
            if (!"approve".equals(type) && !"reject".equals(type)) {
                throw ApiHttpException.unprocessable("decision.type 只支持 approve 或 reject");
            }
        }
    }

    // =========================================================================
    // === 事件压缩（verbose=False 时的对外投影） ===
    // =========================================================================

    public static Map<String, Object> compactMessageDict(Map<String, Object> message) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("id", "role", "content", "type", "message_type")) {
            if (message.get(key) != null) {
                compact.put(key, message.get(key));
            }
        }
        Object extraMetadata = message.get("extra_metadata");
        if (extraMetadata instanceof Map<?, ?> extraMap && extraMap.get("attachments") != null) {
            Map<String, Object> attachments = new LinkedHashMap<>();
            attachments.put("attachments", extraMap.get("attachments"));
            compact.put("extra_metadata", attachments);
        }
        return compact;
    }

    public static Map<String, Object> compactSemanticStreamEvent(Map<String, Object> streamEvent) {
        Object eventType = streamEvent.get("type");
        if ("message_delta".equals(eventType)) {
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String key : List.of(
                    "type", "message_id", "content", "reasoning_content", "additional_reasoning_content")) {
                if (truthy(streamEvent.get(key))) {
                    compact.put(key, streamEvent.get(key));
                }
            }
            return compact;
        }
        if ("tool_call".equals(eventType) || "tool_call_delta".equals(eventType)) {
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String key : List.of("type", "message_id", "tool_call_id", "name", "args", "args_delta")) {
                Object value = streamEvent.get(key);
                if (value != null && !"".equals(value)) {
                    compact.put(key, value);
                }
            }
            if (truthy(streamEvent.get("index"))) {
                compact.put("index", streamEvent.get("index"));
            }
            return compact;
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : streamEvent.entrySet()) {
            if (!"thread_id".equals(entry.getKey()) && !"namespace".equals(entry.getKey())) {
                compact.put(entry.getKey(), entry.getValue());
            }
        }
        return compact;
    }

    public static Map<String, Object> compactToolStreamEvent(Map<String, Object> event) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (truthy(event.get("method"))) {
            compact.put("method", event.get("method"));
        }
        Object data = event.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Map<String, Object> compactData = new LinkedHashMap<>();
            for (String key : List.of("event", "tool_call_id", "tool_name", "output", "error")) {
                Object value = dataMap.get(key);
                if (value != null && !"".equals(value)) {
                    compactData.put(key, value);
                }
            }
            if (!compactData.isEmpty()) {
                compact.put("data", compactData);
            }
        }
        return compact;
    }

    public static Map<String, Object> compactStreamChunk(Map<String, Object> chunk) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of(
                "status", "run_id", "message", "error_type", "error_message", "retryable", "job_try",
                "questions", "approval", "interrupt_info", "source", "agent_state", "compression")) {
            Object value = chunk.get(key);
            if (value != null && !"".equals(value)) {
                compact.put(key, value);
            }
        }
        if (chunk.get("msg") instanceof Map<?, ?> msg) {
            compact.put("msg", compactMessageDict(toStringKeyMap(msg)));
        }
        if (chunk.get("stream_event") instanceof Map<?, ?> streamEvent) {
            compact.put("stream_event", compactSemanticStreamEvent(toStringKeyMap(streamEvent)));
        }
        if (chunk.get("event") instanceof Map<?, ?> event) {
            compact.put("event", compactToolStreamEvent(toStringKeyMap(event)));
        }
        return compact;
    }

    public static String requestIdFromChunk(Object chunk) {
        if (!(chunk instanceof Map<?, ?> map)) {
            return null;
        }
        Object requestId = map.get("request_id");
        if (requestId instanceof String text && !text.isEmpty()) {
            return text;
        }
        Object msg = map.get("msg");
        Object extraMetadata = msg instanceof Map<?, ?> msgMap ? msgMap.get("extra_metadata") : null;
        if (extraMetadata instanceof Map<?, ?> extraMap) {
            Object inner = extraMap.get("request_id");
            if (inner instanceof String text && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    public static String requestIdFromPayload(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object requestId = map.get("request_id");
        if (requestId instanceof String text && !text.isEmpty()) {
            return text;
        }
        String fromChunk = requestIdFromChunk(map.get("chunk"));
        if (fromChunk != null) {
            return fromChunk;
        }
        Object items = map.get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                String found = requestIdFromChunk(item);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static Map<String, Object> compactRunEventPayload(String eventType, Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if ("messages".equals(eventType)) {
            Map<String, Object> compact = new LinkedHashMap<>();
            if (payload.get("items") instanceof List<?> items) {
                List<Object> compactItems = new ArrayList<>();
                for (Object item : items) {
                    compactItems.add(item instanceof Map<?, ?> map ? compactStreamChunk(toStringKeyMap(map)) : item);
                }
                compact.put("items", compactItems);
            }
            if (payload.get("chunk") instanceof Map<?, ?> chunk) {
                compact.put("chunk", compactStreamChunk(toStringKeyMap(chunk)));
            }
            return compact;
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!"chunk".equals(entry.getKey()) && !"request_id".equals(entry.getKey())) {
                compact.put(entry.getKey(), entry.getValue());
            }
        }
        if (payload.get("chunk") instanceof Map<?, ?> chunk) {
            compact.put("chunk", compactStreamChunk(toStringKeyMap(chunk)));
        }
        return compact;
    }

    public static boolean isEmptyAgentState(Object agentState) {
        if (!(agentState instanceof Map<?, ?> map)) {
            return false;
        }
        for (Object value : map.values()) {
            if (truthy(value)) {
                return false;
            }
        }
        return true;
    }

    /** verbose=False 时的信封压缩；返回 null 表示该事件应被丢弃（对应 {@code _compact_run_event_envelope}）。 */
    public static Map<String, Object> compactRunEventEnvelope(Map<String, Object> envelope) {
        String eventType = envelope.get("event") == null ? "" : String.valueOf(envelope.get("event"));
        Object payloadRaw = envelope.get("payload");
        if ("metadata".equals(eventType)) {
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String key : List.of("run_id", "thread_id")) {
                if (envelope.containsKey(key)) {
                    compact.put(key, envelope.get(key));
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (payloadRaw instanceof Map<?, ?> payloadMap) {
                for (String key : List.of("run_type", "source")) {
                    if (payloadMap.containsKey(key)) {
                        payload.put(key, payloadMap.get(key));
                    }
                }
            }
            compact.put("payload", payload);
            return compact;
        }
        if ("custom".equals(eventType) && payloadRaw instanceof Map<?, ?> payloadMap) {
            if (AGENT_STATE_EVENT_NAME.equals(payloadMap.get("name"))) {
                Object state = payloadMap.get("agent_state");
                Map<String, Object> chunk = payloadMap.get("chunk") instanceof Map<?, ?> chunkMap
                        ? toStringKeyMap(chunkMap) : new LinkedHashMap<>();
                if (isEmptyAgentState(state) || isEmptyAgentState(chunk.get("agent_state"))) {
                    return null;
                }
            }
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("run_id", "thread_id")) {
            if (envelope.containsKey(key)) {
                compact.put(key, envelope.get(key));
            }
        }
        String requestId = requestIdFromPayload(payloadRaw);
        if (requestId != null) {
            compact.put("request_id", requestId);
        }
        compact.put("payload", compactRunEventPayload(
                eventType, payloadRaw instanceof Map<?, ?> map ? toStringKeyMap(map) : null));
        return compact;
    }

    // =========================================================================
    // === 运行进度 ===
    // =========================================================================

    static Map<String, Object> progressMessageFromChunk(Map<String, Object> chunk, String seq) {
        Object streamEventRaw = chunk.get("stream_event");
        if (!(streamEventRaw instanceof Map<?, ?> streamEventMap)) {
            return null;
        }
        Map<String, Object> streamEvent = toStringKeyMap(streamEventMap);
        Object streamType = streamEvent.get("type");
        String messageId = streamEvent.get("message_id") == null
                ? "" : String.valueOf(streamEvent.get("message_id")).strip();

        String content;
        String kind;
        if ("message_delta".equals(streamType)) {
            Object value = streamEvent.get("content");
            if (value == null) {
                value = streamEvent.get("reasoning_content");
            }
            if (value == null) {
                value = streamEvent.get("additional_reasoning_content");
            }
            content = value == null ? "" : String.valueOf(value);
            kind = truthy(streamEvent.get("content")) ? "assistant_message" : "assistant_reasoning";
        } else if ("tool_call".equals(streamType) || "tool_call_delta".equals(streamType)) {
            Object nameRaw = streamEvent.get("name");
            if (nameRaw == null) {
                nameRaw = streamEvent.get("tool_call_id");
            }
            String toolName = nameRaw == null ? "工具" : String.valueOf(nameRaw).strip();
            content = "tool_call".equals(streamType) ? "调用工具 " + toolName : "正在准备工具 " + toolName;
            kind = String.valueOf(streamType);
        } else {
            return null;
        }

        content = content.strip();
        if (content.isEmpty()) {
            return null;
        }
        if (content.length() > RUN_PROGRESS_CONTENT_MAX_CHARS) {
            content = "..." + content.substring(content.length() - RUN_PROGRESS_CONTENT_MAX_CHARS);
        }

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("seq", seq);
        if (!messageId.isEmpty()) {
            base.put("message_id", messageId);
        }
        String toolCallId = streamEvent.get("tool_call_id") == null
                ? "" : String.valueOf(streamEvent.get("tool_call_id")).strip();
        if (!toolCallId.isEmpty()) {
            base.put("tool_call_id", toolCallId);
        }
        base.put("kind", kind);
        base.put("content", content);
        return base;
    }

    /** 读取适合 status 轮询返回的轻量运行进度快照（对应 {@code get_agent_run_progress}）。 */
    public Map<String, Object> getAgentRunProgress(String runId, int messageLimit) {
        List<Map<String, Object>> events;
        try {
            events = runQueueService.listRecentRunStreamEvents(runId, RUN_PROGRESS_RECENT_EVENT_SCAN_LIMIT);
        } catch (Exception error) {
            log.warn("Failed to read run progress events for run {}: {}", runId, error.getMessage());
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("last_seq", "0-0");
            empty.put("messages", new ArrayList<>());
            return empty;
        }

        String lastSeq = events.isEmpty() ? "0-0" : String.valueOf(events.get(0).get("seq"));
        int limit = Math.max(1, messageLimit);
        List<Map<String, Object>> messages = new ArrayList<>();

        for (Map<String, Object> event : events) {
            Map<String, Object> envelope = event.get("payload") instanceof Map<?, ?> map
                    ? toStringKeyMap(map) : new LinkedHashMap<>();
            if (!"messages".equals(event.get("event_type")) && !"messages".equals(envelope.get("event"))) {
                continue;
            }
            Object payloadRaw = envelope.get("payload");
            if (!(payloadRaw instanceof Map<?, ?> payloadMapRaw)) {
                continue;
            }
            Map<String, Object> payload = toStringKeyMap(payloadMapRaw);
            List<Map<String, Object>> chunks = new ArrayList<>();
            if (payload.get("chunk") instanceof Map<?, ?> chunk) {
                chunks.add(toStringKeyMap(chunk));
            }
            if (payload.get("items") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map) {
                        chunks.add(toStringKeyMap(map));
                    }
                }
            }
            for (int index = chunks.size() - 1; index >= 0; index--) {
                Map<String, Object> message = progressMessageFromChunk(
                        chunks.get(index), event.get("seq") == null ? "" : String.valueOf(event.get("seq")));
                if (message != null) {
                    messages.add(message);
                }
                if (messages.size() >= limit) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("last_seq", lastSeq);
                    result.put("messages", reversed(messages));
                    return result;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("last_seq", lastSeq);
        result.put("messages", reversed(messages));
        return result;
    }

    // =========================================================================
    // === run 作用域与持久化 ===
    // =========================================================================

    static boolean sameRunRequestScope(
            AgentRun run,
            String uid,
            String agentSlug,
            String conversationThreadId,
            String runType,
            String createdByRunId,
            Integer subagentThreadRelationId) {
        return java.util.Objects.equals(run.getUid(), String.valueOf(uid))
                && java.util.Objects.equals(run.getAgentSlug(), agentSlug)
                && java.util.Objects.equals(run.getConversationThreadId(), conversationThreadId)
                && java.util.Objects.equals(run.getRunType(), runType)
                && java.util.Objects.equals(run.getCreatedByRunId(), createdByRunId)
                && java.util.Objects.equals(run.getSubagentThreadRelationId(), subagentThreadRelationId);
    }

    static ApiHttpException runBusyException(AgentRun activeRun, String agentSlug, String conversationThreadId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", "run_busy");
        detail.put("message", "该智能体线程正在运行，请等待、查询或取消当前运行后再继续");
        detail.put("active_run_id", activeRun.getId());
        detail.put("active_run_status", activeRun.getStatus());
        detail.put("agent_slug", agentSlug);
        detail.put("thread_id", conversationThreadId);
        return ApiHttpException.conflictWithDetail(detail);
    }

    /**
     * 先落库输入消息；run 创建后再回填 run_id，避免 Message 外键先指向不存在的 run
     * （对应 {@code create_agent_run_input_message}）。
     */
    public Message createAgentRunInputMessage(
            Integer conversationId, String requestId, AgentRunInputMessage inputMessage, String deliveryStatus) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent(inputMessage.content());
        message.setMessageType(inputMessage.messageType());
        message.setImageContent(inputMessage.imageContent());
        message.setRequestId(requestId);
        message.setDeliveryStatus(deliveryStatus == null ? "complete" : deliveryStatus);
        message.setExtraMetadata(JSON.toJSONString(inputMessage.extraMetadata()));
        message.setCreatedAt(DateTimeUtils.utcNowNaive());
        messageMapper.insert(message);
        return message;
    }

    /**
     * 登记一条 AgentRun 并绑定已创建的输入消息，返回是否为本次新建
     * （对应 {@code persist_agent_run_record}）。
     */
    public AgentRunRepository.RunResult persistAgentRunRecord(
            String agentSlug,
            String conversationThreadId,
            String runtimeScopeId,
            String currentUid,
            String requestId,
            Integer conversationId,
            String runType,
            Map<String, Object> inputPayload,
            Message persistedInputMessage,
            String createdByRunId,
            Integer subagentThreadRelationId,
            String source,
            String channel,
            String externalId,
            Map<String, Object> originMetadata) {
        String runId = UUID.randomUUID().toString();
        try {
            return nestedTransactionTemplate.execute(status -> {
                AgentRun run = runRepository.createRun(
                        runId,
                        conversationThreadId,
                        runtimeScopeId,
                        agentSlug,
                        String.valueOf(currentUid),
                        requestId,
                        inputPayload,
                        source == null ? "chat" : source,
                        channel == null ? "web" : channel,
                        externalId,
                        originMetadata,
                        conversationId,
                        createdByRunId,
                        subagentThreadRelationId,
                        runType,
                        persistedInputMessage.getId());
                persistedInputMessage.setRunId(runId);
                messageMapper.updateById(persistedInputMessage);
                return new AgentRunRepository.RunResult(run, true);
            });
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            AgentRun existing = runRepository.getRunByRequestId(requestId);
            if (existing != null
                    && sameRunRequestScope(
                            existing, currentUid, agentSlug, conversationThreadId, runType,
                            createdByRunId, subagentThreadRelationId)) {
                messageMapper.deleteById(persistedInputMessage.getId());
                return new AgentRunRepository.RunResult(existing, false);
            }
            AgentRun activeRun =
                    runRepository.getActiveRunByThreadForUser(agentSlug, conversationThreadId, String.valueOf(currentUid));
            if (activeRun != null) {
                throw runBusyException(activeRun, agentSlug, conversationThreadId);
            }
            throw ApiHttpException.conflict("request_id 冲突");
        }
    }

    /**
     * 校验 run 创建作用域，加载对话、智能体、后端和幂等状态，并拒绝同线程并发写入
     * （对应 {@code prepare_agent_run_creation_scope}）。
     */
    public AgentRunCreationScope prepareAgentRunCreationScope(
            String agentSlug,
            String conversationThreadId,
            String currentUid,
            String requestId,
            String runType,
            String agentKind,
            String createdByRunId,
            Integer subagentThreadRelationId) {
        if (conversationThreadId == null || conversationThreadId.isEmpty()) {
            throw ApiHttpException.unprocessable("conversation_thread_id 不能为空");
        }

        Conversation conversation = conversationRepository.lockConversationByThreadId(conversationThreadId);
        // Conversation.agent_id 是历史字段名，实际保存的是 Agent.slug。
        if (conversation == null
                || !java.util.Objects.equals(conversation.getUid(), String.valueOf(currentUid))
                || "deleted".equals(conversation.getStatus())) {
            throw ApiHttpException.notFound("对话线程不存在");
        }
        if (!java.util.Objects.equals(conversation.getAgentId(), agentSlug)) {
            throw ApiHttpException.conflict("已有线程已绑定智能体，不能切换");
        }

        User currentUser = userRepositoryGetByUid(currentUid);
        if (currentUser == null) {
            throw ApiHttpException.notFound("用户不存在");
        }

        Agent agentItem = agentRepository.getVisibleBySlug(
                agentSlug, com.wisesoft.wenqu.permissions.PermissionSubject.of(currentUser),
                "subagent".equals(agentKind)
                        ? AgentRepository.AgentEntryKind.SUBAGENT
                        : AgentRepository.AgentEntryKind.MAIN);
        if (agentItem == null) {
            throw ApiHttpException.notFound("智能体不存在");
        }

        BaseAgent agentBackend = agentManager.getAgent(agentItem.getBackendId());
        if (agentBackend == null) {
            throw ApiHttpException.notFound("智能体后端 " + agentItem.getBackendId() + " 不存在");
        }

        AgentRun existing = runRepository.getRunByRequestId(requestId);
        if (existing != null && !java.util.Objects.equals(existing.getUid(), String.valueOf(currentUid))) {
            throw ApiHttpException.conflict("request_id 冲突");
        }
        if (existing != null
                && !sameRunRequestScope(
                        existing, currentUid, agentSlug, conversationThreadId, runType,
                        createdByRunId, subagentThreadRelationId)) {
            throw ApiHttpException.conflict("request_id 冲突");
        }

        AgentRun parentRun = null;
        if ("resume".equals(runType)) {
            if (createdByRunId == null || createdByRunId.isEmpty()) {
                throw ApiHttpException.unprocessable("created_by_run_id 不能为空");
            }
            if (existing == null) {
                parentRun = runRepository.getRunForUser(createdByRunId, String.valueOf(currentUid));
                if (parentRun == null
                        || !java.util.Objects.equals(parentRun.getConversationThreadId(), conversationThreadId)
                        || !java.util.Objects.equals(parentRun.getAgentSlug(), agentSlug)) {
                    throw ApiHttpException.notFound("被恢复的运行任务不存在");
                }
                if (!"interrupted".equals(parentRun.getStatus())) {
                    throw ApiHttpException.conflict("只有 interrupted run 可以恢复");
                }
                AgentRun latestRun = runRepository.getLatestChatOrResumeRun(
                        String.valueOf(currentUid), agentSlug, conversationThreadId);
                if (latestRun != null && !java.util.Objects.equals(latestRun.getId(), parentRun.getId())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("code", "resume_superseded");
                    detail.put("message", "中断运行已被后续运行超越");
                    throw ApiHttpException.conflictWithDetail(detail);
                }
                Map<String, Object> parentPayload = parseJsonObject(parentRun.getInputPayload());
                if (parentPayload == null || !truthy(parentPayload.get("model_spec"))) {
                    throw ApiHttpException.conflict("被恢复的运行任务缺少模型快照");
                }
            }
        }
        if (existing == null) {
            AgentRun activeRun = runRepository.getActiveRunByThreadForUser(
                    agentSlug, conversationThreadId, String.valueOf(currentUid));
            if (activeRun != null) {
                throw runBusyException(activeRun, agentSlug, conversationThreadId);
            }
        }
        return new AgentRunCreationScope(conversation, agentItem, agentBackend, existing, parentRun);
    }

    /** 把已持久化的 run 投递到后台 worker 队列（对应 {@code enqueue_agent_run}）。 */
    public void enqueueAgentRun(String runId) {
        ArqPort port = arqPort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("Run 唤醒端口未装配（ARQ 队列未照搬），run 已落库但不会被执行");
        }
        try {
            port.enqueueProcessAgentRun(runId);
        } catch (Exception error) {
            throw new IllegalStateException("Run 投递失败: " + runId, error);
        }
    }

    /** {@code _commit_and_enqueue}。 */
    public void commitAndEnqueue(String runId) {
        enqueueAgentRun(runId);
    }

    // =========================================================================
    // === 恢复运行 ===
    // =========================================================================

    /**
     * 继承中断 Run 的配置创建恢复运行，提交后投递 worker
     * （对应 {@code create_resume_run_view}）。
     */
    public Map<String, Object> createResumeRunView(
            String agentSlug,
            String threadId,
            Map<String, Object> meta,
            String currentUid,
            Object resume,
            String createdByRunId,
            String source,
            String channel,
            String externalId,
            Map<String, Object> originMetadata) {
        Map<String, Object> effectiveMeta = meta == null ? new LinkedHashMap<>() : meta;
        if (resume == null) {
            throw ApiHttpException.unprocessable("resume 不能为空");
        }
        validateResumeInput(resume);

        String requestId;
        if (truthy(effectiveMeta.get("request_id"))) {
            requestId = String.valueOf(effectiveMeta.get("request_id"));
        } else {
            String resumeKey = canonicalJson(resume);
            requestId = HashUtils.hashId("resume:", createdByRunId + ":" + resumeKey, 64);
        }

        AgentRunCreationScope scope = prepareAgentRunCreationScope(
                agentSlug, threadId, currentUid, requestId, "resume", "main", createdByRunId, null);
        if (scope.existingRun() != null) {
            if ("pending".equals(scope.existingRun().getStatus())) {
                commitAndEnqueue(scope.existingRun().getId());
            }
            return buildRunResponse(scope.existingRun());
        }

        AgentRun parentRun = scope.parentRun();
        Map<String, Object> parentPayload = parseJsonObject(parentRun.getInputPayload());
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("model_spec", parentPayload == null ? null : parentPayload.get("model_spec"));
        Object parentApprovalMode = parentPayload == null ? null : parentPayload.get("tool_approval_mode");
        // 历史 interrupted Run 可能没有审批模式，沿用原默认值。
        inputPayload.put(
                "tool_approval_mode",
                parentApprovalMode == null ? ToolApproval.DEFAULT_TOOL_APPROVAL_MODE : parentApprovalMode);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("request_id", requestId);
        metadata.put("resume", resume);
        metadata.put("source", "ask_user_question_resume");
        Object attachmentFileIds = effectiveMeta.get("attachment_file_ids");
        if (attachmentFileIds instanceof List<?> list && !list.isEmpty()) {
            metadata.put("attachment_file_ids", attachmentFileIds);
        }
        if (effectiveMeta.get("agent_invocation_meta") instanceof Map<?, ?>) {
            metadata.put("agent_invocation_meta", effectiveMeta.get("agent_invocation_meta"));
        }

        Message persistedInputMessage = createAgentRunInputMessage(
                scope.conversation().getId(),
                requestId,
                InputMessageService.buildResumeInputMessage(resume).withMetadata(metadata),
                "complete");

        String effectiveSource = source != null ? source
                : (parentRun.getSource() == null ? "chat" : parentRun.getSource());
        String effectiveChannel = channel != null ? channel
                : (parentRun.getChannel() == null ? "web" : parentRun.getChannel());
        String effectiveExternalId = externalId != null ? externalId : parentRun.getExternalId();
        Map<String, Object> effectiveOriginMetadata = originMetadata != null
                ? originMetadata : parseJsonObject(parentRun.getOriginMetadata());

        AgentRunRepository.RunResult persisted = persistAgentRunRecord(
                agentSlug,
                threadId,
                null,
                currentUid,
                requestId,
                scope.conversation().getId(),
                "resume",
                inputPayload,
                persistedInputMessage,
                createdByRunId,
                null,
                effectiveSource,
                effectiveChannel,
                effectiveExternalId,
                effectiveOriginMetadata);
        if (persisted.changed()) {
            commitAndEnqueue(persisted.run().getId());
        }
        return buildRunResponse(persisted.run());
    }

    // =========================================================================
    // === 读取结果 / 取消 / 事件流 ===
    // =========================================================================

    public Map<String, Object> getAgentRunView(String runId, String currentUid) {
        AgentRun run = runRepository.getRunForUser(runId, String.valueOf(currentUid));
        if (run == null) {
            throw ApiHttpException.notFound("运行任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run.toDict());
        return result;
    }

    /** 加载某个 run 的最终结果（对应 {@code get_agent_run_result}）。 */
    public Map<String, Object> getAgentRunResult(String runId, String currentUid) {
        AgentRun run = runRepository.getRunForUser(runId, String.valueOf(currentUid));
        if (run == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("status", "failed");
            missing.put("agent_run_id", runId);
            missing.put("output", "");
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", "run_not_found");
            error.put("message", "运行任务不存在");
            missing.put("error", error);
            return missing;
        }

        Message outputMessage = null;
        if (run.getConversationId() != null) {
            outputMessage = outputRepository.getOutputMessage(
                    run.getId(),
                    run.getConversationId(),
                    run.getOutputMessageId(),
                    "completed".equals(run.getStatus()));
        }
        Map<String, Object> outputMetadata = outputMessage == null
                ? new LinkedHashMap<>() : parseJsonObject(outputMessage.getExtraMetadata());
        outputMetadata = outputMetadata == null ? new LinkedHashMap<>() : outputMetadata;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", run.getStatus());
        payload.put("output", outputMessage == null ? "" : outputMessage.getContent());
        payload.put("agent_slug", run.getAgentSlug());
        payload.put("thread_id", run.getConversationThreadId());
        payload.put("conversation_id", run.getConversationId());
        payload.put("agent_run_id", run.getId());
        payload.put("request_id", run.getRequestId());
        payload.put("final_message_id", outputMessage == null ? null : outputMessage.getId());
        payload.put(
                "langfuse_trace_id",
                truthy(run.getLangfuseTraceId()) ? run.getLangfuseTraceId() : outputMetadata.get("langfuse_trace_id"));
        Map<String, Object> tokenUsage = parseJsonObject(run.getTokenUsage());
        payload.put("token_usage", tokenUsage == null ? new LinkedHashMap<>() : tokenUsage);
        payload.put(
                "timing",
                ModelConstants.buildAgentRunTiming(
                        run.getCreatedAt(),
                        run.getStartedAt(),
                        run.getPreparedAt(),
                        run.getFirstOutputAt(),
                        run.getFinishedAt(),
                        run.getFirstModelRequestAt()));
        if (truthy(run.getErrorType()) || truthy(run.getErrorMessage())) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", run.getErrorType());
            error.put("message", run.getErrorMessage());
            payload.put("error", error);
        }
        return payload;
    }

    /** 按用户可见 Run 自身的 trace 关联解析跳转地址（对应 {@code get_agent_run_langfuse_link}）。 */
    public Map<String, Object> getAgentRunLangfuseLink(String runId, String currentUid) {
        Map<String, Object> result = getAgentRunResult(runId, currentUid);
        Object error = result.get("error");
        if (error instanceof Map<?, ?> errorMap && "run_not_found".equals(errorMap.get("type"))) {
            throw ApiHttpException.notFound("运行任务不存在");
        }
        Object traceId = result.get("langfuse_trace_id");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", runId);
        if (!(traceId instanceof String text) || text.strip().isEmpty()) {
            response.put("available", false);
            response.put("reason", "trace_not_available");
            return response;
        }
        String traceUrl = langfuseService.getTraceUrlById(text, 5.0);
        if (traceUrl == null || traceUrl.isEmpty()) {
            response.put("available", false);
            response.put("reason", "langfuse_unavailable");
            return response;
        }
        response.put("available", true);
        response.put("url", traceUrl);
        return response;
    }

    public Map<String, Object> loadAgentRunResult(String runId, String currentUid) {
        return getAgentRunResult(runId, currentUid);
    }

    /** 阻塞至 run 终结并返回最终结果（对应 {@code await_agent_run_result}）。 */
    public Map<String, Object> awaitAgentRunResult(String runId, String currentUid) {
        streamAgentRunEvents(runId, "0-0", currentUid, false, frame -> {});
        Map<String, Object> result = loadAgentRunResult(runId, currentUid);
        if (!ModelConstants.AGENT_RUN_TERMINAL_STATUSES.contains(String.valueOf(result.get("status")))) {
            throw new AgentRunWaitTimeout(result);
        }
        return result;
    }

    /** 请求取消一个 run，并可同时向仍活跃的子 run 发布取消信号（对应 {@code request_cancel_agent_run}）。 */
    public AgentRun requestCancelAgentRun(String runId, String currentUid, boolean cascadeChildren) {
        AgentRunRepository.CancelTreeResult result = runRepository.requestCancelExecutionTree(
                runId, String.valueOf(currentUid), cascadeChildren);
        if (result.run() == null) {
            throw ApiHttpException.notFound("运行任务不存在");
        }
        runQueueService.publishCancelSignals(result.cancelledIds());
        return result.run();
    }

    /** HTTP 取消入口：取消父 run 时默认级联取消活跃子 run（对应 {@code cancel_agent_run_view}）。 */
    public Map<String, Object> cancelAgentRunView(String runId, String currentUid) {
        AgentRun run = requestCancelAgentRun(runId, currentUid, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run == null ? null : run.toDict());
        return result;
    }

    // =========================================================================
    // === Run 事件流（SSE） ===
    // =========================================================================

    private AgentRun loadStreamRunForUser(String runId, String currentUid) {
        return runRepository.getRunForUser(runId, String.valueOf(currentUid));
    }

    private AgentRun loadStreamRun(String runId) {
        return runRepository.getRun(runId);
    }

    static double nextRunSsePollInterval(double currentInterval, double idleSeconds) {
        double maxInterval = idleSeconds >= RUN_SSE_LONG_IDLE_AFTER_SECONDS
                ? RUN_SSE_LONG_IDLE_MAX_POLL_SECONDS
                : RUN_SSE_SHORT_IDLE_MAX_POLL_SECONDS;
        return Math.min(Math.max(currentInterval * 2, RUN_SSE_ACTIVE_POLL_SECONDS), maxInterval);
    }

    static double jitterRunSsePollInterval(double interval) {
        double multiplier = ThreadLocalRandom.current()
                .nextDouble(1 - RUN_SSE_POLL_JITTER_RATIO, 1 + RUN_SSE_POLL_JITTER_RATIO);
        return interval * multiplier;
    }

    /**
     * 按 SSE 格式读取 run 事件流；终结事件缺失时根据数据库状态补发 end
     * （对应 {@code stream_agent_run_events}）。
     *
     * <p>语言差异：Python 异步生成器 → 回调 sink；{@code asyncio.sleep} → {@link Thread#sleep}。
     */
    public void streamAgentRunEvents(
            String runId, String afterSeq, String currentUid, boolean verbose, java.util.function.Consumer<String> sink) {
        LocalDateTime startedAt = DateTimeUtils.utcNowNaive();
        long startedMonotonic = System.nanoTime();
        double lastHeartbeatMonotonic = startedMonotonic;
        String lastSeq = RunQueueService.normalizeAfterSeq(afterSeq);
        long lastEventMonotonic = startedMonotonic;
        long nextStatusCheckMonotonic = startedMonotonic + (long) (RUN_SSE_STATUS_POLL_SECONDS * 1_000_000_000L);
        double pollInterval = RUN_SSE_ACTIVE_POLL_SECONDS;

        try {
            AgentRun run;
            try {
                run = loadStreamRunForUser(runId, currentUid);
                if (run == null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("run_id", runId);
                    data.put("message", "运行任务不存在");
                    sink.accept(SseUtils.formatSse(data, "error", null));
                    return;
                }
            } catch (Exception error) {
                log.warn("Run SSE DB error for run {}: {}", runId, error.getMessage());
                sink.accept(SseUtils.formatSse(errorEnvelope(runId, "db_error"), "error", null));
                return;
            }

            while (true) {
                List<Map<String, Object>> events;
                try {
                    events = runQueueService.listRunStreamEvents(runId, lastSeq, 200);
                } catch (Exception error) {
                    log.warn("Run SSE redis error for run {}: {}", runId, error.getMessage());
                    sink.accept(SseUtils.formatSse(errorEnvelope(runId, "redis_error"), "error", null));
                    return;
                }

                if (!events.isEmpty()) {
                    lastEventMonotonic = System.nanoTime();
                    pollInterval = RUN_SSE_ACTIVE_POLL_SECONDS;
                }

                boolean emittedTerminal = false;
                for (Map<String, Object> event : events) {
                    String seq = event.get("seq") == null ? "0-0" : String.valueOf(event.get("seq"));
                    lastSeq = seq;
                    String eventType = event.get("event_type") == null
                            ? "message" : String.valueOf(event.get("event_type"));
                    Map<String, Object> envelope = event.get("payload") instanceof Map<?, ?> map
                            ? toStringKeyMap(map) : new LinkedHashMap<>();
                    if (!verbose) {
                        envelope = compactRunEventEnvelope(envelope);
                        if (envelope == null) {
                            continue;
                        }
                    }
                    sink.accept(SseUtils.formatSse(envelope, eventType, seq));
                    if ("end".equals(eventType)) {
                        emittedTerminal = true;
                    }
                }

                if (emittedTerminal) {
                    return;
                }

                long nowMonotonic = System.nanoTime();
                if (nowMonotonic >= nextStatusCheckMonotonic) {
                    try {
                        run = loadStreamRun(runId);
                        if (run == null) {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("run_id", runId);
                            data.put("message", "运行任务不存在");
                            sink.accept(SseUtils.formatSse(data, "error", null));
                            return;
                        }
                    } catch (Exception error) {
                        log.warn("Run SSE DB error for run {}: {}", runId, error.getMessage());
                        sink.accept(SseUtils.formatSse(errorEnvelope(runId, "db_error"), "error", null));
                        return;
                    }
                    nextStatusCheckMonotonic = System.nanoTime() + (long) (RUN_SSE_STATUS_POLL_SECONDS * 1_000_000_000L);
                }

                if (ModelConstants.AGENT_RUN_TERMINAL_STATUSES.contains(run.getStatus())
                        && !Boolean.TRUE.equals(run.getRuntimeCleanupPending())
                        && events.isEmpty()) {
                    String terminalSeq = lastSeq;
                    if (terminalSeq.isEmpty() || "0-0".equals(terminalSeq)) {
                        terminalSeq = runQueueService.getLastRunStreamSeq(runId);
                    }
                    if (terminalSeq.isEmpty() || "0-0".equals(terminalSeq)) {
                        terminalSeq = null;
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", run.getStatus());
                    payload.put("request_id", run.getRequestId());
                    Map<String, Object> terminalEnvelope = RunQueueService.buildRunEventEnvelope(
                            runId, "end", payload, run.getConversationThreadId(), DateTimeUtils.utcIsoformat());
                    if (!verbose) {
                        terminalEnvelope = compactRunEventEnvelope(terminalEnvelope);
                    }
                    sink.accept(SseUtils.formatSse(terminalEnvelope, "end", terminalSeq));
                    return;
                }

                LocalDateTime now = DateTimeUtils.utcNowNaive();
                double elapsedSeconds = java.time.Duration.between(startedAt, now).toMillis() / 1000.0;
                double heartbeatElapsed =
                        (System.nanoTime() - lastHeartbeatMonotonic) / 1_000_000_000.0;
                if (heartbeatElapsed >= SseUtils.SSE_HEARTBEAT_SECONDS) {
                    sink.accept(SseUtils.formatHeartbeat());
                    lastHeartbeatMonotonic = System.nanoTime();
                }

                if (elapsedSeconds >= SseUtils.SSE_MAX_CONNECTION_MINUTES * 60L) {
                    return;
                }

                double statusCheckDelay = Math.max(0.0, (nextStatusCheckMonotonic - System.nanoTime()) / 1_000_000_000.0);
                double sleepSeconds = Math.min(jitterRunSsePollInterval(pollInterval), statusCheckDelay);
                Thread.sleep(Math.max(0L, (long) (sleepSeconds * 1000)));
                if (events.isEmpty()) {
                    double idleSeconds = (System.nanoTime() - lastEventMonotonic) / 1_000_000_000.0;
                    pollInterval = nextRunSsePollInterval(pollInterval, idleSeconds);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
    }

    private static Map<String, Object> errorEnvelope(String runId, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        data.put("message", "运行事件流暂时不可用，请重连");
        data.put("reason", reason);
        return data;
    }

    /** 读取线程当前仍需前端关注的最近一个 chat/resume run（对应 {@code get_active_run_by_thread}）。 */
    public Map<String, Object> getActiveRunByThread(String threadId, String currentUid) {
        AgentRun run = runRepository.getLatestChatOrResumeRun(currentUid, null, threadId);
        // 线程内的 run 是串行的，最近一条 run 即代表线程当前状态；
        // 已被回复的 interrupted run 会被更晚创建的 resume run 取代，故不会再被当作待处理中断返回。
        Map<String, Object> result = new LinkedHashMap<>();
        if (run != null && List.of("pending", "running", "cancel_requested", "interrupted")
                .contains(run.getStatus())) {
            result.put("run", run.toDict());
            return result;
        }
        result.put("run", null);
        return result;
    }

    // =========================================================================
    // === 内部小工具 ===
    // =========================================================================

    private User userRepositoryGetByUid(String uid) {
        return userRepositoryRef.getByUid(uid);
    }

    /** 追加注入（避免构造参数过长）：用户仓储。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.wisesoft.wenqu.repositories.UserRepository userRepositoryRef;

    static Map<String, Object> parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Object parsed = JSON.parse(raw);
            return parsed instanceof Map<?, ?> map ? toStringKeyMap(map) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static List<Map<String, Object>> reversed(List<Map<String, Object>> values) {
        List<Map<String, Object>> copy = new ArrayList<>(values);
        java.util.Collections.reverse(copy);
        return copy;
    }

    static String canonicalJson(Object payload) {
        return JSON.toJSONString(payload);
    }

    /** 便捷构造 409 详情（供同包其它服务复用）。 */
    static ApiHttpException queueConflict(String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        return ApiHttpException.conflictWithDetail(detail);
    }

    static Set<String> asStringSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
