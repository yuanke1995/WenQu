package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * scheduled_agent_runs
 * <p>
 * 由参考实现的 models_business 中 ScheduledAgentRun 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("scheduled_agent_runs")
public class ScheduledAgentRun {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;  // 主键
    @TableField("job_id")
    private String jobId;  // 外键 → scheduled_agent_jobs.id，非空
    @TableField("request_id")
    private String requestId;  // 非空
    @TableField("thread_id")
    private String threadId;  // 非空
    private String trigger;  // 非空，默认 "scheduled"
    @TableField("occurrence_key")
    private String occurrenceKey;  // 非空
    @TableField("scheduled_for")
    private LocalDateTime scheduledFor;  // 非空
    @TableField("project_id")
    private String projectId;  // 非空
    @TableField("agent_slug")
    private String agentSlug;  // 非空
    @TableField("conversation_title")
    private String conversationTitle;  // 非空
    private String prompt;  // 非空
    @TableField("tool_approval_mode")
    private String toolApprovalMode;  // 非空
    @TableField("model_spec")
    private String modelSpec;
    private String status;  // 非空，默认 "dispatching"
    @TableField("error_message")
    private String errorMessage;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 非空，默认 utc_now_naive
}
