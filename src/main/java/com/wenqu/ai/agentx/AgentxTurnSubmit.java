package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 提交本轮（从 AgentscopeChatEngine 抽出）：工具面按轮下发、装配期停止复查、submit。
 *
 * <p>工具面按轮落到框架的组激活集上，并把「配置允许的名单」与「框架真正下发给模型的工具表」
 * 逐名比对一次——不一致就发 warn，静默少一道闸正是这套机制最初要治的病。
 *
 * <p>装配期（沙盒/实例要几十秒到几分钟）用户点了停止：不再发起模型调用，直接交给跟随循环按停止收口。
 * 不在这里跳过跟随，是因为 slot / 跟随位 / 目标接续的收尾都在跟随的 finally 里。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnSubmit {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;

    public AgentSession.Task submit(ChatCommand cmd, AgentSession session, RunChannel channel,
                                    ExecutionPolicy policy, String turnId, String modelInput, Msg multimodal) {
        for (String surfaceProblem : sessions.applyToolSurface(cmd, tools.turnGroupedNames(cmd, policy), policy)) {
            channel.emit(RunEventType.WARN, surfaceProblem);
        }
        if (tools.stopRequested(cmd)) {
            log.info("[agentx] 装配完成时本轮已被停止，不再发起模型调用: session={} turn={}",
                    cmd.getSessionId(), turnId);
            return null;
        }
        return multimodal != null ? session.submit(multimodal) : session.submit(modelInput);
    }
}
