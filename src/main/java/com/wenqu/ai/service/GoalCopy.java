package com.wenqu.ai.service;

/**
 * 目标模式的用户可见文案（唯一出口，与 {@code PlanCopy} 同规矩）。
 *
 * <p>纪律：<b>只讲用户结果，不写内部机制</b>——禁止出现 TASK_STATE / scope / verifier / revision /
 * 框架名。续跑提示词也走这里：正文不写沙盒路径、工具名、命令。
 *
 * <p>2026-10-22 任务运行时之后：正式收尾走 {@code goal_submit} 工具（带证据），平台由
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

    /**
     * 一轮续跑/首轮的完整输入（包成 {@code <goal_round>}，与 DSH 的续跑提示词同形）。
     * <p>每一轮都把目标原文、轮次带全：压缩可能把更早的轮次遮掉，模型必须每一轮都知道要做到什么。
     *
     * @param objective 目标原文
     * @param round     本次是第几轮（从 1 起）
     * @param maxRounds 自动续跑的安全上限（只兜底，不是给用户的预算）
     */
    public static String roundPrompt(String objective, int round, int maxRounds) {
        return "<goal_round>\n"
                + "目标：" + objective + "\n"
                + "轮次：" + round + "/" + maxRounds + "\n\n"
                + "在同一会话里继续推进这个目标。以当前工作区、工具结果和会话里已经落地的事实为准，"
                + "不要只凭之前的叙述判断进度；需要时先检查工作区再动手。\n"
                + "这一轮要有实际进展，并自己核对做出来的结果。全部目标真正完成时，在回答最后单独一行写「"
                + DONE_MARKER + "」，并列出本次的交付物（文件名/页面/链接）；做出来或改好的网页、文件、"
                + "表格等请同时用「交付产物」工具登记，用户会在右栏「产物」卡里查看与下载。"
                + "没做完不要写这一行，系统会自动开始下一轮。\n"
                + "如果遇到靠你自己无法解决的阻塞（缺权限、缺资料、外部依赖不可用），在回答最后单独一行写「"
                + BLOCKED_MARKER + "具体原因」；不要为了收尾把没做完的事说成做完了。\n"
                + "不要为了等用户拍板而停下，也不要向用户确认是否继续——按你的判断推进即可。\n"
                + "</goal_round>";
    }

    /**
     * 用户自己那一轮里注入的一句目标上下文：让助手在用户插话时仍然记得当前目标。
     * <p>续跑轮不需要它（{@link #roundPrompt} 已经带全），所以只在交互轮拼进模型输入。
     */
    public static String contextNote(String objective) {
        return "\n\n【当前目标】" + objective
                + "\n（这是你在本会话里持续推进的目标。完成时在回答最后单独一行写「" + DONE_MARKER
                + "」；遇到解决不了的阻塞写「" + BLOCKED_MARKER + "具体原因」。不需要向用户确认是否继续。）";
    }

    /** 条子上「已暂停」那一档的一句人话（受阻原因另有一行，不混在这句里） */
    public static String pausedHint(String endReason) {
        if ("turn-failed".equals(endReason)) return "这一轮没有正常跑完，先停在这里；可以继续。";
        if ("stopped".equals(endReason)) return "已按你的要求停下；可以继续。";
        if ("preempted".equals(endReason)) return "你先说了别的，目标停在原地；可以继续。";
        if ("budget".equals(endReason)) return "已达到本次目标的预算上限，先停在这里；可以改目标或继续。";
        if ("no-progress".equals(endReason)) return "连续几轮没有实质进展，先停在这里；可以改目标或继续。";
        if ("verification-failed".equals(endReason)) return "连续提交的证据都没通过核验，先停在这里；可以改目标或继续。";
        if ("completion-unconfirmed".equals(endReason)) return "连续完成检查仍未确认完成，先停在这里；可以改目标或继续。";
        if ("orphaned".equals(endReason)) return "上一次执行中断，已停下；点继续会先核对状态再接着跑。";
        return null;
    }
}
