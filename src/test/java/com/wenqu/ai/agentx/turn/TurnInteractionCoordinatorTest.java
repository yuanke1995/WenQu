package com.wenqu.ai.agentx.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人在回路等待状态机：三档预算、超时决策、等待累计与 begin/end 成对。
 */
class TurnInteractionCoordinatorTest {

    private static final long PLAN_MS = 600_000L;
    private static final long ASK_MS = 600_000L;
    private static final long APPROVAL_MS = 120_000L;

    private final AtomicLong now = new AtomicLong();
    private final AtomicInteger begins = new AtomicInteger();
    private final AtomicInteger ends = new AtomicInteger();
    private final TurnInteractionCoordinator c = new TurnInteractionCoordinator(
            now::get,
            kind -> switch (kind) {
                case PLAN -> PLAN_MS;
                case ASK -> ASK_MS;
                case APPROVAL -> APPROVAL_MS;
            });

    @Test
    @DisplayName("形态判定：计划/审批/提问/混合/未知")
    void classifiesCards() {
        assertThat(TurnInteractionCoordinator.kindOf(List.of(planData()))).isEqualTo(TurnInteractionCoordinator.PendingKind.PLAN);
        assertThat(TurnInteractionCoordinator.kindOf(List.of(approvalData()))).isEqualTo(TurnInteractionCoordinator.PendingKind.APPROVAL);
        assertThat(TurnInteractionCoordinator.kindOf(List.of(askData()))).isEqualTo(TurnInteractionCoordinator.PendingKind.ASK);
        assertThat(TurnInteractionCoordinator.kindOf(List.of(approvalData(), askData())))
                .isEqualTo(TurnInteractionCoordinator.PendingKind.ASK);
        assertThat(TurnInteractionCoordinator.kindOf(List.of(Map.of())))
                .isEqualTo(TurnInteractionCoordinator.PendingKind.ASK);
    }

    @Test
    @DisplayName("计划超时：未到预算等待，超过预算收轮")
    void planTimeoutStops() {
        assertThat(c.tick(List.of(planData()), null, null)).isEqualTo(TurnInteractionCoordinator.Decision.WAIT);
        now.set(PLAN_MS);
        assertThat(c.tick(List.of(planData()), null, null)).isEqualTo(TurnInteractionCoordinator.Decision.WAIT);
        now.set(PLAN_MS + 1);
        assertThat(c.tick(List.of(planData()), null, null)).isEqualTo(TurnInteractionCoordinator.Decision.STOP_TIMEOUT);
        assertThat(c.kind()).isEqualTo(TurnInteractionCoordinator.PendingKind.PLAN);
    }

    @Test
    @DisplayName("审批超时按 120s 档收轮")
    void approvalTimeoutStops() {
        c.tick(List.of(approvalData()), null, null);
        now.set(APPROVAL_MS + 1);
        assertThat(c.tick(List.of(approvalData()), null, null)).isEqualTo(TurnInteractionCoordinator.Decision.STOP_TIMEOUT);
    }

    @Test
    @DisplayName("提问超时返回 ASK_TIMEOUT；接受后结算等待并继续，不结束本轮")
    void askTimeoutContinues() {
        c.tick(List.of(askData()), this::begin, this::end);
        now.set(ASK_MS + 1);

        assertThat(c.tick(List.of(askData()), this::begin, this::end)).isEqualTo(TurnInteractionCoordinator.Decision.ASK_TIMEOUT);
        assertThat(c.waitingOnHuman()).isTrue();

        c.askTimeoutAccepted(this::end);

        assertThat(c.waitingOnHuman()).isFalse();
        assertThat(c.humanWaitMs()).isEqualTo(ASK_MS + 1);
        assertThat(begins.get()).isEqualTo(1);
        assertThat(ends.get()).isEqualTo(1);
        assertThat(c.tick(List.of(), this::begin, this::end)).isEqualTo(TurnInteractionCoordinator.Decision.NONE);
        assertThat(ends.get()).isEqualTo(1);   // 已经结算过，不重复 end
    }

    @Test
    @DisplayName("begin/end 成对且只调一次；两段等待累计到 humanWaitMs")
    void waitAccountingAcrossTwoWaits() {
        now.set(0);
        c.tick(List.of(planData()), this::begin, this::end);
        now.set(100);
        c.tick(List.of(), this::begin, this::end);
        assertThat(c.humanWaitMs()).isEqualTo(100);
        assertThat(c.waitingOnHuman()).isFalse();

        now.set(200);
        c.tick(List.of(askData()), this::begin, this::end);
        now.set(350);
        c.tick(List.of(), this::begin, this::end);

        assertThat(c.humanWaitMs()).isEqualTo(250);
        assertThat(begins.get()).isEqualTo(2);
        assertThat(ends.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("超时文案、计划裁决与弃卡终态是纯策略")
    void pureOutcomes() {
        assertThat(TurnInteractionCoordinator.timeoutWarning(TurnInteractionCoordinator.PendingKind.PLAN))
                .isEqualTo("计划等待超时，本轮未继续执行");
        assertThat(TurnInteractionCoordinator.timeoutWarning(TurnInteractionCoordinator.PendingKind.APPROVAL))
                .isEqualTo("确认等待超时，本轮未继续执行该工具");

        assertThat(TurnInteractionCoordinator.planOutcome("supersede"))
                .isEqualTo(TurnInteractionCoordinator.PlanOutcome.SUPERSEDED);
        assertThat(TurnInteractionCoordinator.planOutcome("reject"))
                .isEqualTo(TurnInteractionCoordinator.PlanOutcome.CANCELLED);
        assertThat(TurnInteractionCoordinator.planOutcome("approve"))
                .isEqualTo(TurnInteractionCoordinator.PlanOutcome.NONE);
        assertThat(TurnInteractionCoordinator.planOutcome(null))
                .isEqualTo(TurnInteractionCoordinator.PlanOutcome.NONE);

        assertThat(TurnInteractionCoordinator.abandonStatus(true)).isEqualTo("done");
        assertThat(TurnInteractionCoordinator.abandonStatus(false)).isEqualTo("error");
        assertThat(TurnInteractionCoordinator.abandonResult(true, "等待超时"))
                .isEqualTo("（用户未回答这一题（等待超时））");
        assertThat(TurnInteractionCoordinator.abandonResult(false, "本轮已停止"))
                .isEqualTo("本轮已停止，这一步没有执行");
    }

    private void begin() {
        begins.incrementAndGet();
    }

    private void end() {
        ends.incrementAndGet();
    }

    private static Map<String, Object> planData() {
        return Map.of("kind", "confirmation", "call", Map.of("name", "plan_exit"));
    }

    private static Map<String, Object> approvalData() {
        return Map.of("kind", "confirmation", "call", Map.of("name", "run_code"));
    }

    private static Map<String, Object> askData() {
        return Map.of("kind", "external_execution");
    }
}
