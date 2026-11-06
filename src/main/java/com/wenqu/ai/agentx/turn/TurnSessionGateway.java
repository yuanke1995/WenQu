package com.wenqu.ai.agentx.turn;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 框架会话的读取/恢复边界：任务定位、中断残留清理、遗留轮归档、积压提交作废。
 * <p>只碰 Session Log/Inbox/Turns 与 AgentSession 的只读查询；开轮装配（模型、通道、工具面）
 * 仍留在引擎，避免把构造依赖搬成一个巨型网关。
 */
public final class TurnSessionGateway {

    private static final Logger log = LoggerFactory.getLogger(TurnSessionGateway.class);

    /** 这一轮此刻最后那条任务（接续执行起的是新任务，同轮号不同 runId）。 */
    public static AgentSession.Task taskForTurn(AgentSession session, String turnId) {
        AgentSession.Task last = null;
        for (AgentSession.Task t : session.tasks()) {
            last = t;
            if (turnId != null && turnId.equals(t.turnId())) return t;
        }
        return last;
    }

    /** 同一轮里最后出现的那条任务：{@code respond} 接续出来的是新任务（同 turnId、新 runId）。 */
    public static AgentSession.Task latestTaskInTurn(AgentSession session, AgentSession.Task known) {
        if (known == null) return null;
        AgentSession.Task latest = null;
        for (AgentSession.Task t : session.tasks()) {
            if (known.turnId().equals(t.turnId())) latest = t;
        }
        return latest;
    }

    /**
     * 进程中断后的恢复轮：封存上一进程残留的会话写者，把「工具已发出、结果没回来」的调用标成未知。
     * <p>不做这两步，框架 inbox 会一直等那个已经不存在的 writer（2 分钟租约），表现为「目标卡在
     * EXECUTING 不动」；未决工具按未知处理，避免恢复轮重复执行有副作用的写操作。
     */
    public void recoverInterruptedSession(AgentSession session, String turnId) {
        try {
            SessionLog journal = session.log();
            SessionLog.Head head = journal.head();
            if (head != null && head.owner() != null) {
                journal.sealWriter(head.owner());
                log.warn("[GOAL] 已封存中断进程的会话写者: owner={} turn={}", head.owner(), turnId);
            }
            SessionProjection view = session.inspect();
            Set<String> uncertain = view.uncertainToolCalls();
            boolean active = !view.activeRuns().isEmpty();
            if (!uncertain.isEmpty() || active) {
                Map<String, ToolResultBlock> outcomes = new LinkedHashMap<>();
                for (String id : uncertain) {
                    outcomes.put(id, new ToolResultBlock(id, "unknown",
                            TextBlock.builder().text(
                                    "执行进程中断，工具结果未知；恢复轮必须先在真实环境中核对，"
                                            + "不要重复执行有副作用的操作。").build())
                            .withState(ToolResultState.INTERRUPTED));
                }
                // 即使没有未决工具，reconcile 也会把 activeRuns 标成 interrupted；
                // 不标，SessionTurns.latest() 永远不是 ended，submit 会一直等下去。
                SessionRecovery.reconcile(journal, outcomes,
                        "目标执行中断恢复：未决工具结果按未知处理");
                log.warn("[GOAL] 恢复会话：未决工具 {} 个，活动运行 {} 个已归档: turn={}",
                        outcomes.size(), view.activeRuns().size(), turnId);
            }
        } catch (RuntimeException e) {
            log.warn("[GOAL] 恢复会话残留状态失败（继续尝试归档）: turn={} {}", turnId, e.toString());
        }
    }

    /**
     * 遗留轮归档：上一轮停在 suspended/未结束，会话之后每轮都不启动。
     * <p>还挂在 PENDING 且没人放弃的卡照旧不碰——那一张真的在等人。
     */
    public void reconcileStalledTurn(AgentSession session, String sessionId,
                                     Predicate<Collection<String>> abandoned) {
        SessionLog journal = session.log();
        SessionTurns.Turn latest;
        SessionProjection view;
        try {
            latest = SessionTurns.read(journal).latest();
            if (latest == null || latest.ended()) return;
            view = session.inspect();
        } catch (RuntimeException unreadable) {
            log.warn("[agentx] 读会话轮次台账失败 session={}: {}", sessionId, unreadable.toString());
            return;
        }
        Map<String, ?> pending = session.pending();
        boolean hasPending = !pending.isEmpty();
        boolean pendingAbandoned = hasPending && abandoned.test(pending.keySet());
        if (!needsStalledReconcile(latest.ended(), !view.activeRuns().isEmpty(),
                !view.uncertainToolCalls().isEmpty(), hasPending, pendingAbandoned)) {
            return;
        }
        // 先丢弃积压再归档：闸门还关着时投递循环不会 launch，这一步才有竞争安全性。
        // 反过来先归档，那些旧提交会在几十毫秒内被倒着补跑，正文灌进正在跟的这一轮的气泡里。
        discardUnstartedSubmits(journal, sessionId);
        try {
            SessionRecovery.cancelPending(journal, latest.turnId());
        } catch (RuntimeException refused) {
            log.warn("[agentx] 遗留轮 {} 归档失败 session={}: {}",
                    latest.turnId(), sessionId, refused.toString());
            return;
        }
        log.warn("[FAIL-LOUD] 上一轮以 {} 收场未被框架记成结束，已按取消归档（不归档则这条会话之后每轮都不启动）:"
                + " session={} turn={}", latest.status(), sessionId, latest.turnId());
    }

    /** 遗留轮归档的纯守卫：给回归测试单独钉住，不依赖框架会话。 */
    static boolean needsStalledReconcile(boolean latestEnded, boolean activeRuns, boolean uncertainTools,
                                         boolean hasPending, boolean pendingAbandoned) {
        if (latestEnded) return false;
        if (activeRuns || uncertainTools) return false;
        return !hasPending || pendingAbandoned;
    }

    /**
     * 作废「进了收件箱但从未启动」的旧提问。
     * <p>它们是上一轮卡住时被判超时的提问，用户侧已经收到过「超时截断」；留着不管，闸门一开就会被补跑，
     * 后果是几分钟后凭空冒出旧问题的回答。
     */
    public void discardUnstartedSubmits(SessionLog journal, String sessionId) {
        try {
            new SessionInbox(journal).locked(tx -> {
                SessionInbox.Snapshot snapshot = tx.snapshot();
                Set<String> started = SessionTurns.read(journal).started();
                for (SessionInbox.Command command : snapshot.commands()) {
                    if (!"submit".equals(command.kind()) || started.contains(command.id())
                            || snapshot.rejected().containsKey(command.id())) continue;
                    tx.reject(command, "上一轮遗留、从未启动，已作废");
                    log.warn("[FAIL-LOUD] 作废一条从未启动的积压提问 turn={} session={}",
                            command.turnId(), sessionId);
                }
                return null;
            });
        } catch (RuntimeException failed) {
            log.warn("[agentx] 积压提问清理失败 session={}: {}", sessionId, failed.toString());
        }
    }
}
