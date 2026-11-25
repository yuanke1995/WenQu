package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ProjectTurnChangesService;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 本轮槽位换装与轮前改动快照（从 AgentscopeChatEngine 抽出）。
 *
 * <p>工具表在 build 期定型、实例按会话常驻，pool/channel/hooks 只能放进会话级槽位，由这里在
 * submit 之前换掉。早于 sessions.open 是为了让建实例期的降级也能发到当轮通道上。
 *
 * <p>轮前改动快照（异步入队、FIFO 先于本轮第一次写）顺带对账「用户上一次回答之后改了什么」，
 * 结果在下文拼进本轮输入；非本机工作区/执行器离线都在服务内部静默跳过。API Key 轮次没有文件工具，
 * 拍快照没有意义。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxTurnSlotBinder {

    private final WenquAgentxTools tools;
    private final FrameworkSessions sessions;
    private final ProjectTurnChangesService turnChangeService;

    /** 换装结果：槽位、hooks、spills、轮前改动快照。 */
    public record Bound(AgentxTurnSlot slot, WenquAgentxTools.TurnHooks hooks,
                        ConcurrentLinkedQueue<String> spills, ProjectTurnChangesService.Start changes) {
    }

    public Bound bind(ChatCommand cmd, RunChannel channel, WenquSourcePool pool, ExecutionPolicy policy,
                      String ref, String turnId,
                      java.util.function.Supplier<com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger> ledger) {
        ConcurrentLinkedQueue<String> spills = new ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills, ledger.get());
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        slot.begin(tools.newTurn(cmd, pool, channel, policy, hooks, slot.workspaceMemoryTainted()));
        ProjectTurnChangesService.Start changes =
                cmd.isApiKey() ? null : turnChangeService.start(cmd.getSessionId(), turnId);
        return new Bound(slot, hooks, spills, changes);
    }
}
