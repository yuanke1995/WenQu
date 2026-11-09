package com.wenqu.ai.service;

/**
 * 目标模式的用户可见文案（唯一出口，与 {@code PlanCopy} 同规矩）。
 *
 * <p>纪律：<b>只讲用户结果，不写内部机制</b>——禁止出现 TASK_STATE / scope / verifier / revision /
 * 框架名。续跑提示词也走这里：正文不写沙盒路径、工具名、命令。
 *
 * <p>正式收尾走 {@code goal_submit} 工具（带证据），平台由
 * {@code GoalVerifier} 核验后才算完成；下面两个标记保留为<b>兼容入口</b>——模型没调工具时，
 * 标记只生成一份「完成申请」，不会直接改终态。
 */
public final class GoalCopy {

    private GoalCopy() {}

    /** 入口按钮 / 右栏面板标题 */
    public static final String ENTRY_BUTTON = "目标";

    /** 右栏面板标题 */
    public static final String PANEL_TITLE = "目标";

    /**
     * 兼容标记：回答最后单独一行写这个，平台会生成一份完成申请并走证据核验。
     * <p><b>不再直接算完成</b>：没有证据的 marker 会被 Verifier 判缺口并排下一轮。
     * 正式路径是 {@code goal_submit}；保留它只是为了不让旧提示词/旧模型立刻断掉。
     */
    public static final String DONE_MARKER = "【目标完成】";

    /**
     * 兼容标记：回答最后单独一行以它开头，后面跟一句具体原因。
     * <p>受阻不是完成：目标停下等人（暂停态），用户可改目标、继续或删除。
     * 正式路径是 {@code goal_submit} 的 blocked 分支。
     */
    public static final String BLOCKED_MARKER = "【目标受阻】";

    /** 条子上「已暂停」那一档的一句人话（受阻原因另有一行，不混在这句里） */
    public static String pausedHint(String endReason) {
        if ("turn-failed".equals(endReason)) return "这一轮没有正常跑完，先停在这里；可以继续。";
        if ("stopped".equals(endReason)) return "已按你的要求停下；点「继续目标」才接着跑，直接提问不会自动继续。";
        if ("preempted".equals(endReason)) return "这句答完自动继续。";
        if ("budget".equals(endReason)) return "已达到本次目标的预算上限，先停在这里；可以改目标或继续。";
        if ("no-progress".equals(endReason)) return "连续几轮没有实质进展，先停在这里；可以改目标或继续。";
        if ("verification-failed".equals(endReason)) return "连续提交的证据都没通过核验，先停在这里；可以改目标或继续。";
        if ("completion-unconfirmed".equals(endReason)) return "连续完成检查仍未确认完成，先停在这里；可以改目标或继续。";
        if ("orphaned".equals(endReason)) return "上一次执行中断，已停下；点继续会先核对状态再接着跑。";
        return null;
    }
}
