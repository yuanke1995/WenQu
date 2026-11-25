package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.service.EffectiveWorkspace;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * 续跑轮的实例与会话状态（从 AgentxContinuationOpener 抽出）：开/复用常驻实例、收拾上一轮遗留、
 * 把三道按轮闸归零。
 *
 * <p>实例键用完整引用 + 档位 + 工作区 key：换供应商、切档、切工作区都必须重建实例。
 * 建实例失败按「接不动」处理（返回 null），调用方据此如实报失效而不是静默什么都没发生。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxContinuationSession {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final GoalService goalService;
    private final TurnSessionGateway sessionGateway;

    /** 建实例（引擎实现）。 */
    @FunctionalInterface
    public interface BuildFn {
        HarnessAgent apply(ChatCommand cmd, ExecutionPolicy policy, ModelRegistryService.ModelRoute route,
                           WenquAgentxTools.TurnHooks hooks, AgentBinding binding, AgentxTurnSlot slot,
                           boolean sandboxEnabled, EffectiveWorkspace ew);
    }

    public AgentSession open(ChatCommand cmd, String ref, ModelRegistryService.ModelRoute route,
                             ExecutionPolicy policy, WenquAgentxTools.TurnHooks hooks,
                             AgentBinding binding, AgentxTurnSlot slot, boolean sandboxWanted,
                             EffectiveWorkspace ew, BuildFn buildAgent) {
        String wsKey = ew == null ? "" : ew.key();
        try {
            return sessions.open(cmd, ref,
                    binding.key() + "|sb=" + (sandboxWanted ? "1" : "0") + "|ws=" + wsKey,
                    ctx -> buildAgent.apply(cmd, policy, route, hooks, binding, slot, sandboxWanted, ew),
                    sandboxWanted);
        } catch (Exception e) {
            log.warn("[agentx] 重建会话失败 session={}: {}", cmd.getSessionId(), e.toString());
            return null;
        }
    }

    /** 收拾「上一轮没被框架记成结束」的遗留，并把三道按轮闸归零。 */
    public void warm(ChatCommand cmd, AgentSession session, String turnId, boolean recover,
                     WenquAgentxTools tools) {
        if (turnId != null && turnId.startsWith("goal:")) {
            if (recover) sessionGateway.recoverInterruptedSession(session, turnId);
            sessionGateway.reconcileStalledTurn(session, cmd.getSessionId(), sessions::abandoned);
        }
        String key = sessions.key(cmd);
        AgentxStepBudget stepBudget = sessions.stepBudget(key);
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
            }, text -> sessions.steer(cmd.getUserId(), cmd.getSessionId(), text));
        }
        AgentxRepeatGuard repeatGuard = sessions.repeatGuard(key);
        if (repeatGuard != null) repeatGuard.beginTurn(null);
        AgentxFrameworkToolGate toolGate = sessions.frameworkToolGate(key);
        if (toolGate != null) {
            toolGate.beginTurn(turnId);
            toolGate.setCompletionCheck(goalService.isCompletionCheck(turnId));
        }
    }

    /** 续跑轮和正常轮共用同一道工具组闸。 */
    public void warnSurface(ChatCommand cmd, ExecutionPolicy policy, com.wenqu.ai.engine.RunChannel channel) {
        for (String surfaceProblem : sessions.applyToolSurface(cmd, tools.turnGroupedNames(cmd, policy), policy)) {
            channel.emit(com.wenqu.ai.engine.RunEventType.WARN, surfaceProblem);
        }
    }

    /** 深度执行第 1 轮：进计划位（与手动计划模式同一道 plan 门禁）。 */
    public void engagePlan(ChatCommand cmd, boolean planFirst, com.wenqu.ai.engine.RunChannel channel) {
        if (!planFirst) return;
        boolean engaged = sessions.setPlanMode(cmd, true);
        log.info("[GOAL] 第 1 轮进入计划位: session={} engaged={}", cmd.getSessionId(), engaged);
        if (!engaged) {
            channel.emit(com.wenqu.ai.engine.RunEventType.WARN, "计划模式未能启用（框架侧未进入计划位），本轮直接执行");
        }
    }
}
