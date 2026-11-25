package com.wenqu.ai.agentx;

import com.wenqu.ai.service.ChatRunBus;
import io.agentscope.harness.agent.session.AgentSession;
import com.wenqu.ai.service.GoalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 目标轮续跑（从 AgentscopeChatEngine 抽出）：抢会话锁、复查目标是否还该跑、装配上下文、
 * 抢跟随位（抢不到延迟重试）、登记运行总线，然后交给续跑跟随。
 *
 * <p>每一步前都复查一次：从「上一次收尾决定起下一轮」到真正提交之间，用户可能刚停止/删除/改了目标；
 * 不复查就会把一轮已经没人要的续跑放出去（界面上表现为「删了目标它还在跑」）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxGoalRunner {

    private static final long GOAL_RETRY_DELAY_MS = 1_500L;
    private static final int GOAL_RETRY_MAX = 20;

    private final FrameworkSessions sessions;
    private final GoalService goalService;
    private final ChatRunBus chatRunBus;
    private final AgentxPromptContext promptCtx;
    private final ScheduledExecutorService goalRetry;
    private final AgentxContinuationOpener continuationOpener;
    private final AgentxResumeStarter resumeStarter;
    private final java.util.Set<String> backgroundGoalTurns;
    private final java.util.concurrent.ExecutorService goalKick;

    /** 异步发起一轮目标续跑（执行池拒绝提交时当场收锁与目标状态）。 */
    public void submit(com.wenqu.ai.model.Goal goal, String input, int n) {
        try {
            goalKick.execute(() -> {
                try {
                    continueTurn(goal, input, n, 0);
                } catch (RuntimeException e) {
                    log.warn("[GOAL] 目标轮异步发起失败: goal={} n={} {}", goal.getId(), n, e.toString());
                    // 不把目标留在 EXECUTING 等租约过期：起轮阶段就失败，立刻转暂停让用户看见。
                    goalService.markContinueFailed(goal, e.getMessage());
                    // 起轮阶段异常可能发生在任何一步：会话锁在这里兜一次，避免整条会话被锁死
                    goalService.releaseSession(goal.getSessionId());
                }
            });
        } catch (RuntimeException rejected) {
            // 执行池已关闭（停机中）：这一轮没提交出去，锁与目标状态都要当场收掉
            log.warn("[GOAL] 目标轮执行池拒绝提交: goal={} {}", goal.getId(), rejected.toString());
            goalService.markContinueFailed(goal, rejected.getMessage());
            goalService.releaseSession(goal.getSessionId());
        }
    }

    public void continueTurn(com.wenqu.ai.model.Goal goal, String input, int n, int attempt) {
        String sessionId = goal.getSessionId();
        String uid = goal.getUserId();
        String turnId = com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n;
        // 重试支路：等跟随位时把会话锁还给了用户轮，重新起跑要重新抢一次，抢不到继续让位。
        if (attempt > 0) {
            if (!goalService.acquireSessionForTurn(sessionId)) {
                if (attempt >= GOAL_RETRY_MAX) {
                    goalService.markContinueFailed(goal, "会话一直被用户轮占用，目标轮没排上");
                    return;
                }
                goalRetry.schedule(() -> continueTurn(goal, input, n, attempt + 1),
                        GOAL_RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }
        }
        // 提交前复查：从「上一次收尾决定起下一轮」到这一行之间，用户可能刚停止/删除/改了目标。
        // 不复查就会把一轮已经没人要的续跑放出去（界面上表现为「删了目标它还在跑」）。
        if (!goalService.runnable(goal)) {
            log.info("[GOAL] 目标已不在进行中，取消这一轮续跑: goal={} n={}", goal.getId(), n);
            goalService.releaseSession(sessionId);
            return;
        }
        boolean recover = "ORPHANED".equals(goal.getErrorClass());
        AgentxFollowing f = continuationOpener.open(sessionId, uid, turnId, recover, null, n == 1, promptCtx.goalAgentId(goal));
        if (f == null) {
            goalService.markContinueFailed(goal, "会话实例装配不出来（模型/路由解析失败）");
            goalService.releaseSession(sessionId);
            return;
        }
        // 冷启动装配可能十几秒：这段时间里用户完全可能已经取消/停止。复查一次再提交，
        // 否则就是线上那条「17:48:32 取消、17:48:39 照常起跑」。
        if (!goalService.runnable(goal) || goalService.goalTurnAborted(turnId)) {
            log.info("[GOAL] 装配期间目标已被取消/停止，丢弃这一轮续跑: goal={} n={}", goal.getId(), n);
            goalService.releaseSession(sessionId);
            return;
        }
        String sessionKey = sessions.key(f.cmd());
        // 先抢跟随位再 submit：上一轮（多半是刚被停止/删除的旧目标轮）还在收尾时，
        // 直接 submit 会让这一轮没人跟——事件不落库、轮数白涨，界面上就是「目标卡在跑但没动静」。
        // 抢不到就延迟重试；旧轮通常几秒内释放，30 秒仍抢不到如实转暂停。
        if (!sessions.reserveFollower(sessionKey)) {
            if (attempt >= GOAL_RETRY_MAX) {
                log.warn("[GOAL] 续跑抢不到跟随位，转暂停: goal={} n={}", goal.getId(), n);
                goalService.markContinueFailed(goal, "上一轮还在收尾，没抢到跟随位");
                goalService.releaseSession(sessionId);
                return;
            }
            log.info("[GOAL] 上一轮还在收尾，{}/{} 次后重试续跑: goal={} n={}", attempt + 1,
                    GOAL_RETRY_MAX, goal.getId(), n);
            // 等位期间把会话锁还回去：攥着锁等 30 秒，用户这期间连一句话都发不出去。
            goalService.releaseSession(sessionId);
            goalRetry.schedule(() -> continueTurn(goal, input, n, attempt + 1),
                    GOAL_RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        try {
            // 提交前最后一道：跟随位是异步抢的，等位/重试期间目标可能已被取消
            if (!goalService.runnable(goal) || goalService.goalTurnAborted(turnId)) {
                log.info("[GOAL] 抢到跟随位时目标已不在进行中，取消提交: goal={} n={}", goal.getId(), n);
                sessions.releaseFollower(sessionKey);
                goalService.releaseSession(sessionId);
                return;
            }
            AgentSession.Task task = f.session().submit(
                    com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n, input);
            backgroundGoalTurns.add(sessionId + "|" + turnId);
            // 先登记总线再发帧：deep_turn 到浏览器时，它探测 /chat/run 必须已经探得到这一轮
            java.util.concurrent.atomic.AtomicBoolean alive = openTurnRun(f, sessionId, uid, turnId);
            goalService.emitTurn(goal, n, turnId, f.channel());
            // 从这里起，会话锁归跟随循环的 finally 释放（与 follower 同一次收尾）
            resumeStarter.start(f, task, () -> alive.set(false), true);
        } catch (RuntimeException e) {
            log.warn("[GOAL] 续跑轮提交失败: goal={} n={} {}", goal.getId(), n, e.toString());
            goalService.markContinueFailed(goal, e.getMessage());
            sessions.releaseFollower(sessionKey);
            goalService.releaseSession(sessionId);
        }
    }

    public java.util.concurrent.atomic.AtomicBoolean openTurnRun(AgentxFollowing f, String sessionId,
                                                                      String uid, String turnId) {
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        if (!chatRunBus.enabled() || uid == null || uid.isBlank()) return alive;
        com.wenqu.ai.service.ChatRunBus.Run prev = chatRunBus.running(sessionId);
        if (prev != null && !prev.turnId().startsWith("goal:")) {
            log.info("[GOAL] 用户自己那一轮还在跑，后台轮不顶总线: session={}", sessionId);
            return alive;
        }
        Map<String, Object> pending = Map.of("running", true);
        chatRunBus.open(sessionId, turnId, uid, f.channel().emitter(), () -> pending,
                () -> false, alive::get);
        return alive;
    }
}
