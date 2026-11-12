package com.wenqu.ai.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionConsistencyReporterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionConsistencyReporter reporter = new SessionConsistencyReporter(jdbc);

    @Test
    void mismatchListIsReportedWithoutThrowing() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("sessionId", "s1", "tenantId", "t1", "messageCount", 10, "actualSeq", 8)));

        assertDoesNotThrow(reporter::report);
        verify(jdbc).queryForList(anyString());
    }

    @Test
    void dbFailureDoesNotBreakScheduling() {
        when(jdbc.queryForList(anyString())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(reporter::report);
    }
}
