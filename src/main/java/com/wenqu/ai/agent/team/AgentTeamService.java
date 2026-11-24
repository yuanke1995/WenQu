package com.wenqu.ai.agent.team;

import com.wenqu.ai.model.Session;
import com.wenqu.ai.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 智能体团队的「成员生命周期」薄服务：成员树读取、在线状态、叫停、整树收束与会话级开关。
 *
 * <p>框架化后，任务板 / 邮箱 / 唤醒 / 系统提示全部交给 AgentScope {@code teamsMode}
 * （{@code TeamsMiddleware} + {@code TeamTool} + {@code TeamClient}），本类只保留框架不覆盖、
 * 但问渠会话模型必须自己做的几件事：成员是 {@code c_ai_session(kind=team)} 行、在线态来自
 * {@code FrameworkSessions} 活实例、叫停走问渠轮运行时。旧的 {@code sendMessage/recentMessages/
 * waitForMessage/team_*} 一律已删除（不是死代码保留）。
 *
 * @author yuanke
 */
@Service
public class AgentTeamService {

    private static final Logger log = LoggerFactory.getLogger(AgentTeamService.class);

    public record Member(String sessionId, String name, String parentId, String rootId, String state,
                         String agentId, String memberName, String agentName, String role) {
        /** 兼容旧签名（仅显示用）。 */
        public Member(String sessionId, String name, String parentId, String rootId, String state) {
            this(sessionId, name, parentId, rootId, state, null, name, null, null);
        }
    }

    private final SessionService sessionService;
    private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;
    private final com.wenqu.ai.service.ChatRunBus chatRunBus;
    private final com.wenqu.ai.agent.chat.ChatRunRuntime chatRunRuntime;

    public AgentTeamService(SessionService sessionService,
                            com.wenqu.ai.agentx.FrameworkSessions frameworkSessions,
                            com.wenqu.ai.service.ChatRunBus chatRunBus,
                            com.wenqu.ai.agent.chat.ChatRunRuntime chatRunRuntime) {
        this.sessionService = sessionService;
        this.frameworkSessions = frameworkSessions;
        this.chatRunBus = chatRunBus;
        this.chatRunRuntime = chatRunRuntime;
    }

    /** 根会话是否开启团队。 */
    public boolean enabled(String rootSessionId) {
        return sessionService.isTeamEnabled(rootSessionId);
    }

    /**
     * 设置会话团队开关，并强制重建该会话的常驻实例——团队工具与团队提示词都是在 build 期
     * 装配的，开关一变不重建，新工具永远挂不上。
     * <p>关闭时整棵树都要在下一步失去团队工具面：叫停并逐出全部在飞成员实例；成员会话与任务板保留
     * （对齐 DSH「关掉只隐藏工具、保留数据」）。
     */
    public void setEnabled(String uid, String sessionId, boolean enabled) {
        sessionService.setTeamEnabled(uid, sessionId, enabled);
        try { frameworkSessions.evictSession(uid, sessionId); } catch (Exception ignored) { }
        if (!enabled) {
            try {
                for (Member m : listMembers(sessionId, uid)) {
                    interruptMember(uid, m.sessionId());
                    try { frameworkSessions.evictSession(uid, m.sessionId()); } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }
    }

    /** 成员列表（不含根会话）＋在线状态（来自进程内活实例表）。 */
    public List<Member> listMembers(String rootSessionId, String uid) {
        List<Member> out = new ArrayList<>();
        if (rootSessionId == null || rootSessionId.isBlank()) return out;
        for (Session s : sessionService.listTeamMembers(rootSessionId)) {
            String state = s.getMemberState() == null ? "OFFLINE" : s.getMemberState();
            // 「正在执行」以真实在跑的轮为准（唤醒轮不经过分支执行器，不会写 member_state）。
            // 若只按框架实例是否常驻判断，成员跑完后实例仍 held，会永远显示“执行中”。
            boolean running;
            try {
                running = chatRunRuntime.turnIdOf(s.getId()) != null;
            } catch (Exception e) {
                running = false;
            }
            boolean warm = frameworkSessions.peek(uid, s.getId()) != null;
            if (running) {
                state = "RUNNING";
            } else if ("STOPPED".equals(state)) {
                // 显式终态保留
            } else if (warm) {
                state = "IDLE";
            } else if ("RUNNING".equals(state)) {
                // 上一轮结束没回写：按空闲处理，别让面板一直转
                state = "IDLE";
            }
            out.add(new Member(s.getId(), s.getMemberName(), s.getParentSessionId(),
                    s.getRootSessionId(), state, s.getAgentId(), s.getMemberName(),
                    s.getAgentName(), roleOf(s)));
        }
        out.sort(Comparator.comparing(Member::name, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    /** 成员的「职责」副标题：取该成员会话的任务摘要（首条问题落到 title）。 */
    private String roleOf(Session s) {
        String t = s.getTitle();
        if (t == null || t.isBlank()) return null;
        t = t.strip();
        return t.length() > 42 ? t.substring(0, 42) + "…" : t;
    }

    /** 成员开始一轮（分支刚创建/唤醒时调用）。 */
    public void markRunning(String memberSessionId) {
        sessionService.updateMemberState(memberSessionId, "RUNNING");
        emitMemberState(memberSessionId, "RUNNING");
    }

    /** 成员跑完回到 IDLE。 */
    public void markIdle(String memberSessionId) {
        sessionService.updateMemberState(memberSessionId, "IDLE");
        emitMemberState(memberSessionId, "IDLE");
    }

    /** 成员状态变化推给根会话的所有前端 tap（在线面板实时刷新）；失败只记日志。 */
    private void emitMemberState(String memberSessionId, String state) {
        try {
            Session s = sessionService.sessionById(memberSessionId);
            if (s == null || s.getRootSessionId() == null || s.getRootSessionId().isBlank()) return;
            String json = com.alibaba.fastjson2.JSON.toJSONString(
                    java.util.Map.of("sessionId", memberSessionId,
                            "name", s.getMemberName() == null ? "" : s.getMemberName(),
                            "state", state));
            chatRunBus.publishToSession(s.getRootSessionId(), "team_member", json);
        } catch (Exception e) {
            log.debug("[TEAM] 成员状态事件推送失败 session={}: {}", memberSessionId, e.toString());
        }
    }

    /** 祖先叫停一名成员当前轮：只停本轮，排队消息保留，状态落 STOPPED。 */
    public boolean interruptMember(String uid, String memberSessionId) {
        Session s = sessionService.sessionById(memberSessionId);
        if (s == null || !SessionService.KIND_TEAM.equals(s.getKind())) return false;
        chatRunRuntime.stopTurn(memberSessionId, uid);
        frameworkSessions.markPendingTerminal(uid, memberSessionId, "STOPPED");
        sessionService.updateMemberState(memberSessionId, "STOPPED");
        emitMemberState(memberSessionId, "STOPPED");
        return true;
    }

    /** 根会话结束：叫停整棵树的在线成员，并全部标 STOPPED。 */
    public int drain(String uid, String rootSessionId) {
        int n = 0;
        for (Member m : listMembers(rootSessionId, uid)) {
            if (!"OFFLINE".equals(m.state()) && !"STOPPED".equals(m.state())) {
                if (interruptMember(uid, m.sessionId())) n++;
            }
        }
        return n;
    }
}
