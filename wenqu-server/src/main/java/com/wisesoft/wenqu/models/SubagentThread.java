package com.wisesoft.wenqu.models;

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
    private Integer parentConversationId;  // 外键 → conversations.id，非空
    @TableField("child_conversation_id")
    private Integer childConversationId;  // 唯一，外键 → conversations.id，非空
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

    /**
     * 参考实现的 {@code SubagentThread.to_dict()}：键名与键序逐字照搬，
     * 时间列经 {@code format_utc_datetime} → {@link DateTimeUtils#formatUtcDatetime(LocalDateTime)}。
     */
    public Map<String, Object> toDict() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("id", id);
        dict.put("uid", uid);
        dict.put("parent_conversation_id", parentConversationId);
        dict.put("child_conversation_id", childConversationId);
        dict.put("child_thread_id", childThreadId);
        dict.put("subagent_slug", subagentSlug);
        dict.put("created_by_run_id", createdByRunId);
        dict.put("created_at", DateTimeUtils.formatUtcDatetime(createdAt));
        dict.put("updated_at", DateTimeUtils.formatUtcDatetime(updatedAt));
        return dict;
    }
}
