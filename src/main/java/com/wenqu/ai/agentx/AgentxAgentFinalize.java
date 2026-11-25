package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 建实例的最后一段（从 AgentscopeChatEngine 的 buildAgent 抽出）：记忆闸、总线、子智能体注册、
 * 团队模式与 build 后处理（工具说明对齐 + 护栏 + 读图工具）。
 *
 * <p>记忆闸三种轮次三种口径，必须在 build 期定——MemoryConfig / disableMemoryHooks 都烤在常驻实例里：
 * ① 游客/分享/MCP：发布者的 MEMORY.md 不能被注入访客对话，两个开关必须同时关（只关 hooks 仍会注入）；
 * ② 子智能体/咨询轮：只读不写——保留注入与 memory_* 工具，但关 flush 与后台合并；
 * ③ 正常用户轮：平台总闸与个人开关都开着才挂 memoryConfig；
 * ④ 工作区记忆隔离：按①同款全关，给敏感项目一个不串味的场子。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxAgentFinalize {

    private final AgentxMemoryGate memoryGate;
    private final AgentxTeamMode teamMode;
    /** 该会话是否按「工作区记忆隔离」处理（引擎侧查库，注入成谓词以减少本类类型依赖） */
    private final AgentxFrameworkInfra infra;
    private final WenquAgentxTools tools;
    private final AgentxAgentBinding agentBinding;
    private final AgentxToolFinisher toolFinisher;

    /** 收尾所需的全部输入（把十几个参数收成一个对象）。 */
    public record Input(HarnessAgent.Builder agentBuilder, ChatCommand cmd,
                        ModelRegistryService.ModelRoute route, AgentBinding binding,
                        WenquAgentxTools.TurnHooks hooks, AgentxTurnSlot slot,
                        io.agentscope.core.model.Model auxModel,
                        io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFs,
                        List<WenquAgentxTools.SubagentTarget> subagentTargets, boolean teamActive,
                        com.wenqu.ai.agentx.team.WenquRedisMessageBus teamBus,
                        com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway,
                        com.wenqu.ai.service.EffectiveWorkspace ew) {
    }

    public HarnessAgent finalize(Input in) {
        HarnessAgent.Builder agentBuilder = in.agentBuilder();
        ChatCommand cmd = in.cmd();
        WenquAgentxTools.TurnHooks hooks = in.hooks();
        AgentxTurnSlot slot = in.slot();
        io.agentscope.core.model.Model auxModel = in.auxModel();
        io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFs = in.frameworkFs();
        List<WenquAgentxTools.SubagentTarget> subagentTargets = in.subagentTargets();
        boolean teamActive = in.teamActive();
        com.wenqu.ai.agentx.team.WenquRedisMessageBus teamBus = in.teamBus();
        com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway = in.teamGateway();
        com.wenqu.ai.service.EffectiveWorkspace ew = in.ew();
        memoryGate.apply(agentBuilder, cmd, hooks, auxModel);
        io.agentscope.harness.agent.DistributedStore distributedStore = infra.distributedStore();
        if (distributedStore != null) agentBuilder.distributedStore(distributedStore);
        // 非团队轮用无操作实现（避免沙盒文件系统版的每轮 HTTP 往返）；团队轮换 Redis 总线
        boolean teamOn = teamActive && teamBus != null;
        agentBuilder.messageBus(teamOn ? teamBus : NoopBusComponents.MESSAGE_BUS);
        agentBuilder.asyncToolRegistry(NoopBusComponents.ASYNC_TOOL_REGISTRY);
        // 动态子智能体声明关掉：问渠的子智能体全部走 subagentFactory 静态注册
        agentBuilder.disableDynamicSubagents();
        for (WenquAgentxTools.SubagentTarget target : subagentTargets) {
            agentBuilder.subagentFactory(target.agentId(), target.description(), target.factory());
        }
        if (teamActive) teamMode.apply(agentBuilder, cmd, teamGateway);
        HarnessAgent agent = agentBuilder.build();
        toolFinisher.finish(agent, cmd, slot, hooks, frameworkFs, ew);
        return agent;
    }

}
