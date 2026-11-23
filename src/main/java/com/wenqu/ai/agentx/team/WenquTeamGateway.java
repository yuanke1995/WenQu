package com.wenqu.ai.agentx.team;

import com.wenqu.ai.model.Session;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.TenantContext;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberInfo;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 框架团队底座的问渠门面：把 {@link LocalTeamClient}（框架自带任务板/邮箱/名册）与
 * {@link WenquMysqlBaseStore}（持久 BaseStore）拼起来，向引擎与控制器提供「建团队 / 名册 /
 * TeamContext / 任务图 / 邮箱」这几件薄接口。
 *
 * <p>团队领域逻辑（依赖判定、CAS 认领、解锁、邮箱、唤醒）全部在框架里；本类只做
 * 命名空间拼装、DTO 形状与「成员创建时补名册」这三件宿主必须做的事。
 *
 * @author yuanke
 */
@Component
public class WenquTeamGateway {

    private static final Logger log = LoggerFactory.getLogger(WenquTeamGateway.class);

    public static final String LEAD = "lead";

    /** 框架 TeamTool 支持的动作（spawn/shutdown/submitPlan 等因 LocalTeamClient 不支持而排除）。 */
    private static final List<String> LEAD_ACTIONS = List.of(
            "listTasks", "createTask", "assignTask", "claimTask", "unclaimTask",
            "completeTask", "failTask", "listClaimableTasks",
            "sendMessage", "broadcastMessage", "listMessages", "listMembers", "completeTeam");

    private static final List<String> WORKER_ACTIONS = List.of(
            "listTasks", "listClaimableTasks", "claimTask", "unclaimTask",
            "completeTask", "failTask",
            "sendMessage", "broadcastMessage", "listMessages", "listMembers");

    private final WenquMysqlBaseStore store;
    private final SessionService sessionService;
    private final LocalTeamClient client;

    public WenquTeamGateway(WenquMysqlBaseStore store, SessionService sessionService) {
        this.store = store;
        this.sessionService = sessionService;
        // LocalTeamClient 的消息序号是实例内 AtomicLong：必须全应用单实例，否则消息 id 撞车。
        this.client = new LocalTeamClient(store);
    }

    public LocalTeamClient client() {
        return client;
    }

    private static String namespace() {
        String t = TenantContext.current();
        return (t == null || t.isBlank()) ? "default" : t;
    }

    /** 团队名 = 根会话 id（一棵树一个 team）。 */
    private static String teamName(String rootSessionId) {
        return rootSessionId;
    }

    /** 确保团队存在并把 lead 名册种下。幂等：已存在吞掉冲突。 */
    public void ensureTeam(String rootSessionId, String leadAgentRef) {
        if (rootSessionId == null || rootSessionId.isBlank()) return;
        try {
            client.createTeam(new TeamCreateSpec(teamName(rootSessionId), namespace(),
                    "团队会话 " + rootSessionId, leadAgentRef == null ? "" : leadAgentRef, "",
                    List.of())).block();
        } catch (TeamConflictException exists) {
            // 已建过：名册已存在，直接返回
        } catch (RuntimeException e) {
            log.warn("[TEAM] ensureTeam 失败 root={}: {}", rootSessionId, e.toString());
        }
    }

    /** 成员创建后补一条名册（框架 TeamClient 没有 addMember，直写 BaseStore 的成员命名空间）。 */
    public void addMember(String rootSessionId, String memberName, String agentRef, String sessionId) {
        if (rootSessionId == null || memberName == null || memberName.isBlank()) return;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("memberName", memberName);
        value.put("agentRef", agentRef == null ? "" : agentRef);
        value.put("phase", "Working");
        value.put("sessionId", sessionId == null ? "" : sessionId);
        value.put("deployMode", "byo");
        value.put("isLead", LEAD.equals(memberName));
        store.put(List.of("teams", namespace(), teamName(rootSessionId), "members"), memberName, value);
    }

    /** 构造某会话的 TeamContext（根=lead，成员=worker）。 */
    public TeamContext contextFor(String rootSessionId, String myRole, boolean isLead) {
        List<TeamContext.MemberSnapshot> snaps = new ArrayList<>();
        try {
            for (TeamMemberInfo m : client.listMembers(namespace(), teamName(rootSessionId)).block()) {
                snaps.add(new TeamContext.MemberSnapshot(m.memberName(), m.agentRef(), m.phase()));
            }
        } catch (RuntimeException e) {
            log.debug("[TEAM] 读名册失败 root={}: {}", rootSessionId, e.toString());
        }
        return new TeamContext(teamName(rootSessionId), namespace(), "团队会话 " + rootSessionId,
                myRole == null ? WORKER_ACTIONS.get(0) : myRole, isLead, snaps,
                isLead ? LEAD_ACTIONS : WORKER_ACTIONS);
    }

    /** 成员名册（供控制器/面板）：名字、会话 id、状态、agent 名。 */
    public List<Map<String, Object>> members(String rootSessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TeamMemberInfo m : client.listMembers(namespace(), teamName(rootSessionId)).block()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", m.memberName());
                one.put("sessionId", m.sessionId());
                one.put("agentId", m.agentRef());
                one.put("state", m.phase());
                one.put("isLead", m.isLead());
                out.add(one);
            }
        } catch (RuntimeException e) {
            log.debug("[TEAM] 读名册失败 root={}: {}", rootSessionId, e.toString());
        }
        return out;
    }

    /** 任务图 DTO（{nodes,edges}，形状对齐原 TeamTaskService.graph，前端 TeamPanel 无需改）。 */
    public Map<String, Object> tasks(String rootSessionId) {
        List<TeamTask> tasks;
        try {
            tasks = client.listTasks(namespace(), teamName(rootSessionId)).block();
        } catch (RuntimeException e) {
            tasks = List.of();
        }
        if (tasks == null) tasks = List.of();
        Map<String, TeamTask> byId = new HashMap<>();
        Set<String> completed = new HashSet<>();
        for (TeamTask t : tasks) {
            byId.put(t.taskId(), t);
            if (TeamTask.COMPLETED.equals(t.state())) completed.add(t.taskId());
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        for (TeamTask t : tasks) {
            int blocked = 0;
            for (String b : t.blockedBy() == null ? List.<String>of() : t.blockedBy()) {
                edges.add(Map.of("from", b, "to", t.taskId()));
                if (!completed.contains(b)) blocked++;
            }
            String state;
            if (TeamTask.PENDING.equals(t.state()) && blocked == 0) {
                state = "READY";
            } else if (TeamTask.PENDING.equals(t.state())) {
                state = "PENDING";
            } else {
                state = t.state() == null ? "PENDING" : t.state().toUpperCase();
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", t.taskId());
            n.put("subject", t.subject());
            n.put("description", t.description());
            n.put("state", state);
            n.put("ownerName", t.owner());
            n.put("blockedCount", blocked);
            n.put("result", t.result());
            nodes.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** 最近邮箱消息（跨成员聚合，给面板消息卡）。 */
    public List<Map<String, Object>> messages(String rootSessionId, int limit) {
        List<TeamMessage> msgs;
        try {
            msgs = client.listMessages(namespace(), teamName(rootSessionId), Math.max(1, limit)).block();
        } catch (RuntimeException e) {
            msgs = List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (msgs == null) return out;
        for (TeamMessage m : msgs) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", m.id());
            one.put("from", m.from());
            one.put("to", m.to());
            one.put("text", m.content());
            one.put("at", "");
            out.add(one);
        }
        return out;
    }

    /** 面板/用户向成员发消息：走框架邮箱（写库 + TeamWakeups 唤醒）。 */
    public boolean send(String rootSessionId, String from, String to, String text) {
        if (text == null || text.isBlank()) return false;
        try {
            client.sendMessage(namespace(), teamName(rootSessionId),
                    from == null || from.isBlank() ? LEAD : from, to, text).block();
            return true;
        } catch (TeamConflictException conflict) {
            return false;
        } catch (RuntimeException e) {
            log.info("[TEAM] 发消息失败 root={} to={}: {}", rootSessionId, to, e.toString());
            return false;
        }
    }

    /** 团队已存在（名册非空）判定，供引擎决定是否种名册。 */
    public boolean teamExists(String rootSessionId) {
        try {
            List<TeamMemberInfo> ms = client.listMembers(namespace(), teamName(rootSessionId)).block();
            return ms != null && !ms.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 给两个任务加一条依赖（from 完成后 to 才可认领）。
     * <p>框架 {@code TeamTool.createTask} 不暴露 {@code blockedBy}、{@code TeamClient} 也没有独立的
     * addDependency，所以这里在框架的 BaseStore 任务条目上做一次 CAS 追加——只是暴露框架数据模型的
     * 一个缺口，不是另造一套任务板。返回 null=成功，否则错误文本。
     */
    public String addDependency(String rootSessionId, String fromRef, String toRef) {
        String fromId = resolveTaskId(rootSessionId, fromRef);
        String toId = resolveTaskId(rootSessionId, toRef);
        if (fromId == null) return "找不到前置任务「" + fromRef + "」";
        if (toId == null) return "找不到后继任务「" + toRef + "」";
        if (fromId.equals(toId)) return "不能给自己加依赖";
        List<TeamTask> tasks;
        try {
            tasks = client.listTasks(namespace(), teamName(rootSessionId)).block();
        } catch (RuntimeException e) {
            return "读取任务板失败：" + e.getMessage();
        }
        if (tasks == null) return "读取任务板失败";
        Map<String, TeamTask> byId = new HashMap<>();
        for (TeamTask t : tasks) byId.put(t.taskId(), t);
        TeamTask from = byId.get(fromId);
        TeamTask to = byId.get(toId);
        if (from == null || to == null) return "任务不存在或不属于本团队";
        if (to.blockedBy() != null && to.blockedBy().contains(fromId)) return "依赖已存在";
        // 成环检测：from 若已（传递地）依赖 to，再加 to→from 就成环
        if (dependsTransitively(byId, fromId, toId)) return "依赖会成环";
        List<String> blocked = new ArrayList<>(to.blockedBy() == null ? List.of() : to.blockedBy());
        blocked.add(fromId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", to.taskId());
        value.put("teamName", to.teamName());
        value.put("namespace", to.namespace());
        value.put("subject", to.subject());
        value.put("description", to.description());
        value.put("state", to.state());
        value.put("owner", to.owner());
        value.put("blockedBy", blocked);
        value.put("result", to.result());
        value.put("version", to.version() + 1);
        boolean ok = store.putIfVersion(
                List.of("teams", namespace(), teamName(rootSessionId), "tasks"),
                to.taskId(), value, to.version());
        return ok ? null : "任务刚被改动，请重试";
    }

    /** from 顺着 blockedBy 能否走到 target（用于成环判定）。 */
    private static boolean dependsTransitively(Map<String, TeamTask> byId, String from, String target) {
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        stack.push(from);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 1000) {
            String cur = stack.pop();
            if (!seen.add(cur)) continue;
            if (cur.equals(target)) return true;
            TeamTask t = byId.get(cur);
            if (t == null || t.blockedBy() == null) continue;
            for (String b : t.blockedBy()) stack.push(b);
        }
        return false;
    }

    /** 任务标题或 id → 任务 id（同板内标题唯一）。 */
    private String resolveTaskId(String rootSessionId, String ref) {
        if (ref == null || ref.isBlank()) return null;
        String r = ref.trim();
        try {
            List<TeamTask> tasks = client.listTasks(namespace(), teamName(rootSessionId)).block();
            if (tasks == null) return null;
            for (TeamTask t : tasks) if (r.equals(t.taskId())) return t.taskId();
            for (TeamTask t : tasks) if (r.equals(t.subject())) return t.taskId();
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }
}
