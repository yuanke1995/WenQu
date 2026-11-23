package com.wenqu.ai.agent.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.TeamTaskEdgeMapper;
import com.wenqu.ai.mapper.TeamTaskEventMapper;
import com.wenqu.ai.mapper.TeamTaskMapper;
import com.wenqu.ai.model.TeamTask;
import com.wenqu.ai.model.TeamTaskEdge;
import com.wenqu.ai.model.TeamTaskEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体团队任务板（DAG）：任务实体、依赖边、CAS 认领/完成、依赖解锁与事件时间线。
 *
 * <p>状态词唯一口径：PENDING（前置未满足）/ READY（可认领）/ IN_PROGRESS / COMPLETED / FAILED / CANCELLED。
 * 完成前置时在同一次事务里把后继 blocked_count 减一，归零即 READY——这是 DAG 解锁的唯一出口。
 * 租户过滤由 MyBatis-Plus 的 TenantLineHandler 统一拼，业务代码不写 tenant_id。
 *
 * @author yuanke
 */
@Service
public class TeamTaskService {

    /** 任务状态词（看板/工具/事件统一用这一组）。 */
    public static final String PENDING = "PENDING";
    public static final String READY = "READY";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final TeamTaskMapper taskMapper;
    private final TeamTaskEdgeMapper edgeMapper;
    private final TeamTaskEventMapper eventMapper;
    private final com.wenqu.ai.service.ChatRunBus chatRunBus;

    public TeamTaskService(TeamTaskMapper taskMapper, TeamTaskEdgeMapper edgeMapper,
                           TeamTaskEventMapper eventMapper, com.wenqu.ai.service.ChatRunBus chatRunBus) {
        this.taskMapper = taskMapper;
        this.edgeMapper = edgeMapper;
        this.eventMapper = eventMapper;
        this.chatRunBus = chatRunBus;
    }

    public record TaskView(String id, String subject, String description, String state,
                           String ownerSessionId, String ownerName, int blockedCount,
                           String result, long version) {}

    private TaskView view(TeamTask t) {
        return new TaskView(t.getId(), t.getSubject(), t.getDescription(), t.getState(),
                t.getOwnerSessionId(), t.getOwnerName(),
                t.getBlockedCount() == null ? 0 : t.getBlockedCount(),
                t.getResult(), t.getVersion() == null ? 0L : t.getVersion());
    }

    private TeamTask load(String rootId, String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        TeamTask t = taskMapper.selectById(taskId);
        return (t != null && t.getRootSessionId() != null && t.getRootSessionId().equals(rootId)) ? t : null;
    }

    /**
     * 把「任务标题或 id」解析成任务 id（面向模型的工具用标题，避免暴露 UUID）。
     * 先按 id 直取，再按标题精确匹配；找不到返回 null。标题在同一任务板内唯一。
     */
    public String resolveTaskId(String rootId, String subjectOrId) {
        if (subjectOrId == null || subjectOrId.isBlank()) return null;
        String s = subjectOrId.trim();
        TeamTask byId = load(rootId, s);
        if (byId != null) return byId.getId();
        for (TeamTask x : taskMapper.selectList(new LambdaQueryWrapper<TeamTask>()
                .eq(TeamTask::getRootSessionId, rootId)
                .orderByAsc(TeamTask::getCreatedAt))) {
            if (s.equals(x.getSubject())) return x.getId();
        }
        return null;
    }

    private String tenant() {
        String t = com.wenqu.ai.util.TenantContext.current();
        return t == null || t.isBlank() ? "default" : t;
    }

    private void event(String taskId, String kind, String detail) {
        TeamTaskEvent e = new TeamTaskEvent();
        e.setTenantId(tenant());
        e.setTaskId(taskId);
        e.setKind(kind);
        e.setDetail(detail == null || detail.length() <= 480 ? detail : detail.substring(0, 480));
        eventMapper.insert(e);
    }

    private void push(String rootId, String type, Object payload) {
        try {
            chatRunBus.publishToSession(rootId, type,
                    com.alibaba.fastjson2.JSON.toJSONString(payload));
        } catch (Exception ignored) { /* 事件是旁路，失败不影响任务状态 */ }
    }

    /** 建任务：没有依赖时直接 READY。 */
    @Transactional
    public TaskView create(String rootId, String subject, String description) {
        if (rootId == null || rootId.isBlank() || subject == null || subject.isBlank()) return null;
        TeamTask t = new TeamTask();
        t.setId(UUID.randomUUID().toString().replace("-", ""));
        t.setTenantId(tenant());
        t.setRootSessionId(rootId);
        t.setSubject(subject.length() > 500 ? subject.substring(0, 500) : subject);
        t.setDescription(description);
        t.setState(READY);
        t.setBlockedCount(0);
        t.setVersion(0L);
        taskMapper.insert(t);
        event(t.getId(), "created", t.getSubject());
        push(rootId, "team_task", Map.of("taskId", t.getId(), "state", READY, "subject", t.getSubject()));
        return view(t);
    }

    /** 加依赖 from→to（禁止自环、成环）。 */
    @Transactional
    public String depend(String rootId, String fromId, String toId) {
        TeamTask from = load(rootId, fromId);
        TeamTask to = load(rootId, toId);
        if (from == null || to == null) return "任务不存在或不属于本团队";
        if (fromId.equals(toId)) return "不能给自己加依赖";
        if (reaches(rootId, toId, fromId)) return "依赖会成环";
        Long exists = edgeMapper.selectCount(new LambdaQueryWrapper<TeamTaskEdge>()
                .eq(TeamTaskEdge::getFromTaskId, fromId).eq(TeamTaskEdge::getToTaskId, toId));
        if (exists != null && exists > 0) return "依赖已存在";
        TeamTaskEdge e = new TeamTaskEdge();
        e.setId(UUID.randomUUID().toString().replace("-", ""));
        e.setTenantId(tenant());
        e.setRootSessionId(rootId);
        e.setFromTaskId(fromId);
        e.setToTaskId(toId);
        edgeMapper.insert(e);
        if (!COMPLETED.equals(from.getState())) {
            to.setBlockedCount((to.getBlockedCount() == null ? 0 : to.getBlockedCount()) + 1);
            to.setState(PENDING);
            taskMapper.updateById(to);
        }
        event(toId, "depended", "「" + to.getSubject() + "」依赖「" + from.getSubject() + "」");
        push(rootId, "team_task", Map.of("taskId", toId, "state", to.getState(),
                "blockedCount", to.getBlockedCount()));
        return null; // null = 成功
    }

    /** 从 fromId 出发能否走到 targetId（DFS 判环，限制规模）。 */
    private boolean reaches(String rootId, String fromId, String targetId) {
        List<TeamTaskEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<TeamTaskEdge>()
                .eq(TeamTaskEdge::getRootSessionId, rootId));
        Map<String, List<String>> adj = new HashMap<>();
        for (TeamTaskEdge e : edges) adj.computeIfAbsent(e.getFromTaskId(), k -> new ArrayList<>()).add(e.getToTaskId());
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        stack.push(fromId);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 1000) {
            String cur = stack.pop();
            if (!seen.add(cur)) continue;
            if (cur.equals(targetId)) return true;
            for (String nxt : adj.getOrDefault(cur, List.of())) stack.push(nxt);
        }
        return false;
    }

    /** 认领：CAS 保证并发下只有一个人拿到。 */
    @Transactional
    public String claim(String rootId, String taskId, String memberSessionId, String memberName) {
        TeamTask t = load(rootId, taskId);
        if (t == null) return "任务不存在";
        if (!READY.equals(t.getState())) return "任务当前状态 " + t.getState() + " 不可认领";
        int n = taskMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TeamTask>()
                .eq(TeamTask::getId, taskId)
                .eq(TeamTask::getVersion, t.getVersion())
                .eq(TeamTask::getState, READY)
                .set(TeamTask::getState, IN_PROGRESS)
                .set(TeamTask::getOwnerSessionId, memberSessionId)
                .set(TeamTask::getOwnerName, memberName)
                .set(TeamTask::getVersion, t.getVersion() + 1));
        if (n == 0) return "任务刚被其他人认领，请重新查看";
        event(taskId, "claimed", memberName == null ? memberSessionId : memberName);
        push(rootId, "team_task", Map.of("taskId", taskId, "state", IN_PROGRESS, "owner", memberName));
        return null;
    }

    @Transactional
    public String complete(String rootId, String taskId, String result, boolean failed) {
        TeamTask t = load(rootId, taskId);
        if (t == null) return "任务不存在";
        String target = failed ? FAILED : COMPLETED;
        int n = taskMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TeamTask>()
                .eq(TeamTask::getId, taskId)
                .eq(TeamTask::getVersion, t.getVersion())
                .set(TeamTask::getState, target)
                .set(TeamTask::getResult, result)
                .set(TeamTask::getVersion, t.getVersion() + 1));
        if (n == 0) return "任务刚被其他人改动，请重新查看";
        event(taskId, failed ? "failed" : "completed", result);
        push(rootId, "team_task", Map.of("taskId", taskId, "state", target));
        if (!failed) unlockDownstream(rootId, taskId);
        return null;
    }

    /** 完成后解锁直接后继：blocked_count-1，归零置 READY。 */
    private void unlockDownstream(String rootId, String doneId) {
        TeamTask doneTask = load(rootId, doneId);
        String doneLabel = doneTask == null || doneTask.getSubject() == null ? "前置任务" : ("「" + doneTask.getSubject() + "」");
        List<TeamTaskEdge> outs = edgeMapper.selectList(new LambdaQueryWrapper<TeamTaskEdge>()
                .eq(TeamTaskEdge::getFromTaskId, doneId));
        for (TeamTaskEdge e : outs) {
            TeamTask to = load(rootId, e.getToTaskId());
            if (to == null || !PENDING.equals(to.getState())) continue;
            int left = Math.max(0, (to.getBlockedCount() == null ? 0 : to.getBlockedCount()) - 1);
            to.setBlockedCount(left);
            if (left == 0) to.setState(READY);
            taskMapper.updateById(to);
            event(to.getId(), left == 0 ? "ready" : "blocked", doneLabel + "已完成，剩余 " + left);
            push(rootId, "team_task", Map.of("taskId", to.getId(), "state", to.getState(), "blockedCount", left));
        }
    }

    /** 图视图：节点 + 边 + 每个节点的事件时间线（最近 30 条）。 */
    public Map<String, Object> graph(String rootId) {
        List<TeamTask> rows = taskMapper.selectList(new LambdaQueryWrapper<TeamTask>()
                .eq(TeamTask::getRootSessionId, rootId).orderByAsc(TeamTask::getCreatedAt));
        List<TaskView> nodes = new ArrayList<>();
        for (TeamTask t : rows) nodes.add(view(t));
        List<Map<String, String>> edges = new ArrayList<>();
        for (TeamTaskEdge e : edgeMapper.selectList(new LambdaQueryWrapper<TeamTaskEdge>()
                .eq(TeamTaskEdge::getRootSessionId, rootId))) {
            edges.add(Map.of("from", e.getFromTaskId(), "to", e.getToTaskId()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** 某节点的事件时间线。 */
    public List<Map<String, Object>> events(String rootId, String taskId) {
        if (load(rootId, taskId) == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TeamTaskEvent e : eventMapper.selectList(new LambdaQueryWrapper<TeamTaskEvent>()
                .eq(TeamTaskEvent::getTaskId, taskId).orderByAsc(TeamTaskEvent::getId).last("LIMIT 30"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.getKind());
            m.put("detail", e.getDetail());
            m.put("at", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }
}
