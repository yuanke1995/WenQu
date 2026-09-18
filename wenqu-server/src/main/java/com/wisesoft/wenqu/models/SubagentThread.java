package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * subagent_threads
 * <p>
 * 由参考实现的 models_business 中 SubagentThread 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("subagent_threads")
public class SubagentThread {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String uid;  // 非空
    @TableField("parent_conversation_id")
    private String parentConversationId;
    @TableField("child_conversation_id")
    private String childConversationId;
    @TableField("child_thread_id")
    private String childThreadId;  // 唯一，非空
    @TableField("subagent_slug")
    private String subagentSlug;  // 非空
    @TableField("created_by_run_id")
    private String createdByRunId;  // 非空
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
