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

    /**
     * 本轮检索范围的知识库 ID（逗号分隔；NULL=存量日志或未限定范围）。
     * <p>未命中问题必须按库归属：问答日志本身是全局的，只有记下当时的库范围，
     * 库洞察才能把「别的库没答上来的问题」挡在当前库之外。
     */
    private String kbIds;

    /** 是否有引用标注 */
    private Integer hasCitation;

    /**
     * 本轮是否调用过知识检索：1=调用过（含零命中），0=未调用，NULL=存量未记录。
     * <p>「知识缺口」只认调用过检索却没命中的轮次——未挂知识库的纯角色智能体、
     * 模型没调检索工具的闲聊轮、工作流轮次，都不该被算成缺口。
     */
    private Integer retrievalAttempted;

    /** 深度思考是否生效（1=是 0=否；按生效模型的思考能力归一后的结果） */
    private Integer deepThink;

    /** 回答耗时(ms) */
    private Integer elapsedMs;

    /**
     * 本轮回答消息 ID（trace 详情还原 toolCalls/sources/tokens 的关联键）。
     * 为 NULL 时详情降级为「摘要视图」，如实展示，不做近似匹配硬凑。
     */
    private String messageId;

    /** 本轮生效智能体 ID（trace 按智能体筛选；未用智能体为 NULL） */
    private String agentId;

    private LocalDateTime createdAt;
}
