package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.model.Goal;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.state.TaskVerification;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.verification.DeterministicVerifier;
import io.agentscope.harness.agent.verification.JsonResultSchemaVerifier;
import io.agentscope.harness.agent.verification.ShellExitCodeVerifier;
import io.agentscope.harness.agent.verification.VerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 验收怎么跑（目标模式的命门）：按 {@code criteria_json.verifierId} 装配验证器，
 * <b>轮外</b>逐条验，结论写进 {@code c_ai_goal.verifications_json} 与框架任务态。
 *
 * <p>三类验证器（设计稿 §8.1）：
 * <ul>
 *   <li>{@code shell-exit}：框架 {@link ShellExitCodeVerifier}——只读那一次沙盒执行留下的退出码，
 *       <b>不重新跑命令</b>（javadoc：never an LLM or command runner）；</li>
 *   <li>{@code json-schema}：框架 {@link JsonResultSchemaVerifier}——交付物 JSON 的字段完整性；</li>
 *   <li>{@code manual}：{@link ManualCheckVerifier}——观感/语气/口径这类无法机器判定的标准，
 *       读用户在卡片上点的勾选；点之前 outcome 一律 UNKNOWN。</li>
 * </ul>
 *
 * <p><b>验证明显不许进模型工具面</b>（框架 javadoc：Never registered as a model tool）——
 * 否则模型会自己给自己发合格证。本类不注册任何工具。
 *
 * <p>顺序是死的（{@code GoalBridge} 类注释第 3 条）：
 * {@code beginTask → decide → setSubjectVersion → 工具执行落账 → 轮外 verify}。
 * 同一轮内不能验证刚发生的动作——证据还没结算，这是设计而不是 bug。
 */
@Service
public class GoalVerificationRegistry {

    private static final Logger log = LoggerFactory.getLogger(GoalVerificationRegistry.class);

    public static final String SHELL_EXIT = "shell-exit";
    public static final String JSON_SCHEMA = "json-schema";
    public static final String MANUAL = "manual";

    private final com.wenqu.ai.mapper.GoalMapper goalMapper;
    private final SessionEventService sessionEventService;
    private final ConfigService configService;
    private final com.wenqu.ai.agentx.FrameworkSessions sessions;

    /**
     * 框架状态存储的供应口（装配期绑：引擎是 AgentxConfiguration 里 new 的，不归 Spring 管）。
     * <p>为什么不自己建一个：那会是第二份状态真源——验证结论要写进的正是引擎那一份。
     */
    private volatile Supplier<AgentStateStore> stateStoreSupplier;

    public GoalVerificationRegistry(com.wenqu.ai.mapper.GoalMapper goalMapper,
                                    SessionEventService sessionEventService,
                                    ConfigService configService,
                                    com.wenqu.ai.agentx.FrameworkSessions sessions) {
        // 注意：这里**不注入 GoalService**——它要调本类做验收，本类再注入它就成了 Bean 循环
        // （Spring 直接启动失败）。人工核验要读的「用户勾没勾」由 GoalRecords 静态读同一份记录。
        this.goalMapper = goalMapper;
        this.sessionEventService = sessionEventService;
        this.configService = configService;
        this.sessions = sessions;
    }

    public void bindStateStore(Supplier<AgentStateStore> supplier) {
        this.stateStoreSupplier = supplier;
    }

    /** 单条验收标准的验证上限（秒）：超时记 ERROR */
    public int verifyTimeoutSeconds() {
        return configService.getInt("chat.goalMode.verifyTimeoutSeconds", 60);
    }

    /** 验证方式的中文名（界面显示用；不露框架机制） */
    public String labelOf(String verifierId) {
        if (verifierId == null) return "人工核验";
        return switch (verifierId) {
            case SHELL_EXIT -> "命令退出码";
            case JSON_SCHEMA -> "产物结构";
            default -> "人工核验";
        };
    }

    /**
     * 验一条标准。返回 null=这条本轮没法验（实例不在本进程等），调用方跳过不报错。
     *
     * @return {@code {outcome, reason, verifierId, actionId, binding:{contractVersion,subjectVersion}, recordedAt}}
     */
    public Map<String, Object> verify(Goal goal, GoalBridge.Criterion c, HarnessAgent agent, String uid) {
        AgentStateStore store = stateStoreSupplier == null ? null : stateStoreSupplier.get();
        if (store == null || agent == null) {
            return unknown(c, "验证存储未就绪");
        }
        String actionId = pickActionId(goal, c, uid);
        if (actionId == null) {
            // 没有可绑定的执行记录：机器验证器没有证据可读，人工核验也没有锚点
            return unknown(c, "找不到可绑定的执行记录");
        }
        DeterministicVerifier verifier = build(c, goal.getId());
        if (verifier == null) {
            return unknown(c, "未配置验证方式");
        }
        AgentState live = agent.getDelegate().getAgentState(
                RuntimeContext.builder()
                        .userId(uid == null || uid.isBlank() ? "guest" : uid)
                        .sessionId(goal.getSessionId())
                        .build());
        if (live == null) return unknown(c, "会话状态不在本进程");
        RuntimeContext verifyCtx = RuntimeContext.builder()
                .userId(uid == null || uid.isBlank() ? "guest" : uid)
                .sessionId(goal.getSessionId())
                .agentState(live)
                .build();
        TaskVerification report;
        try {
            report = new VerificationService(store)
                    .verify(verifyCtx, c.id(), actionId, verifier)
                    .block(Duration.ofSeconds(Math.max(5, verifyTimeoutSeconds())));
        } catch (Exception e) {
            // 绑定变化（工件改过/要求改过）会抛 ConcurrentModificationException——这正是 STALE 的成因，
            // 如实记成 ERROR 并让上层把结论判过期，不装作验过
            log.warn("[GOAL] 验证执行失败: goal={} 标准={} {}", goal.getId(), c.key(), e.getMessage());
            return error(c, actionId, e.getMessage());
        }
        if (report == null) return unknown(c, "验证未返回结论");
        // 持久化：活状态上的结论写回日志（updateAgentState 重放一遍；已存在则跳过——幂等）
        try {
            agent.getDelegate().updateAgentState(
                    RuntimeContext.builder()
                            .userId(uid == null || uid.isBlank() ? "guest" : uid)
                            .sessionId(goal.getSessionId())
                            .build(),
                    "goal_verify", s -> {
                        TaskContextState tc = s.getTasksContext();
                        boolean has = tc.getVerifications().stream()
                                .anyMatch(v -> v.id().equals(report.id()));
                        if (!has) tc.recordVerification(report, tc.snapshot().getRevision());
                    });
        } catch (Exception e) {
            // 提交态与活态有差的极端情形：结论已在活状态里，下一轮收尾会一起 checkpoint
            log.warn("[GOAL] 验证结论持久化延后: goal={} {}", goal.getId(), e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", report.outcome().name());
        out.put("reason", report.reason());
        out.put("verifierId", report.verifierId());
        out.put("actionId", report.actionId());
        out.put("recordedAt", report.recordedAt());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("contractVersion", report.binding().contractVersion());
        binding.put("subjectVersion", report.binding().subjectVersion());
        out.put("binding", binding);
        if (MANUAL.equals(c.verifierId())) out.put("manual", true);
        // 账本：verification/result 的五个字段照搬不另立（设计稿 §11）
        Map<String, Object> ledger = new LinkedHashMap<>(out);
        ledger.put("requirementId", c.id());
        sessionEventService.append(goal.getSessionId(), uid, goal.getLastTurnId(), goal.getId(),
                "goal_verify", JSON.toJSONString(ledger));
        return out;
    }

    // ==================== 内部 ====================

    private static Map<String, Object> unknown(GoalBridge.Criterion c, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", "UNKNOWN");
        m.put("reason", reason);
        m.put("verifierId", c.verifierId());
        m.put("recordedAt", System.currentTimeMillis());
        return m;
    }

    private static Map<String, Object> error(GoalBridge.Criterion c, String actionId, String reason) {
        Map<String, Object> m = unknown(c, "验证未通过：" + reason);
        m.put("outcome", "ERROR");
        m.put("actionId", actionId);
        return m;
    }

    private DeterministicVerifier build(GoalBridge.Criterion c, String goalId) {
        Map<String, Object> p = c.verifierParams() == null ? Map.of() : c.verifierParams();
        return switch (c.verifierId() == null ? MANUAL : c.verifierId()) {
            case SHELL_EXIT -> {
                Object tool = p.get("toolName");
                Object code = p.get("expectedExitCode");
                yield new ShellExitCodeVerifier(
                        tool == null ? "execute" : String.valueOf(tool),
                        code instanceof Number n ? n.intValue() : 0);
            }
            case JSON_SCHEMA -> {
                Object schema = p.get("schema");
                if (!(schema instanceof Map<?, ?> mp) || mp.isEmpty()) yield null;
                yield new JsonResultSchemaVerifier(castMap(mp));
            }
            default -> new ManualCheckVerifier(freshGoal(goalId), c.key());
        };
    }

    /**
     * 取目标当前那一行（人工勾选是写进 {@code verifications_json} 的，验证时要读最新那份）。
     * <p>为什么现取不用手上那份：勾选可能是在这次验证之前刚落库的。
     */
    private Goal freshGoal(String goalId) {
        Goal g = goalMapper.selectById(goalId);
        return g == null ? new Goal() : g;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> mp) {
        return new LinkedHashMap<>((Map<String, Object>) mp);
    }

    /**
     * 从会话日志里挑这一条标准的证据锚点（{@code action/end} 上的 actionId）。
     * <p>挑法：验证器指定了工具名的，取最后一次匹配那个工具的已结算执行；否则取本目标区间内
     * 最后一次已结算执行（人工核验的锚点——「这个判断是针对哪一刻的世界状态做的」）。
     * <p>只扫最近 500 条事件：目标区间一般不远，全量扫会把长会话的每次验收都变成一次全日志读。
     */
    private String pickActionId(Goal goal, GoalBridge.Criterion c, String uid) {
        try {
            AgentSession session = sessions.peek(uid, goal.getSessionId());
            if (session == null) return null;
            SessionLog log = session.log();
            long head = log.head().seq();
            long from = Math.max(1, head - 500);
            String last = null;
            String lastMatch = null;
            for (SessionEvent e : log.scan(from, head)) {
                if (!"action/end".equals(e.type())) continue;
                Map<String, Object> data = e.data();
                Object obs = data.get("observation");
                if (!(obs instanceof Map<?, ?> o)) continue;
                Object actionId = o.get("actionId");
                Object toolName = o.get("toolName");
                if (actionId == null) continue;
                last = String.valueOf(actionId);
                if (matchesTool(c, toolName == null ? "" : String.valueOf(toolName))) {
                    lastMatch = last;
                }
            }
            return lastMatch != null ? lastMatch : last;
        } catch (Exception e) {
            log.warn("[GOAL] 证据锚点挑选失败: goal={} {}", goal.getId(), e.getMessage());
            return null;
        }
    }

    private static boolean matchesTool(GoalBridge.Criterion c, String toolName) {
        Map<String, Object> p = c.verifierParams() == null ? Map.of() : c.verifierParams();
        Object want = p.get("toolName");
        if (want != null && !String.valueOf(want).isBlank()) {
            return toolName.contains(String.valueOf(want));
        }
        if (JSON_SCHEMA.equals(c.verifierId())) {
            String t = toolName.toLowerCase(java.util.Locale.ROOT);
            return t.contains("write") || t.contains("file") || t.contains("edit");
        }
        return false;
    }
}
