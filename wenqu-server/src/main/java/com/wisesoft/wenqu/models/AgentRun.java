package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
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
}
