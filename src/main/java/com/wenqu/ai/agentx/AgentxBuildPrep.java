package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.service.ModelRegistryService;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 建实例前的准备（从 AgentscopeChatEngine 的 buildAgent 抽出）：三道按实例闸、两份模型、
 * 可派遣助手清单、工作区/用户记忆只读快照。
 *
 * <p>三道闸（步数预算 / 重复调用 / 框架工具档位）随实例常驻，登记到会话表好让引擎开轮时归零。
 * 子轮只读记忆：框架四把记忆工具打包注册，没有「只摘写」的开关，这里在执行前拒 memory_save。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxBuildPrep {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final AgentxModelFactory modelFactory;
    private final AgentxPromptContext promptCtx;
    private final AgentxFrameworkInfra infra;

    /** 准备结果：三道闸、两份模型、助手清单、两段记忆。 */
    public record Prepared(AgentxStepBudget stepBudget, AgentxRepeatGuard repeatGuard,
                           AgentxFrameworkToolGate toolGate, io.agentscope.core.model.Model chatModel,
                           io.agentscope.core.model.Model auxModel,
                           List<WenquAgentxTools.SubagentTarget> subagentTargets,
                           String workspaceMemory, String userMemoryReadonly) {
    }

    public Prepared prepare(ChatCommand cmd, ExecutionPolicy policy, ModelRegistryService.ModelRoute route,
                            AgentBinding binding, WenquAgentxTools.TurnHooks hooks, AgentxTurnSlot slot) {
        String key = sessions.key(cmd);
        AgentxStepBudget stepBudget = new AgentxStepBudget();
        sessions.noteStepBudget(key, stepBudget);
        AgentxRepeatGuard repeatGuard = new AgentxRepeatGuard();
        sessions.noteRepeatGuard(key, repeatGuard);
        AgentxFrameworkToolGate toolGate = new AgentxFrameworkToolGate(() -> {
            AgentxTurnSlot.Turn t = slot.current();
            return t == null ? policy : t.policy();
        }, cmd.getGuestMcpServers());
        sessions.noteFrameworkToolGate(key, toolGate);
        // 子轮只读记忆：执行前拒 memory_save，保留注入与 memory_search/get
        toolGate.denyMemoryWrites(cmd.branchTurn());
        AgentxModelFactory.Models models = modelFactory.build(cmd, route, binding, hooks,
                infra.sharedModelTransport());
        List<WenquAgentxTools.SubagentTarget> targets = tools.consultSubagentTargets(cmd, policy, slot);
        String workspaceMemory = promptCtx.workspaceMemoryContext(cmd);
        String userMemoryReadonly = promptCtx.userMemoryReadonlyContext(cmd);
        // 记忆是持久注入面：本轮后续的破坏性命令/越界写要回到人面前确认（见 AgentxTaint）
        slot.noteWorkspaceMemoryTainted(!workspaceMemory.isEmpty() || !userMemoryReadonly.isEmpty());
        return new Prepared(stepBudget, repeatGuard, toolGate, models.chat(), models.aux(), targets,
                workspaceMemory, userMemoryReadonly);
    }
}
