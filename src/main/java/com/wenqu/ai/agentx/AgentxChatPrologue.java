package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ChatService;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.ModelRegistryService;
import lombok.RequiredArgsConstructor;

import java.util.Set;
import java.util.function.Function;

/**
 * 开轮序章（从 AgentscopeChatEngine 抽出）：成员轮直播包装、模型与路由解析、目标让位。
 *
 * <p>成员轮直播只加一条抄送——本轮的出口、收尾、看门狗、落库全部还是原来那条 channel。
 * 子助手分支轮（depth&gt;0）也必须包上：它的会话行是 KIND_TEMP，单看 kind 判不出来，
 * 而它的正文正是父轮派遣卡要显示的那一段（漏了它，subagent_step 只有工具步）。
 *
 * <p>模型引用按问答侧同一条链解析（请求覆盖 &gt; 会话固定 &gt; 个人默认）：只认请求里那一份时，
 * 靠个人默认作答的轮会解析不出路由（NPE 打断整轮，连错误帧都没有）。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxChatPrologue {

    private final ModelRegistryService modelRegistry;
    private final WenquAgentxTools tools;
    private final GoalService goalService;
    private final Set<String> backgroundGoalTurns;
    private final Function<ChatCommand, ChatService.TurnModel> modelResolver;
    private final Function<ChatCommand, Boolean> isTeamMemberSession;
    private final java.util.function.Supplier<com.wenqu.ai.agentx.team.TeamMemberLiveFeed> memberLiveFeed;

    /** 序章结果：本轮出口、模型引用、路由、执行策略；ref/route 为 null 表示已走 failLoud 收口。 */
    public record Prologue(RunChannel channel, String ref, ModelRegistryService.ModelRoute route,
                           ExecutionPolicy policy, String fallbackNotice) {
    }

    /** 开轮序章。ref 为 null 时调用方应直接返回（已由 failLoud 收口）。 */
    public Prologue begin(ChatCommand cmd, RunChannel channel, FailLoudFn failLoud) {
        RunChannel turnChannel = liveChannel(cmd, channel);
        ChatService.TurnModel turn = modelResolver.apply(cmd);
        if (turn.unusableMessage() != null) {
            failLoud.accept(cmd, turnChannel, turn.unusableMessage());
            return new Prologue(turnChannel, null, null, null, null);
        }
        String ref = turn.ref();
        if (ref == null || ref.isBlank()) {
            failLoud.accept(cmd, turnChannel, "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型");
            return new Prologue(turnChannel, null, null, null, null);
        }
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) {
            failLoud.accept(cmd, turnChannel, "模型「" + ref + "」未登记或已不可用：请在「智能体 → 模型供应商」里确认该供应商与模型");
            return new Prologue(turnChannel, null, null, null, null);
        }
        ExecutionPolicy policy = tools.policyFor(cmd);
        yieldToGoal(cmd, turnChannel);
        return new Prologue(turnChannel, ref, route, policy, turn.fallbackNotice());
    }

    /** 用户开了新的一轮：目标循环让位（用户优先）——只有后台续跑轮在飞时才打断它。 */
    void yieldToGoal(ChatCommand cmd, RunChannel channel) {
        if (backgroundGoalTurns.stream().anyMatch(k -> k.startsWith(cmd.getSessionId() + "|"))) {
            goalService.noteUserTurn(cmd.getSessionId(), cmd.getUserId(), channel);
        }
    }

    /** 成员轮直播包装：成员会话/子助手分支轮同时抄送一份给直播台，其余原样返回。 */
    private RunChannel liveChannel(ChatCommand cmd, RunChannel channel) {
        com.wenqu.ai.agentx.team.TeamMemberLiveFeed feed = memberLiveFeed.get();
        boolean live = feed != null && cmd.getSessionId() != null && !cmd.getSessionId().isBlank()
                && (Boolean.TRUE.equals(isTeamMemberSession.apply(cmd)) || cmd.getSubagentDepth() > 0);
        if (!live) return channel;
        // 幂等：唤醒/分支那两处可能已经开过台（含更早的积压帧），这里只是确保有
        feed.open(cmd.getSessionId());
        return new com.wenqu.ai.agentx.team.TeamMemberLiveChannel(channel, feed, cmd.getSessionId());
    }

    /** 开局即败的统一出口（引擎实现）。 */
    @FunctionalInterface
    public interface FailLoudFn {
        void accept(ChatCommand cmd, RunChannel channel, String message);
    }
}
