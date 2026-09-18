package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * agent_run_requests
 * <p>
 * 由参考实现的 models_business 中 AgentRunRequest 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("agent_run_requests")
public class AgentRunRequest {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("request_id")
    private String requestId;  // 唯一，非空
    private String uid;  // 非空
    @TableField("agent_slug")
    private String agentSlug;  // 非空
    @TableField("conversation_thread_id")
    private String conversationThreadId;  // 非空
    private String source;  // 非空，默认 "chat"
    private String channel;  // 非空，默认 "web"
    @TableField("external_id")
    private String externalId;
    @TableField("origin_metadata")
    private String originMetadata;  // 非空，默认 dict
    @TableField("queue_policy")
    private String queuePolicy;
    private String status;
    @TableField("input_message_id")
    private Integer inputMessageId;  // 外键 → messages.id，非空
    @TableField("dispatched_run_id")
    private String dispatchedRunId;  // 外键 → agent_runs.id
    @TableField("input_payload")
    private String inputPayload;
    @TableField("error_message")
    private String errorMessage;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 非空，默认 utc_now_naive
    @TableField("dispatched_at")
    private LocalDateTime dispatchedAt;
    @TableField("updated_at")
    private String updatedAt;
}
