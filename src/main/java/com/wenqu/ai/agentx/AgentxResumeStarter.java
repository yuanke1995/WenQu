package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.SteerReceipts;
import com.wenqu.ai.service.GoalService;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 起「续跑跟随」（从 AgentscopeChatEngine 抽出）：抢跟随位、占槽位、在单线程池里跑完跟随循环，
 * 收尾释放跟随位/槽位/会话锁，并接目标的下一轮。
 *
 * <p>单线程串行：同一条会话不该被两轮同时驱动。后台轮异常/提前退出没走到 done 时也要摘收据（幂等）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxResumeStarter {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final GoalService goalService;
    private final Set<String> backgroundGoalTurns;
    private final ExecutorService resumePool;
    private final FollowFn followTurn;

    /** 跟随到收尾（引擎实现：转发给 AgentxFollowRunner）。 */
    @FunctionalInterface
    public interface FollowFn {
        void accept(AgentSession session, com.wenqu.ai.agent.chat.ChatCommand cmd,
                    com.wenqu.ai.engine.RunChannel channel, WenquSourcePool pool,
                    com.wenqu.ai.service.ModelRegistryService.ModelRoute route,
                    WenquAgentxTools.TurnHooks hooks, String turnId, int historyBefore, long startCursor,
                    String userMessageId, AgentSession.Task current, AgentxTurnRecording recording,
                    long startedAt, long prepMs, int contextWindow, boolean recovered);
    }

    public void start(AgentxFollowing f, AgentSession.Task current, Runnable onSettled,
                                boolean alreadyReserved) {
        String sessionKey = sessions.key(f.cmd());
        if (!alreadyReserved && !sessions.reserveFollower(sessionKey)) {
            log.info("[agentx] 这一轮已经有跟随者在跑，不重复起 session={}", f.cmd().getSessionId());
            onSettled.run();
            return;
        }
        // 接回的那一轮也要占住槽位：工具与脚本通道读的是「当前轮」，不 begin 就会拿到上一轮
        AgentxTurnSlot slot = sessions.turnSlot(f.cmd());
        slot.begin(tools.newTurn(f.cmd(), f.pool(), f.channel(), f.policy(), f.hooks(),
                slot.workspaceMemoryTainted()));
        tools.ensureRunCodeTurn(slot, f.cmd());
        String turnId = f.hooks().turnId().get();
        if (turnId != null && turnId.startsWith("goal:")) {
            // 续跑轮的目标工具不依赖用户轮的 goalNote；槽位 begin 刚清过标记，这里补回来。
            slot.noteGoalActive(true);
        }
        resumePool.execute(() -> {
            try {
                // 用 hooks 里那一个轮号（openForContinuation 传入的那个），不重新问 TurnRegistry：
                // 后台续跑轮没有轮级外壳登记，turnIdOf 会拿不到把手而回落会话号，
                // 那一轮的账本归属就全挂错了
                followTurn.accept(f.session(), f.cmd(), f.channel(), f.pool(), f.route(), f.hooks(),
                        turnId, f.historyBefore(), f.startCursor(), null, current,
                        // 续跑轮 prepMs 传 0：这一跑不是新问答，cmd 里那个「收到提问」的时刻属于
                        // 原来那一拍，拿它当基准会把「人隔了几天才回来答卡」算进开轮前准备。
                        f.recording(), System.currentTimeMillis(), 0L, f.contextWindow(), f.recovered());
                log.info("[agentx] 续跑已收尾 session={}", f.cmd().getSessionId());
            } catch (Exception e) {
                log.warn("[agentx] 续跑中断 session={}: {}", f.cmd().getSessionId(), e.toString());
                goalService.onRoundSettled(f.cmd().getSessionId(), f.cmd().getUserId(), turnId,
                        0, 0, f.channel(), "", false, false, "跟随循环中断: " + e.getMessage());
            } finally {
                sessions.releaseFollower(sessionKey);
                slot.end(turnId);
                // 后台轮异常/提前退出没走到 done 时也要摘收据（正常路径在 followTurn 里已摘，幂等）
                com.wenqu.ai.agent.chat.SteerReceipts.clear(turnId);
                // 后台目标轮跑完：只摘掉「这一条会话的这一个轮」——旧目标轮收尾不能把新目标在飞的那一轮摘掉
                if (turnId != null && turnId.startsWith("goal:")) {
                    backgroundGoalTurns.remove(f.cmd().getSessionId() + "|" + turnId);
                }
                // 目标轮占的是与用户轮同一把会话轮锁（GoalService.launchRound 抢的），
                // 跟着跟随循环一起还回去——不还，这条会话在 130 分钟租约内再也发不出新问题。
                if (turnId != null && turnId.startsWith("goal:")) {
                    goalService.releaseSession(f.cmd().getSessionId());
                    // 叫停标记留到这一轮真正收场之后才摘：产物结算与落库都在 finish 里，
                    // 提早摘掉会让「已取消的一轮」把产物又交付出去。
                    goalService.clearTurnAbort(turnId);
                }
                onSettled.run();
                // 跟随位释放之后再接下一轮（放在收尾里发会被「已有跟随者」挡掉，目标循环走不到第三轮）。
                // 传这一轮的 turnId，服务端据此区分用户轮收尾与目标轮收尾（让位停下的目标会自动接上）。
                if (turnId != null && turnId.startsWith("goal:")) {
                    goalService.onUserTurnEnd(f.cmd().getSessionId(), f.cmd().getUserId(), turnId);
                }
            }
        });
    }
}
