package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 模型调用审计仓储。
 *
 * <p>由参考实现的 repositories/model_message_audit_repository.py 逐方法翻译：以当前 Run lease
 * 为边界持久化模型调用的开始与结束事实、幂等重放校验（同一 operation_id 只能有一个事实）、
 * owner 一致性校验与顺序一致性校验、以及按 Run 的模型时间线读取。
 *
 * <p>必要替换：
 * <ul>
 *   <li>JSON 列（extra_metadata / usage）：参考实现由 ORM 序列化 dict，本层显式
 *       {@code JSON.toJSONString}；合并语义（{@code {**old, **new}}）用 JSONObject 实现。
 *   <li>调用 {@link AgentRunRepository#lockOutputPersistence} 取 Run 行锁——参考实现复用同一会话，
 *       本工程该方法带 {@code @Transactional}，会加入本方法开启的事务。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}（参数/事实冲突）与
 *       {@code IllegalStateException}（状态非法），与参考实现区分一致。
 * </ul>
 */
@Repository
public class ModelMessageAuditRepository {

    private final MessageMapper messageMapper;
    private final AgentRunRepository runRepository;

    public ModelMessageAuditRepository(MessageMapper messageMapper, AgentRunRepository runRepository) {
        this.messageMapper = messageMapper;
        this.runRepository = runRepository;
    }

    /** 开始记录结果：审计消息与是否首次创建。 */
    public record StartResult(Message message, boolean created) {}

    /**
     * 幂等创建 running 的模型审计消息，并返回是否首次创建。
     *
     * <p>{@code @Transactional}：Run 行锁与审计写入必须处于同一事务，否则 FOR UPDATE 在语句结束即释放。
     */
    @org.springframework.transaction.annotation.Transactional
    public StartResult start(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String operationId,
            long sequence,
            LocalDateTime startedAt,
            Map<String, Object> metadata) {
        String normalizedOperationId = operationId == null ? "" : operationId.trim();
        if (normalizedOperationId.isEmpty()) {
            throw new IllegalArgumentException("Model operation_id 不能为空");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("Model sequence 不能为负数");
        }
        // messages 表没有 updated_at 列（参考实现的模型亦未声明），故不写该字段

        AgentRun run =
                runRepository.lockOutputPersistence(runId, workerId, threadId, requestId, DateTimeUtils.utcNowNaive());
        if (run == null) {
            throw new IllegalArgumentException("AgentRun 不存在: " + runId);
        }

        Message existing = getInternal(runId, normalizedOperationId);
        if (existing != null) {
            requireSameOwner(existing, run.getConversationId(), requestId);
            requireSameStart(existing, sequence);
            return new StartResult(existing, false);
        }

        LocalDateTime now = DateTimeUtils.utcNowNaive();
        Message message = new Message();
        message.setConversationId(run.getConversationId());
        message.setRole("assistant");
        message.setContent("");
        message.setMessageType(ModelConstants.MODEL_AUDIT_MESSAGE_TYPE);
        message.setExtraMetadata(JSON.toJSONString(metadata == null ? new LinkedHashMap<>() : metadata));
        message.setRunId(run.getId());
        message.setRequestId(requestId);
        message.setDeliveryStatus("complete");
        message.setOperationId(normalizedOperationId);
        message.setStartedAt(startedAt);
        message.setSequence(sequence);
        message.setExecutionStatus("running");
        message.setCreatedAt(now);
        messageMapper.insert(message);
        return new StartResult(message, true);
    }

    /** 完成同一模型审计消息；重复 finish 只接受相同业务结果。 */
    @org.springframework.transaction.annotation.Transactional
    public Message finish(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String operationId,
            String content,
            LocalDateTime finishedAt,
            Long durationMs,
            Map<String, Object> usage,
            Map<String, Object> metadata) {
        String normalizedOperationId = operationId == null ? "" : operationId.trim();
        if (normalizedOperationId.isEmpty()) {
            throw new IllegalArgumentException("Model operation_id 不能为空");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("Model duration_ms 不能为负数");
        }

        AgentRun run =
                runRepository.lockOutputPersistence(runId, workerId, threadId, requestId, DateTimeUtils.utcNowNaive());
        if (run == null) {
            throw new IllegalArgumentException("AgentRun 不存在: " + runId);
        }

        Message message = getInternal(runId, normalizedOperationId);
        if (message == null) {
            throw new IllegalArgumentException("Model finish 缺少对应的 start 事实");
        }
        requireSameOwner(message, run.getConversationId(), requestId);

        Map<String, Object> normalizedUsage = usage == null ? null : new LinkedHashMap<>(usage);
        if ("completed".equals(message.getExecutionStatus())) {
            if (!java.util.Objects.equals(message.getContent(), content)
                    || !sameUsage(message.getUsage(), normalizedUsage)) {
                throw new IllegalArgumentException("已完成 Model 审计事实不能被不同结果覆盖");
            }
            return message;
        }
        if (!"running".equals(message.getExecutionStatus())) {
            throw new IllegalStateException("Model 审计事实不能从 " + message.getExecutionStatus() + " 转为 completed");
        }

        JSONObject mergedMetadata = RepoValues.parseObject(message.getExtraMetadata());
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }
        messageMapper.update(
                null,
                new LambdaUpdateWrapper<Message>()
                        .eq(Message::getId, message.getId())
                        .set(Message::getContent, content)
                        .set(Message::getFinishedAt, finishedAt)
                        .set(Message::getDurationMs, durationMs)
                        .set(Message::getExecutionStatus, "completed")
                        .set(Message::getUsage, normalizedUsage == null ? null : JSON.toJSONString(normalizedUsage))
                        .set(Message::getExtraMetadata, JSON.toJSONString(mergedMetadata)));
        message.setContent(content);
        message.setFinishedAt(finishedAt);
        message.setDurationMs(durationMs);
        message.setExecutionStatus("completed");
        message.setUsage(normalizedUsage == null ? null : JSON.toJSONString(normalizedUsage));
        message.setExtraMetadata(JSON.toJSONString(mergedMetadata));
        return message;
    }

    /** 按同一 Run 的稳定来源键读取审计消息。 */
    public Message get(String runId, String operationId) {
        return getInternal(runId, operationId);
    }

    /** 返回 Run 的模型时间线，包括已发布为普通历史的最终消息。 */
    public List<Message> listForRun(String runId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getRunId, runId)
                        .isNotNull(Message::getOperationId)
                        .eq(Message::getRole, "assistant")
                        .orderByAsc(Message::getSequence)
                        .orderByAsc(Message::getId));
    }

    private Message getInternal(String runId, String operationId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getRunId, runId)
                        .eq(Message::getOperationId, operationId)
                        .eq(Message::getRole, "assistant"));
    }

    /** 审计消息必须属于同一 Run、request 与 conversation。 */
    static void requireSameOwner(Message message, Integer conversationId, String requestId) {
        if (!java.util.Objects.equals(message.getConversationId(), conversationId)
                || !java.util.Objects.equals(message.getRequestId(), requestId)
                || !"assistant".equals(message.getRole())
                || !ModelConstants.MODEL_AUDIT_MESSAGE_TYPE.equals(message.getMessageType())) {
            throw new IllegalArgumentException("Model 审计消息必须属于同一 Run、request 和 conversation");
        }
    }

    /** 确保重放的 Model start 没有改写已持久化顺序。 */
    static void requireSameStart(Message message, long sequence) {
        if (message.getSequence() == null || message.getSequence() != sequence) {
            throw new IllegalArgumentException("重复 Model start 与已持久化 sequence 不一致");
        }
    }

    private static boolean sameUsage(String storedUsage, Map<String, Object> usage) {
        if (storedUsage == null && usage == null) {
            return true;
        }
        if (storedUsage == null || usage == null) {
            return false;
        }
        return RepoValues.parseObject(storedUsage).equals(new JSONObject(new LinkedHashMap<>(usage)));
    }
}
