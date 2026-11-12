package com.wenqu.ai.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCloseoutTest {

    @Test
    void interruptMarkerPrefersUserStopThenBudgetThenStallThenTimeout() {
        assertThat(TurnCloseout.interruptMarker(true, true, true, true))
                .isEqualTo(TurnRecorder.STOP_SUFFIX);
        assertThat(TurnCloseout.interruptMarker(false, true, true, true))
                .isEqualTo(TurnRecorder.BUDGET_SUFFIX);
        assertThat(TurnCloseout.interruptMarker(false, true, false, true))
                .isEqualTo(TurnRecorder.STALL_SUFFIX);
        assertThat(TurnCloseout.interruptMarker(false, true, false, false))
                .contains("等待确认超时");
        assertThat(TurnCloseout.interruptMarker(false, false, false, false))
                .isEqualTo(TurnRecorder.TRUNCATION_SUFFIX);
    }

    @Test
    void realAnswerAppendsMarkerAndPersists() {
        TurnCloseout.Body b = TurnCloseout.settleBody("正文", true, false, true, false, false, false, false);
        assertThat(b.text()).startsWith("正文").contains(TurnRecorder.STOP_SUFFIX);
        assertThat(b.noticeText()).isEqualTo(TurnRecorder.STOP_SUFFIX);
        assertThat(b.persist()).isTrue();
    }

    @Test
    void emptyAnswerWithProcessKeepsBodyButSupersededDoesNotAddNotice() {
        TurnCloseout.Body b = TurnCloseout.settleBody("", true, false, true, false, false, false, false);
        assertThat(b.text()).isEqualTo(TurnRecorder.STOP_SUFFIX.trim());
        assertThat(b.persist()).isTrue();

        TurnCloseout.Body superseded = TurnCloseout.settleBody("", true, true, true, false, false, false, false);
        assertThat(superseded.text()).isEmpty();
        assertThat(superseded.noticeText()).isEmpty();
        assertThat(superseded.persist()).isTrue();
    }

    @Test
    void awaitingWithoutAnswerIsNotPersisted() {
        TurnCloseout.Body b = TurnCloseout.settleBody("", true, false, false, false, false, false, true);
        assertThat(b.persist()).isFalse();
        assertThat(TurnCloseout.settleBody("", false, false, true, false, false, false, false).persist()).isFalse();
    }

    @Test
    void citationsOnlyOnCleanAnsweredTurns() {
        assertThat(TurnCloseout.citationsApplicable(false, false, false, false, true)).isTrue();
        assertThat(TurnCloseout.citationsApplicable(true, false, false, false, true)).isFalse();
        assertThat(TurnCloseout.citationsApplicable(false, true, false, false, true)).isFalse();
        assertThat(TurnCloseout.citationsApplicable(false, false, true, false, true)).isFalse();
        assertThat(TurnCloseout.citationsApplicable(false, false, false, true, true)).isFalse();
        assertThat(TurnCloseout.citationsApplicable(false, false, false, false, false)).isFalse();
    }

    @Test
    void earlyExitReasonPrefersSpokenThenFailureShape() {
        assertThat(TurnCloseout.earlyExitReason("已挑好的人话", true, true, true, true, true)).isEqualTo("已挑好的人话");
        assertThat(TurnCloseout.earlyExitReason(null, true, true, true, true, true)).contains("模型没有被调用");
        assertThat(TurnCloseout.earlyExitReason(null, false, true, true, true, true)).contains("超时");
        assertThat(TurnCloseout.earlyExitReason(null, false, false, true, true, true))
                .isEqualTo(TurnRecorder.TURN_BUDGET_NOTE);
        assertThat(TurnCloseout.earlyExitReason(null, false, false, false, true, true))
                .isEqualTo(TurnRecorder.STALL_NOTE);
        assertThat(TurnCloseout.earlyExitReason(null, false, false, false, false, true)).contains("推理步数");
        assertThat(TurnCloseout.earlyExitReason(null, false, false, false, false, false)).isNull();
        assertThat(TurnCloseout.earlyExitReason("  ", false, false, false, false, false)).isNull();
    }

    @Test
    void webhookStatusMapsStopAndBudget() {
        assertThat(TurnCloseout.webhookStatus(true, false, false, false)).isEqualTo("stopped");
        assertThat(TurnCloseout.webhookStatus(false, true, false, false)).isEqualTo("stopped");
        assertThat(TurnCloseout.webhookStatus(false, false, true, false)).isEqualTo("failed");
        assertThat(TurnCloseout.webhookStatus(false, false, false, true)).isEqualTo("failed");
        assertThat(TurnCloseout.webhookStatus(false, false, false, false)).isEqualTo("completed");
    }

    @Test
    void blankAnswerIsTreatedAsEmpty() {
        TurnCloseout.Body b = TurnCloseout.settleBody("   ", false, false, false, false, false, false, false);
        assertThat(b.text()).isBlank();
        assertThat(b.persist()).isFalse();
    }
}
