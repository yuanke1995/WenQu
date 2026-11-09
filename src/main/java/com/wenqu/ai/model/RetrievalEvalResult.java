package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索评估逐题结果：每组每题一行。
 * <p>hitsJson / recallJson / firstRank 都是运行时快照——评估集之后被编辑也不影响历史回看。
 */
@Data
@TableName("c_ai_retrieval_eval_result")
public class RetrievalEvalResult {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String runId;

    private String groupName;

    private String groupMode;

    private String caseId;

    private String question;

    /** JSON 数组字符串 */
    private String expectedIds;

    /** JSON 数组字符串：knowledgeId/docId/title/score */
    private String hitsJson;

    /** JSON 对象字符串：recall@5/10/20 */
    private String recallJson;

    private Double mrr;

    /** 首个期望块的排名（0=未命中） */
    private Integer firstRank;

    private Integer expectedCount;

    private Integer hitCount;

    /** 1=资料足以回答 0=不足 NULL=未评 */
    private Integer judgeCovered;

    private String judgeNote;

    private String error;

    private Long latencyMs;

    private LocalDateTime createTime;
}
