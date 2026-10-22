package com.wenqu.ai.agent.goal;

/**
 * 目标任务的内部阶段（写在 {@code c_ai_goal.phase}）。
 *
 * <p>对外仍然是四档（进行中 / 已暂停 / 已完成 / 已结束）加 FAILED/等待态；phase 只回答
 * 「控制面此刻在做什么」，让调度器、UI 和排障不用从若干布尔字段里猜。
 */
public final class GoalPhase {

    private GoalPhase() {
    }

    /** 已入队，等调度器起轮（或等下一轮时间到）。 */
    public static final String QUEUED = "QUEUED";
    /** 一轮已经提交给引擎，正在模型/工具循环里。 */
    public static final String EXECUTING = "EXECUTING";
    /** 模型提交了完成申请，正在跑 Verifier。 */
    public static final String VERIFYING = "VERIFYING";
    /** 已交付产物但没提交完成申请：下一轮只做完成确认，不做新工作。 */
    public static final String COMPLETION_CHECK = "COMPLETION_CHECK";
    /** 需要人工审批：工具审批/高风险交付，等用户决定。 */
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    /** 等外部事件/异步工具/子任务。 */
    public static final String WAITING_EVENT = "WAITING_EVENT";
    /** 上一轮进程崩溃/租约过期，正在做恢复对账。 */
    public static final String RECOVERING = "RECOVERING";
    /** 终态。 */
    public static final String DONE = "DONE";
}
