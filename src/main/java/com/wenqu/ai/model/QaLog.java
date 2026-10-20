package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 问答日志表（用于数据闭环：无命中分析/热门问题）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_qa_log")
public class QaLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sessionId;

    private String question;

    /** 回答摘要（前 500 字） */
    private String answerSummary;

    /** 命中文档 ID 列表（逗号分隔） */
    private String hitDocIds;

    /** 是否有引用标注 */
    private Integer hasCitation;

    /** 深度思考是否生效（1=是 0=否；按生效模型的思考能力归一后的结果） */
    private Integer deepThink;

    /** 回答耗时(ms) */
    private Integer elapsedMs;

    /**
     * P1：本轮回答消息 ID（trace 详情还原 toolCalls/sources/tokens 的关联键）。
     * 存量行为 NULL——详情降级为「摘要视图」，如实展示，不做近似匹配硬凑。
     */
    private String messageId;

    /** P1：本轮生效智能体 ID（trace 按智能体筛选；未用智能体为 NULL） */
    private String agentId;

    private LocalDateTime createdAt;
}
