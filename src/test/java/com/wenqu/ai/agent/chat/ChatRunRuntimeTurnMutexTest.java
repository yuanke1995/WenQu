package com.wenqu.ai.agent.chat;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.service.ChatRunBus;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SessionEventService;
import com.wenqu.ai.service.SessionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRunRuntimeTurnMutexTest {

    private final TurnStateStore store = mock(TurnStateStore.class);
    private final ChatRunRuntime runtime = new ChatRunRuntime(
            mock(ConfigService.class),
            mock(ChatRunBus.class),
            mock(SessionEventService.class),
            mock(SessionService.class),
            store,
            mock(com.wenqu.ai.service.UserImageService.class));

    @Test
    void secondTurnIsRejectedUntilReleased() {
        when(store.tryAcquire("s1")).thenReturn(true, false, true);

        assertTrue(runtime.tryBeginTurn("s1"));
        assertFalse(runtime.tryBeginTurn("s1"));
        runtime.endTurnMutex("s1");
        assertTrue(runtime.tryBeginTurn("s1"));

        verify(store).release("s1");
    }

    @Test
    void blankSessionNeverReachesStore() {
        assertFalse(runtime.tryBeginTurn(null));
        assertFalse(runtime.tryBeginTurn(" "));

        verify(store, never()).tryAcquire("null");
    }

    @Test
    void storeFailureIsFailLoud() {
        when(store.tryAcquire("s1")).thenThrow(new IllegalStateException("redis down"));

        BizException e = assertThrows(BizException.class, () -> runtime.tryBeginTurn("s1"));
        assertEquals(503, e.getCode());
    }

    @Test
    void releaseFailureDoesNotMaskTurnEnd() {
        doThrow(new IllegalStateException("redis down")).when(store).release("s1");

        assertDoesNotThrow(() -> runtime.endTurnMutex("s1"));
    }
}
