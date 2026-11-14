package com.wenqu.ai.service;

import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionEventMapper;
import com.wenqu.ai.model.SessionEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionEventServiceFailLoudTest {

    @Test
    void insertFailureReturnsNullAndCounts() {
        SessionEventMapper mapper = mock(SessionEventMapper.class);
        ConfigService config = mock(ConfigService.class);
        when(mapper.insert(any(SessionEvent.class))).thenThrow(new RuntimeException("db down"));
        SessionEventService service = new SessionEventService(mapper, mock(MessageMapper.class), config);

        assertNull(service.append("s1", "u1", "t1", "tool_status", "{}"));
        assertEquals(1, service.appendFailures());
    }
}
