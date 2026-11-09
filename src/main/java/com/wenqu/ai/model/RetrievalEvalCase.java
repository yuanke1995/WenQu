package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索评估用例：问题 + 期望知识块/文档。
 * <p>source 记录用例来源（历史引用 / 差评回流 / 手工 / 导入），
 * humanVerified 区分弱监督标签与人工确认标签，界面据此提示标签可信度。
 */
@Data
@TableName("c_ai_retrieval_eval_case")
public class RetrievalEvalCase {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String setId;

    private String question;

    /** JSON 数组字符串 */
    private String expectedKnowledgeIds;

    /** JSON 数组字符串 */
    private String expectedDocIds;

    private String note;

    /** JSON 数组字符串（分群指标用） */
    private String tags;

    /** session / feedback / manual / import */
    private String source;

    /** 1=人工确认 0=仅弱监督 */
    private Integer humanVerified;

    /** 1=参与评估 0=停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
