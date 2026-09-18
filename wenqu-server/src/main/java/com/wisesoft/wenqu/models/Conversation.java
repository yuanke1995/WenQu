package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * conversations
 * <p>
 * 由参考实现的 models_business 中 Conversation 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("conversations")
public class Conversation {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("thread_id")
    private String threadId;  // 唯一，非空
    @TableField("creation_request_id")
    private String creationRequestId;
    private String uid;  // 非空
    @TableField("agent_id")
    private String agentId;  // 非空
    private String title;
    private String status;  // 默认 "active"
    @TableField("is_pinned")
    private Boolean isPinned;  // 非空，默认 False
    @TableField("last_viewed_run_id")
    private String lastViewedRunId;
    @TableField("project_id")
    private String projectId;  // 非空
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
    @TableField("extra_metadata")
    private String extraMetadata;
}
