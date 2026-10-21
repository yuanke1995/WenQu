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
    public static final String ENTRY_BUTTON = "目标";

    /** 右栏面板标题 */
    public static final String PANEL_TITLE = "目标";

    /** 起草态标题 */
    public static final String DRAFT_TITLE = "定个目标";

    /** 待确认态标题 */
    public static final String CONFIRM_TITLE = "等你确认验收标准";

    /** 验收中标题 */
    public static String verifyingTitle(int total) {
        return "正在核验 " + total + " 条标准";
    }

    /** 达成标题 */
    public static String achievedTitle(int n) {
        return "已完成，" + n + " 条验收标准都有凭据。";
    }

    /** 已取消 / 未达成收尾 */
    public static String endedTitle(String endReason) {
        return switch (endReason == null ? "" : endReason) {
            case "cancelled" -> "已取消";
            case "failed" -> "未达成收尾";
            default -> "已结束";
        };
    }

    /** 过期（STALE） */
    public static final String STALE_HINT = "这条结论是在旧版本产物上验的，需要重新验。";

    /** 暂停 */
    public static final String PAUSED_HINT = "已暂停，随时可以继续。";

    /** 同步异常（不许静默） */
    public static final String SYNC_WARN = "目标和助手看到的不一致，本轮以这份为准重新同步。";

    // ---- 下一轮输入（两件套：当前目标 + 未通过/未验证的标准） ----

    /**
     * 首轮输入：目标刚定下、一条验收标准都还没有。
     * <p>为什么不复用 {@link #continuation}：那边会说「所有标准都已有验证结果，请收口」——
     * 目标刚开跑就收到这句话，等于第一轮就让它宣布结束。这里让它先把「什么算完成」提出来，
     * 再往下做；标准由用户发起、助手起草，提出后即被采纳（用户可改）。
     */
    public static String startPrompt(String objective) {
        return "开始推进这个目标：" + objective + "\n"
                + "这个目标还没有验收标准。请先提出 1–3 条「什么算完成」的验收标准，作为候选要求提交给系统"
                + "（不要只写在回答正文里），再往下做：\n"
                + "每条都要能被核对（做到什么、看到什么算过），不要写「做得好」「令人满意」这类无法判定的句子。\n"
                + "提出后会作为本次目标的验收标准，用户可以修改。";
    }

    /**
     * 组下一轮的输入。<b>不带「请你继续」这类空话</b>——带当前目标与未通过/未验证的标准，
     * 模型才知道这一轮该收敛到什么上。没有剩余预算那一件：目标不设预算。
     */
    public static String continuation(String objective, List<String> pendingCriteria) {
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
        sb.append("请基于当前进展继续，不要重复已完成的部分。");
        return sb.toString();
    }
}
