package com.wenqu.ai.agent.team;

import com.wenqu.ai.model.Session;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.SessionEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体团队的成员树与消息服务（会话级开关，不做系统配置）。
 *
 * <p>一棵树的根是开启团队模式的用户会话；成员是 {@code kind=team} 的持久会话。
 * 本服务只做「发现 / 寻址 / 消息 / 状态」，真正的执行仍走问答管线——与
 * {@code SubAgentBranchRuntime} 同一个 TurnRunner 接缝，避免 ChatService ↔ 团队服务的循环依赖。
 *
 * @author yuanke
 */
@Service
public class AgentTeamService {

    private static final Logger log = LoggerFactory.getLogger(AgentTeamService.class);

    /** 与 SubAgentBranchRuntime 共用的「起一轮」接缝，由问答侧绑定。 */
    public interface TurnRunner {
        void run(com.wenqu.ai.agent.chat.ChatCommand cmd,
                 org.springframework.web.servlet.mvc.method.annotation.SseEmitter sink);
    }

    public record Member(String sessionId, String name, String parentId, String rootId, String state,
                         String agentId, String memberName, String agentName, String role) {
        /** 兼容旧签名（仅显示用）。 */
        public Member(String sessionId, String name, String parentId, String rootId, String state) {
            this(sessionId, name, parentId, rootId, state, null, name, null, null);
        }
    }

    private final SessionService sessionService;
    private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;
    private final SessionEventService eventService;
    private final com.wenqu.ai.service.ChatRunBus chatRunBus;
    private final com.wenqu.ai.agent.chat.ChatRunRuntime chatRunRuntime;

    private volatile TurnRunner turnRunner;

    public AgentTeamService(SessionService sessionService,
                            com.wenqu.ai.agentx.FrameworkSessions frameworkSessions,
                            SessionEventService eventService,
                            com.wenqu.ai.service.ChatRunBus chatRunBus,
                            com.wenqu.ai.agent.chat.ChatRunRuntime chatRunRuntime) {
        this.sessionService = sessionService;
        this.frameworkSessions = frameworkSessions;
        this.eventService = eventService;
        this.chatRunBus = chatRunBus;
        this.chatRunRuntime = chatRunRuntime;
    }

    public void bindTurnRunner(TurnRunner runner) {
        this.turnRunner = runner;
    }

    /** 根会话是否开启团队。 */
    public boolean enabled(String rootSessionId) {
        return sessionService.isTeamEnabled(rootSessionId);
    }

    /**
     * 设置会话团队开关，并强制重建该会话的常驻实例——团队工具与团队提示词都是在 build 期
     * 装配的，开关一变不重建，新工具永远挂不上。
     */
    public void setEnabled(String uid, String sessionId, boolean enabled) {
        sessionService.setTeamEnabled(uid, sessionId, enabled);
        try { frameworkSessions.evictSession(uid, sessionId); } catch (Exception ignored) { }
    }

    /** 取根会话 id：成员轮带 teamRootSessionId，根轮就是自己的 sessionId。 */
    public String rootOf(String sessionId) {
        Session s = sessionService.sessionById(sessionId);
        if (s == null) return sessionId;
        if (s.getRootSessionId() != null && !s.getRootSessionId().isBlank()) return s.getRootSessionId();
        return s.getId();
    }

    /** 成员列表（不含根会话）＋在线状态（来自进程内活实例表）。 */
    public List<Member> listMembers(String rootSessionId, String uid) {
        List<Member> out = new ArrayList<>();
        if (rootSessionId == null || rootSessionId.isBlank()) return out;
        for (Session s : sessionService.listTeamMembers(rootSessionId)) {
            String state = s.getMemberState() == null ? "OFFLINE" : s.getMemberState();
            boolean live = frameworkSessions.peek(uid, s.getId()) != null;
            if (live && ("OFFLINE".equals(state) || "IDLE".equals(state))) state = "RUNNING";
            out.add(new Member(s.getId(), s.getMemberName(), s.getParentSessionId(),
                    s.getRootSessionId(), state, s.getAgentId(), s.getMemberName(),
                    s.getAgentName(), roleOf(s)));
        }
        out.sort(Comparator.comparing(Member::name, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    /**
     * 成员的「职责」副标题：取该成员会话的任务摘要（首条问题落到 title，取自派活那句话）。
     * 团队面板用它替代裸露的 sessionId，让每一行能看出「这一位在干什么」。
     * 过长按字符截断（中文按字算，不做字节截断，避免半个字）；空则返回 null 由前端兜底。
     */
    private String roleOf(Session s) {
        String t = s.getTitle();
        if (t == null || t.isBlank()) return null;
        t = t.strip();
        return t.length() > 42 ? t.substring(0, 42) + "…" : t;
    }

    /** lead=根，全路径/短名/sessionId 原样返回（本树内解析由调用方结合 listMembers 完成）。 */
    public String resolveName(String rootSessionId, String name) {
        if (name == null || name.isBlank()) return "";
        return "lead".equals(name.trim()) ? rootSessionId : name.trim();
    }

    /**
     * 向成员发送消息。在线成员走框架 steer（运行中下一模型步读到），离线成员落账本事件并标 WAITING。
     * 返回 true 表示消息已被成员会话接受；false 表示目标不是本树成员。
     */
    public boolean sendMessage(String uid, String rootId, String fromSessionId, String toName, String text) {
        if (text == null || text.isBlank()) return false;
        String target = resolveName(rootId, toName);
        if (target.equals(rootId)) return false;
        Session member = sessionService.findTeamMember(rootId, target);
        if (member == null) {
            log.info("[TEAM] 消息目标不在成员树: root={} target={}", rootId, target);
            return false;
        }
        // 目标成员若正挂在 team_wait 上：用这条消息直接恢复它挂起的那一轮（框架 pending → respond）。
        String requestId = frameworkSessions.pendingRequestIdOf(uid, member.getId());
        if (requestId != null) {
            persistMessage(uid, member.getId(), fromSessionId, text);
            String sessionKey = (uid == null || uid.isBlank() ? "guest" : uid) + "|" + member.getId();
            frameworkSessions.respondRaw(sessionKey, requestId, "ask", false, null,
                    "来自团队成员 " + shortName(fromSessionId) + " 的消息：\n" + text);
            sessionService.updateMemberState(member.getId(), "RUNNING");
            emitMemberState(member.getId(), "RUNNING");
            return true;
        }
        String receipt = frameworkSessions.steer(uid, member.getId(),
                "来自团队成员 " + shortName(fromSessionId) + " 的消息：\n" + text);
        if (receipt != null) {
            persistMessage(uid, member.getId(), fromSessionId, text);
            sessionService.updateMemberState(member.getId(), "RUNNING");
            emitMemberState(member.getId(), "RUNNING");
            return true;
        }
        Long eventId = persistMessage(uid, member.getId(), fromSessionId, text);
        sessionService.updateMemberState(member.getId(), "WAITING");
        emitMemberState(member.getId(), "WAITING");
        if (eventId != null) sessionService.advanceReadCursor(member.getId(), eventId);

        // 空闲/离线成员：落事件之后真的起一轮把它唤醒（否则消息只躺在收件箱里没人读）。
        // 与 BranchRunner 同一个 TurnRunner 接缝，由问答侧绑定；未绑定时保留降级（只落事件）。
        TurnRunner runner = turnRunner;
        if (runner == null) return true;
        String memberSession = member.getId();
        String memberAgent = member.getAgentId();
        String root = member.getRootSessionId() == null || member.getRootSessionId().isBlank()
                ? rootId : member.getRootSessionId();
        String wakeText = "团队消息唤醒（来自 " + shortName(fromSessionId) + "）：\n" + text
                + "\n\n请先处理这条消息；若它包含新任务，按任务执行并回报。";
        com.wenqu.ai.service.CollectingSseEmitter sink = new com.wenqu.ai.service.CollectingSseEmitter();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                runner.run(com.wenqu.ai.agent.chat.ChatCommand.builder()
                        .sessionId(memberSession)
                        .question(wakeText)
                        .userId(uid)
                        .userImages(java.util.List.of())
                        .attachments(java.util.List.of())
                        .skills(java.util.List.of())
                        .agentId(memberAgent)
                        .teamMode(true)
                        .memberSessionId(memberSession)
                        .memberName(member.getMemberName())
                        .teamRootSessionId(root)
                        .source("team")
                        .build(), sink);
            } catch (Exception e) {
                log.warn("[TEAM] 唤醒成员失败 session={}: {}", memberSession, e.toString());
            } finally {
                markIdle(memberSession);
            }
        }, java.util.concurrent.ForkJoinPool.commonPool());
        return true;
    }

    /** 落一条团队消息事件（发/收都记在目标成员会话上），返回事件 id 供游标用。 */
    private Long persistMessage(String uid, String memberSessionId, String fromSessionId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "team_message");
        payload.put("from", fromSessionId);
        payload.put("to", memberSessionId);
        payload.put("text", text);
        return eventService.appendLarge(memberSessionId, uid, null, "team_message",
                com.alibaba.fastjson2.JSON.toJSONString(payload));
    }

    /** 最近团队消息（跨成员聚合，按事件 id 倒序取前 N 条）；给团队面板「成员消息」卡用。 */
    public List<Map<String, Object>> recentMessages(String uid, String rootSessionId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rootSessionId == null || rootSessionId.isBlank()) return out;
        for (Session m : sessionService.listTeamMembers(rootSessionId)) {
            try {
                for (com.wenqu.ai.model.SessionEvent row : eventService.listRecent(m.getId(), uid, 30)) {
                    if (!"team_message".equals(row.getType()) || row.getPayload() == null) continue;
                    com.alibaba.fastjson2.JSONObject jo =
                            com.alibaba.fastjson2.JSON.parseObject(row.getPayload());
                    if (jo == null) continue;
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("id", row.getId());
                    one.put("from", shortName(jo.getString("from")));
                    one.put("to", m.getMemberName() == null ? m.getId() : m.getMemberName());
                    one.put("text", jo.getString("text"));
                    one.put("at", row.getCreatedAt() == null ? "" : row.getCreatedAt().toString());
                    out.add(one);
                }
            } catch (Exception ignored) { /* 单个成员读失败不影响整体 */ }
        }
        out.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("id", 0L)).longValue(),
                ((Number) a.getOrDefault("id", 0L)).longValue()));
        return out.size() > limit ? out.subList(0, Math.max(0, limit)) : out;
    }

    /** 成员开始一轮（分支刚创建/唤醒时调用）。 */
    public void markRunning(String memberSessionId) {        sessionService.updateMemberState(memberSessionId, "RUNNING");
        emitMemberState(memberSessionId, "RUNNING");
    }

    /** 成员跑完回到 IDLE。 */
    public void markIdle(String memberSessionId) {
        sessionService.updateMemberState(memberSessionId, "IDLE");
        emitMemberState(memberSessionId, "IDLE");
    }

    /** 成员状态变化推给根会话的所有前端 tap（在线面板实时刷新）；失败只记日志，不影响主链路。 */
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

    /** 未读消息及其游标：text=拼接好的文本，cursor=应推进到的事件 id（0=没有新消息）。 */
    public record PendingMessages(String text, long cursor) {}

    /**
     * 取成员尚未读到的团队消息（游标之后、最近若干条，全文），供成员再次开轮时作为上下文注入。
     * 游标落在会话行的 last_read_event_id，由调用方在成功注入后推进，避免重复注入。
     */
    public PendingMessages pendingMessagesWithCursor(String memberSessionId, String uid) {
        if (memberSessionId == null || memberSessionId.isBlank()) return new PendingMessages("", 0L);
        try {
            long last = sessionService.lastReadEventId(memberSessionId);
            List<com.wenqu.ai.model.SessionEvent> rows = eventService.listRecent(memberSessionId, uid, 50);
            StringBuilder sb = new StringBuilder();
            long max = last;
            for (com.wenqu.ai.model.SessionEvent row : rows) {
                if (row.getId() == null || row.getId() <= last) continue;
                if (!"team_message".equals(row.getType())) continue;
                String payload = row.getPayload() == null ? "" : row.getPayload();
                com.alibaba.fastjson2.JSONObject jo = com.alibaba.fastjson2.JSON.parseObject(payload);
                if (jo == null) continue;
                String from = jo.getString("from");
                String text = jo.getString("text");
                if (text == null || text.isBlank()) continue;
                sb.append("- 来自 ").append(shortName(from)).append("：").append(text).append('\n');
                max = Math.max(max, row.getId());
            }
            return new PendingMessages(sb.toString().stripTrailing(), sb.length() == 0 ? last : max);
        } catch (Exception e) {
            log.warn("[TEAM] 读成员未读消息失败 session={}: {}", memberSessionId, e.toString());
            return new PendingMessages("", 0L);
        }
    }

    /** 兼容旧调用（只要文本）。 */
    public String pendingMessages(String memberSessionId, String uid) {
        return pendingMessagesWithCursor(memberSessionId, uid).text();
    }

    /** 消息前缀用的短名。 */
    public String shortName(String sessionId) {
        if (sessionId == null) return "unknown";
        if ("lead".equals(sessionId)) return "lead";
        Session s = sessionService.sessionById(sessionId);
        if (s != null && s.getMemberName() != null && !s.getMemberName().isBlank()) {
            String n = s.getMemberName();
            return n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
        }
        // 根会话/非团队会话认领时：显示智能体名（没有就回退「问渠」），不要暴露会话短 id
        if (s != null && !com.wenqu.ai.service.SessionService.KIND_TEAM.equals(s.getKind())) {
            if (s.getAgentName() != null && !s.getAgentName().isBlank()) return s.getAgentName();
            return "问渠";
        }
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    /**
     * 祖先叫停一名成员当前轮：只停本轮，排队消息保留，状态落 STOPPED。
     * 权限由调用方（祖先链或根）校验；这里只执行。
     * @return true=发出停止请求（目标不存在也按 ok 处理，对齐 DSH 的空操作语义）
     */
    public boolean interruptMember(String uid, String memberSessionId) {
        Session s = sessionService.sessionById(memberSessionId);
        if (s == null || !com.wenqu.ai.service.SessionService.KIND_TEAM.equals(s.getKind())) return false;
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

    /**
     * 等待当前成员的下一条消息。真正的 inbox 等待由框架 / wait_agent 承接；
     * 这里只做成员身份校验，返回占位语义供工具层渲染。
     */
    public String waitForMessage(String sessionId) {
        Session s = sessionService.sessionById(sessionId);
        return (s != null && com.wenqu.ai.service.SessionService.KIND_TEAM.equals(s.getKind()))
                ? "wait" : "not-member";
    }
}
