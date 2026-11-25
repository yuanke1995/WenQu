package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.firstNonNull;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.toolNameOf;

/**
 * 收尾时把仍挂着的待办卡收口（从 AgentscopeChatEngine 抽出）。
 *
 * <p>停止/超时时，本轮前面挂着的提问卡、工具卡都还「在等」：这里按收尾那份权威快照
 * 逐个补终态（结构性收口排在发帧之前——通道可能已被收掉，结算没做才是大事）。
 * 提问卡按「未作答」收、其它工具按失败收（给 ✓ 就是假装它跑完了）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxPendingAbandoner {

    private final FrameworkSessions sessions;
    private final AgentxToolCardBuilder toolCardBuilder;

    public void abandon(AgentSession session, ChatCommand cmd, RunChannel channel,
                        AgentxTurnRecording recording, boolean stopped,
                        Map<String, SessionEvent> pendingAtClose) {
        // 用收尾那份权威快照，不再自己读一遍（pending() 全量回放在万级事件日志上要十几秒）
        Map<String, SessionEvent> pending = new LinkedHashMap<>(pendingAtClose);
        if (pending.isEmpty()) return;
        String why = stopped ? "本轮已停止" : "等待超时";
        // 结构性收口排在发帧之前：通道可能已经被停止/断开收掉了，那一帧发不出去是小事，
        // 结算没做才是大事（这条会话之后就再也起不动）。
        cancelFrameworkTurns(session, cmd, pending);
        sessions.markPendingTerminal(sessions.key(cmd), stopped ? "STOPPED" : "TIMEOUT");
        for (Map.Entry<String, SessionEvent> entry : pending.entrySet()) {
            abandonOne(entry, cmd, channel, recording, why);
        }
        log.info("[agentx] 本轮不再跟随，{} 条待办已收口: session={} 原因={}",
                pending.size(), cmd.getSessionId(), why);
    }

    /** 把框架那几条待办撤销（不落 turn/cancelled，这条会话之后可能起不动）。 */
    private void cancelFrameworkTurns(AgentSession session, ChatCommand cmd, Map<String, SessionEvent> pending) {
        Set<String> frameworkTurns = new LinkedHashSet<>();
        for (SessionEvent e : pending.values()) {
            if (e.turnId() != null && !e.turnId().isBlank()) frameworkTurns.add(e.turnId());
        }
        for (String fwTurn : frameworkTurns) {
            try {
                SessionRecovery.cancelPending(session.log(), fwTurn);
            } catch (RuntimeException refused) {
                log.warn("[FAIL-LOUD] 框架待办取消失败（不落 turn/cancelled 这条会话之后可能起不动）"
                        + " session={} turn={}: {}", cmd.getSessionId(), fwTurn, refused.toString());
            }
        }
    }

    /** 给一条还挂着的待办补终态：提问卡按「未作答」收、其它工具按失败收（给 ✓ 就是假装它跑完了）。 */
    private void abandonOne(Map.Entry<String, SessionEvent> entry, ChatCommand cmd, RunChannel channel,
                            AgentxTurnRecording recording, String why) {
        Map<String, Object> data = entry.getValue().data() == null ? Map.of() : entry.getValue().data();
        Object call = firstNonNull(data.get("call"), data);
        String name = toolNameOf(call);
        if (name.isBlank()) name = recording.lastToolName;
        if (name.isBlank()) return;
        // 已经出过终态的那一把不补（例如 plan_exit：确认是另一件事，工具结果早到了）
        if (!recording.hasOpenTool(name)) return;
        String id = call instanceof Map<?, ?> cm && cm.get("id") != null
                ? String.valueOf(cm.get("id")) : entry.getKey();
        String callKey = AgentxTurnRecording.toolCallKey(id, name);
        long beganAt = recording.takeToolStart(callKey);
        long covered = recording.takeToolWait(callKey);
        long charged = recording.takeToolCharge(callKey);
        String args = recording.toolInputByKey.remove(callKey);
        if (args == null) args = recording.lastToolInput;
        long elapsed = beganAt <= 0L ? 0L
                : Math.max(0L, System.currentTimeMillis() - beganAt - covered);
        boolean ask = AskUserTool.NAME.equals(name);
        String status = TurnInteractionCoordinator.abandonStatus(ask);
        String result = TurnInteractionCoordinator.abandonResult(ask, why);
        // 落库那份先写、无条件：刷新读的就是它（那句解释也要一起落，刷新后卡片才说得出为什么没跑）
        recording.toolCall(name, status, args, elapsed, beganAt, charged, result);
        try {
            AgentxEmitUtil.emit(channel, RunEventType.TOOL_STATUS,
                    toolCardBuilder.toolCard(recording.uid, name, status, elapsed, beganAt, charged, args, result, null,
                            recording.hooks.step().index()));
        } catch (RuntimeException gone) {
            log.info("[agentx] 待办终态帧没发出去（通道已收），落库那份仍在: session={} tool={}",
                    cmd.getSessionId(), name);
        }
    }
}
