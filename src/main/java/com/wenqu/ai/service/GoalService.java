package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.mapper.GoalMapper;
import com.wenqu.ai.mapper.ToolApprovalMapper;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.model.ToolApproval;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.agentscope.core.state.TaskVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标模式的状态机与编排（问渠这一侧的全部「目标」逻辑）。
 *
 * <p>权威分工（设计稿 §5.2）：{@code c_ai_goal} 是产品面真源——界面显示与预算判定只读它；
 * 框架 {@code tasksContext} 是模型视图，每轮开跑前由 {@link GoalBridge} 从库里覆盖写进去。
 * 框架侧写永远走「本轮开跑前覆盖写」，不做增量、不只 build 期写一次：进程重启、多副本换副本后
 * {@code tasksContext} 不在这个进程里，而目标面板要能显示「与模型看到的一致吗」。
 *
 * <p>两条硬约束（照抄框架语义，别自创）：
 * <ol>
 *   <li><b>todo 全 done ≠ 目标达成</b>：达成判定只看 {@code verifications}，不看清单；</li>
 *   <li><b>换目标必须换新 id</b>：同 id 改目标文本框架直接抛，所以「改目标」在产品上是
 *       「结束旧目标 + 起新目标」，历史与旧验证留在旧行里。</li>
 * </ol>
 *
 * <p>轮次钩子由引擎调：{@link #syncBeforeSubmit}（submit 前覆盖写框架态）与
 * {@link #onTurnEnd}（记账 / 80% 预警 / 撞闸 / 验收 / 续跑一拍）。
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);

    // ==================== 状态机（设计稿 §四，10 态） ====================

    public static final String DRAFT = "DRAFT";
    public static final String CLARIFYING = "CLARIFYING";
    public static final String AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";
    public static final String RUNNING = "RUNNING";
    public static final String VERIFYING = "VERIFYING";
    public static final String WAITING_INPUT = "WAITING_INPUT";
    public static final String PAUSED = "PAUSED";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String ACHIEVED = "ACHIEVED";
    public static final String ENDED_INCOMPLETE = "ENDED_INCOMPLETE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 非终态集合（同会话至多一条） */
    private static final java.util.Set<String> ACTIVE = java.util.Set.of(
            DRAFT, CLARIFYING, AWAITING_CONFIRMATION, RUNNING, VERIFYING, WAITING_INPUT, PAUSED);

    /** 目标确认卡在 c_ai_tool_approval 里的类型标识（与 askUser / planApproval 同形的第四档） */
    public static final String GOAL_CONFIRM_TOOL = "goalConfirm";

    /** 单目标标准条数上限（框架帽 64；问渠收到 12：一张卡看得完、写得完） */
    public static final int MAX_CRITERIA = 12;

    /** 四条预算全不限时的硬闸轮数（防无限循环；到线按撞闸收尾出未完成清单） */
    private static final int HARD_TURN_CAP = 50;

    private final GoalMapper goalMapper;
    private final GoalBridge goalBridge;
    private final SessionEventService sessionEventService;
    private final ConfigService configService;
    private final ChatRunBus chatRunBus;
    private final com.wenqu.ai.agentx.FrameworkSessions sessions;
    private final ToolApprovalMapper toolApprovalMapper;
    private final com.wenqu.ai.service.SandboxService sandboxService;
    private final GoalVerificationRegistry verificationRegistry;

    /** 续跑驱动（引擎实现，构造期后绑）：跨轮循环的控制流归框架，问渠只数预算并发起下一轮 */
    private volatile GoalTurnDriver turnDriver;

    /** 每目标一把锁：状态迁移与框架同步不许并发（同会话串行已经挡了一层，这里是控制器动作之间） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public GoalService(GoalMapper goalMapper, GoalBridge goalBridge, SessionEventService sessionEventService,
                       ConfigService configService, ChatRunBus chatRunBus,
                       com.wenqu.ai.agentx.FrameworkSessions sessions, ToolApprovalMapper toolApprovalMapper,
                       com.wenqu.ai.service.SandboxService sandboxService,
                       GoalVerificationRegistry verificationRegistry) {
        this.goalMapper = goalMapper;
        this.goalBridge = goalBridge;
        this.sessionEventService = sessionEventService;
        this.configService = configService;
        this.chatRunBus = chatRunBus;
        this.sessions = sessions;
        this.toolApprovalMapper = toolApprovalMapper;
        this.sandboxService = sandboxService;
        this.verificationRegistry = verificationRegistry;
    }

    /** 续跑驱动（引擎侧实现）。由装配处显式绑定——引擎是那里 new 的，不归 Spring 管。 */
    public void bindTurnDriver(GoalTurnDriver driver) {
        this.turnDriver = driver;
    }

    /** 跨轮续跑的发起口（实现里做 submit + 跟随，见 AgentscopeChatEngine#continueGoalTurn）。 */
    public interface GoalTurnDriver {
        void continueTurn(Goal goal, String input, int n);
    }

    // ==================== 配置与预算 ====================

    public boolean enabled() {
        return configService.getBoolean("chat.goalMode.enabled", false);
    }

    /** 这个智能体开不开目标模式（三态：null=继承平台） */
    public boolean enabledFor(com.wenqu.ai.model.Agent agent) {
        if (!enabled()) return false;
        return agent == null || agent.getGoalEnabled() == null || agent.getGoalEnabled() == 1;
    }

    public long confirmTimeoutMs() {
        return configService.getLong("chat.goalTimeoutMs", 600_000L);
    }

    public int verifyTimeoutSeconds() {
        return configService.getInt("chat.goalMode.verifyTimeoutSeconds", 60);
    }

    public boolean requirementProposals() {
        return configService.getBoolean("chat.goalMode.requirementProposals", true);
    }

    /**
     * 生效预算：平台默认值（目标模式配置不下放到个人，只留平台一档）。
     * <p>个人发起目标时仍可在请求里在此基础上<b>往下</b>收（{@link #start} 的 budget 入参），
     * 平台默认值本身不提供「每人一份上限」那一层。
     */
    public record Caps(int turns, long tokens, int toolCalls, int minutes) {}

    public Caps capsFor(String uid) {
        return new Caps(
                configService.getInt("chat.goalMode.defaultTurns", 12),
                configService.getLong("chat.goalMode.defaultTokens", 1_000_000L),
                configService.getInt("chat.goalMode.defaultToolCalls", 120),
                configService.getInt("chat.goalMode.defaultMinutes", 30));
    }

    // ==================== 用户动作 ====================

    /**
     * 设为目标：建行进 {@code AWAITING_CONFIRMATION}（标准草稿已成形，等用户拍板）。
     * <p>同会话已有非终态目标时按「换目标」处理：旧的收成 {@code ENDED_INCOMPLETE} 再起新的
     * ——历史与旧验证留在旧行里可审计（框架那边也只在 beginTask 换 taskId 时清场）。
     */
    public Goal start(String sessionId, String uid, String agentId, String objective,
                      List<Map<String, Object>> criteriaInput, Map<String, Object> budgetInput,
                      RunChannel channel) {
        if (objective == null || objective.isBlank()) throw new IllegalArgumentException("目标不能为空");
        if (objective.length() > 4000) throw new IllegalArgumentException("目标太长（上限 4000 字）");
        if (criteriaInput != null && criteriaInput.size() > MAX_CRITERIA) {
            throw new IllegalArgumentException("验收标准最多 " + MAX_CRITERIA + " 条");
        }
        Object lock = locks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            Goal previous = activeGoal(sessionId);
            if (previous != null) {
                finish(previous, ENDED_INCOMPLETE, "incomplete", channel);
            }
            String goalId = UUID.randomUUID().toString().replace("-", "");
            Goal goal = new Goal();
            goal.setId(goalId);
            goal.setSessionId(sessionId);
            goal.setUserId(uid);
            goal.setAgentId(agentId);
            goal.setObjective(objective.strip());
            goal.setCriteriaJson(JSON.toJSONString(buildCriteria(criteriaInput)));
            goal.setConstraintsJson(JSON.toJSONString(List.of()));
            goal.setStatus(AWAITING_CONFIRMATION);
            Caps caps = capsFor(uid);
            goal.setBudgetTurns(applyBudget(budgetInput == null ? null : budgetInput.get("turns"), caps.turns()));
            goal.setBudgetTokens(applyBudgetLong(budgetInput == null ? null : budgetInput.get("tokens"), caps.tokens()));
            goal.setBudgetToolCalls(applyBudget(budgetInput == null ? null : budgetInput.get("toolCalls"), caps.toolCalls()));
            goal.setBudgetMinutes(applyBudget(budgetInput == null ? null : budgetInput.get("minutes"), caps.minutes()));
            goal.setUsedTurns(0);
            goal.setUsedTokens(0L);
            goal.setUsedToolCalls(0);
            goal.setUsedMs(0L);
            goal.setTurnCounter(0);
            goal.setCreatedAt(LocalDateTime.now());
            goalMapper.insert(goal);
            // 确认卡复用 c_ai_tool_approval（tool_name=goalConfirm）：它已经是「带轮身份的持久待办 +
            // 终态 + 答复审计」的通用表，再立一张 approval 表就是第四份平行真值源
            openConfirmCard(goal, channel);
            log.info("[GOAL] 目标已创建待确认: goal={} session={} 标准 {} 条", goalId, sessionId,
                    criteriaInput == null ? 0 : criteriaInput.size());
            emitState(goal, channel, null);
            return goal;
        }
    }

    /**
     * 用户在确认卡上拍板：逐条标准落裁决 → 框架 {@code decide} → RUNNING。
     * <p>权威分离：确认只由用户点（{@code Decision.authority=USER}），平台没有自动放行这条路。
     */
    public Goal confirmCriteria(String goalId, String uid, List<Map<String, Object>> decisions,
                                RunChannel channel) {
        Goal goal = own(goalId, uid);
        Object lock = locks.computeIfAbsent(goalId, k -> new Object());
        synchronized (lock) {
            if (!AWAITING_CONFIRMATION.equals(goal.getStatus())) {
                throw new IllegalStateException("这条目标不在待确认状态");
            }
            List<GoalBridge.Criterion> criteria = readCriteria(goal);
            List<GoalBridge.Criterion> constraints = readConstraints(goal);
            applyDecisions(criteria, constraints, decisions);
            goal.setCriteriaJson(JSON.toJSONString(criteria));
            goal.setConstraintsJson(JSON.toJSONString(constraints));
            // 框架侧立刻落 decide（实例不在本进程就留给下一轮开跑前同步，不算失败）
            String syncWarn = null;
            io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, goal.getSessionId());
            GoalBridge.SyncResult r = goalBridge.confirmCriteria(agent, uid, goal.getSessionId(),
                    goal, criteria, constraints);
            if (r.ok()) {
                persistFrameworkIds(goal, criteria, constraints, r);
                goal.setStateRevision(r.revision());
                goal.setContractVersion(r.contractVersion());
            } else {
                syncWarn = GoalCopy.SYNC_WARN + "（" + r.reason() + "）";
            }
            goal.setStatus(RUNNING);
            goal.setStartedAt(LocalDateTime.now());
            goalMapper.updateById(goal);
            resolveConfirmCard(goal, "APPROVED", "用户确认并开始");
            // 工件类标准钉指纹（纯检索/写作类目标不钉：没有工件可钉，recordVerification 会拿不到 binding）
            pinSubjectIfNeeded(goal);
            log.info("[GOAL] 标准已确认，目标启动: goal={} session={}", goalId, goal.getSessionId());
            emitState(goal, channel, syncWarn);
            // 确认即开工（分岔 1 取 A：全自动续跑）：不等用户再问一句，目标自己往下推
            kickContinuation(goal, channel);
            return goal;
        }
    }

    public Goal pause(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            if (!java.util.Set.of(RUNNING, VERIFYING, WAITING_INPUT).contains(goal.getStatus())) {
                throw new IllegalStateException("只有进行中的目标可以暂停");
            }
            goal.setStatus(PAUSED);
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            return goal;
        }
    }

    /** 继续：重判目标/权限/工件版本/剩余预算都在下一轮开跑前做（框架侧覆盖写同一条路）。 */
    public Goal resume(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            if (!java.util.Set.of(PAUSED, BUDGET_EXCEEDED).contains(goal.getStatus())) {
                throw new IllegalStateException("这条目标不在暂停状态");
            }
            if (budgetExhausted(goal)) {
                goal.setStatus(BUDGET_EXCEEDED);
                goalMapper.updateById(goal);
                emitState(goal, channel, null);
                throw new IllegalStateException("预算仍然用满，请先加预算");
            }
            goal.setStatus(RUNNING);
            goal.setEndReason(null);
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            // 立刻接上下一轮：用户点了「继续」，不该等他再问一句
            kickContinuation(goal, channel);
            return goal;
        }
    }

    public Goal cancel(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            finish(goal, CANCELLED, "cancelled", channel);
            resolveConfirmCard(goal, "REJECTED", "用户取消目标");
            return goal;
        }
    }

    /** 主动收尾（预算内）：未完成清单随终态帧给用户，不悄然截断。 */
    public Goal finishIncomplete(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            finish(goal, ENDED_INCOMPLETE, "incomplete", channel);
            resolveConfirmCard(goal, "REJECTED", "用户主动收尾");
            return goal;
        }
    }

    public Goal extendBudget(String goalId, String uid, Integer extraTurns, Long extraTokens, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            if (extraTurns != null && extraTurns > 0) {
                goal.setBudgetTurns(goal.getBudgetTurns() + extraTurns);
            }
            if (extraTokens != null && extraTokens > 0) {
                goal.setBudgetTokens(goal.getBudgetTokens() + extraTokens);
            }
            if (BUDGET_EXCEEDED.equals(goal.getStatus())) {
                goal.setStatus(RUNNING);
                goal.setEndReason(null);
            }
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            kickContinuation(goal, channel);
            return goal;
        }
    }

    // ==================== 引擎钩子 ====================

    /**
     * 每轮 submit 前：把库里的目标态覆盖写进框架活状态（设计稿 §5.2 的回显兜底那一条）。
     *
     * @return 本轮生效的目标（null=这条会话没有进行中的目标，或目标模式关着）
     */
    public Goal syncBeforeSubmit(String sessionId, String uid, String agentId) {
        if (!enabled()) return null;
        Goal goal = activeGoal(sessionId);
        if (goal == null || !java.util.Set.of(RUNNING, VERIFYING, WAITING_INPUT).contains(goal.getStatus())) {
            return null;
        }
        synchronized (lockFor(goal.getId())) {
            List<GoalBridge.Criterion> criteria = readCriteria(goal);
            List<GoalBridge.Criterion> constraints = readConstraints(goal);
            io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, sessionId);
            GoalBridge.SyncResult r = goalBridge.beginOrUpdate(agent, uid, sessionId, goal,
                    criteria, constraints, goal.getSubjectVersion());
            if (r.ok()) {
                persistFrameworkIds(goal, criteria, constraints, r);
                goal.setStateRevision(r.revision());
                goal.setContractVersion(r.contractVersion());
                goalMapper.updateById(goal);
            } else {
                // 同步不上的轮照跑（目标还在库里），但面板必须出声——不许静默
                log.warn("[GOAL] 开跑前同步失败，模型本轮可能看不到目标: goal={} {}", goal.getId(), r.reason());
            }
            return goal;
        }
    }

    /**
     * 轮收尾：记账 → 80% 预警 → 撞闸 → 验收 → 续跑一拍。
     * <p>叫停/宽限中止时框架的 {@code ended()} 只认 completed/cancelled，{@code failed} 与挂在
     * 提问卡上的 {@code suspended} 都不算结束——所以目标循环退出前在这里自己结算，不指望框架兜。
     */
    public void onTurnEnd(String sessionId, String uid, String turnId, long machineMs, int toolCalls,
                          RunChannel channel) {
        Goal goal = activeGoal(sessionId);
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            goal.setUsedTurns((int) used(goal.getUsedTurns()) + 1);
            goal.setTurnCounter((int) used(goal.getTurnCounter()) + 1);
            goal.setUsedMs(used(goal.getUsedMs()) + Math.max(0L, machineMs));
            goal.setUsedToolCalls((int) used(goal.getUsedToolCalls()) + Math.max(0, toolCalls));
            if (goal.getFirstTurnId() == null) goal.setFirstTurnId(turnId);
            goal.setLastTurnId(turnId);
            goal.setUsedTokens(sumTokens(sessionId, goal.getStartedAt()));
            // 工件指纹复判：变了就作废旧结论（ACHIEVED 回落到 RUNNING，投影显示 STALE）
            String fp = fingerprint(sessionId, uid);
            if (fp != null && goal.getSubjectVersion() != null && !fp.equals(goal.getSubjectVersion())) {
                io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, sessionId);
                goalBridge.invalidateSubject(agent, uid, sessionId);
                goal.setSubjectVersion(fp);
                goal.setContractVersion(used(goal.getContractVersion()) + 1);
                log.info("[GOAL] 工件已变，旧验证结论作废: goal={}", goal.getId());
            }
            if (budgetExhausted(goal)) {
                stopForBudget(goal, channel);
                return;
            }
            // 验收（轮外）：每条标准彼此独立，一条 ERROR 不打回其它条
            if (java.util.Set.of(RUNNING, VERIFYING).contains(goal.getStatus())) {
                verifyAll(goal, uid, channel);
            }
            goalMapper.updateById(goal);
            if (ACHIEVED.equals(goal.getStatus())) {
                emitState(goal, channel, null);
                return;
            }
            boolean warn = budgetRatio(goal) >= 0.8;
            emitState(goal, channel, warn ? GoalCopy.budgetWarn(remainingCriteria(goal)) : null);
            if (warn) {
                channel.emit(RunEventType.WARN, GoalCopy.budgetWarn(remainingCriteria(goal)));
            }
            // 续跑不在这里发起：这一轮还占着会话的跟随位（跟随位没释放时新的一轮会被「已有跟随者」挡掉）。
            // 由调用方在本轮彻底收尾之后调 {@link #continueAfterTurn}。
        }
    }

    /**
     * 本轮彻底收尾后接下一轮（引擎在跟随位释放之后调）。
     * <p>为什么不能塞在 {@link #onTurnEnd} 里：那一刻本轮还占着会话的跟随位，
     * 新起的后台轮会被「这一轮已经有跟随者在跑」挡掉——目标循环走到第二轮就停了。
     */
    public void continueAfterTurn(String sessionId, String uid) {
        Goal goal = activeGoal(sessionId);
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            kickContinuation(goal, null);
        }
    }

    /**
     * 用户开了新的一轮：目标循环让位（用户优先）。后台轮在飞就打断它，目标转 PAUSED。
     * <p>不打断的后果是用户那句提问排在后台轮后面等几分钟才出声——会话互斥下「用户优先」
     * 只能靠显式让位兑现，框架不会替问渠决定谁重要。
     */
    public void noteUserTurn(String sessionId, String uid, RunChannel channel) {
        Goal goal = activeGoal(sessionId);
        if (goal == null || !RUNNING.equals(goal.getStatus())) return;
        synchronized (lockFor(goal.getId())) {
            io.agentscope.harness.agent.session.AgentSession session = sessions.peek(uid, sessionId);
            if (session != null) {
                try {
                    session.interrupt();
                } catch (Exception e) {
                    log.warn("[GOAL] 打断后台轮失败: goal={} {}", goal.getId(), e.getMessage());
                }
            }
            goal.setStatus(PAUSED);
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            log.info("[GOAL] 用户插话，目标循环让位转暂停: goal={}", goal.getId());
        }
    }

    /** 后台轮被用户打断/自行失败：目标转 PAUSED（不是 FAILED——回来可续）。 */
    public void markContinueFailed(Goal goal, String reason) {
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            if (java.util.Set.of(RUNNING, VERIFYING, WAITING_INPUT).contains(goal.getStatus())) {
                goal.setStatus(PAUSED);
                goalMapper.updateById(goal);
            }
            log.warn("[GOAL] 后台续跑没起来: goal={} {}", goal.getId(), reason);
        }
    }

    private void kickContinuation(Goal goal, RunChannel channel) {
        GoalTurnDriver driver = this.turnDriver;
        if (driver == null) return;
        if (!RUNNING.equals(goal.getStatus())) return;
        if (budgetExhausted(goal)) return;
        int n = (int) used(goal.getTurnCounter()) + 1;
        if (used(goal.getBudgetTurns()) > 0 && n > used(goal.getBudgetTurns())) return;
        // 四条全 0（全不限）时的硬闸：无限循环的目标会把会话线程与 token 一起烧穿。
        // 到线停下来出未完成清单——框架文档要求「不是悄然截断」
        if (allUnlimited(goal) && n > HARD_TURN_CAP) {
            stopForBudget(goal, channel);
            return;
        }
        List<String> pending = new ArrayList<>();
        for (GoalBridge.Criterion c : readCriteria(goal)) {
            if (!c.confirmed() || !c.acceptance()) continue;
            Map<String, Object> v = GoalRecords.latestVerification(goal, c.key());
            if (v == null || !"PASSED".equals(String.valueOf(v.get("outcome"))) || GoalRecords.isStale(goal, v)) {
                pending.add(c.text());
            }
        }
        int turnsLeft = used(goal.getBudgetTurns()) > 0
                ? (int) (used(goal.getBudgetTurns()) - used(goal.getUsedTurns())) : 0;
        long tokensLeft = used(goal.getBudgetTokens()) > 0
                ? used(goal.getBudgetTokens()) - used(goal.getUsedTokens()) : 0;
        String input = GoalCopy.continuation(goal.getObjective(), pending, turnsLeft, tokensLeft);
        log.info("[GOAL] 起第 {} 轮续跑: goal={} 未过标准 {} 条", n, goal.getId(), pending.size());
        driver.continueTurn(goal, input, n);
    }

    // ==================== 验收（M2 的编排在这里，验证器在 GoalVerificationRegistry） ====================

    /** 用户点「现在验收」：全量验一遍，不管预算与轮次。 */
    public Map<String, Object> verifyNow(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            verifyAll(goal, uid, channel);
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            return view(goal, null);
        }
    }

    /**
     * 全量验收：每条已确认的验收标准各走自己的验证器。
     * <p>顺序是死的（设计稿 §六）：beginTask → decide → setSubjectVersion → 工具执行落账 →
     * <b>轮外</b> verify。同一轮内不能验证刚发生的动作——证据还没结算，这是设计而不是 bug。
     */
    private void verifyAll(Goal goal, String uid, RunChannel channel) {
        List<GoalBridge.Criterion> criteria = readCriteria(goal);
        if (criteria.isEmpty()) return;
        io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, goal.getSessionId());
        io.agentscope.harness.agent.session.AgentSession session = sessions.peek(uid, goal.getSessionId());
        if (agent == null || session == null) {
            // 实例不在本进程（重启/换副本）：机器验证器没有证据可读，但**人工勾选的结论仍算数**——
            // 达成判定只看验证记录，不能因为框架实例不在就把「用户已确认达成」判成没完成
            log.info("[GOAL] 实例不在本进程，本轮只按已有验证记录判定: goal={}", goal.getId());
        } else {
            goal.setStatus(VERIFYING);
            for (GoalBridge.Criterion c : criteria) {
                if (!c.confirmed() || !c.acceptance() || c.id() == null) continue;
                try {
                    Map<String, Object> result = verificationRegistry.verify(goal, c, agent, uid);
                    if (result != null) {
                        GoalRecords.upsertVerification(goal, c.key(), result);
                        if (!"PASSED".equals(String.valueOf(result.get("outcome")))) {
                            channel.emit(RunEventType.WARN,
                                    "有 " + remainingCriteria(goal) + " 条标准还没通过验证，继续推进");
                        }
                    }
                } catch (Exception e) {
                    // 一条 ERROR 不把其它条打回：记一条 ERROR 继续走下一条
                    log.warn("[GOAL] 单条验收异常: goal={} 标准={} {}", goal.getId(), c.key(), e.getMessage());
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("outcome", "ERROR");
                    err.put("reason", "验证器执行失败：" + e.getMessage());
                    err.put("recordedAt", System.currentTimeMillis());
                    GoalRecords.upsertVerification(goal, c.key(), err);
                }
            }
        }
        // ACHIEVED 判定只看 verifications：每条已确认标准都有当前有效的 PASSED
        boolean allPassed = !criteria.isEmpty();
        for (GoalBridge.Criterion c : criteria) {
            if (!c.confirmed() || !c.acceptance()) continue;
            Map<String, Object> v = GoalRecords.latestVerification(goal, c.key());
            if (v == null || !"PASSED".equals(String.valueOf(v.get("outcome"))) || GoalRecords.isStale(goal, v)) {
                allPassed = false;
                break;
            }
        }
        if (allPassed) {
            goal.setStatus(ACHIEVED);
            goal.setEndReason("achieved");
            goal.setEndedAt(LocalDateTime.now());
            log.info("[GOAL] 目标达成: goal={} 全部标准有凭据", goal.getId());
        } else if (RUNNING.equals(goal.getStatus()) || VERIFYING.equals(goal.getStatus())) {
            // 没达成就回到「进行中」；但用户手动暂停的目标不在这儿被拽起来（暂停是用户的动作）
            goal.setStatus(RUNNING);
            goal.setEndedAt(null);
        }
    }

    // ==================== 查询 ====================

    /**
     * 用户在卡片上点「达成这条 / 没过这条」：人工核验的勾选落库。
     * <p>落的是「用户已经做出的决定」，不是验证结论——结论由下一轮验收（或「现在验收」）
     * 经 {@code recordVerification} 走一遍，才有绑定与 STALE 语义。
     */
    public Goal markManual(String goalId, String uid, String criterionKey, String outcome, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            if (!outcome.equals("PASSED") && !outcome.equals("FAILED")) {
                throw new IllegalArgumentException("人工核验只能记 达成/未达成");
            }
            GoalRecords.upsertVerification(goal, criterionKey,
                    ManualCheckVerifier.verdict(outcome, outcome.equals("PASSED") ? "用户确认达成" : "用户标记未达成"));
            // 勾完立刻按当前证据重判一次：用户点「达成这条」就是在说「这条好了」，
            // 还要他再点一次「现在验收」才看到达成，是把同一次表态拆成两步
            verifyAll(goal, uid, channel);
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            return goal;
        }
    }

    /** 这条会话当前进行中的目标（非终态；同会话至多一条） */
    public Goal activeGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            return goalMapper.selectOne(new LambdaQueryWrapper<Goal>()
                    .eq(Goal::getSessionId, sessionId)
                    .in(Goal::getStatus, ACTIVE)
                    .orderByDesc(Goal::getCreatedAt)
                    .last("limit 1"));
        } catch (Exception e) {
            log.warn("[GOAL] 进行中目标查询失败: session={} {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 当前/最近一条目标（右栏回显，读 DB 不读框架） */
    public Goal latestGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            return goalMapper.selectOne(new LambdaQueryWrapper<Goal>()
                    .eq(Goal::getSessionId, sessionId)
                    .orderByDesc(Goal::getCreatedAt)
                    .last("limit 1"));
        } catch (Exception e) {
            log.warn("[GOAL] 最近目标查询失败: session={} {}", sessionId, e.getMessage());
            return null;
        }
    }

    public Goal get(String goalId) {
        return goalId == null ? null : goalMapper.selectById(goalId);
    }

    /**
     * 待确认/进行中的目标（恢复端点用：重启/换副本回 {@code live:false} + DB 那份）。
     * <p>{@code live} 说的是「框架实例在不在本进程」——不在的时候目标卡仍可显示与操作，
     * 只是框架侧同步要等下一轮开跑前。
     */
    public Map<String, Object> pending(String sessionId, String uid) {
        Goal goal = activeGoal(sessionId);
        // 形状与 /session/{id}/goal 一致：{goal:视图模型|null}（前端两处取数同源，不用各自判形状）。
        // 大多数会话没有目标，这里必须容得下 null——Map.of 会抛 NPE 把端点打成 500
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goal", goal == null ? null : view(goal, null));
        return out;
    }

    /**
     * 目标过程账本（按 goal_id 聚合；过程面板深链用）。
     * <p>归属只认 userId——账本按人隔离，别人的目标查不到。
     */
    public List<Map<String, Object>> goalEvents(String sessionId, String userId, String goalId) {
        List<com.wenqu.ai.model.SessionEvent> rows =
                sessionEventService.listByGoal(sessionId, userId, goalId, 0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.wenqu.ai.model.SessionEvent e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("type", e.getType());
            m.put("turnId", e.getTurnId());
            m.put("payload", e.getPayload());
            m.put("at", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** 前端视图模型（右栏面板、目标卡、goal_state 帧共用同一份）。 */
    public Map<String, Object> view(Goal goal, String syncWarn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goalId", goal.getId());
        m.put("status", goal.getStatus());
        m.put("objective", goal.getObjective());
        m.put("criteria", criteriaView(goal));
        m.put("budget", budgetView(goal));
        m.put("turnIndex", Math.max(1, used(goal.getTurnCounter())));
        m.put("turnsMax", used(goal.getBudgetTurns()));
        m.put("budgetWarn", budgetRatio(goal) >= 0.8 && !budgetExhausted(goal));
        m.put("syncWarn", syncWarn);
        m.put("firstTurnId", goal.getFirstTurnId() == null ? "" : goal.getFirstTurnId());
        m.put("lastTurnId", goal.getLastTurnId() == null ? "" : goal.getLastTurnId());
        m.put("live", sessions.peek(goal.getUserId(), goal.getSessionId()) != null);
        m.put("endReason", goal.getEndReason());
        m.put("createdAt", iso(goal.getCreatedAt()));
        m.put("startedAt", iso(goal.getStartedAt()));
        m.put("endedAt", iso(goal.getEndedAt()));
        m.put("remainingMs", confirmTimeoutMs());
        return m;
    }

    private List<Map<String, Object>> criteriaView(Goal goal) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GoalBridge.Criterion c : allCriteria(goal)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", c.key());
            row.put("id", c.id());
            row.put("kind", c.kind());
            row.put("text", c.text());
            row.put("status", c.status());
            row.put("verifierId", c.verifierId());
            row.put("verifierLabel", verificationRegistry.labelOf(c.verifierId()));
            Map<String, Object> v = GoalRecords.latestVerification(goal, c.key());
            if (v != null) {
                Map<String, Object> vv = new LinkedHashMap<>(v);
                vv.put("stale", GoalRecords.isStale(goal, v));
                row.put("verification", vv);
            } else {
                row.put("verification", null);
            }
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> budgetView(Goal goal) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("turns", dim(used(goal.getUsedTurns()), used(goal.getBudgetTurns())));
        b.put("tokens", dim(used(goal.getUsedTokens()), used(goal.getBudgetTokens())));
        b.put("toolCalls", dim(used(goal.getUsedToolCalls()), used(goal.getBudgetToolCalls())));
        b.put("minutes", dim(used(goal.getUsedMs()) / 60000, used(goal.getBudgetMinutes())));
        return b;
    }

    /** 库里那一列可能是空的（历史行/手改过）：一律当 0，不让面板因为一个 null 崩掉 */
    private static long used(Integer v) {
        return v == null ? 0L : v.longValue();
    }

    private static long used(Long v) {
        return v == null ? 0L : v;
    }

    private static Map<String, Object> dim(long used, long max) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("used", used);
        d.put("max", max);
        return d;
    }

    // ==================== 帧 ====================

    /** goal_state：账本（带 goal_id）+ 通道或总线。channel 为空=控制器侧动作，只进总线。 */
    public void emitState(Goal goal, RunChannel channel, String warn) {
        String frame = JSON.toJSONString(view(goal, warn));
        sessionEventService.append(goal.getSessionId(), goal.getUserId(), goal.getLastTurnId(),
                goal.getId(), "goal_state", frame);
        if (channel != null) {
            channel.emit(RunEventType.GOAL_STATE, frame);
            // 后台续跑轮的通道是收集型（没有浏览器通道）：这一帧得另走总线才送得到还在看的观众。
            // 交互轮不能这么做——channel.emit 已经过总线扇出过一次，再发一遍观众会收到两帧
            if (channel.emitter() instanceof com.wenqu.ai.service.CollectingSseEmitter) {
                chatRunBus.publishMirrorToSession(goal.getSessionId(), "goal_state", frame);
            }
        } else {
            chatRunBus.publishMirrorToSession(goal.getSessionId(), "goal_state", frame);
        }
    }

    /** goal_turn：后台轮开跑（进账本不进镜像——它是帧不是状态）。 */
    public void emitTurn(Goal goal, int n, String turnId, RunChannel channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goalId", goal.getId());
        m.put("n", n);
        m.put("turnId", turnId);
        m.put("startedAt", System.currentTimeMillis());
        m.put("reason", "continuation");
        String frame = JSON.toJSONString(m);
        sessionEventService.append(goal.getSessionId(), goal.getUserId(), turnId,
                goal.getId(), "goal_turn", frame);
        if (channel != null) {
            channel.emit(RunEventType.GOAL_TURN, frame);
        } else {
            chatRunBus.publishToSession(goal.getSessionId(), "goal_turn", frame);
        }
    }

    // ==================== 内部：标准行的读写 ====================

    private static List<GoalBridge.Criterion> buildCriteria(List<Map<String, Object>> input) {
        List<GoalBridge.Criterion> out = new ArrayList<>();
        if (input == null) return out;
        int i = 1;
        for (Map<String, Object> raw : input) {
            if (raw == null) continue;
            String kind = String.valueOf(raw.getOrDefault("kind", "ACCEPTANCE_CRITERION"));
            String text = raw.get("text") == null ? "" : String.valueOf(raw.get("text")).strip();
            if (text.isBlank()) continue;
            if (text.length() > 2000) throw new IllegalArgumentException("单条标准太长（上限 2000 字）");
            String verifierId = raw.get("verifierId") == null ? null : String.valueOf(raw.get("verifierId"));
            Map<String, Object> params = raw.get("verifierParams") instanceof Map<?, ?> mp
                    ? new LinkedHashMap<>((Map<String, Object>) mp) : new LinkedHashMap<>();
            out.add(new GoalBridge.Criterion("k" + i++, null, kind, text, "CANDIDATE", null,
                    verifierId, params));
        }
        return out;
    }

    private static void applyDecisions(List<GoalBridge.Criterion> criteria,
                                       List<GoalBridge.Criterion> constraints,
                                       List<Map<String, Object>> decisions) {
        if (decisions == null) return;
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Map<String, Object> d : decisions) {
            if (d == null) continue;
            Object k = d.get("key");
            Object s = d.get("status");
            if (k != null && s != null) byKey.put(String.valueOf(k), String.valueOf(s));
        }
        for (List<GoalBridge.Criterion> list : List.of(criteria, constraints)) {
            for (int i = 0; i < list.size(); i++) {
                GoalBridge.Criterion c = list.get(i);
                String decided = byKey.get(c.key());
                if (decided == null) continue;
                if (!decided.equals("CONFIRMED") && !decided.equals("REJECTED")) continue;
                list.set(i, new GoalBridge.Criterion(c.key(), c.id(), c.kind(), c.text(), decided,
                        "goal-card:" + decided.toLowerCase(), c.verifierId(), c.verifierParams()));
            }
        }
    }

    List<GoalBridge.Criterion> readCriteria(Goal goal) {
        return readList(goal.getCriteriaJson());
    }

    List<GoalBridge.Criterion> readConstraints(Goal goal) {
        return readList(goal.getConstraintsJson());
    }

    List<GoalBridge.Criterion> allCriteria(Goal goal) {
        List<GoalBridge.Criterion> all = new ArrayList<>(readCriteria(goal));
        all.addAll(readConstraints(goal));
        return all;
    }

    /**
     * 标准行反序列化。<b>一律手工过一遍 Map</b>：本仓 fastjson 1.x（spring-ai-alibaba 传递进来）
     * 不认 record，直接 {@code JSON.parseArray(json, Criterion.class)} 会得到一堆空元素——
     * 这条坑设计稿 §十四 点过名，别在这里再踩一次。
     */
    private static List<GoalBridge.Criterion> readList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<Map<String, Object>> rows = JSON.parseObject(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<GoalBridge.Criterion> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (r == null) continue;
                out.add(new GoalBridge.Criterion(
                        str(r.get("key")), str(r.get("id")), str(r.get("kind")), str(r.get("text")),
                        str(r.get("status")), str(r.get("decisionSource")), str(r.get("verifierId")),
                        r.get("verifierParams") instanceof Map<?, ?> mp
                                ? new LinkedHashMap<>((Map<String, Object>) mp) : new LinkedHashMap<>()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[GOAL] 标准行解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 框架分配的 requirementId 回填库（propose 自己发 id，问渠侧的 key 只在这之前认人）。 */
    private static void persistFrameworkIds(Goal goal, List<GoalBridge.Criterion> criteria,
                                            List<GoalBridge.Criterion> constraints,
                                            GoalBridge.SyncResult r) {
        if (r.newRequirementIds().isEmpty()) return;
        for (List<GoalBridge.Criterion> list : List.of(criteria, constraints)) {
            for (int i = 0; i < list.size(); i++) {
                GoalBridge.Criterion c = list.get(i);
                String frameworkId = r.newRequirementIds().get(c.key());
                if (frameworkId != null && c.id() == null) {
                    list.set(i, new GoalBridge.Criterion(c.key(), frameworkId, c.kind(), c.text(),
                            c.status(), c.decisionSource(), c.verifierId(), c.verifierParams()));
                }
            }
        }
        goal.setCriteriaJson(JSON.toJSONString(criteria));
        goal.setConstraintsJson(JSON.toJSONString(constraints));
    }

    // ==================== 内部：验证记录的读写 ====================
    // 读写与「是否过期」的判据都在 GoalRecords（静态）：验收注册表也要读同一份，
    // 挂在 bean 上会与目标服务构成循环依赖（Spring 直接启动失败）。

    // ==================== 内部：预算 ====================

    private static boolean dimExceeded(long used, long max) {
        return max > 0 && used >= max;
    }

    public boolean budgetExhausted(Goal goal) {
        return dimExceeded(used(goal.getUsedTurns()), used(goal.getBudgetTurns()))
                || dimExceeded(used(goal.getUsedTokens()), used(goal.getBudgetTokens()))
                || dimExceeded(used(goal.getUsedToolCalls()), used(goal.getBudgetToolCalls()))
                || dimExceeded(used(goal.getUsedMs()) / 60000, used(goal.getBudgetMinutes()));
    }

    /** 四条形里最逼近上限的那一条的比例（80% 预警判据） */
    public double budgetRatio(Goal goal) {
        double max = 0;
        max = Math.max(max, ratio(used(goal.getUsedTurns()), used(goal.getBudgetTurns())));
        max = Math.max(max, ratio(used(goal.getUsedTokens()), used(goal.getBudgetTokens())));
        max = Math.max(max, ratio(used(goal.getUsedToolCalls()), used(goal.getBudgetToolCalls())));
        max = Math.max(max, ratio(used(goal.getUsedMs()) / 60000, used(goal.getBudgetMinutes())));
        return max;
    }

    private static double ratio(long used, long max) {
        return max <= 0 ? 0 : (double) used / max;
    }

    /** 四条预算是不是全都不限（0=不限） */
    private static boolean allUnlimited(Goal goal) {
        return used(goal.getBudgetTurns()) <= 0 && used(goal.getBudgetTokens()) <= 0
                && used(goal.getBudgetToolCalls()) <= 0 && used(goal.getBudgetMinutes()) <= 0;
    }

    private long sumTokens(String sessionId, LocalDateTime since) {
        try {
            List<Map<String, Object>> rows = goalMapper.sumSessionTokens(sessionId, since);
            long sum = 0;
            for (Map<String, Object> r : rows) {
                Object t = r.get("tokens");
                if (t instanceof Number n) sum += n.longValue();
            }
            return sum;
        } catch (Exception e) {
            log.warn("[GOAL] token 账聚合失败: session={} {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /** 还没通过验证的标准条数（预警与未完成清单用） */
    public int remainingCriteria(Goal goal) {
        int n = 0;
        for (GoalBridge.Criterion c : readCriteria(goal)) {
            if (!c.confirmed() || !c.acceptance()) continue;
            Map<String, Object> v = GoalRecords.latestVerification(goal, c.key());
            if (v == null || !"PASSED".equals(String.valueOf(v.get("outcome"))) || GoalRecords.isStale(goal, v)) n++;
        }
        return n;
    }

    private void stopForBudget(Goal goal, RunChannel channel) {
        goal.setStatus(BUDGET_EXCEEDED);
        goal.setEndReason("budget");
        goal.setEndedAt(LocalDateTime.now());
        goalMapper.updateById(goal);
        // 未完成清单（框架文档要求「不是悄然截断」）
        List<String> remaining = new ArrayList<>();
        for (GoalBridge.Criterion c : readCriteria(goal)) {
            if (!c.confirmed() || !c.acceptance()) continue;
            Map<String, Object> v = GoalRecords.latestVerification(goal, c.key());
            if (v == null || !"PASSED".equals(String.valueOf(v.get("outcome"))) || GoalRecords.isStale(goal, v)) {
                remaining.add(c.text());
            }
        }
        String msg = GoalCopy.budgetStop(remaining.isEmpty() ? "（无）" : String.join("；", remaining),
                remaining.isEmpty() ? "（无）" : remaining.size() + " 条");
        channel.emit(RunEventType.WARN, msg);
        emitState(goal, channel, null);
        log.info("[GOAL] 预算撞闸，目标停轮: goal={} 还差 {} 条", goal.getId(), remaining.size());
    }

    // ==================== 内部：终态与卡片 ====================

    private void finish(Goal goal, String status, String endReason, RunChannel channel) {
        goal.setStatus(status);
        goal.setEndReason(endReason);
        goal.setEndedAt(LocalDateTime.now());
        goalMapper.updateById(goal);
        emitState(goal, channel, null);
        log.info("[GOAL] 目标终态: goal={} status={} reason={}", goal.getId(), status, endReason);
    }

    private void openConfirmCard(Goal goal, RunChannel channel) {
        try {
            ToolApproval row = new ToolApproval();
            row.setId(UUID.randomUUID().toString().replace("-", ""));
            row.setSessionId(goal.getSessionId());
            row.setUserId(goal.getUserId());
            row.setTurnId(goal.getLastTurnId());
            row.setToolName(GOAL_CONFIRM_TOOL);
            row.setStatus("PENDING");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("goalId", goal.getId());
            args.put("objective", goal.getObjective());
            args.put("criteria", criteriaView(goal));
            row.setRequestArgs(JSON.toJSONString(args));
            toolApprovalMapper.insert(row);
        } catch (Exception e) {
            // 卡落库失败不挡目标创建：c_ai_goal 自己就是权威，这张表只补审计与恢复
            log.warn("[GOAL] 确认卡落库失败（不影响目标）: goal={} {}", goal.getId(), e.getMessage());
        }
    }

    private void resolveConfirmCard(Goal goal, String status, String answer) {
        try {
            toolApprovalMapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                    .eq(ToolApproval::getSessionId, goal.getSessionId())
                    .eq(ToolApproval::getToolName, GOAL_CONFIRM_TOOL)
                    .eq(ToolApproval::getStatus, "PENDING")
                    .set(ToolApproval::getStatus, status)
                    .set(ToolApproval::getAnswer, answer)
                    .set(ToolApproval::getResolvedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[GOAL] 确认卡终态更新失败: goal={} {}", goal.getId(), e.getMessage());
        }
    }

    // ==================== 内部：杂项 ====================

    private Object lockFor(String goalId) {
        return locks.computeIfAbsent(goalId == null ? "" : goalId, k -> new Object());
    }

    private Goal own(String goalId, String uid) {
        Goal goal = goalMapper.selectById(goalId);
        if (goal == null) throw new IllegalArgumentException("目标不存在");
        if (uid != null && !uid.isBlank() && goal.getUserId() != null
                && !goal.getUserId().equals(uid)) {
            throw new IllegalStateException("只有发起人可以操作这条目标");
        }
        return goal;
    }

    /**
     * 工件指纹：sha256(沙盒工作区全部文件的 路径|大小|修改时间)。
     * <p>只有目标带工件类标准时才钉（{@link #pinSubjectIfNeeded}）——纯检索/纯写作类目标不钉，
     * 否则会因「没有工件」而永远验不了（recordVerification 拿不到 binding 直接抛）。
     */
    public String fingerprint(String sessionId, String uid) {
        try {
            io.agentscope.harness.agent.filesystem.AbstractFilesystem fs =
                    sandboxService.frameworkFilesystem(sessionId, uid, false);
            if (fs == null) return null;
            io.agentscope.core.agent.RuntimeContext rc = io.agentscope.core.agent.RuntimeContext.builder()
                    .userId(uid == null || uid.isBlank() ? "guest" : uid)
                    .sessionId(sessionId)
                    .build();
            List<String> lines = new ArrayList<>();
            walkFs(fs, rc, "/", lines, 0);
            if (lines.isEmpty()) return null;
            java.util.Collections.sort(lines);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                md.update(line.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return "sha256:" + hex(md.digest());
        } catch (Exception e) {
            log.warn("[GOAL] 工件指纹计算失败: session={} {}", sessionId, e.getMessage());
            return null;
        }
    }

    private static void walkFs(io.agentscope.harness.agent.filesystem.AbstractFilesystem fs,
                               io.agentscope.core.agent.RuntimeContext rc, String dir,
                               List<String> lines, int depth) {
        if (depth > 4 || lines.size() > 500) return;   // 有界：工作区不是文件系统的全部
        io.agentscope.harness.agent.filesystem.model.LsResult ls = fs.ls(rc, dir);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return;
        for (var entry : ls.entries()) {
            if (entry == null || entry.path() == null) continue;
            if (entry.isDirectory()) {
                walkFs(fs, rc, entry.path(), lines, depth + 1);
            } else {
                lines.add(entry.path() + "|" + entry.size() + "|" + entry.modifiedAt());
            }
        }
    }

    /**
     * 钉工件指纹。<b>每一条目标都钉</b>——机器验证器要绑定，人工核验同样要一个绑定锚点
     * （{@code recordVerification} 拿不到 binding 直接抛，纯手动目标不钉就永远验不了）。
     * <p>有工件（沙盒工作区有文件）时钉 sha256 指纹，工件被改 → 旧结论 STALE；
     * 没有工件时钉一个稳定的目标常量（{@code goal:{id}}）——它不会因「没有工件」而失效，
     * 也不会假装有版本可失效。
     */
    private void pinSubjectIfNeeded(Goal goal) {
        String fp = fingerprint(goal.getSessionId(), goal.getUserId());
        if (fp == null) fp = "goal:" + goal.getId();
        goal.setSubjectVersion(fp);
        io.agentscope.harness.agent.HarnessAgent agent =
                sessions.agentOf(goal.getUserId(), goal.getSessionId());
        GoalBridge.SyncResult r = goalBridge.pinSubject(agent, goal.getUserId(), goal.getSessionId(), fp);
        if (r.ok()) {
            goal.setStateRevision(r.revision());
            goal.setContractVersion(r.contractVersion());
        }
        log.info("[GOAL] 工件指纹已钉: goal={} {}", goal.getId(), fp);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static int applyBudget(Object requested, int cap) {
        if (!(requested instanceof Number n)) return cap;
        int v = n.intValue();
        if (v <= 0) return 0;
        return cap <= 0 ? v : Math.min(v, cap);
    }

    private static long applyBudgetLong(Object requested, long cap) {
        if (!(requested instanceof Number n)) return cap;
        long v = n.longValue();
        if (v <= 0) return 0;
        return cap <= 0 ? v : Math.min(v, cap);
    }
}
