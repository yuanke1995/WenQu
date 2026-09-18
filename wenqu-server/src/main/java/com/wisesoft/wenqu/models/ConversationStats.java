package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * conversation_stats
 * <p>
 * 由参考实现的 models_business 中 ConversationStats 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("conversation_stats")
public class ConversationStats {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("conversation_id")
    private String conversationId;
    @TableField("message_count")
    private Integer messageCount;  // 默认 0
    @TableField("total_tokens")
    private Integer totalTokens;  // 默认 0
    @TableField("model_used")
    private String modelUsed;
    @TableField("user_feedback")
    private String userFeedback;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
