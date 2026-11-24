package com.wenqu.ai.agentx.team;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.team.TeamTaskEventService;
import com.wenqu.ai.service.ChatRunBus;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamInfo;
import io.agentscope.harness.agent.team.TeamMemberInfo;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 框架 {@link TeamClient} 的薄装饰器：<b>不改变任何团队语义</b>，只把任务/消息的每次变更
 * 投影成两件事——一条任务事件账本（供时间线）与一条 {@code team_task}/{@code team_message}
 * 运行事件（供面板实时刷新）。
 *
 * <p>为什么用装饰器而不是改框架：任务板、CAS、依赖判定、解锁全部在 {@code LocalTeamClient} 里，
 * 问渠只需要「旁听」它变了什么。{@code LocalTeamClient} 本身是 final，故用 {@code TeamClient}
 * 接口包一层，逐一委派，只在任务/消息方法上挂 {@code doOnNext} 旁路。
 *
 * <p>本类不做任何状态判定，也不缓存；账本写失败只影响时间线，不影响任务本身。
 *
 * @author yuanke
 */
public class WenquTeamClient implements TeamClient {

    private final TeamClient delegate;
    private final TeamTaskEventService events;
    private final ChatRunBus bus;
    private final MemberController members;

    /** 成员生命周期回调：由网关实现（建/停/唤醒 问渠 kind=team 会话）。 */
    public interface MemberController {
        void spawn(String rootSessionId, String memberName, String agentRef);

        void shutdown(String rootSessionId, String memberName);

        /** 把消息/任务落到某成员后主动叫起它一轮（框架 TeamWakeups 依赖已建实例，新建成员拿不到，故由宿主兜底）。 */
        void wake(String rootSessionId, String memberName, String message);
    }

    public WenquTeamClient(TeamClient delegate, TeamTaskEventService events, ChatRunBus bus) {
        this(delegate, events, bus, null);
    }

    public WenquTeamClient(TeamClient delegate, TeamTaskEventService events, ChatRunBus bus,
                           MemberController members) {
        this.delegate = delegate;
        this.events = events;
        this.bus = bus;
        this.members = members;
    }

    // ---- 旁路：事件账本 + 实时事件 ----

    private void taskEvent(String root, TeamTask t, String kind, String detail) {
        if (t == null) return;
        if (events != null) events.record(root, t.taskId(), kind, detail);
        publish(root, "team_task", taskFrame(t));
    }

    private static Map<String, Object> taskFrame(TeamTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.taskId());
        m.put("subject", t.subject());
        m.put("state", t.state());
        m.put("owner", t.owner());
        return m;
    }

    private void publish(String root, String type, Map<String, Object> payload) {
        if (bus == null || root == null || root.isBlank()) return;
        try {
            bus.publishToSession(root, type, JSON.toJSONString(payload));
        } catch (Exception ignored) {
            // 实时推送失败不影响任务/消息本身
        }
    }

    private static String cut(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ---- 任务板 ----

    @Override
    public Mono<List<TeamTask>> listTasks(String namespace, String teamName) {
        return delegate.listTasks(namespace, teamName);
    }

    @Override
    public Mono<TeamTask> createTask(String namespace, String teamName, String subject,
                                     String description, List<String> blockedBy, String createdBy) {
        return delegate.createTask(namespace, teamName, subject, description, blockedBy, createdBy)
                .doOnNext(t -> taskEvent(teamName, t, "created", subject == null ? null : "创建任务：" + subject));
    }

    @Override
    public Mono<TeamTask> assignTask(String namespace, String teamName, String taskId,
                                     String owner, long version) {
        return delegate.assignTask(namespace, teamName, taskId, owner, version)
                .doOnNext(t -> {
                    taskEvent(teamName, t, "assigned", "指派给 " + owner);
                    if (owner != null && !owner.isBlank() && members != null) {
                        members.wake(teamName, owner, "你有一个新任务被指派：请查看任务板并认领后执行。");
                    }
                });
    }

    @Override
    public Mono<TeamTask> claimTask(String namespace, String teamName, String taskId,
                                    String owner, long version) {
        return delegate.claimTask(namespace, teamName, taskId, owner, version)
                .doOnNext(t -> taskEvent(teamName, t, "claimed", "由 " + owner + " 认领"));
    }

    @Override
    public Mono<TeamTask> completeTask(String namespace, String teamName, String taskId, String result) {
        return delegate.completeTask(namespace, teamName, taskId, result)
                .doOnNext(t -> taskEvent(teamName, t, "completed", cut(result, 200)));
    }

    @Override
    public Mono<TeamTask> failTask(String namespace, String teamName, String taskId, String reason) {
        return delegate.failTask(namespace, teamName, taskId, reason)
                .doOnNext(t -> taskEvent(teamName, t, "failed", cut(reason, 200)));
    }

    @Override
    public Mono<TeamTask> unclaimTask(String namespace, String teamName, String taskId, long version) {
        return delegate.unclaimTask(namespace, teamName, taskId, version)
                .doOnNext(t -> taskEvent(teamName, t, "unclaimed", "取消认领"));
    }

    @Override
    public Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName, String member) {
        return delegate.listClaimableTasks(namespace, teamName, member);
    }

    @Override
    public Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName) {
        return delegate.listClaimableTasks(namespace, teamName);
    }

    // ---- 邮箱 ----

    @Override
    public Mono<TeamMessage> sendMessage(String namespace, String teamName, String from,
                                         String to, String content) {
        return delegate.sendMessage(namespace, teamName, from, to, content)
                .doOnNext(m -> {
                    publishMessage(teamName, m);
                    // 框架的 TeamWakeups 只唤醒「已建实例」的成员；刚 spawn 的成员没有实例，故由宿主直接叫起，
                    // 并把消息正文作为这一轮的问题带进去（否则新成员收件箱是空的，不知道要做什么）。
                    if (members != null && to != null && !to.isBlank()) members.wake(teamName, to, m.content());
                });
    }

    @Override
    public Mono<List<TeamMessage>> broadcastMessage(String namespace, String teamName,
                                                    String from, String content) {
        return delegate.broadcastMessage(namespace, teamName, from, content)
                .doOnNext(list -> {
                    if (list != null) {
                        for (TeamMessage m : list) {
                            publishMessage(teamName, m);
                            if (members != null && m.to() != null && !m.to().isBlank()) members.wake(teamName, m.to(), m.content());
                        }
                    }
                });
    }

    private void publishMessage(String root, TeamMessage m) {
        if (m == null) return;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("from", m.from());
        f.put("to", m.to());
        f.put("text", cut(m.content(), 300));
        publish(root, "team_message", f);
    }

    @Override
    public Mono<List<TeamMessage>> listMessages(String namespace, String teamName, int limit) {
        return delegate.listMessages(namespace, teamName, limit);
    }

    // ---- 名册 / 团队 ----

    @Override
    public Mono<List<TeamMemberInfo>> listMembers(String namespace, String teamName) {
        return delegate.listMembers(namespace, teamName);
    }

    @Override
    public Mono<Void> spawnMember(String namespace, String teamName, String memberName,
                                  String agentRef, String sessionId) {
        // 框架 LocalTeamClient 把 spawnMember 留给「control-plane」实现；这里转成问渠真实成员会话。
        if (members == null) return delegate.spawnMember(namespace, teamName, memberName, agentRef, sessionId);
        return Mono.fromRunnable(() -> members.spawn(teamName, memberName, agentRef)).then();
    }

    @Override
    public Mono<Void> shutdownMember(String namespace, String teamName, String memberName) {
        if (members == null) return delegate.shutdownMember(namespace, teamName, memberName);
        return Mono.fromRunnable(() -> members.shutdown(teamName, memberName)).then();
    }

    @Override
    public Mono<Void> submitPlan(String namespace, String teamName, String plan, String by) {
        return delegate.submitPlan(namespace, teamName, plan, by);
    }

    @Override
    public Mono<Void> approvePlan(String namespace, String teamName, String planId) {
        return delegate.approvePlan(namespace, teamName, planId);
    }

    @Override
    public Mono<Void> rejectPlan(String namespace, String teamName, String planId) {
        return delegate.rejectPlan(namespace, teamName, planId);
    }

    @Override
    public Mono<TeamInfo> createTeam(TeamCreateSpec spec) {
        return delegate.createTeam(spec);
    }

    @Override
    public Mono<Void> completeTeam(String namespace, String teamName) {
        return delegate.completeTeam(namespace, teamName);
    }
}
