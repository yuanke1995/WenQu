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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent Call 路由，逐端点对齐参考实现 {@code server/routers/agent_invocation_call_router.py}（2 个端点）。
 *
 * <p>前缀 {@code /api/agent-invocation/agent-call}（参考实现 {@code prefix="/agent-invocation/agent-call"}）。
 * 本模块<b>只做 HTTP 协议适配</b>：请求/响应格式、同步等待、OpenAI-compatible 响应装配；
 * Conversation / Request / Run 的创建统一交给 {@link AgentRequestService#submitAgentRequest}。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()} 取 uid，再经
 *       {@link UserRepository#getByUid} 取用户实体（对应参考实现里由认证依赖一并完成的用户加载）。</li>
 *   <li>pydantic 请求模型 → {@code @RequestBody Map} + {@link PydanticValidation} 逐条补齐：
 *       必填 {@code Field(...)}（{@code agent_slug} / {@code messages} / {@code run_id}）、可选
 *       {@code str | None}、{@code bool} 的宽松解析。三个模型都<b>没有</b>
 *       {@code extra="forbid"}，故未知键按 pydantic 默认静默忽略，本类不做拒绝。</li>
 *   <li>{@code HTTPException(504, detail={"message": ..., "run": ...})} → 同形结构化 detail。</li>
 * </ul>
 *
 * <h3>照搬时容易写丢的点</h3>
 * <ul>
 *   <li>{@code _normalize_required_text}：{@code agent_slug=" translator "} 必须 strip（参考单测
 *       {@code test_agent_call_router_adapts_payload} 断言），不是原样透传。</li>
 *   <li>{@code queue_policy} 默认值是两个分支二选一（{@code async_mode} 决定 {@code enqueue} /
 *       {@code reject}），且"同步 + 非 reject"必须 422；漏掉这条会让同步调用被静默降级为排队。</li>
 *   <li>{@code _validate_agent_call_meta}：{@code agent_call_meta.context} 是<b>故意</b>拒绝的
 *       （防止绕过显式运行上下文字段），不能当成普通字段放过。</li>
 *   <li>OpenAI 兼容响应里 {@code choices[0]} 的键名是 {@code messages}（不是 OpenAI 惯例的
 *       {@code message}）——这是参考实现的字面行为，照搬，勿"顺手修正"。</li>
 *   <li>{@code status == "dispatched"} 对外报 {@code "pending"}。</li>
 * </ul>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/agent-invocation/agent-call")
@RequiredArgsConstructor
@Tag(name = "agent-invocation", description = "Agent Call HTTP 协议适配（OpenAI 兼容）")
public class AgentInvocationCallController {

    /** 参考实现 {@code MAX_REQUEST_ID_LENGTH}。 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    /** 参考实现 {@code _invocation_thread_id} 的哈希前缀与长度。 */
    private static final String INVOCATION_THREAD_PREFIX = "invocation_";

    private static final int INVOCATION_THREAD_LENGTH = 64;

    private static final Set<String> FAILED_FINISH_STATUSES = Set.of("failed", "cancelled", "interrupted");

    private final AgentRequestService agentRequestService;
    private final AgentRunService agentRunService;
    private final UserRepository userRepository;

    /** 创建 Agent Call，并按 {@code async_mode} 决定是否等待最终结果（对应 {@code create_agent_call_run}）。 */
    @Operation(summary = "创建 Agent Call 运行",
            description = "body: agent_slug/messages/stream/agent_call_meta/thread_id/request_id/"
                    + "model_spec/tool_approval_mode/async_mode/queue_policy")
    @PostMapping("/runs")
    public Map<String, Object> createAgentCallRun(@RequestBody(required = false) Map<String, Object> body) {
        PydanticValidation.requireBody(body);
        // pydantic 契约先行（校验不过不进入业务）。
        String rawAgentSlug = PydanticValidation.requireString(body, "agent_slug", PydanticValidation.NO_LENGTH_LIMIT);
        List<Map<String, Object>> messages = PydanticValidation.requireListOfDict(body, "messages");
        boolean stream = Boolean.TRUE.equals(
                PydanticValidation.optionalBoolean(body, "stream", Boolean.FALSE));
        Map<String, Object> agentCallMeta = PydanticValidation.optionalDict(body, "agent_call_meta");
        String threadIdInput = PydanticValidation.optionalString(body, "thread_id", PydanticValidation.NO_LENGTH_LIMIT);
        String requestIdInput =
                PydanticValidation.optionalString(body, "request_id", PydanticValidation.NO_LENGTH_LIMIT);
        String modelSpec = PydanticValidation.optionalString(body, "model_spec", PydanticValidation.NO_LENGTH_LIMIT);
        String toolApprovalMode =
                PydanticValidation.optionalString(body, "tool_approval_mode", PydanticValidation.NO_LENGTH_LIMIT);
        boolean asyncMode = Boolean.TRUE.equals(
                PydanticValidation.optionalBoolean(body, "async_mode", Boolean.FALSE));
        String queuePolicyInput =
                PydanticValidation.optionalString(body, "queue_policy", PydanticValidation.NO_LENGTH_LIMIT);

        User currentUser = requireCurrentUser();
        String uid = String.valueOf(currentUser.getUid());

        String agentSlug = normalizeRequiredText(rawAgentSlug, "agent_slug");
        if (stream) {
            throw ApiHttpException.unprocessable("agent-call 暂不支持 stream=true");
        }

        InputMessageService.AgentRunInputMessage inputMessage = extractInputMessage(messages);
        String requestId = normalizeRequestId(requestIdInput);
        validateAgentCallMeta(agentCallMeta);

        // 参考实现：str(payload.queue_policy or ("enqueue" if async_mode else "reject")).strip()
        // 注意「空值回退」发生在 strip 之前，故 "  " 会被 strip 成空串而非回退成默认值。
        String rawQueuePolicy = queuePolicyInput == null || queuePolicyInput.isEmpty()
                ? (asyncMode ? "enqueue" : "reject")
                : queuePolicyInput;
        String queuePolicy = rawQueuePolicy.strip();
        if (!asyncMode && !"reject".equals(queuePolicy)) {
            throw ApiHttpException.unprocessable("同步 agent-call 仅支持 queue_policy=reject");
        }

        String threadId = threadIdInput == null ? "" : threadIdInput.strip();
        if (threadId.isEmpty()) {
            threadId = invocationThreadId(uid, agentSlug, requestId);
        }

        Map<String, Object> originMetadata = new LinkedHashMap<>();
        if (!agentCallMeta.isEmpty()) {
            originMetadata.put("agent_invocation_meta", new LinkedHashMap<>(agentCallMeta));
        }
        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("request_id", requestId);

        AgentRequestService.AgentRequestInput requestInput = new AgentRequestService.AgentRequestInput(
                agentSlug,
                threadId,
                requestId,
                inputMessage,
                new AgentRequestService.RunOrigin("agent_call", "api", requestId, originMetadata),
                requestMetadata,
                modelSpec,
                toolApprovalMode,
                queuePolicy,
                true,
                "Agent Call Run",
                null);

        Map<String, Object> runResponse = agentRequestService.submitAgentRequest(requestInput, currentUser);

        if (asyncMode) {
            if (!JsonValues.truthy(runResponse.get("run_id"))) {
                return runResponse;
            }
            Map<String, Object> asyncResult = new LinkedHashMap<>();
            asyncResult.put("run_id", runResponse.get("run_id"));
            asyncResult.put("agent_slug", agentSlug);
            asyncResult.put("thread_id", runResponse.get("thread_id"));
            asyncResult.put("status", runResponse.get("status"));
            asyncResult.put("request_id", runResponse.get("request_id"));
            asyncResult.put("output", "");
            return buildAgentCallResponse(asyncResult);
        }

        if ("rejected".equals(JsonValues.text(runResponse.get("status")))) {
            return runResponse;
        }

        String runId = JsonValues.text(runResponse.get("run_id"));
        Map<String, Object> result;
        try {
            result = agentRunService.awaitAgentRunResult(runId, uid);
        } catch (AgentRunService.AgentRunWaitTimeout exc) {
            throw waitTimeout(exc);
        }
        return buildAgentCallResponse(result);
    }

    /** 读取 Agent Call Run 的 OpenAI-compatible 结果（对应 {@code get_agent_call_run_result}）。 */
    @Operation(summary = "读取 Agent Call 运行结果", description = "body: run_id/agent_slug")
    @PostMapping("/runs/result")
    public Map<String, Object> getAgentCallRunResult(@RequestBody(required = false) Map<String, Object> body) {
        PydanticValidation.requireBody(body);
        String rawRunId = PydanticValidation.requireString(body, "run_id", PydanticValidation.NO_LENGTH_LIMIT);
        String rawAgentSlug =
                PydanticValidation.optionalString(body, "agent_slug", PydanticValidation.NO_LENGTH_LIMIT);

        String uid = AuthGuards.requireUser();

        // 参考实现：str(payload.run_id or "").strip()，空则 422（不是 404）。
        String runId = rawRunId.strip();
        if (runId.isEmpty()) {
            throw ApiHttpException.unprocessable("run_id 不能为空");
        }
        Map<String, Object> runView = agentRunService.getAgentRunView(runId, uid);
        Map<String, Object> run = JsonValues.asMap(runView.get("run"));
        String expectedAgentSlug = rawAgentSlug == null ? "" : rawAgentSlug.strip();
        if (!expectedAgentSlug.isEmpty() && !expectedAgentSlug.equals(run.get("agent_slug"))) {
            throw ApiHttpException.conflict("run_id 与 agent_slug 不匹配");
        }
        Map<String, Object> result = agentRunService.getAgentRunResult(runId, uid);
        return buildAgentCallResponse(result);
    }

    // =========================================================================
    // === 内部小工具（逐函数对齐参考实现同名私有函数） ===
    // =========================================================================

    /** 为没有显式 Thread 的 Agent Call 生成稳定线程 ID（对应 {@code _invocation_thread_id}）。 */
    private static String invocationThreadId(String uid, String agentSlug, String requestId) {
        return HashUtils.hashId(
                INVOCATION_THREAD_PREFIX, uid + ":" + agentSlug + ":" + requestId, INVOCATION_THREAD_LENGTH);
    }

    /** 清理必填文本字段，空值返回 422（对应 {@code _normalize_required_text}）。 */
    private static String normalizeRequiredText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw ApiHttpException.unprocessable(fieldName + " 不能为空");
        }
        return normalized;
    }

    /** 生成或校验 Agent Call 请求幂等 ID（对应 {@code _normalize_request_id}）。 */
    private static String normalizeRequestId(String value) {
        if (value == null || value.strip().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_REQUEST_ID_LENGTH) {
            throw ApiHttpException.unprocessable("request_id 不能超过 " + MAX_REQUEST_ID_LENGTH + " 个字符");
        }
        return normalized;
    }

    /** 拒绝通过元数据绕过显式运行上下文字段（对应 {@code _validate_agent_call_meta}）。 */
    private static void validateAgentCallMeta(Map<String, Object> meta) {
        if (meta.containsKey("context")) {
            throw ApiHttpException.unprocessable(
                    "agent_call_meta.context 不允许覆盖 Agent context，请使用 model_spec 覆盖模型");
        }
    }

    /** 从消息列表中提取最后一条 user 消息作为运行输入（对应 {@code _extract_input_message}）。 */
    private static InputMessageService.AgentRunInputMessage extractInputMessage(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) {
            throw ApiHttpException.unprocessable("messages 不能为空");
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            if (!"user".equals(JsonValues.text(message.get("role")))) {
                continue;
            }
            try {
                return InputMessageService.buildChatInputMessageFromOpenaiContent(message.get("content"));
            } catch (IllegalArgumentException exc) {
                // 参考实现 catch ValueError → 422，detail 为原始异常文案。
                throw ApiHttpException.unprocessable(exc.getMessage());
            }
        }
        throw ApiHttpException.unprocessable("messages 必须包含 user 消息");
    }

    /** 把不同来源的 usage 字段归一为 OpenAI-compatible 计数字段（对应 {@code _normalize_usage}）。 */
    private static Map<String, Object> normalizeUsage(Object usage) {
        Map<String, Object> raw = JsonValues.asMapOrNull(usage);
        if (raw == null) {
            return null;
        }
        Object prompt = raw.containsKey("prompt_tokens") ? raw.get("prompt_tokens") : raw.getOrDefault("input_tokens", 0);
        Object completion = raw.containsKey("completion_tokens")
                ? raw.get("completion_tokens")
                : raw.getOrDefault("output_tokens", 0);
        Object total = raw.get("total_tokens");
        int promptCount = asIntOrZero(prompt);
        int completionCount = asIntOrZero(completion);
        int totalCount = isInteger(total) ? ((Number) total).intValue() : promptCount + completionCount;
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("prompt_tokens", promptCount);
        normalized.put("completion_tokens", completionCount);
        normalized.put("total_tokens", totalCount);
        return normalized;
    }

    /**
     * 对应 pydantic 侧的 {@code isinstance(value, int)}。
     *
     * <p>能力差异：Python 的 JSON 整数只有一种类型，Java 侧经 fastjson2 反序列化后可能是
     * {@link Integer} 或 {@link Long}（超出 int 范围时），故两者都按"整数"采纳——只认
     * {@code Integer} 会让大 token 计数被静默归 0。{@code float} / 字符串 / null 同参考实现归 0。
     */
    private static int asIntOrZero(Object value) {
        return isInteger(value) ? ((Number) value).intValue() : 0;
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    /** 将 AgentRun 结果装配为 Agent Call 响应（对应 {@code _build_agent_call_response}）。 */
    private static Map<String, Object> buildAgentCallResponse(Map<String, Object> result) {
        String rawStatus = JsonValues.text(JsonValues.or(result.get("status"), "unknown"));
        String status = "dispatched".equals(rawStatus) ? "pending" : rawStatus;
        String output = result.get("output") instanceof String text ? text : "";
        Object tokenUsage = result.get("token_usage");
        Object tokenTotal = null;
        if (tokenUsage instanceof Map<?, ?> usage && Boolean.TRUE.equals(usage.get("complete"))) {
            tokenTotal = usage.get("total");
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        // 注意：键名是 messages（参考实现的字面行为），不是 OpenAI 惯例的 message。
        choice.put("messages", List.of(Map.of("role", "assistant", "content", output)));
        choice.put("finish_reason", finishReason(status));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", JsonValues.or(result.get("agent_run_id"), result.get("run_id")));
        payload.put("agent_slug", result.get("agent_slug"));
        payload.put("thread_id", result.get("thread_id"));
        payload.put("status", status);
        payload.put("request_id", result.get("request_id"));
        payload.put("output", output);
        payload.put("choices", List.of(choice));
        payload.put("usage", normalizeUsage(tokenTotal));
        if (JsonValues.truthy(result.get("error"))) {
            payload.put("error", result.get("error"));
        }
        return payload;
    }

    /** 根据运行终态生成 OpenAI {@code choices.finish_reason}（对应 {@code _finish_reason}）。 */
    private static Object finishReason(String status) {
        if ("completed".equals(status)) {
            return "stop";
        }
        if (FAILED_FINISH_STATUSES.contains(status)) {
            return status;
        }
        return null;
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
