package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.EffectiveWorkspace;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.function.BiFunction;

/**
 * build 之后的工具收尾（从 AgentxAgentFinalize 抽出）：说明对齐、原生工具护栏、读图工具挂载。
 *
 * <p>框架在 build 期把文件/命令工具注册进 toolkit 的副本，问渠只能在这个副本上按同名重新注册一层；
 * 三件事必须都在 build 之后做，早一步工具还不存在。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxToolFinisher {

    private final AgentxToolGuard toolGuard;
    private final BiFunction<ChatCommand, EffectiveWorkspace, List<String>> workspaceRoots;

    public void finish(HarnessAgent agent, ChatCommand cmd, AgentxTurnSlot slot,
                       WenquAgentxTools.TurnHooks hooks, AbstractFilesystem frameworkFs,
                       EffectiveWorkspace ew) {
        AgentxToolDescription.alignWriteFileDescription(agent);
        AgentxToolDescription.alignExecuteDescription(agent);
        toolGuard.apply(agent, slot, cmd, workspaceRoots.apply(cmd, ew));
        toolGuard.registerReadImage(agent, hooks, frameworkFs);
    }
}
