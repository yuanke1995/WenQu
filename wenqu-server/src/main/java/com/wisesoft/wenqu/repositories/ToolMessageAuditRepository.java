package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.models.ToolCall;
import com.wisesoft.wenqu.repository.port.AgentRunMapper;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repository.port.ToolCallMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具消息生命周期审计仓储。
 *
 * <p>由参考实现的 repositories/tool_message_audit_repository.py 逐方法翻译：以当前 Run lease 为边界
 * 持久化 ToolMessage（审计事实）与 ToolCall（兼容投影）两条记录，并保证它们的生命周期一致。
 *
 * <p>必要替换：
 * <ul>
 *   <li>调用 {@link AgentRunRepository#lockOutputPersistence} 取 Run 行锁——参考实现复用同一会话，
 *       本工程该方法带 {@code @Transactional}，会加入本方法开启的事务。
 *   <li>JSON 列（extra_metadata / tool_input / tool_output）：参考实现由 ORM 序列化 dict，
 *       本层显式 {@code JSON.toJSONString}。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}（参数非法/事实冲突）与
 *       {@code IllegalStateException}（状态非法），与参考实现区分一致。
 *   <li>{@code db.refresh()} 在 MyBatis-Plus 无对应语义（写入后实体已在手），省略。
 * </ul>
 *
 * <p>注意：ToolCall 表没有 updated_at 列（与参考实现的模型一致），只填 created_at。
 */
@Repository
public class ToolMessageAuditRepository {

    private final MessageMapper messageMapper;
    private final ToolCallMapper toolCallMapper;
    private final AgentRunMapper runMapper;
    private final AgentRunRepository runRepository;

    public ToolMessageAuditRepository(
            MessageMapper messageMapper,
            ToolCallMapper toolCallMapper,
            AgentRunMapper runMapper,
            AgentRunRepository runRepository) {
        this.messageMapper = messageMapper;
        this.toolCallMapper = toolCallMapper;
        this.runMapper = runMapper;
        this.runRepository = runRepository;
    }

    /** 开始记录结果：审计消息与是否首次创建。 */
    public record StartResult(Message message, boolean created) {}

    /**
     * 幂等创建 running ToolMessage，并从该事实建立 ToolCall 兼容投影。
     *
     * <p>{@code @Transactional}：Run 行锁与审计写入必须处于同一事务，否则 FOR UPDATE 在语句结束即释放。
     */
    @Transactional
    public StartResult start(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String toolCallId,
            String toolName,
            Map<String, Object> toolInput,
            long sequence,
            LocalDateTime startedAt,
            Map<String, Object> metadata) {
        String operationId = toolCallId == null ? "" : toolCallId.trim();
        String normalizedName = toolName == null ? "" : toolName.trim();
        if (operationId.isEmpty()) {
            throw new IllegalArgumentException("Tool tool_call_id 不能为空");
        }
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Tool tool_name 不能为空");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("Tool sequence 不能为负数");
        }

        AgentRun run = lockRun(runId, requestId, threadId, workerId);
        Message existing = getInternal(runId, operationId);
        if (existing != null) {
            requireSameOwner(existing, run.getConversationId(), requestId);
            requireSameStart(existing, normalizedName, toolInput, sequence);
            return new StartResult(existing, false);
        }

        Message sourceMessage = findSourceModelMessage(run, operationId);
        if (sourceMessage == null) {
            throw new IllegalArgumentException("Tool start 无法关联声明该 tool_call_id 的 Model Message");
        }
        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            auditMetadata.putAll(metadata);
        }
        auditMetadata.put("audit_kind", "tool");
        auditMetadata.put("tool_call_id", operationId);
        auditMetadata.put("tool_name", normalizedName);
        auditMetadata.put("input", toolInput == null ? new LinkedHashMap<>() : new LinkedHashMap<>(toolInput));
        auditMetadata.put("source_model_operation_id", sourceMessage.getOperationId());

        Message message = new Message();
        message.setConversationId(run.getConversationId());
        message.setRole("tool");
        message.setContent("");
        message.setMessageType(ModelConstants.TOOL_AUDIT_MESSAGE_TYPE);
        message.setExtraMetadata(JSON.toJSONString(auditMetadata));
        message.setRunId(run.getId());
        message.setRequestId(requestId);
        message.setDeliveryStatus("complete");
        message.setOperationId(operationId);
        message.setStartedAt(startedAt);
        message.setSequence(sequence);
        message.setExecutionStatus("running");
        message.setCreatedAt(DateTimeUtils.utcNowNaive());
        messageMapper.insert(message);

        ToolCall toolCall = getToolCallForMessage(sourceMessage.getId(), operationId);
        if (toolCall == null) {
            toolCall = new ToolCall();
            toolCall.setMessageId(sourceMessage.getId());
            toolCall.setLanggraphToolCallId(operationId);
            toolCall.setToolName(normalizedName);
            toolCall.setToolInput(JSON.toJSONString(toolInput == null ? new LinkedHashMap<>() : toolInput));
            toolCall.setStatus("pending");
            toolCall.setCreatedAt(DateTimeUtils.utcNowNaive());
            toolCallMapper.insert(toolCall);
        } else if (!"pending".equals(toolCall.getStatus())) {
            throw new IllegalArgumentException("已结束 ToolCall 不能开始新的 ToolMessage lifecycle");
        } else {
            toolCall.setToolName(normalizedName);
            toolCall.setToolInput(JSON.toJSONString(toolInput == null ? new LinkedHashMap<>() : toolInput));
            toolCallMapper.updateById(toolCall);
        }

        auditMetadata.put("compatibility_tool_call_id", toolCall.getId());
        message.setExtraMetadata(JSON.toJSONString(auditMetadata));
        updateMetadata(message.getId(), auditMetadata);
        return new StartResult(message, true);
    }

    /** 完成同一 ToolMessage，并同步成功 ToolCall 投影。 */
    @Transactional
    public Message complete(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String toolCallId,
            Object output,
            String content,
            LocalDateTime finishedAt,
            Long durationMs,
            Long finishedSequence) {
        return finishInternal(
                runId,
                requestId,
                threadId,
                workerId,
                toolCallId,
                "completed",
                "success",
                output,
                content,
                null,
                finishedAt,
                durationMs,
                finishedSequence);
    }

    /** 关闭失败 ToolMessage；终态 State 可补全 stream error 缺少的 ToolMessage 内容。 */
    @Transactional
    public Message fail(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String toolCallId,
            String errorMessage,
            Object output,
            String content,
            LocalDateTime finishedAt,
            Long durationMs,
            Long finishedSequence) {
        return finishInternal(
                runId,
                requestId,
                threadId,
                workerId,
                toolCallId,
                "failed",
                "error",
                output,
                content,
                errorMessage,
                finishedAt,
                durationMs,
                finishedSequence);
    }

    /** 保存裸 tool-error，最终状态由 Run failed/interrupted 事务裁决。 */
    @Transactional
    public Message observeError(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String toolCallId,
            String errorMessage,
            LocalDateTime finishedAt,
            Long durationMs,
            long finishedSequence) {
        String operationId = toolCallId == null ? "" : toolCallId.trim();
        if (operationId.isEmpty()) {
            throw new IllegalArgumentException("Tool tool_call_id 不能为空");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("Tool duration_ms 不能为负数");
        }
        if (finishedSequence < 0) {
            throw new IllegalArgumentException("Tool finished_sequence 不能为负数");
        }
        AgentRun run = lockRun(runId, requestId, threadId, workerId);
        Message message = getInternal(runId, operationId);
        if (message == null) {
            throw new IllegalArgumentException("Tool error 缺少对应的 start 事实");
        }
        requireSameOwner(message, run.getConversationId(), requestId);
        if (!"running".equals(message.getExecutionStatus())) {
            JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
            if (!Objects.equals(metadata.get("error_message"), errorMessage)) {
                throw new IllegalArgumentException("已关闭 Tool 审计事实不能被不同错误覆盖");
            }
            return message;
        }

        JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
        metadata.put("error_message", errorMessage);
        metadata.put("finished_sequence", finishedSequence);
        metadata.put("awaiting_run_terminal", true);
        updateFinishColumns(message.getId(), metadata, null, finishedAt, durationMs, null);
        message.setExtraMetadata(JSON.toJSONString(metadata));
        message.setFinishedAt(finishedAt);
        message.setDurationMs(durationMs);
        return message;
    }

    /** 按 ProtocolEvent sequence 返回 Run 的 ToolMessage。 */
    public List<Message> listForRun(String runId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getRunId, runId)
                        .eq(Message::getMessageType, ModelConstants.TOOL_AUDIT_MESSAGE_TYPE)
                        .eq(Message::getRole, "tool")
                        .orderByAsc(Message::getSequence)
                        .orderByAsc(Message::getId));
    }

    /** 原子关闭 Tool 审计及其兼容 ToolCall。 */
    private Message finishInternal(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String toolCallId,
            String executionStatus,
            String toolCallStatus,
            Object output,
            String content,
            String errorMessage,
            LocalDateTime finishedAt,
            Long durationMs,
            Long finishedSequence) {
        String operationId = toolCallId == null ? "" : toolCallId.trim();
        if (operationId.isEmpty()) {
            throw new IllegalArgumentException("Tool tool_call_id 不能为空");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("Tool duration_ms 不能为负数");
        }
        if (finishedSequence != null && finishedSequence < 0) {
            throw new IllegalArgumentException("Tool finished_sequence 不能为负数");
        }

        AgentRun run = lockRun(runId, requestId, threadId, workerId);
        Message message = getInternal(runId, operationId);
        if (message == null) {
            throw new IllegalArgumentException("Tool terminal 缺少对应的 start 事实");
        }
        requireSameOwner(message, run.getConversationId(), requestId);

        JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
        if (executionStatus.equals(message.getExecutionStatus())) {
            if (!Objects.equals(message.getContent(), content)
                    || !Objects.equals(metadata.get("error_message"), errorMessage)
                    || !Objects.equals(
                            canonicalToolOutput(metadata.get("output")), canonicalToolOutput(output))) {
                throw new IllegalArgumentException("已关闭 Tool 审计事实不能被不同结果覆盖");
            }
            return message;
        }
        if (!"running".equals(message.getExecutionStatus())) {
            throw new IllegalStateException(
                    "Tool 审计事实不能从 " + message.getExecutionStatus() + " 转为 " + executionStatus);
        }

        ToolCall toolCall = requireCompatibilityToolCall(metadata, operationId);

        LocalDateTime effectiveFinishedAt =
                message.getFinishedAt() != null ? message.getFinishedAt() : finishedAt;
        Long effectiveDurationMs = durationMs != null ? durationMs : message.getDurationMs();
        metadata.put("output", output);
        metadata.put("error_message", errorMessage);
        if (finishedSequence != null) {
            metadata.put("finished_sequence", finishedSequence);
        }
        updateFinishColumns(
                message.getId(), metadata, content, effectiveFinishedAt, effectiveDurationMs, executionStatus);

        // 同步兼容 ToolCall 投影
        toolCall.setToolOutput(content == null || content.isEmpty() ? null : content);
        toolCall.setStatus(toolCallStatus);
        toolCall.setErrorMessage(errorMessage);
        LambdaUpdateWrapper<ToolCall> callUpdate = new LambdaUpdateWrapper<ToolCall>().eq(ToolCall::getId, toolCall.getId());
        callUpdate.set(ToolCall::getToolOutput, toolCall.getToolOutput());
        callUpdate.set(ToolCall::getStatus, toolCallStatus);
        callUpdate.set(ToolCall::getErrorMessage, errorMessage);
        toolCallMapper.update(null, callUpdate);

        message.setContent(content);
        message.setFinishedAt(effectiveFinishedAt);
        message.setDurationMs(effectiveDurationMs);
        message.setExecutionStatus(executionStatus);
        message.setExtraMetadata(JSON.toJSONString(metadata));
        return message;
    }

    private void updateMetadata(Integer messageId, Map<String, Object> metadata) {
        messageMapper.update(
                null,
                new LambdaUpdateWrapper<Message>()
                        .eq(Message::getId, messageId)
                        .set(Message::getExtraMetadata, JSON.toJSONString(metadata)));
    }

    private void updateFinishColumns(
            Integer messageId,
            Map<String, Object> metadata,
            String content,
            LocalDateTime finishedAt,
            Long durationMs,
            String executionStatus) {
        LambdaUpdateWrapper<Message> update = new LambdaUpdateWrapper<Message>().eq(Message::getId, messageId);
        update.set(Message::getExtraMetadata, JSON.toJSONString(metadata));
        if (content != null) {
            update.set(Message::getContent, content);
        }
        update.set(Message::getFinishedAt, finishedAt);
        update.set(Message::getDurationMs, durationMs);
        if (executionStatus != null) {
            update.set(Message::getExecutionStatus, executionStatus);
        }
        messageMapper.update(null, update);
    }

    private AgentRun lockRun(String runId, String requestId, String threadId, String workerId) {
        AgentRun run =
                runRepository.lockOutputPersistence(runId, workerId, threadId, requestId, DateTimeUtils.utcNowNaive());
        if (run == null) {
            throw new IllegalArgumentException("AgentRun 不存在: " + runId);
        }
        return run;
    }

    private Message getInternal(String runId, String operationId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getRunId, runId)
                        .eq(Message::getOperationId, operationId)
                        .eq(Message::getMessageType, ModelConstants.TOOL_AUDIT_MESSAGE_TYPE));
    }

    private ToolCall getToolCallForMessage(Integer messageId, String operationId) {
        return toolCallMapper.selectOne(
                new LambdaQueryWrapper<ToolCall>()
                        .eq(ToolCall::getMessageId, messageId)
                        .eq(ToolCall::getLanggraphToolCallId, operationId));
    }

    /** 在当前 Run 及合法 resume 祖先中查找 ToolCall 声明。 */
    private Message findSourceModelMessage(AgentRun run, String toolCallId) {
        List<String> sourceRunIds = sourceRunIds(run);
        List<Message> candidates =
                messageMapper.selectList(
                        new LambdaQueryWrapper<Message>()
                                .in(Message::getRunId, sourceRunIds)
                                .eq(Message::getRole, "assistant")
                                .isNotNull(Message::getOperationId)
                                .eq(Message::getMessageType, ModelConstants.MODEL_AUDIT_MESSAGE_TYPE)
                                .orderByDesc(Message::getCreatedAt)
                                .orderByDesc(Message::getSequence)
                                .orderByDesc(Message::getId));
        for (Message message : candidates) {
            JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
            JSONArray toolCalls = metadata.getJSONArray("tool_calls");
            if (toolCalls == null) {
                continue;
            }
            for (Object item : toolCalls) {
                if (item instanceof Map<?, ?> map
                        && Objects.equals(String.valueOf(map.get("id")), toolCallId)) {
                    return message;
                }
            }
        }
        return null;
    }

    /** 返回同 Conversation 内无环的 resume 来源链。 */
    private List<String> sourceRunIds(AgentRun run) {
        List<String> sourceRunIds = new ArrayList<>();
        sourceRunIds.add(run.getId());
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(run.getId());
        String parentId = "resume".equals(run.getRunType()) ? run.getCreatedByRunId() : null;
        while (parentId != null && !parentId.isEmpty()) {
            if (seen.contains(parentId)) {
                throw new IllegalArgumentException("Resume Run ancestry 存在循环");
            }
            AgentRun parent = runMapper.selectById(parentId);
            if (parent == null || !Objects.equals(parent.getConversationId(), run.getConversationId())) {
                throw new IllegalArgumentException("Resume Run ancestry 与当前 conversation 不一致");
            }
            sourceRunIds.add(parent.getId());
            seen.add(parent.getId());
            parentId = "resume".equals(parent.getRunType()) ? parent.getCreatedByRunId() : null;
        }
        return sourceRunIds;
    }

    /** 读取并校验审计绑定的兼容 ToolCall。 */
    private ToolCall requireCompatibilityToolCall(Map<String, Object> metadata, String operationId) {
        Object toolCallId = metadata.get("compatibility_tool_call_id");
        ToolCall toolCall = toolCallId instanceof Number number ? toolCallMapper.selectById(number.intValue()) : null;
        if (toolCall == null || !Objects.equals(toolCall.getLanggraphToolCallId(), operationId)) {
            throw new IllegalArgumentException("ToolMessage 缺少 ToolCall 兼容投影");
        }
        return toolCall;
    }

    private static void requireSameOwner(Message message, Integer conversationId, String requestId) {
        if (!Objects.equals(message.getConversationId(), conversationId)
                || !Objects.equals(message.getRequestId(), requestId)
                || !"tool".equals(message.getRole())
                || !ModelConstants.TOOL_AUDIT_MESSAGE_TYPE.equals(message.getMessageType())) {
            throw new IllegalArgumentException("Tool 审计消息必须属于同一 Run、request 和 conversation");
        }
    }

    private static void requireSameStart(
            Message message, String toolName, Map<String, Object> toolInput, long sequence) {
        JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
        Object storedInput = metadata.get("input");
        boolean sameInput =
                toolInput == null
                        ? storedInput == null
                        : new JSONObject(new LinkedHashMap<>(toolInput)).equals(RepoValues.toJsonObject(storedInput));
        if (!Objects.equals(metadata.get("tool_name"), toolName)
                || !sameInput
                || message.getSequence() == null
                || message.getSequence() != sequence) {
            throw new IllegalArgumentException("重复 Tool start 与已持久化事实不一致");
        }
    }

    /** 忽略 LangGraph 写入 State 时补充的 ToolMessage id/name，其余原始输出必须一致。 */
    static Object canonicalToolOutput(Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return output;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach(
                (key, value) -> {
                    String name = String.valueOf(key);
                    if (!"id".equals(name) && !"name".equals(name)) {
                        result.put(name, value);
                    }
                });
        return result;
    }
}
