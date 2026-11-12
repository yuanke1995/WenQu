package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.mapper.SteerItemMapper;
import com.wenqu.ai.model.SteerItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 插话待发队列：drain 只在「有活轮」时消费，失败退回排队，收据回写。
 * 用 mock 的 mapper/runtime 把状态机钉住——真实 SQL 的 CAS 语义由 WHERE status 保证。
 */
class SteerQueueServiceTest {

    private SteerItemMapper mapper;
    private SessionService sessions;
    private ChatRunRuntime runtime;
    private SteerQueueService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SteerItemMapper.class);
        sessions = mock(SessionService.class);
        runtime = mock(ChatRunRuntime.class);
        service = new SteerQueueService(mapper, sessions, runtime);
    }

    private static SteerItem item(String id, String status) {
        SteerItem it = new SteerItem();
        it.setId(id);
        it.setSessionId("s1");
        it.setUserId("u1");
        it.setText("改方向");
        it.setStatus(status);
        it.setAttempts(0);
        return it;
    }

    @Test
    @DisplayName("没有活轮：不消费任何排队项（留到下一轮）")
    void drainWithoutLiveRunDoesNothing() {
        when(runtime.activeTurnOwner("s1")).thenReturn(null);
        service.drain("s1");
        verify(runtime, never()).steerTurnDetailed(anyString(), anyString(), anyString(), any());
        verify(mapper, never()).claim(anyString(), any());
    }

    @Test
    @DisplayName("有活轮：抢行→送达→记收据；送不进去的退回排队")
    void drainClaimsAndDelivers() {
        when(runtime.activeTurnOwner("s1")).thenReturn("u1");
        SteerItem ok = item("i1", "PENDING");
        SteerItem rejected = item("i2", "PENDING");
        when(mapper.pendingOf("s1")).thenReturn(List.of(ok, rejected));
        when(mapper.claim(eq("i1"), isNull())).thenReturn(1);
        when(mapper.claim(eq("i2"), isNull())).thenReturn(1);
        // 第一条送达成功（有收据），第二条框架不收（返回 null → 退回排队）
        when(runtime.steerTurnDetailed(eq("s1"), eq("u1"), eq("改方向"), any()))
                .thenReturn(new ChatRunRuntime.SteerOutcome("m1", "r1", "goal:g:1"), null);

        service.drain("s1");

        verify(mapper).setReceipt("i1", "r1", "goal:g:1");
        verify(mapper).release("i2", "这一轮不收插话");
    }

    @Test
    @DisplayName("抢占失败（别的副本先抢到）就跳过，不重复送达")
    void drainSkipsUnclaimedRows() {
        when(runtime.activeTurnOwner("s1")).thenReturn("u1");
        when(mapper.pendingOf("s1")).thenReturn(List.of(item("i1", "PENDING")));
        when(mapper.claim(eq("i1"), isNull())).thenReturn(0);

        service.drain("s1");

        verify(runtime, never()).steerTurnDetailed(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("框架确认已应用：按收据置 APPLIED")
    void appliedByReceipt() {
        service.applied("s1", "r1");
        verify(mapper).applied("r1");
    }

    @Test
    @DisplayName("目标终态：该会话排队项收敛为未送达")
    void dropsPendingOnTerminal() {
        service.dropAllPending("s1");
        verify(mapper).dropAllPending("s1");
    }

    @Test
    @DisplayName("入队：过会话归属，超长文案按 steer 同一尺子截断")
    void enqueueCapsText() {
        String longText = "x".repeat(900);
        service.enqueue("s1", "u1", longText, null);
        var captor = org.mockito.ArgumentCaptor.forClass(SteerItem.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getText()).hasSize(801).endsWith("…");
        verify(sessions).assertOwned("s1", "u1");
    }
}
