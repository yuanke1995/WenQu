package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ProjectService;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;

/**
 * 建实例期的记忆闸（从 AgentscopeChatEngine 的 buildAgent 抽出）。
 *
 * <p>三种轮次三种口径，必须在 build 期定——MemoryConfig / disableMemoryHooks 都烤在常驻实例里：
 * ① 游客/分享/MCP：发布者的 MEMORY.md 不能被注入访客对话，两个开关必须同时关（只关 hooks 仍会注入）；
 * ② 子智能体/咨询轮：只读不写——保留注入与 memory_* 工具，但关 flush 与后台合并，
 *    否则每派一支就多一次提炼调用、内部推演还会沉淀成用户事实；
 * ③ 正常用户轮：平台总闸与个人开关都开着才挂 memoryConfig，任一关掉走 disableMemoryHooks；
 * ④ 工作区记忆隔离（Project.memoryIsolated，干净房间）：按①同款全关。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxMemoryGate {

    private final AgentxModelConfig modelConfig;
    private final ProjectService projectService;

    public void apply(HarnessAgent.Builder agentBuilder, ChatCommand cmd,
                      WenquAgentxTools.TurnHooks hooks, Model auxModel) {
        if (cmd.isGuestMode() || projectService.memoryIsolatedOfSession(cmd.getSessionId())) {
            agentBuilder.disableMemoryHooks().disableMemoryTools();
        } else if (cmd.branchTurn() || !modelConfig.memoryOn(cmd)) {
            agentBuilder.disableMemoryHooks();
        } else {
            agentBuilder.memory(modelConfig.memoryConfig(cmd, auxModel, hooks.modelRef()));
        }
    }
}
