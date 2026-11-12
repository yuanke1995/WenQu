package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.ChatRunBus;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.service.SessionEventService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.UserImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 目标轮（{@code goal:…} 后台续跑轮）插话：没有 TurnHandle，但运行总线上有活 run。
 * <p>回归的是线上那条「深度执行期间插话永远 accepted=false」——准入原来只看把手。
 */
class ChatRunRuntimeSteerTest {

    private final ChatRunBus bus = mock(ChatRunBus.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final SessionEventService events = mock(SessionEventService.class);
    private final ChatRunRuntime runtime = new ChatRunRuntime(mock(ConfigService.class), bus, events,
            sessionService, mock(TurnStateStore.class), mock(UserImageService.class));

    @AfterEach
    void tearDown() {
        TurnRegistry.removeHandle("s1");
        SteerReceipts.clear("goal:g:1");
        SteerReceipts.clear("t1");
    }

    private ChatRunBus.Run run(String turnId, String uid) {
        ChatRunBus.Run run = mock(ChatRunBus.Run.class);
        when(run.turnId()).thenReturn(turnId);
        when(run.userId()).thenReturn(uid);
        return run;
    }

    @Test
    @DisplayName("目标轮在跑（无把手）→ 接受，消息挂到 goal 轮号上")
    void goalRoundAcceptedAndAttributed() {
        ChatRunBus.Run live = run("goal:g:1", "admin");
        when(bus.running("s1")).thenReturn(live);
        runtime.bindSteer((uid, sid, text, imgs) -> "receipt-1");
        when(sessionService.appendMessage(any())).thenReturn("m1");

        assertThat(runtime.steerTurn("s1", "admin", " 改一下 logo ")).isEqualTo("m1");

        ArgumentCaptor<MessageWrite> write = ArgumentCaptor.forClass(MessageWrite.class);
        verify(sessionService).appendMessage(write.capture());
        assertThat(write.getValue().getTurnId()).isEqualTo("goal:g:1");
        assertThat(write.getValue().getContent()).isEqualTo("改一下 logo");
        verify(events).append("s1", "admin", "goal:g:1", "steer", "改一下 logo");
        // 收据按 goal 轮号可查：inbox/applied 到的时候能找回这句
        assertThat(SteerReceipts.find("goal:g:1", "receipt-1").messageId()).isEqualTo("m1");
    }

    @Test
    @DisplayName("目标轮在跑但不是本人 → 拒，框架一次都不调")
    void goalRoundOtherUserRejected() {
        ChatRunBus.Run live = run("goal:g:1", "someone");
        when(bus.running("s1")).thenReturn(live);
        runtime.bindSteer((uid, sid, text, imgs) -> "receipt-1");
        assertThat(runtime.steerTurn("s1", "admin", "插一句")).isNull();
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("目标轮已被叫停（用户轮把手同轮 stopped）→ 拒")
    void stoppedGoalRoundRejected() {
        TurnHandle h = new TurnHandle("goal:g:1", "admin", null);
        h.stopped().set(true);
        TurnRegistry.registerHandle("s1", h);
        ChatRunBus.Run live = run("goal:g:1", "admin");
        when(bus.running("s1")).thenReturn(live);
        runtime.bindSteer((uid, sid, text, imgs) -> "receipt-1");

        assertThat(runtime.steerTurn("s1", "admin", "插一句")).isNull();
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("没有活 run 也没有把手 → 拒（收尾后不再假装能插）")
    void noLiveTurnRejected() {
        runtime.bindSteer((uid, sid, text, imgs) -> "receipt-1");
        assertThat(runtime.steerTurn("s1", "admin", "插一句")).isNull();
        verifyNoInteractions(sessionService);
    }
}
