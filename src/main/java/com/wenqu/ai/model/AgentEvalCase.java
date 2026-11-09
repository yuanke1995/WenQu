package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体评测用例：问题 + 期望引用（一题一轮）。
 * <p>
 * 期望引用 = 该题「答对」时应至少命中的知识块/文档；回归时与回答实际引用的
 * sources 求交集算引用召回。无期望引用的用例不计召回项，只按忠实度/相关性评分。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent_eval_case")
public class AgentEvalCase implements com.wenqu.ai.service.AgentEvalScorer.AgentEvalCaseLike {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属套件 ID */
    private String suiteId;

    /** 智能体 ID（冗余：删套件/按智能体清理时不用先查套件） */
    private String agentId;

    /** 问题（一题一轮） */
    private String question;

    /** 期望知识块 ID（JSON 数组字符串；NULL=无期望引用） */
    private String expectedKnowledgeIds;

    /** 期望文档 ID（JSON 数组字符串；与知识块任一命中即算召回命中） */
    private String expectedDocIds;

    /** 期望调用的工具名（JSON 数组字符串；仅记录，暂不参与判分） */
    private String expectedTools;

    /** 备注 / 考察点 */
    private String note;

    /** 标签（JSON 数组字符串） */
    private String tags;

    /** 用例来源: manual / session / import */
    private String source;

    /** 是否启用：1=参与回归 0=停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
