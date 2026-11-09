package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体评测运行：一次回归一行。启动写 running，结束收口 succeeded/failed/cancelled；
 * 进程重启的残留 running 由启动清理器置 failed（「服务重启，运行中断」），不永久卡住。
 * <p>
 * agent_version / judge_model / threshold_rate / pass_score 都是<b>运行时快照</b>——
 * 门禁「有变更未评测」的判据（当前版本号 &gt; 最近成功运行的快照版本号）与历史口径
 * 复现都只认快照，不回头看现值。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent_eval_run")
public class AgentEvalRun {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属套件 ID */
    private String suiteId;

    /** 智能体 ID */
    private String agentId;

    /** 运行时智能体版本号快照（versionCount） */
    private Integer agentVersion;

    /** 触发方式: manual / gate / scheduled（列名 trigger_kind 避开保留字 trigger） */
    private String triggerKind;

    /** running / succeeded / failed / cancelled */
    private String status;

    /** 本轮用例数 */
    private Integer total;

    /** 通过条数 */
    private Integer passed;

    /** 通过率（百分比 0-100） */
    private Double passRate;

    /** 平均综合分 */
    private Double avgScore;

    /** 平均单题耗时（毫秒） */
    private Long avgLatencyMs;

    /** 本轮总 token（作答+评判） */
    private Long totalTokens;

    /** 对比基线运行 ID（NULL=按上一次成功运行推断） */
    private String baselineRunId;

    /** 评判模型引用快照（NULL=未启用评判） */
    private String judgeModel;

    /** 作答模型引用快照（NULL=跟随智能体/全局路由） */
    private String answerModel;

    /** 运行时门槛快照 */
    private Double thresholdRate;

    /** 运行时单条通过分快照 */
    private Double passScore;

    /** 失败原因（人话；单条用例失败写在 result，不写这里） */
    private String error;

    /** 开始时刻 */
    private LocalDateTime startedAt;

    /** 结束时刻 */
    private LocalDateTime finishedAt;

    /** 触发人 uid */
    private String createdBy;

    private LocalDateTime createTime;
}
