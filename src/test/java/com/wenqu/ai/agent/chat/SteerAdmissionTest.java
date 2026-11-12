package com.wenqu.ai.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插话准入：活 run 定身份，把手只在「同一轮」时补 stopped。
 * <p>回归的是线上那两个反例：目标轮插不进、以及把目标轮听到的话记到已结束的用户轮上。
 */
class SteerAdmissionTest {

    private static TurnHandle handle(String turnId, String uid, boolean stopped) {
        TurnHandle h = new TurnHandle(turnId, uid, null);
        h.stopped().set(stopped);
        return h;
    }

    @Test
    @DisplayName("目标轮在跑（无把手）→ 收，身份用 run 的 turnId")
    void goalRoundAccepted() {
        ChatRunRuntime.SteerActor actor =
                ChatRunRuntime.resolveSteerActor("u1", null, "goal:g:1", "u1");
        assertThat(actor).isNotNull();
        assertThat(actor.turnId()).isEqualTo("goal:g:1");
    }

    @Test
    @DisplayName("已结束用户轮的把手还在、目标轮已开跑 → 用 run 身份，且不继承旧把手的叫停标记")
    void staleHandleDoesNotHijack() {
        TurnHandle stale = handle("user-turn", "u1", true);
        ChatRunRuntime.SteerActor actor =
                ChatRunRuntime.resolveSteerActor("u1", stale, "goal:g:2", "u1");
        assertThat(actor).isNotNull();
        assertThat(actor.turnId()).isEqualTo("goal:g:2");
    }

    @Test
    @DisplayName("同一轮被叫停 → 拒")
    void stoppedSameTurnRejected() {
        ChatRunRuntime.SteerActor actor =
                ChatRunRuntime.resolveSteerActor("u1", handle("t1", "u1", true), "t1", "u1");
        assertThat(actor).isNull();
    }

    @Test
    @DisplayName("不是本人的轮 → 拒（不管有没有把手）")
    void otherUsersTurnRejected() {
        assertThat(ChatRunRuntime.resolveSteerActor("u1", null, "goal:g:1", "u2")).isNull();
        assertThat(ChatRunRuntime.resolveSteerActor("u1", handle("t1", "u2", false), null, null)).isNull();
    }

    @Test
    @DisplayName("收集型通道（无 run）→ 认把手；收集型轮被叫停同样拒")
    void collectingTurnFallsBackToHandle() {
        ChatRunRuntime.SteerActor actor =
                ChatRunRuntime.resolveSteerActor("u1", handle("t9", "u1", false), null, null);
        assertThat(actor).isNotNull();
        assertThat(actor.turnId()).isEqualTo("t9");
        assertThat(ChatRunRuntime.resolveSteerActor("u1", handle("t9", "u1", true), null, null)).isNull();
    }

    @Test
    @DisplayName("没有任何在跑的轮 → 拒")
    void noLiveTurnRejected() {
        assertThat(ChatRunRuntime.resolveSteerActor("u1", null, null, null)).isNull();
        assertThat(ChatRunRuntime.resolveSteerActor(null, null, "goal:g:1", "u1")).isNull();
    }
}
