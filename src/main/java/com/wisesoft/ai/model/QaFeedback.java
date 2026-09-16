package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 问答反馈表（message_id 唯一，用户可改评）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_qa_feedback")
public class QaFeedback {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联消息ID（c_ai_message.id，唯一） */
    private String messageId;

    /** 1=有帮助 0=没帮助 */
    private Integer rating;

    /** 反馈文本 */
    private String feedbackText;

    private LocalDateTime createdAt;
}
