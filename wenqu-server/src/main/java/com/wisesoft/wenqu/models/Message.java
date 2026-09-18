package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * messages
 * <p>
 * 由参考实现的 models_business 中 Message 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("messages")
public class Message {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("conversation_id")
    private Integer conversationId;  // 外键 → conversations.id，非空
    private String role;  // 非空
    private String content;  // 非空
    @TableField("message_type")
    private String messageType;  // 默认 "text"
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("token_count")
    private Integer tokenCount;
    @TableField("extra_metadata")
    private String extraMetadata;
    @TableField("image_content")
    private String imageContent;
    @TableField("run_id")
    private String runId;  // 外键 → agent_runs.id
    @TableField("request_id")
    private String requestId;
    @TableField("delivery_status")
    private String deliveryStatus;  // 非空，默认 "complete"
    @TableField("operation_id")
    private String operationId;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("finished_at")
    private LocalDateTime finishedAt;
    @TableField("duration_ms")
    private Long durationMs;
    private Long sequence;
    @TableField("execution_status")
    private String executionStatus;
    private String usage;
}
