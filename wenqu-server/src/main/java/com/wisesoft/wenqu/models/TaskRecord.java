package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * tasks
 * <p>
 * 由参考实现的 models_business 中 TaskRecord 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("tasks")
public class TaskRecord {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;  // 主键
    private String name;  // 非空
    private String type;  // 非空
    private String status;  // 非空，默认 "pending"
    private Double progress;  // 非空，默认 0.0
    private String message;  // 非空，默认 ""
    private String payload;
    private String result;
    private String error;
    @TableField("cancel_requested")
    private Integer cancelRequested;  // 非空，默认 0
    @TableField("handler_version")
    private Integer handlerVersion;  // 非空，默认 1
    @TableField("dedupe_key")
    private String dedupeKey;
    @TableField("attempt_count")
    private Integer attemptCount;  // 非空，默认 0
    @TableField("worker_id")
    private String workerId;
    @TableField("heartbeat_at")
    private LocalDateTime heartbeatAt;
    @TableField("lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    @TableField("timeout_seconds")
    private Double timeoutSeconds;  // 非空，默认 21600.0
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
