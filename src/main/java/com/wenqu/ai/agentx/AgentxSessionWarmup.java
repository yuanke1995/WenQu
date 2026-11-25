package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import io.agentscope.harness.agent.session.AgentSession;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Predicate;

/**
 * 开轮前的会话暖场（从 AgentscopeChatEngine 抽出）：事件日志换代、归档上一轮的失败残留。
 *
 * <p>换代必须排在所有全量读之前：框架每轮开始要把整条事件链从头读好几遍，代价按事件数的平方算，
 * 而本轮第一个这样的读就是归档检查（它读 SessionTurns.read）。上一轮留着没结算的卡或没撒手的
 * 租约时框架自己会拒换代，那一轮就照旧不换、下一轮再试。
 *
 * <p>上一轮只要以失败收场过，这条会话后续每一轮都不会被框架启动：开轮前先把它归档掉。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxSessionWarmup {

    private final JournalRotation journalRotation;
    private final TurnSessionGateway gateway;

    public void warm(ChatCommand cmd, AgentSession session, Predicate<java.util.Collection<String>> abandoned) {
        journalRotation.rotateIfNeeded(cmd.getUserId(), cmd.getSessionId(), AgentscopeChatEngine.AGENT_ID);
        gateway.reconcileStalledTurn(session, cmd.getSessionId(), abandoned);
    }
}
