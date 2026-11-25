package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import lombok.RequiredArgsConstructor;

/**
 * 每轮开头把三道按轮闸归零（从 AgentscopeChatEngine 抽出）。
 *
 * <p>实例按会话常驻，闸也随实例常驻——不归零就是上一轮的状态判这一轮：
 * 步数预算会吃掉本轮额度、重复调用闸会把上一轮最后那条命令判成本轮第一步重复、
 * 工具档位闸会把被拒的工具挂到上一轮头上。三处同一条口径：降级登记 + WARN 帧。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxTurnGateReset {

    private final FrameworkSessions sessions;

    public void reset(ChatCommand cmd, String turnId, WenquAgentxTools tools,
                      WenquSourcePool pool, RunChannel channel) {
        String key = sessions.key(cmd);
        AgentxStepBudget stepBudget = sessions.stepBudget(key);
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
                pool.registrar().degrade(code, message);
                channel.emit(RunEventType.WARN, message);
            }, text -> sessions.steer(cmd.getUserId(), cmd.getSessionId(), text));
        }
        AgentxRepeatGuard repeatGuard = sessions.repeatGuard(key);
        if (repeatGuard != null) {
            repeatGuard.beginTurn((code, message) -> {
                pool.registrar().degrade(code, message);
                channel.emit(RunEventType.WARN, message);
            });
        }
        AgentxFrameworkToolGate toolGate = sessions.frameworkToolGate(key);
        if (toolGate != null) {
            toolGate.beginTurn(turnId);
        }
    }
}
