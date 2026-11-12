package com.wenqu.ai.agent.chat;

/**
 * 一轮收尾里纯计算的那部分：中断尾标、正文与 notice 的拼装、这一轮要不要落库。
 *
 * <p>抽出来是为了让两条运行时（框架引擎 / 手写历史口径）共用同一套判据，并且能在单测里
 * 覆盖边界：正文为空但有过程、被「继续对话」取代、在等人工裁决、超时/预算/停滞/用户停止
 * 的优先级。I/O（引用自检、落库、发帧）仍留在调用方，本类不碰任何服务。
 */
public final class TurnCloseout {

    private TurnCloseout() {
    }

    /** 中断尾标的优先级：用户停止 &gt; 预算 &gt; 停滞 &gt; 等待超时 &gt; 默认截断。 */
    public static String interruptMarker(boolean stopped, boolean timedOut, boolean budget, boolean stalled) {
        if (stopped) return TurnRecorder.STOP_SUFFIX;
        if (budget) return TurnRecorder.BUDGET_SUFFIX;
        if (stalled) return TurnRecorder.STALL_SUFFIX;
        if (timedOut) return "\n\n> ⏹ 等待确认超时，这一轮没有继续执行";
        return TurnRecorder.TRUNCATION_SUFFIX;
    }

    /**
     * 正文的最终形态与是否落库。
     *
     * @param answer      模型正文（可能为空）
     * @param hasProcess  这一轮有没有工具卡或思考
     * @param superseded  这一轮被「继续对话」取代（正文为空时不再补中断标注）
     * @param awaiting    收尾时仍在等人工裁决（提问/审批/计划卡挂起）
     */
    public static Body settleBody(String answer, boolean hasProcess, boolean superseded,
                                  boolean stopped, boolean timedOut, boolean budget, boolean stalled,
                                  boolean awaiting) {
        String body = answer == null ? "" : answer;
        boolean realAnswer = !body.isBlank();
        String noticeText = "";
        if (!realAnswer && hasProcess) {
            if (!superseded) {
                noticeText = interruptMarker(stopped, timedOut, budget, stalled).trim();
                body = noticeText;
            }
        } else if (realAnswer && (stopped || timedOut || budget || stalled)) {
            String marker = interruptMarker(stopped, timedOut, budget, stalled);
            body = body + marker;
            noticeText = marker;
        }
        boolean persist = realAnswer || (hasProcess && !awaiting);
        return new Body(body, noticeText, persist);
    }

    /**
     * 站内通知的「提前结束」原因：优先用 emitRunFailure 挑好的人话，缺失时按失败形态补一句。
     * 原始异常串不进通知（英文+响应体碎片读不出该充值还是换模型）。
     */
    public static String earlyExitReason(String spokenFailure, boolean neverLaunched, boolean timedOut,
                                         boolean ranOutBudget, boolean stalled, boolean cutByMaxIters) {
        if (spokenFailure != null && !spokenFailure.isBlank()) return spokenFailure;
        if (neverLaunched) return "这一轮模型没有被调用，请重试或新建一条会话再问";
        if (timedOut) return "等你确认的那一步超时，这一轮没有继续执行";
        if (ranOutBudget) return TurnRecorder.TURN_BUDGET_NOTE;
        if (stalled) return TurnRecorder.STALL_NOTE;
        if (cutByMaxIters) return "推理步数已用满，活没干完就停了";
        return null;
    }

    /** 引用自检只在「正常答完且有正文」的轮次跑；停止/超时/预算/停滞与空正文都不跑。 */
    public static boolean citationsApplicable(boolean stopped, boolean timedOut, boolean budget,
                                              boolean stalled, boolean realAnswer) {
        return !stopped && !timedOut && !budget && !stalled && realAnswer;
    }

    /** 轮终 webhook 的状态口径：停/stall=stopped（用户侧行为），超时/预算=failed，其余=completed。 */
    public static String webhookStatus(boolean stopped, boolean stalled, boolean timedOut, boolean budget) {
        if (stopped || stalled) return "stopped";
        if (timedOut || budget) return "failed";
        return "completed";
    }

    /** 收尾后的正文、notice 文本与「要不要落库」。 */
    public record Body(String text, String noticeText, boolean persist) {
    }
}
