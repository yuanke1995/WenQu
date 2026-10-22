package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目标模式的一条目标（产品面权威）。
 *
 * <p>形状对齐 DSH 的 goal：<b>一个跨轮的目标 + 一个阶段</b>，没有验收标准、没有人工确认、
 * 没有逐条核验。用户输入目标后系统自己往下推进；助手在回答最后一行写「【目标完成】」即停，
 * 写「【目标受阻】」则以受阻原因停下。用户的动作只剩三个：改目标原文、停止/继续、删除。
 *
 * <p>阶段取值见 {@code GoalService}：{@code RUNNING}（正在自动推进）、{@code PAUSED}（用户停了/
 * 运行异常停下）、{@code ACHIEVED}（助手自报完成）、{@code CANCELLED}（用户删除）。
 * 同会话至多一条未结束目标。
 *
 * <p>历史上那几列 {@code criteria_json}/{@code constraints_json}/{@code verifications_json} 等
 * 已不再读写（表里保留，老行不迁移；删列是不可逆动作，交给运维窗口）。目标原文就是模型这一侧
 * 唯一要看到的东西，每轮由续跑提示词原样带上。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_goal")
public class Goal {

    /** 目标ID（UUID；续跑幂等键 {@code goal:{id}:{n}} 用它，永不复用） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属会话 */
    private String sessionId;

    /** 发起用户（仅本人可操作） */
    private String userId;

    /** 发起时绑定的智能体 */
    private String agentId;

    /** 目标原文（续跑提示词逐字带上；框架无帽，这里按产品上限 4000） */
    private String objective;

    /** 阶段: RUNNING / PAUSED / ACHIEVED / CANCELLED（老行的其余状态在读取时归一，见 GoalService） */
    private String status;

    /** 自动续跑已起的轮数（只数后台目标轮，用户插话那一轮不算） */
    private Integer turnCounter;

    /** 目标区间内一共跑过多少轮（含用户插话轮，只做展示与记账） */
    private Integer usedTurns;

    /** 已用 token（按 c_ai_usage_log 聚合） */
    private Long usedTokens;

    /** 已用工具调用 */
    private Integer usedToolCalls;

    /** 已用机器毫秒 */
    private Long usedMs;

    /** 目标覆盖的首轮 */
    private String firstTurnId;

    /** 目标覆盖的末轮 */
    private String lastTurnId;

    /** 停下来的原因: achieved/paused/preempted/round-limit/blocked/turn-failed/cleared */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String endReason;

    /** 助手报告的受阻原因（endReason=blocked 时有值；自由文本，界面上原样显示） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String blockedReason;

    // ==================== 任务运行时（2026-10-22） ====================

    /**
     * 内部阶段：QUEUED / EXECUTING / VERIFYING / WAITING_APPROVAL / WAITING_EVENT / RECOVERING / DONE。
     * <p>{@link #status} 是对外阶段（四档 + FAILED/等待态），phase 只描述「控制面此刻在做什么」。
     */
    private String phase;

    /** 目标原文版本：edit 递增；轮次输入快照带版本，改目标不会污染在飞那一轮 */
    private Integer objectiveVersion;

    /** 当前执行者（实例ID）；同一目标同一时刻只允许一个 worker */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;

    /** 执行租约到期时刻（崩溃后由调度器回收） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

    /** 执行者心跳时刻 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime heartbeatAt;

    /** 下一轮最早可开始时刻；NULL=有轮在飞，不可调度 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRunAt;

    /** 续跑上下文 JSON（GoalContinuation）：目标/阶段/计划/事实/产物/失败尝试/缺口/预算 */
    private String continuationJson;

    /** 结构化结果 JSON（GoalOutcome）：state/code/summary/evidence/remaining */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String outcomeJson;

    /** 最近一条 goal 事件的账本ID（断线重连游标） */
    private Long lastEventId;

    /** 最近一次失败分类：TRANSIENT/CONFIG/ENVIRONMENT/PERMISSION/BUSINESS/UNKNOWN/ORPHANED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorClass;

    /** 连续失败次数（成功一轮清零） */
    private Integer attempt;

    /** 连续无进展轮数（产物/事实/计划/缺口都没变） */
    private Integer noProgressRounds;

    /** 崩溃恢复次数（超过阈值转 PAUSED，避免无限重跑） */
    private Integer recoveryCount;

    /** 最近一次有进展的时刻 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastProgressAt;

    /** WAITING_* 阶段在等什么（审批/外部事件/用户输入） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String waitingReason;

    /** Token 硬预算（0=用配置默认） */
    private Long budgetTokens;

    /** 机器毫秒硬预算（0=用配置默认） */
    private Long budgetMs;

    /** 工具调用硬预算（0=用配置默认） */
    private Integer budgetToolCalls;

    /** 轮次硬预算（0=用配置默认） */
    private Integer budgetRounds;

    /** 乐观锁版本（状态迁移与调度竞争用） */
    private Long rowVersion;

    /** 创建时刻 */
    private LocalDateTime createdAt;

    /** 进入 RUNNING 时刻 */
    private LocalDateTime startedAt;

    /** 终态时刻 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime endedAt;
}
