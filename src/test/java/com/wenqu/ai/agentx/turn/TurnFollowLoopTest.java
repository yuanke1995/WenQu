package com.wenqu.ai.agentx.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 跟随循环状态：迁移只置位、不互相覆盖；两个退出守卫与循环判据同源。 */
class TurnFollowLoopTest {

    @Test
    @DisplayName("状态迁移：各自置位，初始全否")
    void stateTransitions() {
        TurnFollowLoop loop = new TurnFollowLoop();
        assertThat(loop.launched()).isFalse();
        assertThat(loop.stopped()).isFalse();
        assertThat(loop.stalled()).isFalse();
        assertThat(loop.timedOut()).isFalse();
        assertThat(loop.neverLaunched()).isFalse();

        loop.launch();
        loop.stop();
        loop.stall();
        loop.timeout();
        loop.markNeverLaunched();

        assertThat(loop.launched()).isTrue();
        assertThat(loop.stopped()).isTrue();
        assertThat(loop.stalled()).isTrue();
        assertThat(loop.timedOut()).isTrue();
        assertThat(loop.neverLaunched()).isTrue();
    }

    @Test
    @DisplayName("迭代状态：种子游标、播报计数、批准去重集与用量基准都收在循环对象里")
    void iterationState() {
        TurnFollowLoop loop = new TurnFollowLoop();
        loop.seed(5, 3);
        assertThat(loop.cursor()).isEqualTo(5);
        assertThat(loop.emitFrom()).isEqualTo(3);

        loop.cursor(9);
        loop.announcedRefs(2);
        loop.announcedApprovals().add("req-1");
        loop.lastStateProbeAt(1234);

        assertThat(loop.cursor()).isEqualTo(9);
        assertThat(loop.announcedRefs()).isEqualTo(2);
        assertThat(loop.announcedApprovals()).containsExactly("req-1");
        assertThat(loop.lastStateProbeAt()).isEqualTo(1234);
        assertThat(loop.usageStamp()).containsExactly(-1L, -1L);
    }

    @Test
    @DisplayName("空转终止只对未结算的轮生效")
    void stallGuard() {
        assertThat(TurnFollowLoop.shouldStopForStall(true, false)).isTrue();
        assertThat(TurnFollowLoop.shouldStopForStall(true, true)).isFalse();
        assertThat(TurnFollowLoop.shouldStopForStall(false, false)).isFalse();
    }

    @Test
    @DisplayName("预算达到上限即尽，与循环退出条件同判据")
    void budgetGuard() {
        assertThat(TurnFollowLoop.budgetExhausted(99, 100)).isFalse();
        assertThat(TurnFollowLoop.budgetExhausted(100, 100)).isTrue();
        assertThat(TurnFollowLoop.budgetExhausted(101, 100)).isTrue();
    }
}
