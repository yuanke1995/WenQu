package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.common.JsonValues;
import com.wisesoft.wenqu.common.PydanticValidation;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentRequestService;
import com.wisesoft.wenqu.service.AgentRunService;
import com.wisesoft.wenqu.service.ChannelCommandService;
import com.wisesoft.wenqu.service.ChatService;
import com.wisesoft.wenqu.service.InputMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 纯文本 Channel 消息入口（对应参考实现 {@code server/routers/agent_invocation_channel_router.py}，1 端点）。
 *
 * <p>Channel 只负责把消息信封转换为统一 Run 提交命令；少量控制命令（{@code /state}、{@code /approve}）
 * 在普通消息提交之前处理，避免状态查询或审批决议被错误排进 Agent Request 队列。
 *
 * <p>pydantic 契约逐条落为控制器层 422（本模型<b>无</b> {@code extra="forbid"} → 未知键忽略，不过滤）：
 * <ul>
 *   <li>{@code channel}/{@code account_id}/{@code agent_slug} 经参考 {@code _normalize_required}
 *       （{@code str(value or "")} strip 后非空，否则 422）——注意参考对这三个字段都做了 strip + 非空，
 *       尽管 {@code channel}/{@code account_id} 有 pydantic 默认值；</li>
 *   <li>{@code message.text} strip 后非空 422「text 不能为空」；</li>
 *   <li>{@code request_id}（含 hash 推导后）超 64 字符 422「request_id 不能超过 64 个字符」；</li>
 *   <li>{@code _resolve_thread_id}：显式 {@code thread_id} 用它；否则需 {@code chat_id}（缺则 422
 *       「thread_id 或 chat_id 至少提供一个」），无则 {@code hash_id("channel_", ...)}；</li>
 *   <li>{@code parse_slash_command} 抛 {@code ValueError} → 422（本地 {@code IllegalArgumentException}）；
 *       未知 command 422「不支持的 slash command: /{name}」；{@code /state}/{@code /approve} 带参数
 *       422「/{name} 不接受参数」。</li>
 * </ul>
 *
 * <p>参考 {@code queue_policy} 默认 {@code "steer"}（允许 enqueue/reject/steer）；本地
 * {@link AgentRequestService#submitAgentRequest} 已支持 {@code steer}（Channel source 允许）。
 *
 * <p>平台差异（必要替换）：FastAPI {@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * 参考为 async，本地 Spring MVC 同步直调（各服务方法本为同步）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/agent-invocation/channel")
@RequiredArgsConstructor
@Tag(name = "AgentInvocationChannelController", description = "纯文本 Channel 消息入口")
public class AgentInvocationChannelController {

    private final AgentRunRepository agentRunRepository;
    private final AgentRunService agentRunService;
    private final AgentRequestService agentRequestService;
    private final ChatService chatService;
    private final UserRepository userRepository;

    /** 处理纯文本 Channel 消息或最小 slash command。 */
    @Operation(summary = "Channel 消息", description = "POST /agent-invocation/channel/messages")
    @PostMapping("/messages")
    public Map<String, Object> receiveChannelMessage(
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);

        String channel = normalizeRequired(
                PydanticValidation.optionalString(body, "channel", 32), "channel");
        String accountId = normalizeRequired(
                PydanticValidation.optionalString(body, "account_id", PydanticValidation.NO_LENGTH_LIMIT),
                "account_id");
        String agentSlug = normalizeRequired(
                PydanticValidation.requireString(body, "agent_slug", PydanticValidation.NO_LENGTH_LIMIT),
                "agent_slug");
        String chatId = PydanticValidation.optionalString(body, "chat_id", PydanticValidation.NO_LENGTH_LIMIT);
        String requestedThreadId = PydanticValidation.optionalString(
                body, "thread_id", PydanticValidation.NO_LENGTH_LIMIT);
        String senderId = PydanticValidation.optionalString(body, "sender_id", PydanticValidation.NO_LENGTH_LIMIT);
        String messageId = PydanticValidation.optionalString(body, "message_id", 128);

        Map<String, Object> message = PydanticValidation.optionalModel(body, "message", "ChannelTextMessage");
        String messageText = message.get("text") == null ? null : JsonValues.text(message.get("text"));
        String messageType = JsonValues.text(message.get("type"));
        if (messageText == null || messageText.strip().isEmpty()) {
            throw PydanticValidation.validationError(
                    PydanticValidation.bodyLoc("message", "text"),
                    "text 不能为空",
                    "value_error");
        }
        String normalizedText = messageText.strip();

        String threadId = resolveThreadId(uid, channel, accountId, chatId, requestedThreadId);

        String rawRequestId = JsonValues.text(body.get("request_id"));
        rawRequestId = rawRequestId == null ? "" : rawRequestId.strip();
        String externalId;
        if (messageId != null && !messageId.strip().isEmpty()) {
            externalId = messageId.strip();
        } else if (!rawRequestId.isEmpty()) {
            externalId = rawRequestId;
        } else {
            externalId = UUID.randomUUID().toString();
        }
        String requestId = rawRequestId.isEmpty()
                ? HashUtils.hashId(
                        "channel_request_",
                        uid + ":" + channel + ":" + accountId + ":" + (chatId == null ? threadId : chatId)
                                + ":" + externalId,
                        64)
                : rawRequestId;
        if (requestId.length() > 64) {
            throw new ApiHttpException(422, "request_id 不能超过 64 个字符");
        }

        Map<String, Object> originMetadata = new LinkedHashMap<>();
        if (accountId != null && !accountId.isEmpty()) {
            originMetadata.put("account_id", accountId);
        }
        if (chatId != null && !chatId.isEmpty()) {
            originMetadata.put("chat_id", chatId);
        }
        if (senderId != null && !senderId.isEmpty()) {
            originMetadata.put("sender_id", senderId);
        }

        // slash command 预处理（在普通消息提交之前，避免控制命令被排进队列）
        ChannelCommandService.SlashCommand command;
        try {
            command = ChannelCommandService.parseSlashCommand(normalizedText);
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(422, exc.getMessage());
        }

        if (command != null) {
            if ("state".equals(command.name())) {
                requireNoArgs(command.name(), command.args());
                User user = userRepository.getByUid(uid);
                if (user == null) {
                    throw new ApiHttpException(404, "用户不存在");
                }
                Map<String, Object> state = chatService.getAgentStateView(threadId, user, false, true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", "command");
                result.put("command", "state");
                result.put("thread_id", threadId);
                result.put("state", state);
                return result;
            }
            if ("approve".equals(command.name())) {
                requireNoArgs(command.name(), command.args());
                return approveLatestRun(
                        agentSlug, threadId, requestId, externalId, channel, originMetadata, uid);
            }
            throw new ApiHttpException(422, "不支持的 slash command: /" + command.name());
        }

        AgentRun latestRun = agentRunRepository.getLatestChatOrResumeRun(uid, agentSlug, threadId);
        if (latestRun != null
                && "interrupted".equals(latestRun.getStatus())
                && !"human_approval_required".equals(latestRun.getErrorType())) {
            throw ApiHttpException.conflictWithDetail(Map.of(
                    "code", "ask_user_question_unsupported",
                    "message", "当前线程等待用户回答，Channel 暂不支持 ask_user_question"));
        }

        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("message_type", messageType == null || messageType.isEmpty() ? "text" : messageType);

        String queuePolicy = normalizeQueuePolicy(body.get("queue_policy"));

        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(404, "用户不存在");
        }
        AgentRequestService.AgentRequestInput requestInput = new AgentRequestService.AgentRequestInput(
                agentSlug,
                threadId,
                requestId,
                InputMessageService.buildChatInputMessage(normalizedText, null),
                new AgentRequestService.RunOrigin(
                        "channel", channel, externalId, originMetadata),
                requestMetadata,
                null,
                null,
                queuePolicy,
                true,
                channel + " Channel Run",
                null);

        Map<String, Object> result = agentRequestService.submitAgentRequest(requestInput, user);
        result.put("kind", "run");
        result.put("channel", channel);
        return result;
    }

    /** 审批当前等待中的工具调用，并优先复用同 request_id 的恢复 run。 */
    private Map<String, Object> approveLatestRun(
            String agentSlug,
            String threadId,
            String requestId,
            String externalId,
            String channel,
            Map<String, Object> originMetadata,
            String uid) {
        AgentRun existingRun = agentRunRepository.getRunByRequestId(requestId);
        AgentRun latestRun = agentRunRepository.getLatestChatOrResumeRun(uid, agentSlug, threadId);
        String parentRunId;
        if (existingRun != null) {
            if (!uid.equals(existingRun.getUid())
                    || !agentSlug.equals(existingRun.getAgentSlug())
                    || !threadId.equals(existingRun.getConversationThreadId())
                    || !"resume".equals(existingRun.getRunType())
                    || existingRun.getCreatedByRunId() == null
                    || latestRun == null
                    || !latestRun.getId().equals(existingRun.getId())) {
                throw new ApiHttpException(409, "request_id 冲突");
            }
            parentRunId = existingRun.getCreatedByRunId();
        } else {
            if (latestRun == null || !"interrupted".equals(latestRun.getStatus())) {
                throw ApiHttpException.conflictWithDetail(Map.of(
                        "code", "no_pending_approval",
                        "message", "没有待审批的运行"));
            }
            if (!"human_approval_required".equals(latestRun.getErrorType())) {
                throw ApiHttpException.conflictWithDetail(Map.of(
                        "code", "ask_user_question_unsupported",
                        "message", "当前中断不是工具审批，暂不支持处理"));
            }
            parentRunId = latestRun.getId();
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("request_id", requestId);
        meta.put("source", "channel");
        meta.put("channel", channel);
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("decisions", List.of(Map.of("type", "approve")));

        Map<String, Object> runResult = agentRunService.createResumeRunView(
                agentSlug,
                threadId,
                meta,
                uid,
                resume,
                parentRunId,
                "channel",
                channel,
                externalId,
                originMetadata);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "command");
        result.put("command", "approve");
        result.put("thread_id", threadId);
        result.put("run", runResult);
        return result;
    }

    /** 根据显式 thread 或通道会话信息解析稳定 Thread ID（对应 {@code _resolve_thread_id}）。 */
    private static String resolveThreadId(
            String uid, String channel, String accountId, String chatId, String requestedThreadId) {
        if (requestedThreadId != null && !requestedThreadId.strip().isEmpty()) {
            return requestedThreadId.strip();
        }
        if (chatId == null || chatId.strip().isEmpty()) {
            throw new ApiHttpException(422, "thread_id 或 chat_id 至少提供一个");
        }
        return HashUtils.hashId("channel_", uid + ":" + channel + ":" + accountId + ":" + chatId.strip(), 64);
    }

    /** 校验并清理必填字符串字段（对应 {@code _normalize_required}）。 */
    private static String normalizeRequired(String value, String fieldName) {
        String normalized = String.valueOf(value == null ? "" : value).strip();
        if (normalized.isEmpty()) {
            throw new ApiHttpException(422, fieldName + " 不能为空");
        }
        return normalized;
    }

    /** 拒绝当前不支持参数的 slash command 变体（对应 {@code _require_no_args}）。 */
    private static void requireNoArgs(String name, List<String> args) {
        if (args != null && !args.isEmpty()) {
            throw new ApiHttpException(422, "/" + name + " 不接受参数");
        }
    }

    /** 归一化 queue_policy：默认 steer，仅接受 enqueue/reject/steer（pydantic Literal）。 */
    private static String normalizeQueuePolicy(Object raw) {
        String value = raw == null ? "steer" : String.valueOf(raw).strip();
        if ("enqueue".equals(value) || "reject".equals(value) || "steer".equals(value)) {
            return value;
        }
        throw PydanticValidation.validationError(
                PydanticValidation.bodyLoc("queue_policy"),
                "Input should be 'enqueue' or 'reject' or 'steer'",
                "literal_error");
    }
}
