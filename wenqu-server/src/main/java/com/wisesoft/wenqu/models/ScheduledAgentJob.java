package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * scheduled_agent_jobs
 * <p>
 * 由参考实现的 models_business 中 ScheduledAgentJob 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("scheduled_agent_jobs")
public class ScheduledAgentJob {

    @TableId(value = "id", type = IdType.AUTO)
    private String id;  // 主键
    private String uid;  // 外键 → users.uid，非空
    @TableField("creation_request_id")
    private String creationRequestId;  // 非空
    @TableField("creation_intent_hash")
    private String creationIntentHash;  // 非空
    @TableField("project_id")
    private String projectId;  // 非空
    @TableField("agent_slug")
    private String agentSlug;  // 非空
    private String name;  // 非空
    private String prompt;  // 非空
    @TableField("tool_approval_mode")
    private String toolApprovalMode;  // 非空，默认 "default"
    @TableField("model_spec")
    private String modelSpec;
    @TableField("cron_expression")
    private String cronExpression;  // 非空
    private String timezone;  // 非空
    private Boolean enabled;  // 非空，默认 True
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
    @TableField("next_run_at")
    private LocalDateTime nextRunAt;  // 非空
    @TableField("created_at")
    private LocalDateTime createdAt;  // 非空，默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 非空，默认 utc_now_naive
}
