package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体评测逐条结果：一题一行，答案/引用/指标全部落快照——临时会话跑完即删，
 * 这里是失败原因与「为什么」的唯一留存处。
 * <p>
 * 综合分 = 引用召回×40% + 忠实度×30% + 相关性×30%（无期望引用按 0/50/50 归一）。
 * 评判模型不可用时忠实度/相关性记 null，综合分按引用召回单独计算并在 failure_reason 标注。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent_eval_result")
public class AgentEvalResult {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属运行 ID */
    private String runId;

    /** 用例 ID（用例可能在跑之前被删 → NULL） */
    private String caseId;

    /** 问题快照 */
    private String question;

    /** 回答快照（≤8000 字截断） */
    private String answer;

    /** 本轮引用来源快照（JSON 数组，截断 ≤32KB） */
    private String sources;

    /** 工具调用摘要（JSON 数组 [{name,status}]，截断 ≤8KB） */
    private String toolCalls;

    /** 引用召回 0-1（NULL=无期望引用不计此项） */
    private Double recall;

    /** 忠实度 0-100（NULL=评判不可用） */
    private Double faithfulness;

    /** 相关性 0-100（NULL=评判不可用） */
    private Double relevance;

    /** 评判摘要 / issue（一句话指出最主要的问题） */
    private String judgeNote;

    /** 综合分 0-100 */
    private Double score;

    /** 是否通过：1=通过 0=失败 */
    private Integer passed;

    /** 失败原因（人话） */
    private String failureReason;

    /** 单题耗时（毫秒） */
    private Long latencyMs;

    /** 本题 token（作答+评判） */
    private Long tokens;

    private LocalDateTime createTime;
}
