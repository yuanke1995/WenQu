package com.wisesoft.wenqu.models;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wisesoft.wenqu.common.DateTimeUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * agent_runs
 * <p>
 * 由参考实现的 models_business 中 AgentRun 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("agent_runs")
public class AgentRun {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;  // 主键
    @TableField("conversation_thread_id")
    private String conversationThreadId;  // 非空
    @TableField("runtime_scope_id")
    private String runtimeScopeId;  // 非空
    @TableField("runtime_cleanup_pending")
    private Boolean runtimeCleanupPending;  // 非空，默认 False
    @TableField("agent_slug")
    private String agentSlug;  // 非空
    private String uid;  // 非空
    private String status;  // 非空，默认 "pending"
    @TableField("request_id")
    private String requestId;  // 唯一，非空
    private String source;  // 非空，默认 "chat"
    private String channel;  // 非空，默认 "web"
    @TableField("external_id")
    private String externalId;
    @TableField("origin_metadata")
    private String originMetadata;  // 非空，默认 dict
    @TableField("conversation_id")
    private Integer conversationId;  // 外键 → conversations.id
    @TableField("created_by_run_id")
    private String createdByRunId;
    @TableField("subagent_thread_relation_id")
    private Integer subagentThreadRelationId;  // 外键 → subagent_threads.id
    @TableField("run_type")
    private String runType;  // 非空，默认 "chat"
    @TableField("input_message_id")
    private Integer inputMessageId;
    @TableField("output_message_id")
    private Integer outputMessageId;
    @TableField("input_payload")
    private String inputPayload;  // 非空，默认 dict
    @TableField("token_usage")
    private String tokenUsage;  // 非空，默认 dict
    @TableField("langfuse_trace_id")
    private String langfuseTraceId;
    @TableField("error_type")
    private String errorType;
    @TableField("error_message")
    private String errorMessage;
    @TableField("worker_id")
    private String workerId;
    @TableField("heartbeat_at")
    private LocalDateTime heartbeatAt;
    @TableField("lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    private String manifest;
    @TableField("manifest_fingerprint")
    private String manifestFingerprint;
    @TableField("manifest_recorded_at")
    private LocalDateTime manifestRecordedAt;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("prepared_at")
    private LocalDateTime preparedAt;
    @TableField("first_model_request_at")
    private LocalDateTime firstModelRequestAt;
    @TableField("first_output_at")
    private LocalDateTime firstOutputAt;
    @TableField("finished_at")
    private LocalDateTime finishedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive

    /**
     * 运行任务的对外投影（对应参考实现的 {@code AgentRun.to_dict}）。
     *
     * <p>键序与参考实现逐字一致；JSON 列（origin_metadata / input_payload / token_usage /
     * manifest）在本工程以文本存储，此处解析为对象；空值回落 {@code {}}（同参考实现的
     * {@code or {}}）；时间列统一走 {@link DateTimeUtils#formatUtcDatetime}。
     */
    public Map<String, Object> toDict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("conversation_thread_id", conversationThreadId);
        data.put("runtime_scope_id", runtimeScopeId);
        data.put("runtime_cleanup_pending", Boolean.TRUE.equals(runtimeCleanupPending));
        data.put("agent_slug", agentSlug);
        data.put("uid", uid);
        data.put("status", status);
        data.put("request_id", requestId);
        data.put("source", source);
        data.put("channel", channel);
        data.put("external_id", externalId);
        data.put("origin_metadata", jsonColumn(originMetadata));
        data.put("conversation_id", conversationId);
        data.put("created_by_run_id", createdByRunId);
        data.put("subagent_thread_relation_id", subagentThreadRelationId);
        data.put("run_type", runType);
        data.put("input_message_id", inputMessageId);
        data.put("output_message_id", outputMessageId);
        data.put("input_payload", jsonColumn(inputPayload));
        data.put("token_usage", jsonColumn(tokenUsage));
        data.put("langfuse_trace_id", langfuseTraceId);
        data.put("error_type", errorType);
        data.put("error_message", errorMessage);
        data.put("manifest", manifest == null || manifest.isBlank() ? null : JSON.parse(manifest));
        data.put("manifest_fingerprint", manifestFingerprint);
        data.put("started_at", DateTimeUtils.formatUtcDatetime(startedAt));
        data.put("prepared_at", DateTimeUtils.formatUtcDatetime(preparedAt));
        data.put("first_model_request_at", DateTimeUtils.formatUtcDatetime(firstModelRequestAt));
        data.put("first_output_at", DateTimeUtils.formatUtcDatetime(firstOutputAt));
        data.put("finished_at", DateTimeUtils.formatUtcDatetime(finishedAt));
        data.put("created_at", DateTimeUtils.formatUtcDatetime(createdAt));
        data.put("updated_at", DateTimeUtils.formatUtcDatetime(updatedAt));
        data.put("timing", ModelConstants.buildAgentRunTiming(
                createdAt, startedAt, preparedAt, firstOutputAt, finishedAt, firstModelRequestAt));
        return data;
    }

    /** JSON 文本列 → 对象；空/非法回落空表（同参考实现 {@code or {}}）。 */
    private static Map<String, Object> jsonColumn(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = JSON.parse(raw);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        } catch (Exception ignored) {
            // 与参考实现 or {} 一致：解析失败回落空表，不抛出
        }
        return new LinkedHashMap<>();
    }
}
