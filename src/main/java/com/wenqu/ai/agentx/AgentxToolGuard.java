package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.EffectiveWorkspace;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 框架原生文件/命令工具的护栏 + 读图工具挂载（从 AgentscopeChatEngine 抽出）。
 *
 * <p>build 之后按同名重新注册一层包装：名字、schema、只读位、执行全部不变，只多「结果加信封」与
 * 「污点时强制确认」。只包会返回外部内容的几把（read/grep/glob/list/execute）；write/edit 只管越界确认。
 * 游客分享/API Key 轮没有审批卡通道：污点触发时直接拒，不挂一张没人答的卡等超时。
 *
 * <p>{@code workspaceRootsOf} 仍留在引擎（approvalReason 也用，且被契约测试锁定），以 BiFunction 注入。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxToolGuard {

    private final WenquAgentxTools tools;
    private final ModelRegistryService modelRegistry;

    public void apply(HarnessAgent agent, AgentxTurnSlot slot, ChatCommand cmd, List<String> roots) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        // 游客分享/API Key 轮没有审批卡通道：污点触发时直接拒，别挂一张没人答的卡等超时
        boolean canAsk = !cmd.isGuestMode() && !cmd.isApiKey() && AgentxTextUtil.interactiveTurn(cmd);
        // 根会话级放行：用户在父轮点过「本会话都允许」的工具，这一支的污点确认不再问
        String grantScope = tools.toolGrantScope(cmd);
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.READ_FILE,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.READ_FILE));
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.GREP_FILES,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.GREP_FILES));
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.GLOB_FILES,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.GLOB_FILES));
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.LIST_FILES,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.LIST_FILES));
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.EXECUTE,
                AgentxNativeToolGuard.Kind.EXEC, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.EXECUTE));
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.WRITE_FILE,
                AgentxNativeToolGuard.Kind.WRITE, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.WRITE_FILE));
        wrap(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.EDIT_FILE,
                AgentxNativeToolGuard.Kind.EDIT, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.EDIT_FILE));
    }

    private static void wrap(Toolkit toolkit, AgentxTurnSlot slot, List<String> roots,
                                   boolean canAsk, String name, AgentxNativeToolGuard.Kind kind,
                                   java.util.function.BooleanSupplier sessionApproved) {
        AgentTool tool = toolkit.getTool(name);
        if (tool == null || tool instanceof AgentxNativeToolGuard) return;
        toolkit.registerAgentTool(new AgentxNativeToolGuard(tool, kind, slot, roots, canAsk, sessionApproved));
    }

    public void registerReadImage(HarnessAgent agent, WenquAgentxTools.TurnHooks hooks,
                                       io.agentscope.harness.agent.filesystem.AbstractFilesystem fs) {
        if (agent == null || fs == null) return;
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        if (toolkit.getTool(ReadImageTool.NAME) != null) return;
        // 不配平台开关：读图是只读能力，挂载条件只有「模型这一轮能读图 + 文件系统可用」；
        // 要关就按名停用 read_image（平台停用名单/智能体 deny），与文件类工具同一口径。
        boolean vision = hooks != null && modelRegistry.visionCapableOf(hooks.modelRef());
        if (!vision) return;
        int mb = Math.max(1, Math.min(20, ConfigDefaults.CHAT_MAX_IMAGE_MB));
        toolkit.registerAgentTool(new ReadImageTool(fs, mb * 1024 * 1024));
    }
}
