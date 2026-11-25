package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.harness.agent.session.AgentSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次收尾的共享状态（从 AgentxTurnFinisher 的内部类抽出）。
 *
 * <p>定稿正文、引用自检结果、用量、落库行与 done 帧都从这一份快照出——落库与下发逐字段同一份，
 * 不存在「落库后池子又被读一次」的窗口。
 *
 * @author yuanke
 */
public final class AgentxTurnDraft {
    final ChatCommand cmd;
    final RunChannel channel;
    final WenquSourcePool pool;
    final ModelRegistryService.ModelRoute route;
    final String modelRef;
    final int contextWindow;
    final AgentxTurnRecording recording;
    final String userMessageId;
    final boolean stopped, timedOut, budget, stalled, awaiting;
    final long startedAtMs, humanWaitMs, prepMs;
    final AgentSession session;
    final int historyBefore;
    final String turnId;
    final Map<String, Object> planRecord;
    final String recoveredNote;
    String body;
    String noticeText;
    boolean persist;
    boolean realAnswer;
    List<String> relatedChips;
    List<String> finalImages;
    Map<String, Object> tokens;
    long wallMs;
    long elapsedMs;
    String framedTurnId;
    Map<String, Object> turnChanges;
    String lastRetrieved;
    List<Map<String, Object>> artifactItems;
    String artifactsJson;
    String blocksJson;
    Map<String, Object> viewBlocks;
    String replaceGroup;
    List<Map<String, Object>> snapSources;
    List<Map<String, String>> snapDegrades;
    List<String> snapSignedImages;
    String snapWarn;
    boolean snapRetrievalAttempted;
    List<String> snapRetrievalKbIds;
    String snapAgentName;
    boolean bodyRewritten;

    public AgentxTurnDraft(ChatCommand cmd, RunChannel channel, WenquSourcePool pool, ModelRegistryService.ModelRoute route,
          String modelRef, int contextWindow, String answer, AgentxTurnRecording recording, String userMessageId,
          boolean stopped, boolean timedOut, boolean budget, boolean stalled, boolean awaiting,
          long startedAtMs, long humanWaitMs, long prepMs, AgentSession session, int historyBefore,
          String turnId, Map<String, Object> planRecord, String recoveredNote) {
        this.cmd = cmd; this.channel = channel; this.pool = pool; this.route = route; this.modelRef = modelRef;
        this.contextWindow = contextWindow; this.recording = recording; this.userMessageId = userMessageId;
        this.stopped = stopped; this.timedOut = timedOut; this.budget = budget; this.stalled = stalled;
        this.awaiting = awaiting; this.startedAtMs = startedAtMs; this.humanWaitMs = humanWaitMs;
        this.prepMs = prepMs; this.session = session; this.historyBefore = historyBefore; this.turnId = turnId;
        this.planRecord = planRecord; this.recoveredNote = recoveredNote; this.body = answer == null ? "" : answer;
    }
}
