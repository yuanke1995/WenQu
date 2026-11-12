package com.wenqu.ai.agentx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransientSessionStateTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final TransientSessionState state = new TransientSessionState(redis);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void planOutcomeIsPersistedAndTakenOnce() {
        state.putPlanOutcome("s1", null);
        verify(values).set("wenqu:plan:outcome:s1", "reject", Duration.ofHours(2));

        when(values.getAndDelete("wenqu:plan:outcome:s1")).thenReturn("approve");
        assertThat(state.takePlanOutcome("s1")).isEqualTo("approve");
        verify(redis).delete(List.of("wenqu:plan:revising:s1", "wenqu:plan:feedback:s1"));
    }

    @Test
    void planRevisingAndFeedbackAreSeparated() {
        state.planRevising("s1", true, "改一下");
        verify(values).set("wenqu:plan:revising:s1", "1", Duration.ofHours(2));
        verify(values).set("wenqu:plan:feedback:s1", "改一下", Duration.ofHours(2));

        when(values.get("wenqu:plan:revising:s1")).thenReturn("1");
        when(values.get("wenqu:plan:feedback:s1")).thenReturn("改一下");
        assertThat(state.isPlanRevising("s1")).isTrue();
        assertThat(state.peekPlanFeedback("s1")).isEqualTo("改一下");

        state.planRevising("s1", false, null);
        verify(redis).delete("wenqu:plan:revising:s1");
    }

    @Test
    void compactRequestAndRebuildMarkerArePersisted() {
        state.putCompactRequest("u1|s1", "");
        verify(values).set("wenqu:compact:request:u1|s1", "", Duration.ofHours(2));
        when(values.getAndDelete("wenqu:compact:request:u1|s1")).thenReturn("");
        assertThat(state.takeCompactRequest("u1|s1")).isEmpty();

        state.markRebuildAfterCompact("u1|s1");
        verify(values).set("wenqu:compact:rebuild:u1|s1", "1", Duration.ofHours(2));
        when(values.getAndDelete("wenqu:compact:rebuild:u1|s1")).thenReturn("1");
        assertThat(state.consumeRebuildAfterCompact("u1|s1")).isTrue();
    }

    @Test
    void clearRemovesWholeCluster() {
        state.clear("s1");
        verify(redis).delete(List.of(
                "wenqu:plan:outcome:s1", "wenqu:plan:revising:s1", "wenqu:plan:feedback:s1",
                "wenqu:compact:request:s1", "wenqu:compact:rebuild:s1"));
    }
}
