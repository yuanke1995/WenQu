package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.SessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnPersisterTest {

    private final SessionService sessions = mock(SessionService.class);
    private final QaLogService qaLog = mock(QaLogService.class);
    private final TurnPersister persister = new TurnPersister(sessions, qaLog);

    private TurnPersister.Input.InputBuilder base() {
        return TurnPersister.Input.builder()
                .sessionId("s1").body("正文").finalImages(List.of("img")).sourcesJson("[]")
                .thinking("想").retrieved("r").artifactsJson("[]").toolCallsJson("[]")
                .tokensJson("{}").timelineJson("[]").processText("p").agentId("a1")
                .agentName("助手").model("m1").relatedJson("[]").planJson("{}")
                .turnId("t1").blocksJson("{}").warn("w").degradationsJson("[]")
                .changesJson("{}").variantGroup("g1")
                .question("问题").hitDocIds(List.of("d1")).hasCitation(true)
                .retrievalAttempted(true).elapsedMs(1234L).deepThink(true).kbIds(List.of("kb1"));
    }

    @Test
    void persistsAssistantMessageWithAllFinalSnapshotFields() {
        when(sessions.appendMessage(any())).thenReturn("mid-1");
        String id = persister.persist(base().persist(true).realAnswer(true).build());
        assertThat(id).isEqualTo("mid-1");

        ArgumentCaptor<MessageWrite> cap = ArgumentCaptor.forClass(MessageWrite.class);
        verify(sessions).appendMessage(cap.capture());
        MessageWrite w = cap.getValue();
        assertThat(w.getContent()).isEqualTo("正文");
        assertThat(w.getRole()).isEqualTo("assistant");
        assertThat(w.getTurnId()).isEqualTo("t1");
        assertThat(w.getVariantGroup()).isEqualTo("g1");
        assertThat(w.getImages()).containsExactly("img");
        verify(qaLog).logAsync(eq("s1"), eq("问题"), eq("正文"), eq(List.of("d1")),
                eq(true), eq(true), eq(1234L), eq(true), eq("mid-1"), eq("a1"), eq(List.of("kb1")));
    }

    @Test
    void skipsQaLogWhenNoRealAnswerButStillPersistsProcessOnlyTurn() {
        when(sessions.appendMessage(any())).thenReturn("mid-2");
        persister.persist(base().persist(true).realAnswer(false).build());
        verify(sessions).appendMessage(any());
        verify(qaLog, never()).logAsync(any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                anyLong(), anyBoolean(), any(), any(), any());
    }

    @Test
    void skipsBothWhenNotPersisting() {
        assertThat(persister.persist(base().persist(false).realAnswer(true).build())).isNull();
        verify(sessions, never()).appendMessage(any());
        verify(qaLog, never()).logAsync(any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                anyLong(), anyBoolean(), any(), any(), any());
    }
}
