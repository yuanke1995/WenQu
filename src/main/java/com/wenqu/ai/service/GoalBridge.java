package com.wenqu.ai.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.core.state.TaskVerification;
import io.agentscope.harness.agent.HarnessAgent;
import com.wenqu.ai.model.Goal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标模式的存储桥：<b>问渠的护栏 + 框架的 tasksContext</b>（形状照 {@link TodoBridge}，别自创第二套）。
 *
 * <p>权威分工：{@code c_ai_goal} 是产品面真源（界面显示与预算判定只读它），框架的
 * {@code TaskContextState}（Scope/要求/验证）是模型视图——每轮开跑前由本类从库里覆盖写进去。
 * 单向同步，不做增量：进程重启、多副本换副本之后 {@code tasksContext} 不在这个进程里，
 * 而目标面板要能显示「与模型看到的一致吗」，所以每次写都把整份目标态刷成库里那一份。
 *
 * <p><b>三条必须记住的实测事实</b>（前两条 {@link TodoBridge} 已经踩过）：
 * <ol>
 *   <li><b>写活状态，不写 {@code session.inspect()} 那份快照</b>——后者每次从日志重放出新对象，
 *       改完即丢，症状是「写入成功、回读为空」。轮外唯一的写入口是
 *       {@code ReActAgent#updateAgentState(ctx, reason, consumer)}：它抢 writer lease、
 *       <b>有未结算工具调用直接抛</b>，所以只能在轮外（或本轮 submit 之前）调；</li>
 *   <li><b>{@code decide} 的 {@code Decision.authority} 只允许 USER/CALLER</b>，{@code sourceRef}
 *       必填非空 ≤2000 字。用户在卡片上点批准 = {@code USER} + {@code "goal-card:"+goalId}；
 *       <b>平台自动放行一律禁止</b>——不许出现「系统替用户确认验收标准」这条路；</li>
 *   <li>{@code recordVerification} 的 {@code binding} 必须<b>逐字等于</b>
 *       {@code state.getEvidenceBinding()}，且该标准必须是 {@code ACCEPTANCE_CRITERION} +
 *       {@code CONFIRMED}，否则抛。所以顺序是死的：
 *       {@code beginTask → decide → setSubjectVersion →（这一轮工具执行，action/end 落账）→ 轮外 verify()}。
 *       <b>同一轮内不能验证刚发生的动作</b>（证据还没结算），这是设计而不是 bug。</li>
 * </ol>
 *
 * <p>乐观锁：所有写都要 {@code expectedRevision}，冲突抛
 * {@link java.util.ConcurrentModificationException}。本类的写法是「每次变异前重读快照取水位」——
 * 一次回调里连着改好几处，水位每次都在变，拿开头那个旧值去改第二处必抛。
 */
@Service
public class GoalBridge {

    private static final Logger log = LoggerFactory.getLogger(GoalBridge.class);

    /** 目标来源引用前缀（进 {@code Scope.sourceRef}；用户可读，不露框架机制） */
    public static final String SOURCE_PREFIX = "wenqu-goal:";

    /** 用户确认的决策来源（进 {@code Decision.sourceRef}） */
    public static final String DECISION_SOURCE_PREFIX = "goal-card:";

    /**
     * 写入结果。失败时带一句可回给界面的原因（{@code syncWarn} 用）；
     * {@code newRequirementIds} 是「框架分配的 requirementId」——库里标准行的 id 列用它回填。
     */
    public record SyncResult(boolean ok, String reason, long revision, long contractVersion,
                             Map<String, String> newRequirementIds) {
        public static SyncResult bad(String reason) {
            return new SyncResult(false, reason, -1, -1, Map.of());
        }

        public static SyncResult good(long revision, long contractVersion, Map<String, String> newIds) {
            return new SyncResult(true, null, revision, contractVersion, newIds == null ? Map.of() : newIds);
        }
    }

    /**
     * 一条验收标准/约束的库内形状（criteria_json / constraints_json 的元素）。
     *
     * @param key           问渠侧的稳定键（框架 propose 自己发 id，落库前靠它认人）
     * @param id            框架 requirementId（首次同步后回填；null=还没进框架）
     * @param kind          CONSTRAINT / ACCEPTANCE_CRITERION
     * @param text          条文原文（≤2000，超了在 Controller 就拦）
     * @param status        CANDIDATE / CONFIRMED / REJECTED（库里这张卡的裁决态）
     * @param decisionSource 谁拍的板（用户卡片 = goal-card:{goalId}）
     * @param verifierId    验证器ID（manual / shell-exit / json-schema；null=未配）
     * @param verifierParams 验证器参数（退出码/JSON Schema 等，按 verifierId 解释）
     */
    public record Criterion(String key, String id, String kind, String text, String status,
                            String decisionSource, String verifierId, Map<String, Object> verifierParams) {

        public static Criterion of(String key, String kind, String text, String verifierId,
                                   Map<String, Object> verifierParams) {
            return new Criterion(key, null, kind, text, "CANDIDATE", null, verifierId, verifierParams);
        }

        public boolean confirmed() {
            return "CONFIRMED".equals(status);
        }

        public boolean acceptance() {
            return "ACCEPTANCE_CRITERION".equals(kind);
        }
    }

    private static RuntimeContext ctx(String uid, String sessionId) {
        return RuntimeContext.builder()
                .userId(uid == null || uid.isBlank() ? "guest" : uid)
                .sessionId(sessionId)
                .build();
    }

    // ==================== 三个写入口 ====================

    /**
     * 把库里的目标态覆盖写进框架活状态（每轮 submit 前调，幂等）。
     *
     * <p>做三件事：① 框架里没有这个 taskId（或挂着上一个目标）→ {@code beginTask} 换上来，
     * 顺带清掉旧的清单/要求/验证（框架语义：换目标清场）；② 库里有的条文框架还没有 →
     * {@code propose} 建候选（框架自己发 id，收进 {@code newRequirementIds} 回填库）；
     * ③ 库里已确认而框架还是候选 → {@code decide(CONFIRMED, USER)}。
     * <b>不</b>替用户确认：status 不是 CONFIRMED 的条文一概不动。
     *
     * @param goal 库里那一行（含已回填 id 的 criteria/constraints）
     * @return 同步结果；{@code !ok} 时调用方把 reason 放进 goal_state 的 syncWarn（不许静默）
     */
    public SyncResult beginOrUpdate(HarnessAgent agent, String uid, String sessionId, Goal goal,
                                    List<Criterion> criteria, List<Criterion> constraints,
                                    String subjectVersion) {
        if (agent == null) return SyncResult.bad("会话实例不在本进程，目标将在下一轮开跑前同步");
        Map<String, String> newIds = new LinkedHashMap<>();
        try {
            long[] out = {-1, -1};
            agent.getDelegate().updateAgentState(ctx(uid, sessionId), "goal_sync", state -> {
                TaskContextState tc = state.getTasksContext();
                if (tc.getScope() == null || !goal.getId().equals(tc.getScope().taskId())) {
                    tc.beginTask(new TaskContextState.Scope(goal.getId(), goal.getObjective(),
                            SOURCE_PREFIX + goal.getId()), tc.snapshot().getRevision());
                }
                for (Criterion c : all(criteria, constraints)) {
                    TaskRequirement existing = byId(tc.getRequirements(), c.id());
                    if (existing == null && c.id() == null) {
                        // 框架 propose 自己生成 id（UUID），问渠侧的 key 只在这一步之前认人
                        TaskRequirement created = tc.propose(kindOf(c.kind()), c.text(),
                                SOURCE_PREFIX + goal.getId());
                        newIds.put(c.key(), created.id());
                        existing = created;
                    }
                    if (existing == null) continue;
                    if (c.confirmed() && existing.status() == TaskRequirement.Status.CANDIDATE) {
                        // 库里已确认（用户点过）而框架还是候选 → 当场决定。
                        // 新建候选的那一次也走这条：否则模型这一轮看到的是「候选」，
                        // 而用户在问渠那边早就勾了——两边对不上，标准就成了「模型说没被批准」
                        tc.decide(existing.id(), TaskRequirement.Status.CONFIRMED,
                                new TaskRequirement.Decision(TaskRequirement.Authority.USER,
                                        DECISION_SOURCE_PREFIX + goal.getId()),
                                tc.snapshot().getRevision());
                    } else if ("REJECTED".equals(c.status())
                            && existing.status() != TaskRequirement.Status.REJECTED) {
                        tc.decide(existing.id(), TaskRequirement.Status.REJECTED,
                                new TaskRequirement.Decision(TaskRequirement.Authority.USER,
                                        DECISION_SOURCE_PREFIX + goal.getId()),
                                tc.snapshot().getRevision());
                    }
                }
                if (subjectVersion != null && !subjectVersion.isBlank()
                        && !subjectVersion.equals(tc.getSubjectVersion())) {
                    tc.setSubjectVersion(subjectVersion, tc.snapshot().getRevision());
                }
                out[0] = tc.snapshot().getRevision();
                out[1] = tc.snapshot().getContractVersion();
            });
            return SyncResult.good(out[0], out[1], newIds);
        } catch (Exception e) {
            // 有未结算工具调用/租约被占都会落到这里：目标还在库里，下一轮开跑前会再同步
            log.warn("[GOAL] 目标同步进框架失败: goal={} session={} {}", goal.getId(), sessionId, e.toString());
            return bat(e);
        }
    }

    /**
     * 用户在确认卡上拍板后立刻落框架（Controller 调；实例不在本进程就留给下一轮开跑前同步）。
     *
     * @param decisions 每条标准库里最新的裁决态（key→CONFIRMED/REJECTED）
     */
    public SyncResult confirmCriteria(HarnessAgent agent, String uid, String sessionId, Goal goal,
                                      List<Criterion> criteria, List<Criterion> constraints) {
        if (agent == null) return SyncResult.bad("会话实例不在本进程，确认结果将在下一轮开跑前同步");
        try {
            long[] out = {-1, -1};
            agent.getDelegate().updateAgentState(ctx(uid, sessionId), "goal_confirm", state -> {
                TaskContextState tc = state.getTasksContext();
                if (tc.getScope() == null || !goal.getId().equals(tc.getScope().taskId())) {
                    tc.beginTask(new TaskContextState.Scope(goal.getId(), goal.getObjective(),
                            SOURCE_PREFIX + goal.getId()), tc.snapshot().getRevision());
                }
                for (Criterion c : all(criteria, constraints)) {
                    if (c.id() == null) continue;   // 还没 propose 过：beginOrUpdate 会建
                    TaskRequirement existing = byId(tc.getRequirements(), c.id());
                    if (existing == null) continue;
                    if (c.confirmed() && existing.status() == TaskRequirement.Status.CANDIDATE) {
                        tc.decide(existing.id(), TaskRequirement.Status.CONFIRMED,
                                new TaskRequirement.Decision(TaskRequirement.Authority.USER,
                                        DECISION_SOURCE_PREFIX + goal.getId()),
                                tc.snapshot().getRevision());
                    } else if ("REJECTED".equals(c.status())
                            && existing.status() != TaskRequirement.Status.REJECTED) {
                        tc.decide(existing.id(), TaskRequirement.Status.REJECTED,
                                new TaskRequirement.Decision(TaskRequirement.Authority.USER,
                                        DECISION_SOURCE_PREFIX + goal.getId()),
                                tc.snapshot().getRevision());
                    }
                }
                out[0] = tc.snapshot().getRevision();
                out[1] = tc.snapshot().getContractVersion();
            });
            return SyncResult.good(out[0], out[1], Map.of());
        } catch (Exception e) {
            log.warn("[GOAL] 标准确认写框架失败: goal={} session={} {}", goal.getId(), sessionId, e.toString());
            return bat(e);
        }
    }

    /**
     * 钉工件指纹（{@code setSubjectVersion}）。<b>只有目标带工件类标准时才钉</b>：
     * 纯检索/纯写作类目标不钉，否则会因「没有工件」而永远验不了
     * （{@code recordVerification} 拿不到 binding 直接抛 IllegalStateException）。
     */
    public SyncResult pinSubject(HarnessAgent agent, String uid, String sessionId, String version) {
        if (agent == null) return SyncResult.bad("会话实例不在本进程，指纹将在下一轮开跑前钉上");
        if (version == null || version.isBlank()) return SyncResult.good(-1, -1, Map.of());
        try {
            long[] out = {-1, -1};
            agent.getDelegate().updateAgentState(ctx(uid, sessionId), "goal_subject", state -> {
                TaskContextState tc = state.getTasksContext();
                if (tc.getScope() == null) return;   // 没有目标就没有证据主体，跳过
                if (!version.equals(tc.getSubjectVersion())) {
                    tc.setSubjectVersion(version, tc.snapshot().getRevision());
                }
                out[0] = tc.snapshot().getRevision();
                out[1] = tc.snapshot().getContractVersion();
            });
            return SyncResult.good(out[0], out[1], Map.of());
        } catch (Exception e) {
            log.warn("[GOAL] 工件指纹钉写失败: session={} {}", sessionId, e.toString());
            return bat(e);
        }
    }

    /**
     * 工件被改 → {@code invalidateSubject}：投影里旧结论显示 {@code STALE}，
     * {@code ACHIEVED} 必须回落到 {@code RUNNING}（框架文档原话：「代码之后再次变化，
     * 旧的验证结论也可能不再适用」）。
     */
    public SyncResult invalidateSubject(HarnessAgent agent, String uid, String sessionId) {
        if (agent == null) return SyncResult.bad("会话实例不在本进程");
        try {
            long[] out = {-1, -1};
            agent.getDelegate().updateAgentState(ctx(uid, sessionId), "goal_invalidate", state -> {
                TaskContextState tc = state.getTasksContext();
                if (tc.getScope() == null || tc.getSubjectVersion() == null) return;
                tc.invalidateSubject(tc.snapshot().getRevision());
                out[0] = tc.snapshot().getRevision();
                out[1] = tc.snapshot().getContractVersion();
            });
            return SyncResult.good(out[0], out[1], Map.of());
        } catch (Exception e) {
            log.warn("[GOAL] 工件指纹作废失败: session={} {}", sessionId, e.toString());
            return bat(e);
        }
    }

    /**
     * 读回框架那份任务状态的水位（同步对账用：不一致就在面板上出声，不许静默）。
     *
     * @return [revision, contractVersion]；取不到（不在本进程/无状态）返回 null
     */
    public long[] watermarks(HarnessAgent agent, String uid, String sessionId) {
        if (agent == null) return null;
        try {
            AgentState state = agent.getDelegate().getAgentState(ctx(uid, sessionId));
            if (state == null) return null;
            TaskContextState tc = state.getTasksContext();
            return new long[]{tc.snapshot().getRevision(), tc.snapshot().getContractVersion()};
        } catch (Exception e) {
            log.warn("[GOAL] 框架水位读回失败: session={} {}", sessionId, e.toString());
            return null;
        }
    }

    // ==================== 内部 ====================

    private static List<Criterion> all(List<Criterion> criteria, List<Criterion> constraints) {
        List<Criterion> out = new ArrayList<>();
        if (criteria != null) out.addAll(criteria);
        if (constraints != null) out.addAll(constraints);
        return out;
    }

    private static TaskRequirement byId(List<TaskRequirement> requirements, String id) {
        if (id == null) return null;
        for (TaskRequirement r : requirements) {
            if (id.equals(r.id())) return r;
        }
        return null;
    }

    private static TaskRequirement.Kind kindOf(String kind) {
        return "CONSTRAINT".equals(kind)
                ? TaskRequirement.Kind.CONSTRAINT
                : TaskRequirement.Kind.ACCEPTANCE_CRITERION;
    }

    /** 异常 → 失败结果（包级可见：外层类的 catch 块要用它） */
    static SyncResult bat(Exception e) {
        return SyncResult.bad(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
