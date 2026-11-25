package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.harness.agent.session.AgentSession;

/**
 * 一轮跟随所需的全部输入（把 followTurn 的十几个参数收成一个对象）。
 *
 * <p>收成对象的直接收益：参数列表从 16 项降到 1 项，跟随循环、续跑、目标轮三条路
 * 传的是同一份东西，加一个字段只改这里一处。
 *
 * @author yuanke
 */
public record AgentxTurnRun(
        AgentSession session,
        ChatCommand cmd,
        RunChannel channel,
        WenquSourcePool pool,
        ModelRegistryService.ModelRoute route,
        WenquAgentxTools.TurnHooks hooks,
        String turnId,
        int historyBefore,
        long startCursor,
        String userMessageId,
        AgentSession.Task current,
        AgentxTurnRecording recording,
        long startedAt,
        long prepMs,
        int contextWindow,
        boolean recovered) {
}
