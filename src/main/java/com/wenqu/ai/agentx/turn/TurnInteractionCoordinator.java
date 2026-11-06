package com.wenqu.ai.agentx.turn;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * 人在回路的等待与超时状态机：只做决策，不发帧、不碰会话。
 * <p>三张卡三档预算：提问超时按「未作答」继续本轮，计划/审批超时收轮；等待期间单独累计
 * humanWaitMs，机器预算按它扣掉。引擎只负责 begin/end 台账与超时后的 I/O。
 */
public final class TurnInteractionCoordinator {

    public enum PendingKind {ASK, PLAN, APPROVAL}

    public enum Decision {NONE, WAIT, ASK_TIMEOUT, STOP_TIMEOUT}

    /** 计划卡的三种裁决结果：继续对话=取代、拒绝=取消、其余=无终态帧。 */
    public enum PlanOutcome {NONE, SUPERSEDED, CANCELLED}

    private final LongSupplier clock;
    private final ToLongFunction<PendingKind> budgetMs;

    private long pendingSince;
    private long waitBeganAt;
    private long humanWaitMs;
    private boolean waiting;
    private PendingKind kind;

    public TurnInteractionCoordinator(LongSupplier clock, ToLongFunction<PendingKind> budgetMs) {
        this.clock = clock;
        this.budgetMs = budgetMs;
    }

    /**
     * 每次循环跳一次：没有待办就结算等待；有待办就按当前形态计预算。
     * <p>begin/end 回调成对出现：只有第一次挂卡时 begin，待办清空或提问超时被接受时才 end。
     */
    public Decision tick(List<Map<String, Object>> pendingDatas, Runnable onBeginWait, Runnable onEndWait) {
        long now = clock.getAsLong();
        if (pendingDatas == null || pendingDatas.isEmpty()) {
            settle(now, onEndWait);
            return Decision.NONE;
        }
        if (!waiting) {
            pendingSince = now;
            waitBeganAt = now;
            waiting = true;
            if (onBeginWait != null) onBeginWait.run();
        }
        kind = kindOf(pendingDatas);
        if (now - pendingSince > budgetMs.applyAsLong(kind)) {
            return kind == PendingKind.ASK ? Decision.ASK_TIMEOUT : Decision.STOP_TIMEOUT;
        }
        return Decision.WAIT;
    }

    /** 提问超时已被接受：这一轮继续跑，等待段结算并结束。 */
    public void askTimeoutAccepted(Runnable onEndWait) {
        long now = clock.getAsLong();
        if (waiting) {
            humanWaitMs += Math.max(0L, now - waitBeganAt);
            waitBeganAt = 0;
            waiting = false;
        }
        pendingSince = 0;
        if (onEndWait != null) onEndWait.run();
    }

    /** 待办已清空：把正在进行的等待结算掉；没有等待时是空操作。 */
    public void settle(Runnable onEndWait) {
        settle(clock.getAsLong(), onEndWait);
    }

    private void settle(long now, Runnable onEndWait) {
        if (waiting) {
            humanWaitMs += Math.max(0L, now - waitBeganAt);
            waitBeganAt = 0;
            waiting = false;
            if (onEndWait != null) onEndWait.run();
        }
        pendingSince = 0;
    }

    /** 已结算的人工等待累计（不含正在进行的那一段；机器预算另按 waitingOnHuman/waitBeganAt 扣）。 */
    public long humanWaitMs() {
        return humanWaitMs;
    }

    public boolean waitingOnHuman() {
        return waiting;
    }

    public long waitBeganAt() {
        return waitBeganAt;
    }

    public PendingKind kind() {
        return kind;
    }

    /** 超时警示原文：计划与其余两类分开，前端与日志都读这一句。 */
    public static String timeoutWarning(PendingKind kind) {
        return kind == PendingKind.PLAN
                ? "计划等待超时，本轮未继续执行"
                : "确认等待超时，本轮未继续执行该工具";
    }

    /** 框架计划裁决原始值 → 界面终态帧；不认识的值为 NONE，不造帧。 */
    public static PlanOutcome planOutcome(String raw) {
        if ("supersede".equals(raw)) return PlanOutcome.SUPERSEDED;
        if ("reject".equals(raw)) return PlanOutcome.CANCELLED;
        return PlanOutcome.NONE;
    }

    /** 弃卡终态：提问卡按「已答但未作答」收 done；其它工具确实没执行，按 error 收。 */
    public static String abandonStatus(boolean ask) {
        return ask ? "done" : "error";
    }

    public static String abandonResult(boolean ask, String why) {
        return ask ? "（用户未回答这一题（" + why + "））" : why + "，这一步没有执行";
    }

    /**
     * 待办形态判定：只要有一张非 confirmation 的挂起就是提问（提问最需要不替用户做决定）；
     * confirmation 且工具名以 plan_ 开头是计划；其余是审批。判据与卡片分流同源。
     */
    static PendingKind kindOf(List<Map<String, Object>> pendingDatas) {
        for (Map<String, Object> data : pendingDatas == null ? List.<Map<String, Object>>of() : pendingDatas) {
            if (data == null) continue;
            String kind = str(data.get("kind"));
            if ("confirmation".equals(kind)) {
                Object call = data.get("call") != null ? data.get("call") : data;
                if (toolNameOf(call).startsWith("plan_")) return PendingKind.PLAN;
            } else {
                return PendingKind.ASK;
            }
        }
        return PendingKind.APPROVAL;
    }

    private static String toolNameOf(Object node) {
        if (!(node instanceof Map<?, ?> map)) return "";
        Object content = map.get("content");
        if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> bm && bm.get("name") != null) return String.valueOf(bm.get("name"));
            }
        }
        Object name = map.get("name");
        return name == null ? "" : String.valueOf(name);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
