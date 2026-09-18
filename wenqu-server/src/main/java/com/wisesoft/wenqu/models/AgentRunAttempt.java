package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * agent_run_attempts
 * <p>
 * 由参考实现的 models_business 中 AgentRunAttempt 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("agent_run_attempts")
public class AgentRunAttempt {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("run_id")
    private String runId;
    @TableField("attempt_no")
    private Integer attemptNo;  // 非空
    @TableField("worker_id")
    private String workerId;  // 非空
    @TableField("started_at")
    private LocalDateTime startedAt;  // 非空
    @TableField("heartbeat_at")
    private LocalDateTime heartbeatAt;
    @TableField("lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    @TableField("finished_at")
    private LocalDateTime finishedAt;
    private String outcome;
    @TableField("error_type")
    private String errorType;
    @TableField("error_message")
    private String errorMessage;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
