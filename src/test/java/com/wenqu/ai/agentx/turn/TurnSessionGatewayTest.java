package com.wenqu.ai.agentx.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 遗留轮归档守卫：只归档「不会再动」的上一轮。 */
class TurnSessionGatewayTest {

    @Test
    @DisplayName("已结束、仍在跑、有未对账工具：都不归档")
    void skipsEndedActiveOrUncertain() {
        assertThat(TurnSessionGateway.needsStalledReconcile(true, false, false, false, false)).isFalse();
        assertThat(TurnSessionGateway.needsStalledReconcile(false, true, false, false, false)).isFalse();
        assertThat(TurnSessionGateway.needsStalledReconcile(false, false, true, false, false)).isFalse();
    }

    @Test
    @DisplayName("还挂在 PENDING 且没人放弃：不归档；已放弃：归档")
    void pendingOnlyWhenAbandoned() {
        assertThat(TurnSessionGateway.needsStalledReconcile(false, false, false, true, false)).isFalse();
        assertThat(TurnSessionGateway.needsStalledReconcile(false, false, false, true, true)).isTrue();
    }

    @Test
    @DisplayName("干净遗留轮：归档")
    void cleanStalledTurnReconciles() {
        assertThat(TurnSessionGateway.needsStalledReconcile(false, false, false, false, false)).isTrue();
    }
}
