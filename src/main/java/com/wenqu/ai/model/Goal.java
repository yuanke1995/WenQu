package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
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
    private String endReason;

    /** 助手报告的受阻原因（endReason=blocked 时有值；自由文本，界面上原样显示） */
    private String blockedReason;

    /** 创建时刻 */
    private LocalDateTime createdAt;

    /** 进入 RUNNING 时刻 */
    private LocalDateTime startedAt;

    /** 终态时刻 */
    private LocalDateTime endedAt;
}
