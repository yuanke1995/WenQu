package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库引用反馈：用户对回答中某条引用「有用 / 没用」的判定。
 * <p>
 * 用途：差评回流到该库的「未命中问题」列表，可一键加入评测集；
 * 与 QaLog 分离，避免把反馈伪造成问答记录污染命中统计。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_kb_feedback")
public class KbFeedback {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String kbId;

    private String knowledgeId;

    private String docId;

    /** 触发该引用的用户问题（对话侧可能为空） */
    private String question;

    /** 1=有用 0=没用 */
    private Integer useful;

    private String uid;

    private LocalDateTime createTime;
}
