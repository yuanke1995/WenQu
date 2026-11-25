package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ChatService;
import com.wenqu.ai.service.EffectiveWorkspace;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.agent.subagent.SubAgentConsultService;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * 续跑轮的「开跑前事实」装配（从 AgentxContinuationOpener 抽出）：重建命令、选模型路由、
 * 判档、建通道、建引用池、建本轮身份。
 *
 * <p>抽出来的理由与判档同源：这一步要同时摸到 {@link FrameworkSessions}、工具层、模型注册、
 * 绑定与沙箱判档五处，混在「开会话 + 装配结果」里会让打开器引用的类型数一路涨到 Checkstyle
 * 阈值之上（实测 27 > 20）。这里只负责「把这一轮的前提问清楚」，不碰会话实例。
 *
 * <p>任何一步拿不到（无会话 id、模型不可用、无路由）都返回 {@code null}，
 * 由调用方如实报失效，而不是静默什么都不发生。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxContinuationPrep {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final ModelRegistryService modelRegistry;
    private final AgentxResumeCommand resumeCommand;
    private final AgentxAgentBinding agentBinding;
    private final AgentxSandboxIntent sandboxIntent;
    private final Function<ChatCommand, ChatService.TurnModel> modelResolver;
    /** 工作区解析（引擎侧能力，注入成函数以减少本类引用的类型数） */
    private final Function<ChatCommand, EffectiveWorkspace> workspaceResolver;
    private final SubAgentConsultService consultService;

    /** 开跑前问清楚的前提：本轮身份、档位、路由、引用池与槽位。 */
    public record Prepared(ChatCommand cmd, String ref, RunChannel channel, WenquSourcePool pool,
                           ModelRegistryService.ModelRoute route, WenquAgentxTools.TurnHooks hooks,
                           ExecutionPolicy policy, AgentBinding binding, AgentxTurnSlot slot,
                           boolean sandboxWanted, EffectiveWorkspace ew) {
    }

    public Prepared prepare(String sessionId, String userId, String turnId, String guestUid,
                            boolean planFirst, String agentOverride) {
        if (sessionId == null || sessionId.isBlank()) return null;
        ChatCommand cmd = resumeCommand.build(sessionId, userId, guestUid, planFirst, agentOverride);
        ChatService.TurnModel turn = modelResolver.apply(cmd);
        String ref = turn.usable() ? turn.ref() : "";
        if (ref.isBlank()) return null;
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) return null;
        RunChannel channel = resumeCommand.channel(sessionId);
        ExecutionPolicy policy = tools.policyFor(cmd);
        WenquSourcePool pool = tools.newPool(cmd, channel.emitter());
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId,
                new ConcurrentLinkedQueue<>(), consultService.newLedger());
        AgentBinding binding = agentBinding.build(cmd, policy, ref);
        boolean sandboxWanted = sandboxIntent.wants(cmd, policy);
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        slot.noteProfile(sandboxWanted ? "workspace" : "chat");
        EffectiveWorkspace ew = (sandboxWanted && workspaceResolver != null)
                ? workspaceResolver.apply(cmd) : null;
        return new Prepared(cmd, ref, channel, pool, route, hooks, policy, binding, slot, sandboxWanted, ew);
    }
}
