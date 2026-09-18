package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * message_feedbacks
 * <p>
 * 由参考实现的 models_business 中 MessageFeedback 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("message_feedbacks")
public class MessageFeedback {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("message_id")
    private Integer messageId;  // 外键 → messages.id，非空
    private String uid;  // 非空
    private String rating;  // 非空
    private String reason;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
}
