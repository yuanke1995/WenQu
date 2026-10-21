package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目标模式的一条目标（产品面权威）。
 * <p>
 * 分工是刻意的：<b>本表是界面与预算判定的唯一真源</b>，框架的 {@code tasksContext}
 * （Scope/要求/验证）只是模型视图——每轮开跑前由 {@code GoalBridge} 从本表覆盖写进框架，
 * 进程重启/换副本后框架那份不在本进程，右栏仍要能长出目标，所以不读框架。
 * <p>
 * 同会话至多一条非终态目标。换目标在产品上是「结束旧行 + 起新行」：框架禁止同 taskId
 * 改目标文本，而历史与旧验证要留在旧行里可审计。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_goal")
public class Goal {

    /** 目标ID（= 框架 Scope.taskId，一次目标一个，永不复用） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属会话 */
    private String sessionId;

    /** 发起用户（仅本人可操作） */
    private String userId;

    /** 发起时绑定的智能体 */
    private String agentId;

    /** 目标原文（逐字进框架 Scope.objective；框架帽 4000） */
    private String objective;

    /** 验收标准 JSON [{id,kind,text,status,decisionSource,verifierId,verifierParams}] */
    private String criteriaJson;

    /** 约束 JSON（同结构，kind=CONSTRAINT） */
    private String constraintsJson;

    /** 工件指纹（sha256(路径+mtime+size)；纯检索/写作类目标不钉） */
    private String subjectVersion;

    /** 每标准最新一份验证 JSON {requirementId,outcome,verifierId,reason,actionId,binding,recordedAt} */
    private String verificationsJson;

    /** 状态: DRAFT/CLARIFYING/AWAITING_CONFIRMATION/RUNNING/VERIFYING/WAITING_INPUT/PAUSED/BUDGET_EXCEEDED/ACHIEVED/ENDED_INCOMPLETE/FAILED/CANCELLED */
    private String status;

    /** 轮数上限（0=不限） */
    private Integer budgetTurns;

    /** token 上限（0=不限） */
    private Long budgetTokens;

    /** 工具调用上限（0=不限） */
    private Integer budgetToolCalls;

    /** 机器分钟上限（0=不限） */
    private Integer budgetMinutes;

    /** 已用轮数 */
    private Integer usedTurns;

    /** 已用 token */
    private Long usedTokens;

    /** 已用工具调用 */
    private Integer usedToolCalls;

    /** 已用机器毫秒 */
    private Long usedMs;

    /** 框架 contractVersion 水位（同步对账用） */
    private Long contractVersion;

    /** 框架 TaskContextState.revision 水位（不一致就本轮重读再写） */
    private Long stateRevision;

    /** 目标覆盖的首轮 */
    private String firstTurnId;

    /** 目标覆盖的末轮 */
    private String lastTurnId;

    /** 已跑轮计数（幂等键 goal:{id}:{n} 的 n） */
    private Integer turnCounter;

    /** 终态原因: achieved/budget/cancelled/failed/incomplete */
    private String endReason;

    /** 创建时刻 */
    private LocalDateTime createdAt;

    /** 进入 RUNNING 时刻 */
    private LocalDateTime startedAt;

    /** 终态时刻 */
    private LocalDateTime endedAt;
}
