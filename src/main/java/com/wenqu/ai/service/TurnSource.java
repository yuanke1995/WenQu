package com.wenqu.ai.service;

/**
 * 一轮问答的发起方来源，随轮传递：通知按「只有用户发起的轮才推送」过滤，
 * 定时任务 / 工作流 / 评测的账本据此标明来处。
 */
public final class TurnSource {

    /** 网页/分享页里用户直接发起的一轮对话。 */
    public static final String USER = "user";
    /** 定时任务到点触发的一轮。 */
    public static final String SCHEDULED = "scheduled";
    /** 工作流节点里子智能体的一轮。 */
    public static final String WORKFLOW = "workflow";
    /**
     * 智能体团队成员轮（lead 或成员被消息唤醒）。它在自己的成员会话里跑、有可见的团队面板，
     * 与定时/工作流不同：审批通知与人工确认要照常送达，只是不广播成「用户发起的一轮」。
     */
    public static final String TEAM = "team";
    /** 智能体评测回归的一轮。 */
    public static final String EVAL = "eval";

    private TurnSource() {
    }
}
