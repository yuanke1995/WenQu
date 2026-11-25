package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.SteerReceipts;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agentx.turn.BodyReconcile;
import com.wenqu.ai.agentx.turn.TurnDonePayload;
import com.wenqu.ai.agentx.turn.TurnTranscriptRecorder;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.ProjectTurnChangesService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.webhook.AgentWebhookService;
import com.wenqu.ai.util.TurnBlocks;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.TurnCloseout;
import com.wenqu.ai.agent.chat.TurnPersister;
import com.wenqu.ai.agent.chat.TurnRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一轮的落库与下发（从 AgentxTurnFinisher 抽出）：定稿快照、消息行落库、done 帧、webhook、收台。
 *
 * <p>全程只读定稿快照一次：落库行与 done 帧逐字段同一份，不存在二次读池的窗口。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnPersister {

    private final ProjectTurnChangesService turnChangeService;
    private final AgentxTurnUsage turnUsage;
    private final TurnPersister persister;
    private final ArtifactService artifactService;
    private final GoalService goalService;
    private final AgentxAgentBinding agentBinding;
    private final AgentWebhookService agentWebhookService;
    private final FrameworkSessions sessions;
    private final SessionService sessionService;
    private final WenquAgentxTools tools;
    private final Supplier<com.wenqu.ai.agentx.team.TeamMemberLiveFeed> memberLiveFeed;

    public void persistAndEmit(AgentxTurnDraft c) {
        preparePersistence(c);
        emitDone(c, doPersist(c));
    }

    private void preparePersistence(AgentxTurnDraft c) {
        if (c.recording.compactedThisTurn) {
            c.tokens.put("compacted", 1);
            persistCompactionSummary(c.cmd);
        }
        c.lastRetrieved = turnUsage.retrievedJson(c.cmd, c.pool, c.recording);
        if (c.lastRetrieved != null) c.channel.emit(RunEventType.RETRIEVED, c.lastRetrieved);
        c.artifactItems = artifactService.settleArtifacts(c.cmd.getSessionId());
        if (goalService.goalTurnAborted(c.turnId) && !c.artifactItems.isEmpty()) {
            log.info("[GOAL] 目标轮已被叫停，丢弃本轮产物交付: session={} turn={} count={}",
                    c.cmd.getSessionId(), c.turnId, c.artifactItems.size());
            c.artifactItems = List.of();
        }
        String artifactList = JSON.toJSONString(c.artifactItems);
        c.artifactsJson = "[]".equals(artifactList) ? null : artifactList;
        c.recording.artifacts(c.artifactItems);
        if (!c.noticeText.isEmpty()) {
            c.recording.notice(TurnTranscriptRecorder.toneOf(c.stopped, c.timedOut, c.budget, c.stalled), c.noticeText);
        }
        c.blocksJson = c.recording.blocksJson(c.framedTurnId, c.body);
        c.viewBlocks = c.blocksJson == null ? null
                : (c.cmd.isGuestMode() ? TurnBlocks.answerOnly(c.blocksJson) : JSON.parseObject(c.blocksJson));
        c.replaceGroup = null;
        if (c.persist && c.cmd.getReplaceMessageId() != null && !c.cmd.getReplaceMessageId().isBlank()) {
            c.replaceGroup = sessionService.beginAnswerReplace(c.cmd.getReplaceMessageId());
        }
        snapshotFinal(c);
    }

    private void snapshotFinal(AgentxTurnDraft c) {
        c.bodyRewritten = com.wenqu.ai.agentx.turn.BodyReconcile.classify(
                c.recording.answerText(), c.body) != com.wenqu.ai.agentx.turn.BodyReconcile.Verdict.UNCHANGED;
        c.snapSources = c.pool.sources();
        c.snapDegrades = new ArrayList<>(c.pool.degradations());
        c.snapSignedImages = c.pool.sign(c.finalImages);
        c.snapWarn = c.channel.lastWarn();
        c.snapRetrievalAttempted = c.pool.retrievalAttempted();
        c.snapRetrievalKbIds = c.pool.retrievalKbIds();
        c.snapAgentName = agentBinding.agentNameOf(c.cmd);
    }

    private String doPersist(AgentxTurnDraft c) {
        String snapSourcesJson = JSON.toJSONString(c.snapSources);
        String snapDegradesJson = c.snapDegrades.isEmpty() ? null : JSON.toJSONString(c.snapDegrades);
        String snapChangesJson = c.turnChanges == null ? null : JSON.toJSONString(c.turnChanges);
        return persister.persist(TurnPersister.Input.builder()
                .sessionId(c.cmd.getSessionId())
                .body(c.body)
                .finalImages(c.finalImages)
                .sourcesJson(snapSourcesJson)
                .thinking(c.recording.thinkingText())
                .retrieved(c.lastRetrieved)
                .artifactsJson(c.artifactsJson)
                .toolCallsJson(c.recording.toolCallsJson())
                .tokensJson(c.tokens.isEmpty() ? null : JSON.toJSONString(c.tokens))
                .timelineJson(c.recording.timelineJson(c.body, c.recording.answerText()))
                .processText(c.recording.related.process().isEmpty() ? null : c.recording.related.process())
                .agentId(c.cmd.getAgentId())
                .agentName(c.snapAgentName)
                .model(c.route.modelId())
                .relatedJson(c.relatedChips.isEmpty() || !agentBinding.relatedSuggestionsEnabled(c.cmd.getUserId()) ? null
                        : JSON.toJSONString(c.relatedChips))
                .planJson(c.planRecord == null ? null : JSON.toJSONString(c.planRecord))
                .turnId(c.framedTurnId)
                .blocksJson(c.blocksJson)
                .warn(c.snapWarn)
                .degradationsJson(snapDegradesJson)
                .changesJson(snapChangesJson)
                .variantGroup(c.replaceGroup)
                .persist(c.persist)
                .realAnswer(c.realAnswer)
                .question(c.cmd.getQuestion())
                .hitDocIds(c.snapSources.stream().map(x -> String.valueOf(x.get("docId"))).toList())
                .hasCitation(!c.snapSources.isEmpty())
                .retrievalAttempted(c.snapRetrievalAttempted || !c.snapSources.isEmpty())
                .elapsedMs(c.elapsedMs)
                .deepThink(c.cmd.isDeepThink())
                .kbIds(c.snapRetrievalKbIds)
                .build());
    }

    private void emitDone(AgentxTurnDraft c, String assistantMessageId) {
        c.channel.emit(RunEventType.DONE, buildDonePayload(c, assistantMessageId));
        SteerReceipts.clear(c.turnId);
        if (!c.awaiting) {
            String webhookStatus = TurnCloseout.webhookStatus(c.stopped, c.stalled, c.timedOut, c.budget);
            agentWebhookService.onTurnFinished(c.cmd, c.framedTurnId, webhookStatus, null,
                    c.body, assistantMessageId, c.tokens, c.artifactItems, c.elapsedMs);
        }
        if (c.turnId != null && c.turnId.startsWith("goal:")) {
            tools.closeLocalBrowser(c.cmd.getUserId());
        }
        c.channel.complete();
        closeMemberLive(c);
    }

    private String buildDonePayload(AgentxTurnDraft c, String assistantMessageId) {
        List<Map<String, Object>> pendingSteers = c.awaiting ? null : AgentxPlanSteerUtil.unsentSteers(c.turnId, c.recording);
        if (pendingSteers != null && pendingSteers.isEmpty()) pendingSteers = null;
        return TurnDonePayload.toJson(TurnDonePayload.Input.builder()
                .finalContent(c.body)
                .messageId(assistantMessageId)
                .userMessageId(c.userMessageId)
                .turnId(c.framedTurnId)
                .model(c.route.modelId())
                .agentId(c.cmd.getAgentId())
                .agentName(c.snapAgentName)
                .sources(c.snapSources)
                .finalImages(c.snapSignedImages)
                .degradations(c.snapDegrades)
                .bodyRewritten(c.bodyRewritten)
                .elapsedMs(c.elapsedMs)
                .humanWaitMs(c.humanWaitMs)
                .turnWallMs(c.wallMs + c.prepMs)
                .prepMs(c.prepMs)
                .tokens(c.tokens)
                .toolCalls(c.recording.toolCallList())
                .blocks(c.viewBlocks)
                .changes(c.turnChanges)
                .artifacts(c.artifactItems)
                .related(c.relatedChips)
                .thinking(c.recording.thinking.length() == 0 ? null : c.recording.thinking.toString())
                .planRecord(c.planRecord)
                .pendingSteers(pendingSteers)
                .build());
    }

    private void closeMemberLive(AgentxTurnDraft c) {
        com.wenqu.ai.agentx.team.TeamMemberLiveFeed feed = memberLiveFeed.get();
        if (feed == null || c.cmd.getSessionId() == null || c.cmd.getSessionId().isBlank()) return;
        try {
            feed.finish(c.cmd.getSessionId(), "这一轮已结束");
        } catch (RuntimeException e) {
            log.debug("[TEAM] 直播收台失败（不影响本轮）session={}: {}", c.cmd.getSessionId(), e.toString());
        }
    }

    private void persistCompactionSummary(ChatCommand cmd) {
        FrameworkSessions.CompactionSummary live = sessions.liveCompactionSummary(cmd.getUserId(), cmd.getSessionId());
        if (live == null || live.text().isBlank()) {
            log.warn("[FAIL-LOUD] 本轮压过上下文但摘要没落库（框架活状态里认不出摘要消息）: session={}",
                    cmd.getSessionId());
            return;
        }
        sessionService.updateHistorySummary(cmd.getSessionId(), live.text(), live.coveredMessages());
    }
}
