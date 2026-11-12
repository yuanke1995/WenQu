package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.ReplicaCoordination;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisTurnStateStoreTest {

    private final ReplicaCoordination coordination = mock(ReplicaCoordination.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void tryAcquireDelegatesToLease() {
        when(coordination.acquireOrThrow(eq("wenqu:turn:in-flight:s1"), any(Duration.class))).thenReturn(true);
        RedisTurnStateStore store = new RedisTurnStateStore(coordination, redis, "single");

        assertTrue(store.tryAcquire("s1"));
    }

    @Test
    void acquireFailureIsReturnedAsHeld() {
        when(coordination.acquireOrThrow(eq("wenqu:turn:in-flight:s1"), any(Duration.class))).thenReturn(false);
        RedisTurnStateStore store = new RedisTurnStateStore(coordination, redis, "single");

        assertFalse(store.tryAcquire("s1"));
    }

    @Test
    void singleModeClearsStaleLocksOnStartup() {
        when(redis.keys("wenqu:turn:in-flight:*")).thenReturn(Set.of("k1", "k2"));
        RedisTurnStateStore store = new RedisTurnStateStore(coordination, redis, "single");

        store.clearStaleOnStartup();

        verify(redis).delete(Set.of("k1", "k2"));
    }

    @Test
    void multiModeDoesNotTouchLocks() {
        RedisTurnStateStore store = new RedisTurnStateStore(coordination, redis, "multi");

        store.clearStaleOnStartup();

        verify(redis, never()).keys("wenqu:turn:in-flight:*");
        verifyNoInteractions(coordination);
    }
}
