package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 跟随循环与本轮上下文之间的适配层（从 AgentxFollowRunner 的内部类抽出）。
 *
 * <p>循环只问「现在几点、机器耗时多少、该读哪段事件、有没有待办、要不要叫停」，
 * 具体落在哪个池/哪条通道/哪本台账由这里答。抽成独立类是为了让 followTurn 本身只剩编排。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxFollowHooks implements TurnFollowLoop.Hooks {

private final AgentSession session;
private final ChatCommand cmd;
private final RunChannel channel;
private final WenquSourcePool pool;
private final String turnId;
private final int historyBefore;
private final int contextWindow;
private final AgentxTurnRecording recording;
private final long startedAt;
private final TurnInteractionCoordinator interactions;
private final TurnFollowLoop loop;
private final AgentxTurnMirror mirror;
private final AgentxTurnUsage turnUsage;
private final AgentxPendingAnnouncer pendingAnnouncer;
private final AgentxTurnSupport turnSupport;
private final AgentxLogDrain logDrain;
private final FrameworkSessions sessions;
private final WenquAgentxTools tools;
private final GoalService goalService;
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public long machineMs() {
            return AgentxTextUtil.machineMs(startedAt, interactions.humanWaitMs(),
                    interactions.waitingOnHuman(), interactions.waitBeganAt());
        }

        @Override
        public void pause(long millis) {
            AgentxToolCardCodec.sleep(millis);
        }

        @Override
        public long drain(long cursor, long emitFrom) {
            return logDrain.drain(session, cursor, emitFrom, turnId, channel, recording, mirror);
        }

        @Override
        public boolean takePendingChanged() {
            return mirror.takePendingChanged();
        }

        @Override
        public boolean takeTaskChanged() {
            return mirror.takeTaskChanged();
        }

        @Override
        public boolean takeMessageChanged() {
            return mirror.takeMessageChanged();
        }

        @Override
        public void emitLiveUsage(long[] usageStamp, boolean messageChanged) {
            turnUsage.emitLiveUsage(session, cmd, pool, channel, historyBefore,
                    usageStamp, messageChanged, contextWindow);
        }

        @Override
        public int emitRetrieved(int announcedRefs) {
            return turnUsage.emitRetrieved(cmd, pool, channel, announcedRefs, recording);
        }

        @Override
        public boolean planSuperseded() {
            return "supersede".equals(sessions.peekPlanOutcome(sessions.key(cmd)));
        }

        @Override
        public void interrupt() {
            session.interrupt();
        }

        @Override
        public void announcePending(Map<String, SessionEvent> pending) {
            pendingAnnouncer.announce(pending, cmd, channel,
                    loop.announcedApprovals(), turnId, recording);
        }

        @Override
        public boolean stopRequested() {
            // 用户轮的停止把手 + 目标轮自己的叫停判据。目标轮没有 TurnHandle（后台起跑），
            // 只读用户那把手会让「取消目标」永远停不下来——线上那条取消后又跑了 4 分钟就是它。
            return tools.stopRequested(cmd) || goalService.goalTurnAborted(turnId);
        }

        @Override
        public void steerWrapUp() {
            sessions.steer(cmd.getUserId(), cmd.getSessionId(), AgentxTurnProgress.WRAP_UP_TEXT);
            log.info("[agentx] 连续无进展，已要求模型收尾: session={} turn={} streak={}",
                    cmd.getSessionId(), turnId, recording.progress().staleStreak());
        }

        @Override
        public boolean takeWrapUp() {
            return recording.progress().takeWrapUp();
        }

        @Override
        public boolean progressStalled() {
            return recording.progress().stalled();
        }

        @Override
        public void warn(String text) {
            channel.emit(RunEventType.WARN, text);
        }

        @Override
        public void warnStall() {
            channel.emit(RunEventType.WARN, com.wenqu.ai.agent.chat.TurnRecorder.STALL_NOTE);
            log.warn("[FAIL-LOUD] 连续无进展，已叫停框架那一跑: session={} turn={} streak={}",
                    cmd.getSessionId(), turnId, recording.progress().staleStreak());
        }

        @Override
        public boolean hasPending() {
            return mirror.hasPending();
        }

        @Override
        public Map<String, SessionEvent> pendingSnapshot() {
            return mirror.pendingSnapshot();
        }

        @Override
        public Map<String, SessionEvent> pending() {
            return session.pending();
        }

        @Override
        public boolean answerTimedOutAsk(Map<String, SessionEvent> pending) {
            return turnSupport.answerTimedOutAsk(pending, cmd, recording);
        }

        @Override
        public void beginHumanWait(Map<String, SessionEvent> pending) {
            AgentxEmitUtil.beginHumanWait(channel, recording, pending);
        }

        @Override
        public void endHumanWait() {
            AgentxEmitUtil.endHumanWait(channel, recording);
        }

        @Override
        public AgentSession.Task latestTask(AgentSession.Task known) {
            return TurnSessionGateway.latestTaskInTurn(session, known);
        }

        @Override
        public boolean activeRunsEmpty() {
            return session.inspect().activeRuns().isEmpty();
        }
}