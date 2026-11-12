package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.SessionService;
import lombok.Builder;

import java.util.Collection;
import java.util.List;

/**
 * 一轮收尾的持久化 I/O：助手消息落库 + 问答台账。
 *
 * <p>抽出来之前这两件事散在引擎 {@code finish} 里：先拼一个 20 多字段的 MessageWrite，
 * 再按 {@code persist && realAnswer} 决定要不要写台账。判据与字段顺序都靠调用点自觉，
 * 改动一处就出现「消息落了、台账没落」或「台账指着空 messageId」。这里把两件事绑成一次调用，
 * 并用 {@link Input} 一次性交齐输入，引擎只负责从定稿快照里取值。
 *
 * <p>纯副作用类，不做引用自检/通知/webhook——那些仍有各自的服务与时机。
 */
public class TurnPersister {

    private final SessionService sessions;
    private final QaLogService qaLog;

    public TurnPersister(SessionService sessions, QaLogService qaLog) {
        this.sessions = sessions;
        this.qaLog = qaLog;
    }

    /**
     * @return 落库后的助手消息 ID；这一轮不落库时为 null
     */
    public String persist(Input in) {
        String messageId = in.persist ? sessions.appendMessage(MessageWrite.builder()
                .sessionId(in.sessionId)
                .role("assistant")
                .content(in.body)
                .images(in.finalImages)
                .sources(in.sourcesJson)
                .thinking(in.thinking)
                .retrieved(in.retrieved)
                .artifacts(in.artifactsJson)
                .toolCalls(in.toolCallsJson)
                .tokens(in.tokensJson)
                .timeline(in.timelineJson)
                .processText(in.processText)
                .agentId(in.agentId)
                .agentName(in.agentName)
                .model(in.model)
                .related(in.relatedJson)
                .plan(in.planJson)
                .turnId(in.turnId)
                .blocks(in.blocksJson)
                .warn(in.warn)
                .degradationsJson(in.degradationsJson)
                .changesJson(in.changesJson)
                .variantGroup(in.variantGroup)
                .build()) : null;
        // 台账与消息同一处时机：半程轮不落台账（那是中断轮的收尾语义）
        if (in.persist && in.realAnswer) {
            qaLog.logAsync(in.sessionId, in.question, in.body,
                    in.hitDocIds == null ? List.of() : in.hitDocIds,
                    in.hasCitation, in.retrievalAttempted, in.elapsedMs, in.deepThink,
                    messageId, in.agentId, in.kbIds == null ? List.of() : in.kbIds);
        }
        return messageId;
    }

    /** 一次收尾的定稿快照：落库列 + 台账列 + 两个落库判据。 */
    @Builder
    public static class Input {
        final String sessionId;
        final String body;
        final List<String> finalImages;
        final String sourcesJson;
        final String thinking;
        final String retrieved;
        final String artifactsJson;
        final String toolCallsJson;
        final String tokensJson;
        final String timelineJson;
        final String processText;
        final String agentId;
        final String agentName;
        final String model;
        final String relatedJson;
        final String planJson;
        final String turnId;
        final String blocksJson;
        final String warn;
        final String degradationsJson;
        final String changesJson;
        final String variantGroup;
        final boolean persist;
        final boolean realAnswer;
        final String question;
        final List<String> hitDocIds;
        final boolean hasCitation;
        final boolean retrievalAttempted;
        final long elapsedMs;
        final boolean deepThink;
        final Collection<String> kbIds;
    }
}
