package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.SteerReceipts;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.GoalService;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 提交本轮并起跟随（从 AgentscopeChatEngine 抽出）：起表基准早帧、技能命中帧、跟随循环，
 * 收尾释放跟随位/槽位/收据并接目标的下一轮。
 *
 * <p>跟随位占住与释放成对：答复端点据此判断「这次答完有没有人接着跑」。目标下一轮必须放在
 * 跟随位释放之后接——放在收尾里发会被「已有跟随者」挡掉，目标循环走不到第三轮。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnLauncher {

    private final FrameworkSessions sessions;
    private final GoalService goalService;
    private final AgentxTurnSupport turnSupport;
    private final FollowFn followTurn;

    /** 跟随到收尾（引擎实现：转发给 AgentxFollowRunner）。 */
    @FunctionalInterface
    public interface FollowFn {
        void accept(AgentSession session, ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                    com.wenqu.ai.service.ModelRegistryService.ModelRoute route,
                    WenquAgentxTools.TurnHooks hooks, String turnId, int historyBefore, long startCursor,
                    String userMessageId, AgentSession.Task current, AgentxTurnRecording recording,
                    long startedAt, long prepMs, int contextWindow, boolean recovered);
    }

    /**
     * @param prepMs 开轮前准备那一段（服务端收到提问 → 开轮起表）；0 = 没有请求体的入口
     */
    public void launch(AgentSession session, ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                       com.wenqu.ai.service.ModelRegistryService.ModelRoute route,
                       WenquAgentxTools.TurnHooks hooks, String turnId, int historyBefore, long startCursor,
                       String userMessageId, AgentSession.Task current, AgentxTurnRecording recording,
                       AgentxTurnSlot slot, int contextWindow) {
        // 起表基准：网页/分享链路由控制器在请求入口记下 receivedAtMs；定时/目标/工作流这类
        // 没有请求体的入口以开轮这一刻为准（prepMs=0，不把到达流水线前的等待算成准备）。
        long startedAt = System.currentTimeMillis();
        long prepMs = cmd.getReceivedAtMs() > 0L
                ? Math.max(0L, startedAt - cmd.getReceivedAtMs()) : 0L;
        String sessionKey = sessions.key(cmd);
        sessions.claimFollower(sessionKey);
        try {
            // 起表基准早帧：外壳在请求入口已发过一帧，这里用开轮后的准确 prepMs 再校准一次；
            // 两帧同一基准，重锚不会让读数跳。没有请求体的入口这一帧就是它唯一的起表基准。
            channel.emit(RunEventType.TURN_START, JSON.toJSONString(
                    Map.of("wallMs", prepMs, "prepMs", prepMs)));
            // 本轮点名的技能：正文已由装配段注入上下文，这里记用量并给前端发命中帧——
            // 没有这一帧，「选了技能」这件事在界面上是静默的，用户永远不知道模型到底照没照做。
            turnSupport.emitPickedSkillHits(cmd, channel);
            followTurn.accept(session, cmd, channel, pool, route, hooks, turnId, historyBefore, startCursor,
                    userMessageId, current, recording, startedAt, prepMs, contextWindow, false);
        } finally {
            sessions.releaseFollower(sessionKey);
            // 收口后清掉本轮落点：之后再有工具调用会明确失败，而不是回落到这一轮的池子里
            slot.end(turnId);
            // 异常/提前退出没走到 done 的轮也要摘收据，否则会一直挂在台账里
            SteerReceipts.clear(turnId);
            // 本轮彻底收尾（跟随位已释放）才接目标的下一轮：放在收尾里发会被「已有跟随者」挡掉。
            // 传这一轮的 turnId：服务端据此区分「用户轮收尾」与「目标轮收尾」（不重复接）。
            goalService.onUserTurnEnd(cmd.getSessionId(), cmd.getUserId(), turnId);
        }
    }
}
