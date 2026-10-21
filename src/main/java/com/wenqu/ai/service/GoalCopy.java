package com.wenqu.ai.service;

import java.util.List;

/**
 * 目标模式的用户可见文案（唯一出口，与 {@code PlanCopy} 同规矩）。
 *
 * <p>纪律：<b>只讲用户结果，不写内部机制</b>——禁止出现 TASK_STATE / scope / beginTask /
 * verifier / evidenceBinding / revision / 框架名。模型下一轮输入也走这里
 * （{@link #continuation}），正文不写沙盒路径、工具名、命令（与 PlanCopy.guidance() 同一条）。
 */
public final class GoalCopy {

    private GoalCopy() {}

    // ---- 入口与面板 ----

    /** 入口按钮 */
    public static final String ENTRY_BUTTON = "设为目标";

    /** 右栏面板标题 */
    public static final String PANEL_TITLE = "目标";

    /** 起草态标题 */
    public static final String DRAFT_TITLE = "定个目标";

    /** 待确认态标题 */
    public static final String CONFIRM_TITLE = "等你确认验收标准";

    /** 进行中标题（第 {n}/{max} 轮 · 已用 {tokens} token） */
    public static String runningTitle(int turnIndex, int turnsMax, long tokens) {
        return "进行中 · 第 " + turnIndex + "/" + turnsMax + " 轮 · 已用 " + tokens + " token";
    }

    /** 验收中标题 */
    public static String verifyingTitle(int total) {
        return "正在核验 " + total + " 条标准";
    }

    /** 达成标题 */
    public static String achievedTitle(int n) {
        return "已完成，" + n + " 条验收标准都有凭据。";
    }

    /** 预算用满标题 */
    public static String budgetExceededTitle(int remaining) {
        return remaining > 0
                ? "预算用满，这件事还没做完（还差 " + remaining + " 条没验过）。"
                : "预算用满，这件事还没做完。";
    }

    /** 已取消 / 未达成收尾 */
    public static String endedTitle(String endReason) {
        return switch (endReason == null ? "" : endReason) {
            case "cancelled" -> "已取消";
            case "failed" -> "未达成收尾";
            default -> "已结束";
        };
    }

    // ---- 预警与撞闸 ----

    /** 80% 预警（面板黄条 + 喂给模型的收敛指令同源） */
    public static String budgetWarn(int remaining) {
        return "预算快用完了，我先把能交付的部分收口，还差 " + remaining + " 条没验过。";
    }

    /** 撞闸（未完成清单：框架文档要求「不是悄然截断」） */
    public static String budgetStop(String donePart, String remainingPart) {
        return "预算用满，这件事还没做完。\n已完成：" + donePart + "\n还差：" + remainingPart
                + "\n要继续的话给我加点预算。";
    }

    /** 过期（STALE） */
    public static final String STALE_HINT = "这条结论是在旧版本产物上验的，需要重新验。";

    /** 暂停 */
    public static final String PAUSED_HINT = "已暂停，随时可以继续。";

    /** 同步异常（不许静默） */
    public static final String SYNC_WARN = "目标和助手看到的不一致，本轮以这份为准重新同步。";

    // ---- 下一轮输入（三件套：当前目标 + 未通过/未验证的标准 + 剩余预算） ----

    /**
     * 组下一轮的输入。<b>不带「请你继续」这类空话</b>——带当前目标、未通过/未验证的标准、
     * 剩余预算三件套，模型才知道这一轮该收敛到什么上。
     */
    public static String continuation(String objective, List<String> pendingCriteria, int turnsLeft, long tokensLeft) {
        StringBuilder sb = new StringBuilder();
        sb.append("继续推进这个目标：").append(objective).append('\n');
        if (pendingCriteria != null && !pendingCriteria.isEmpty()) {
            sb.append("还没有通过验证的标准：\n");
            for (String c : pendingCriteria) {
                sb.append("- ").append(c).append('\n');
            }
        } else {
            sb.append("所有标准都已有验证结果，请收口并给出结论。\n");
        }
        if (turnsLeft > 0) sb.append("剩余轮数预算：").append(turnsLeft).append(" 轮。\n");
        if (tokensLeft > 0) sb.append("剩余 token 预算：").append(tokensLeft).append("。\n");
        sb.append("请基于当前进展继续，不要重复已完成的部分。");
        return sb.toString();
    }

    /** 80% 预警时喂给模型的 runtime 上下文块（与面板黄条同源，让它自己收敛范围） */
    public static String budgetWarnBlock(String objective, int remaining, int turnsLeft, long tokensLeft) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标预算即将用尽（还剩 ").append(remaining).append(" 条标准没有通过验证）。\n");
        sb.append("当前目标：").append(objective).append('\n');
        sb.append("请收敛范围：停新委派、优先可交付部分，把已有工作收口成结论。\n");
        if (turnsLeft > 0) sb.append("剩余轮数：").append(turnsLeft).append("。\n");
        if (tokensLeft > 0) sb.append("剩余 token：").append(tokensLeft).append("。");
        return sb.toString();
    }
}
