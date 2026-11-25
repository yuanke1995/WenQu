package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.EffectiveWorkspace;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.SandboxService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * 打开（或复用）本轮的常驻会话实例（从 AgentscopeChatEngine 抽出）。
 *
 * <p>实例键用<b>完整引用</b>而不是裸模型名：同一个模型名可以挂在好几个供应商下（各自的 baseUrl 与 Key
 * 不同），只按裸名判「模型没换」就不重建实例，于是换了供应商仍拿旧网关旧 Key 跑、日志上一切正常。
 * 档位也进键：聊天档/工作区档的工具面与文件系统不同，切档必须重建。同一份 EW 既出键、又喂给
 * buildAgent 的 FS/prompt/guard roots——不许两处各算各的。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxSessionOpener {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final SandboxService sandboxService;
    private final AgentxSandboxIntent sandboxIntent;
    private final AgentxAgentBinding agentBinding;

    /** 建实例（引擎实现：buildAgent 的实参文本必须留在引擎里）。 */
    @FunctionalInterface
    public interface BuildAgentFn {
        HarnessAgent apply(ChatCommand cmd, ExecutionPolicy policy, ModelRegistryService.ModelRoute route,
                           WenquAgentxTools.TurnHooks hooks, AgentBinding binding, AgentxTurnSlot slot,
                           boolean sandboxEnabled, EffectiveWorkspace ew);
    }

    /** 打开结果：实例、绑定、是否工作区档、以及本轮 EW。 */
    public record Opened(AgentSession session, AgentBinding binding, boolean sandboxWanted, EffectiveWorkspace ew) {
    }

    public Opened open(ChatCommand cmd, String ref, ModelRegistryService.ModelRoute route,
                       ExecutionPolicy policy, WenquAgentxTools.TurnHooks hooks, AgentxTurnSlot slot,
                       String turnId, WenquSourcePool pool, RunChannel channel, BuildAgentFn buildAgent) {
        boolean sandboxWanted = sandboxIntent.wants(cmd, policy);
        slot.noteProfile(sandboxWanted ? "workspace" : "chat");
        warnWorkspaceFallback(cmd, sandboxWanted, pool, channel);
        AgentBinding binding = agentBinding.build(cmd, policy, ref);
        EffectiveWorkspace ew = (sandboxWanted && sandboxService != null)
                ? sandboxService.resolveWorkspace(cmd.getSessionId(), cmd.getUserId(), true) : null;
        String wsKey = ew == null ? "" : ew.key();
        AgentSession session;
        try {
            session = sessions.open(cmd, ref,
                    binding.key() + "|sb=" + (sandboxWanted ? "1" : "0") + "|ws=" + wsKey,
                    ctx -> buildAgent.apply(cmd, policy, route, hooks, binding, slot, sandboxWanted, ew),
                    sandboxWanted);
        } catch (RuntimeException e) {
            // 建实例失败也要把槽位收回：否则这一会话的「当前轮」永远停在一次没跑起来的轮上
            slot.end(turnId);
            throw e;
        }
        tools.ensureRunCodeTurn(slot, cmd);
        // 首轮 build 之后才知道系统提示词里有没有工作区记忆：补标到本轮（缓存命中时 begin 已带上）
        if (slot.workspaceMemoryTainted() && slot.current() != null) {
            slot.current().taint().mark("workspace-memory");
        }
        return new Opened(session, binding, sandboxWanted, ew);
    }

    /** 本机执行器离线时的兜底必须让用户看见：这一轮文件在服务器上，不在他的电脑上。 */
    private void warnWorkspaceFallback(ChatCommand cmd, boolean sandboxWanted,
                                       WenquSourcePool pool, RunChannel channel) {
        if (!sandboxWanted || sandboxService == null) return;
        try {
            String wsNotice = sandboxService.sessionWorkspaceNotice(cmd.getSessionId(), cmd.getUserId());
            if (wsNotice != null) {
                pool.registrar().degrade("workspaceFallback", wsNotice);
                channel.emit(RunEventType.WARN, wsNotice);
            }
        } catch (RuntimeException e) {
            log.warn("[PROFILE] 工作区后端提示解析失败 session={}: {}", cmd.getSessionId(), e.toString());
        }
    }
}
