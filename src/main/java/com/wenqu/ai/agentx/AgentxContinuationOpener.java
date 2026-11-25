package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 为一轮续跑装配上下文（从 AgentscopeChatEngine 抽出）：按归属重建或复用常驻会话实例、
 * 建引用池与本轮身份。通道挂在收集型 emitter 上——这一跑未必有真实观众，但事件仍走同一条
 * sendSseEvent（账本、运行总线、落库都不变），断线重连那一份 HTTP 响应才是观众。
 *
 * <p>本类只做编排：「开跑前的前提」归 {@link AgentxContinuationPrep}，
 * 「实例与会话状态」归 {@link AgentxContinuationSession}，
 * 「录制器与游标」归 {@link AgentxTurnRecordingFactory}。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxContinuationOpener {

    private final AgentxContinuationPrep prep;
    private final AgentxContinuationSession continuationSession;
    private final AgentxTurnRecordingFactory recordingFactory;
    private final WenquAgentxTools tools;
    private final AgentxContinuationSession.BuildFn buildAgent;

    public AgentxFollowing open(String sessionId, String userId, String turnId, boolean recover,
                                String guestUid, boolean planFirst, String agentOverride) {
        AgentxContinuationPrep.Prepared p = prep.prepare(sessionId, userId, turnId, guestUid, planFirst, agentOverride);
        if (p == null) return null;
        AgentSession session = continuationSession.open(p.cmd(), p.ref(), p.route(), p.policy(), p.hooks(),
                p.binding(), p.slot(), p.sandboxWanted(), p.ew(), buildAgent);
        if (session == null) return null;
        continuationSession.warnSurface(p.cmd(), p.policy(), p.channel());
        continuationSession.engagePlan(p.cmd(), planFirst, p.channel());
        continuationSession.warm(p.cmd(), session, turnId, recover, tools);
        AgentxTurnRecordingFactory.Created created = recordingFactory.create(p.cmd(), session, p.hooks(),
                new ConcurrentLinkedQueue<>());
        return new AgentxFollowing(p.cmd(), p.channel(), p.pool(), p.route(), p.hooks(), p.policy(), session,
                created.historyBefore(), created.startCursor(), created.recording(),
                p.binding().contextWindow(), recover);
    }
}
