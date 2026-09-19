package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.common.JsonValues;
import com.wisesoft.wenqu.common.PydanticValidation;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentRequestService;
import com.wisesoft.wenqu.service.AgentRunService;
import com.wisesoft.wenqu.service.InputMessageService;
import com.wisesoft.wenqu.service.RunQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent Evaluation 路由，逐端点对齐参考实现 {@code server/routers/agent_invocation_eval_router.py}
 * （1 个端点 + 轻量轨迹摘要）。
 *
 * <p>前缀 {@code /api/agent-invocation/eval}（参考实现 {@code prefix="/agent-invocation/eval"}）。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()} 取 uid，再经
 *       {@link UserRepository#getByUid} 取用户实体。</li>
 *   <li>{@code list_run_stream_events(run_id, after_seq="0-0", limit=500)} →
 *       {@link RunQueueService#listRunStreamEvents(String, String, int)}（同一 Redis Stream 读取面）。</li>
 *   <li>pydantic 请求模型 → {@code @RequestBody Map} + {@link PydanticValidation} 逐条补齐。
 *       {@code AgentEvaluationContext} 无 {@code extra="forbid"}，未知键静默忽略——参考单测
 *       {@code test_agent_eval_router_adapts_payload} 传 {@code {"ignored": "drop"}} 正是断言这一点。</li>
 *   <li>{@code HTTPException(504, detail={"message": ..., "run": ...})} → 同形结构化 detail。</li>
 * </ul>
 *
 * <h3>照搬时容易写丢的点</h3>
 * <ul>
 *   <li>{@code thread_id} 有 {@code max_length=64}（call 路由<b>没有</b>）→ 超长 422；参考单测
 *       {@code test_agent_eval_router_rejects_thread_id_longer_than_database_limit} 专测这条。</li>
 *   <li>{@code evaluation} 落到 origin metadata 前要经 {@code model_dump(exclude_none=True)} +
 *       白名单过滤：只保留 {@code EVALUATION_FIELDS} 且非空 strip——不是原样透传整个对象。</li>
 *   <li>{@code request_id} 从 {@code meta} 里取（call 路由从顶层取），且同样有 64 字符上限。</li>
 *   <li>{@code queue_policy} 固定 {@code reject}（评估是阻塞同步语义，不参与排队）。</li>
 *   <li>轨迹摘要失败<b>只告警不影响主结果</b>（参考实现 catch Exception 后 logger.warning）。
 *       写成抛错会让一次成功的评估因为事件流读取失败而整体 500。</li>
 * </ul>
 *
 * <h3>依赖面说明（未搬部分）</h3>
 * <p>事件流的<b>写入侧</b>（{@code services/run_worker.py}，含 {@code _map_chunk_to_run_event} 与
 * {@code {"items": buffer.items}} / {@code {"chunk": chunk}} 两种 payload 形状）属 §一 未搬项。
 * 本控制器的读取形状与 {@link RunQueueService} 的既有实现一致，待写入侧落地后即整体闭环；
 * 在此之前 {@code include_trajectory_summary} 读到的是空事件列表（摘要为零计数，不是错误）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-invocation/eval")
@RequiredArgsConstructor
@Tag(name = "agent-invocation", description = "Agent Evaluation HTTP 协议适配与轻量轨迹摘要")
public class AgentInvocationEvalController {

    /** 参考实现 {@code EVALUATION_FIELDS}：落到 origin metadata 的评估字段白名单。 */
    private static final List<String> EVALUATION_FIELDS =
            List.of("dataset_name", "dataset_item_id", "experiment_name");

    /** 参考实现 {@code EVALUATION_SOURCE}。 */
    private static final String EVALUATION_SOURCE = "agent_evaluation";

    /** 参考实现 {@code TRAJECTORY_SUMMARY_EVENT_LIMIT}。 */
    private static final int TRAJECTORY_SUMMARY_EVENT_LIMIT = 500;

    /** 参考实现 {@code INTERRUPT_STATUSES}。 */
    private static final Set<String> INTERRUPT_STATUSES =
            Set.of("ask_user_question_required", "human_approval_required", "interrupted");

    /** {@code _build_trajectory_summary} 里 {@code chunk.status} 计中断时豁免的事件类型。 */
    private static final Set<String> INTERRUPT_EXEMPT_EVENT_TYPES = Set.of("interrupt", "end");

    /** 参考实现 {@code AgentEvalRunCreate.thread_id} 的 {@code max_length}。 */
    private static final int MAX_THREAD_ID_LENGTH = 64;

    /** 参考实现 {@code _normalize_request_id} 的长度上限。 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private static final String INVOCATION_THREAD_PREFIX = "invocation_";

    private static final int INVOCATION_THREAD_LENGTH = 64;

    private final AgentRequestService agentRequestService;
    private final AgentRunService agentRunService;
    private final RunQueueService runQueueService;
    private final UserRepository userRepository;

    /** 运行一次评估样例，并阻塞等待最终 AgentRun 结果（对应 {@code create_agent_eval_run}）。 */
    @Operation(summary = "创建评估运行",
            description = "body: query/agent_slug/thread_id/evaluation/meta/image_content/"
                    + "model_spec/tool_approval_mode/include_trajectory_summary")
    @PostMapping("/runs")
    public Map<String, Object> createAgentEvalRun(@RequestBody(required = false) Map<String, Object> body) {
        PydanticValidation.requireBody(body);
        String rawQuery = PydanticValidation.requireString(body, "query", PydanticValidation.NO_LENGTH_LIMIT);
        String rawAgentSlug = PydanticValidation.requireString(body, "agent_slug", PydanticValidation.NO_LENGTH_LIMIT);
        String threadIdInput = PydanticValidation.optionalString(body, "thread_id", MAX_THREAD_ID_LENGTH);
        Map<String, Object> evaluationInput =
                PydanticValidation.optionalModel(body, "evaluation", "AgentEvaluationContext");
        Map<String, Object> meta = PydanticValidation.optionalDict(body, "meta");
        String imageContent = PydanticValidation.optionalString(body, "image_content", PydanticValidation.NO_LENGTH_LIMIT);
        String modelSpec = PydanticValidation.optionalString(body, "model_spec", PydanticValidation.NO_LENGTH_LIMIT);
        String toolApprovalMode =
                PydanticValidation.optionalString(body, "tool_approval_mode", PydanticValidation.NO_LENGTH_LIMIT);
        boolean includeTrajectorySummary = Boolean.TRUE.equals(
                PydanticValidation.optionalBoolean(body, "include_trajectory_summary", Boolean.FALSE));

        User currentUser = requireCurrentUser();
        String uid = String.valueOf(currentUser.getUid());

        String agentSlug = rawAgentSlug.strip();
        if (agentSlug.isEmpty()) {
            throw ApiHttpException.unprocessable("agent_slug 不能为空");
        }
        if (rawQuery.isEmpty()) {
            throw ApiHttpException.unprocessable("query 不能为空");
        }

        String requestId = normalizeRequestId(meta);
        Map<String, Object> evaluation = normalizeEvaluation(evaluationInput);
        Map<String, Object> originMetadata = new LinkedHashMap<>();
        if (!evaluation.isEmpty()) {
            Map<String, Object> invocationMeta = new LinkedHashMap<>();
            invocationMeta.put("evaluation", evaluation);
            originMetadata.put("agent_invocation_meta", invocationMeta);
        }

        String threadId = threadIdInput == null ? "" : threadIdInput.strip();
        if (threadId.isEmpty()) {
            threadId = HashUtils.hashId(
                    INVOCATION_THREAD_PREFIX, uid + ":" + agentSlug + ":" + requestId, INVOCATION_THREAD_LENGTH);
        }

        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("request_id", requestId);
        requestMetadata.put(
                "attachment_file_ids",
                JsonValues.or(meta.get("attachment_file_ids"), new ArrayList<>()));

        AgentRequestService.AgentRequestInput requestInput = new AgentRequestService.AgentRequestInput(
                agentSlug,
                threadId,
                requestId,
                InputMessageService.buildChatInputMessage(rawQuery, imageContent),
                new AgentRequestService.RunOrigin(EVALUATION_SOURCE, "api", requestId, originMetadata),
                requestMetadata,
                modelSpec,
                toolApprovalMode,
                "reject",
                true,
                "Agent Evaluation Run",
                null);

        Map<String, Object> runResponse = agentRequestService.submitAgentRequest(requestInput, currentUser);
        String runId = JsonValues.text(runResponse.get("run_id"));

        Map<String, Object> result;
        try {
            result = agentRunService.awaitAgentRunResult(runId, uid);
        } catch (AgentRunService.AgentRunWaitTimeout exc) {
            throw waitTimeout(exc);
        }

        if (includeTrajectorySummary) {
            try {
                Map<String, Object> summary = loadTrajectorySummary(runId);
                if (JsonValues.truthy(result.get("langfuse_trace_id"))) {
                    summary.put("langfuse_trace_id", result.get("langfuse_trace_id"));
                }
                result.put("trajectory_summary", summary);
            } catch (RuntimeException exc) {
                log.warn("Failed to load trajectory summary for run {}: {}", runId, exc.toString());
            }
        }
        return result;
    }

    // =========================================================================
    // === 内部小工具（逐函数对齐参考实现同名私有函数） ===
    // =========================================================================

    /** 从评估元数据中提取或生成请求幂等 ID（对应 {@code _normalize_request_id}）。 */
    private static String normalizeRequestId(Map<String, Object> meta) {
        String requestId = JsonValues.text(JsonValues.or(meta.get("request_id"), "")).strip();
        if (!requestId.isEmpty()) {
            if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
                throw ApiHttpException.unprocessable("request_id 不能超过 " + MAX_REQUEST_ID_LENGTH + " 个字符");
            }
            return requestId;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 只保留非空的评估上下文字段（对应 {@code _normalize_evaluation}）。
     *
     * <p>入参对应 {@code payload.evaluation.model_dump(exclude_none=True)}：子模型字段是
     * {@code str | None}，pydantic 已保证值只会是字符串或 null，故此处非字符串即报
     * {@code string_type}（而不是静默 {@code str()} 转换——那会把 {@code dataset_name: 123}
     * 变成合法输入，参考实现会 422）。
     */
    private static Map<String, Object> normalizeEvaluation(Map<String, Object> evaluation) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String key : EVALUATION_FIELDS) {
            if (!evaluation.containsKey(key) || evaluation.get(key) == null) {
                continue;
            }
            String value = PydanticValidation.checkStringAt(
                    evaluation.get(key),
                    PydanticValidation.bodyLoc("evaluation", key),
                    PydanticValidation.NO_LENGTH_LIMIT);
            if (!value.strip().isEmpty()) {
                normalized.put(key, value.strip());
            }
        }
        return normalized;
    }

    /** 读取运行事件并生成轻量轨迹摘要（对应 {@code _load_trajectory_summary}）。 */
    private Map<String, Object> loadTrajectorySummary(String runId) {
        List<Map<String, Object>> events =
                runQueueService.listRunStreamEvents(runId, "0-0", TRAJECTORY_SUMMARY_EVENT_LIMIT);
        return buildTrajectorySummary(events);
    }

    /** 从运行事件中统计工具调用、错误和中断概览（对应 {@code _build_trajectory_summary}）。 */
    private static Map<String, Object> buildTrajectorySummary(List<Map<String, Object>> events) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", 1);
        summary.put("source", "run_events");
        summary.put("event_count", events.size());
        summary.put("events_truncated", events.size() >= TRAJECTORY_SUMMARY_EVENT_LIMIT);

        Map<String, Object> eventRange = new LinkedHashMap<>();
        eventRange.put("first_seq", seqOf(events.isEmpty() ? null : events.get(0)));
        eventRange.put("last_seq", seqOf(events.isEmpty() ? null : events.get(events.size() - 1)));
        summary.put("event_range", eventRange);

        // 先占位保证键序与参考实现一致，循环结束后回填实际计数。
        summary.put("tool_call_count", 0);
        summary.put("tool_error_count", 0);
        summary.put("interrupt_count", 0);
        summary.put("tools", new ArrayList<>());

        Map<String, String> toolCalls = new LinkedHashMap<>();
        Set<String> toolErrors = new LinkedHashSet<>();
        Map<String, List<String>> openTools = new LinkedHashMap<>();
        int[] fallbackIndex = {0};
        int interruptCount = 0;

        for (Map<String, Object> event : events) {
            String eventType = JsonValues.text(event.get("event_type"));
            if ("interrupt".equals(eventType)) {
                interruptCount++;
            }
            for (Map<String, Object> chunk : iterEventChunks(event)) {
                if (!INTERRUPT_EXEMPT_EVENT_TYPES.contains(eventType)
                        && INTERRUPT_STATUSES.contains(JsonValues.text(chunk.get("status")))) {
                    interruptCount++;
                }

                Object streamEvent = chunk.get("stream_event");
                if (streamEvent instanceof Map<?, ?>) {
                    Map<String, Object> streamMap = JsonValues.asMap(streamEvent);
                    if ("tool_call".equals(JsonValues.text(streamMap.get("type")))) {
                        String name = orUnknown(streamMap.get("name"));
                        String key = toolKey(
                                JsonValues.text(streamMap.get("tool_call_id")), name, true, false,
                                openTools, fallbackIndex);
                        toolCalls.putIfAbsent(key, name);
                    }
                }

                Object toolEvent = chunk.get("event");
                Object dataRaw = toolEvent instanceof Map<?, ?> ? JsonValues.asMap(toolEvent).get("data") : null;
                if (!(dataRaw instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> data = JsonValues.asMap(dataRaw);
                String name = orUnknown(data.get("tool_name"), data.get("name"));
                String eventName = JsonValues.text(data.get("event"));
                String key = toolKey(
                        JsonValues.text(data.get("tool_call_id")),
                        name,
                        "tool-started".equals(eventName),
                        "tool-finished".equals(eventName),
                        openTools,
                        fallbackIndex);
                if ("tool-started".equals(eventName) || !toolCalls.containsKey(key)) {
                    toolCalls.putIfAbsent(key, name);
                }
                if (JsonValues.truthy(data.get("error")) || "error".equals(eventType)) {
                    toolErrors.add(key);
                }
            }
        }

        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : toolCalls.entrySet()) {
            Map<String, Object> item = tools.computeIfAbsent(entry.getValue(), toolName -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("name", toolName);
                created.put("call_count", 0);
                created.put("error_count", 0);
                return created;
            });
            item.put("call_count", (Integer) item.get("call_count") + 1);
            if (toolErrors.contains(entry.getKey())) {
                item.put("error_count", (Integer) item.get("error_count") + 1);
            }
        }

        List<Map<String, Object>> toolList = new ArrayList<>(tools.values());
        toolList.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.get("name"))));

        summary.put("interrupt_count", interruptCount);
        summary.put("tool_call_count", toolCalls.size());
        summary.put("tool_error_count", toolErrors.size());
        summary.put("tools", toolList);
        return summary;
    }

    /** 对应 {@code tool_key}：把 started/finished 事件对归并到同一个工具调用 key。 */
    private static String toolKey(
            String toolCallId,
            String name,
            boolean start,
            boolean finish,
            Map<String, List<String>> openTools,
            int[] fallbackIndex) {
        if (JsonValues.truthy(toolCallId)) {
            return toolCallId;
        }
        List<String> pending = openTools.get(name);
        if (finish && pending != null && !pending.isEmpty()) {
            return pending.remove(0);
        }
        String key = "name:" + name + ":" + fallbackIndex[0];
        fallbackIndex[0]++;
        if (start && !finish) {
            openTools.computeIfAbsent(name, ignored -> new ArrayList<>()).add(key);
        }
        return key;
    }

    /**
     * 遍历单个运行事件里的有效 chunk（对应 {@code _iter_event_chunks}）。
     *
     * <p>事件信封为 {@code {seq, event_type, payload: {schema_version, run_id, thread_id, event,
     * payload: {items|chunk}, created_at}, ts}}，chunk 在<b>内层</b> {@code payload} 里，且
     * {@code items} 先于 {@code chunk} yield（顺序影响 {@code tool_key} 的 fallback 归并）。
     */
    private static List<Map<String, Object>> iterEventChunks(Map<String, Object> event) {
        Object envelope = event.get("payload");
        if (!(envelope instanceof Map<?, ?>)) {
            return List.of();
        }
        Object payload = JsonValues.asMap(envelope).get("payload");
        if (!(payload instanceof Map<?, ?>)) {
            return List.of();
        }
        Map<String, Object> payloadMap = JsonValues.asMap(payload);
        List<Map<String, Object>> chunks = new ArrayList<>();
        Object items = payloadMap.get("items");
        if (items instanceof List<?> itemList) {
            for (Object item : itemList) {
                if (item instanceof Map<?, ?>) {
                    chunks.add(JsonValues.asMap(item));
                }
            }
        }
        Object chunk = payloadMap.get("chunk");
        if (chunk instanceof Map<?, ?>) {
            chunks.add(JsonValues.asMap(chunk));
        }
        return chunks;
    }

    private static Object seqOf(Map<String, Object> event) {
        if (event == null || event.get("seq") == null) {
            return null;
        }
        return String.valueOf(event.get("seq"));
    }

    /** 对应 {@code str(value or "unknown")}。 */
    private static String orUnknown(Object value) {
        return JsonValues.text(JsonValues.or(value, "unknown"));
    }

    /** 对应 {@code str(a or b or "unknown")}。 */
    private static String orUnknown(Object primary, Object secondary) {
        return JsonValues.text(JsonValues.or(JsonValues.or(primary, secondary), "unknown"));
    }

    /** 等待超时 → 504，detail 为结构化 {@code {"message": ..., "run": ...}}（对应参考实现的同形 raise）。 */
    private static ApiHttpException waitTimeout(AgentRunService.AgentRunWaitTimeout exc) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", "运行仍在进行中，等待最终结果超时");
        detail.put("run", exc.getResult());
        return ApiHttpException.objectDetail(504, "运行仍在进行中，等待最终结果超时", detail);
    }

    /** 取当前用户实体（参考实现里由认证依赖一并完成）。 */
    private User requireCurrentUser() {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw ApiHttpException.notFound("用户不存在");
        }
        return user;
    }
}
