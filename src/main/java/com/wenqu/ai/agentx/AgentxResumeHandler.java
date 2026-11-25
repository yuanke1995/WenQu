package com.wenqu.ai.agentx;

import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 重启接续与「答完没人跟」两条补跟入口（从 AgentscopeChatEngine 抽出，FrameworkSessions.ResumeHandler 的实现体）。
 *
 * <p>为什么能做：框架的会话状态本来就持久化在 stateStore 里，缺的只是「内存里那条常驻实例」。
 * 按表里的归属重建实例、respond 进去、跟着跑到收尾并落库，用户刷新就能看到那一轮的答案，
 * 而不是「已批准」之后什么都没发生。
 *
 * <p>分工：respond <b>同步</b>做（控制器要当场知道这次裁决有没有接上，接不上才如实报失效），
 * 续跑<b>异步</b>做（可能跑几分钟，不能占住那次 HTTP 请求）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxResumeHandler {

    private final FrameworkSessions sessions;
    private final AgentxContinuationOpener continuationOpener;
    private final AgentxResumeStarter resumeStarter;

    /** 重启后接回一条待办并把它跑完；返回接续出来的任务（null = 接不动）。 */
    public AgentSession.Task resume(String sessionId, String userId, String turnId, String requestId,
                                    String kind, boolean approved, String reason, String answer) {
        if (requestId == null) return null;
        AgentxFollowing f = continuationOpener.open(sessionId, userId, turnId, false, null, false, null);
        if (f == null) return null;
        AgentSession.Task task;
        try {
            task = sessions.respondRaw(sessions.key(f.cmd()), requestId, kind, approved, reason, answer);
        } catch (Exception e) {
            log.warn("[agentx] 重启后 respond 失败 {} session={}: {}", requestId, sessionId, e.toString());
            return null;
        }
        if (task == null) return null;
        resumeStarter.start(f, task, () -> {}, false);
        return task;
    }

    /** 卡片已答复、但这一轮没有活着的跟随者：补起一个跟到收尾。 */
    public void follow(String sessionId, String userId, String turnId, boolean recover, String guestUid) {
        String sessionKey = (userId == null || userId.isBlank() ? "guest" : userId) + "|" + sessionId;
        if (sessions.hasFollower(sessionKey)) {
            // 必须说出来：扫描器报「已交给引擎接续」而这里因为租约还亮着（可能属于一个刚被硬杀、
            // 来不及释放的副本）直接不看，用户的屏幕上就还是那条没人回答的问题。
            log.info("[agentx] 这一轮已有跟随者（或上一副本的租约未到期），本轮不重复接 session={} turn={}",
                    sessionId, turnId);
            return;
        }
        AgentxFollowing f = continuationOpener.open(sessionId, userId, turnId, recover, guestUid, false, null);
        if (f == null) {
            // 接不动必须说：扫描器报「已交给引擎接续 N 条」而这里悄悄返回 null，
            // 用户看到的就是「扫到了、也交接了、什么都没发生」。
            log.warn("[agentx] 这一轮接不回来（模型解析不到或会话重建失败）session={} turn={} user={}",
                    sessionId, turnId, userId);
            return;
        }
        resumeStarter.start(f, com.wenqu.ai.agentx.turn.TurnSessionGateway.taskForTurn(f.session(), turnId), () -> {}, false);
    }
}
