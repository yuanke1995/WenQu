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
 * {@link #onTurnEnd}（记账 / 首轮采纳助手起草的标准 / 验收 / 人工核验关卡 / 续跑一拍）。
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
    public static final String ACHIEVED = "ACHIEVED";
    public static final String ENDED_INCOMPLETE = "ENDED_INCOMPLETE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 非终态集合（同会话至多一条）：就是 {@link #activeGoal} 的查询条件，摘要条回捞靠它 */
    private static final java.util.Set<String> ACTIVE = java.util.Set.of(
            DRAFT, CLARIFYING, AWAITING_CONFIRMATION, RUNNING, VERIFYING, WAITING_INPUT, PAUSED);

    /** 目标确认卡在 c_ai_tool_approval 里的类型标识（与 askUser / planApproval 同形的第四档） */
    public static final String GOAL_CONFIRM_TOOL = "goalConfirm";

    /** 单目标标准条数上限（框架帽 64；问渠收到 12：一张卡看得完、写得完） */
    public static final int MAX_CRITERIA = 12;

    private final GoalMapper goalMapper;
    private final GoalBridge goalBridge;
    private final SessionEventService sessionEventService;
    private final ConfigService configService;
    private final ChatRunBus chatRunBus;
    private final com.wenqu.ai.agentx.FrameworkSessions sessions;
    private final ToolApprovalMapper toolApprovalMapper;
    private final com.wenqu.ai.service.SandboxService sandboxService;
    private final GoalVerificationRegistry verificationRegistry;
    /** 目标指令回声要写进对话（c_ai_message），状态机本体不碰这张表 */
    private final SessionService sessionService;

    /** 续跑驱动（引擎实现，构造期后绑）：跨轮循环的控制流归框架，问渠只数预算并发起下一轮 */
    private volatile GoalTurnDriver turnDriver;

    /** 每目标一把锁：状态迁移与框架同步不许并发（同会话串行已经挡了一层，这里是控制器动作之间） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public GoalService(GoalMapper goalMapper, GoalBridge goalBridge, SessionEventService sessionEventService,
                       ConfigService configService, ChatRunBus chatRunBus,
                       com.wenqu.ai.agentx.FrameworkSessions sessions, ToolApprovalMapper toolApprovalMapper,
                       com.wenqu.ai.service.SandboxService sandboxService,
                       GoalVerificationRegistry verificationRegistry, SessionService sessionService) {
        this.goalMapper = goalMapper;
        this.goalBridge = goalBridge;
        this.sessionEventService = sessionEventService;
        this.configService = configService;
        this.chatRunBus = chatRunBus;
        this.sessions = sessions;
        this.toolApprovalMapper = toolApprovalMapper;
        this.sandboxService = sandboxService;
        this.verificationRegistry = verificationRegistry;
        this.sessionService = sessionService;
    }

    /** 续跑驱动（引擎侧实现）。由装配处显式绑定——引擎是那里 new 的，不归 Spring 管。 */
    public void bindTurnDriver(GoalTurnDriver driver) {
        this.turnDriver = driver;
    }

    /** 跨轮续跑的发起口（实现里做 submit + 跟随，见 AgentscopeChatEngine#continueGoalTurn）。 */
    public interface GoalTurnDriver {
        void continueTurn(Goal goal, String input, int n);
    }

    // ==================== 配置 ====================

    /**
     * 这个智能体开不开目标模式（资源级三态：null=开）。
     * <p>平台级总闸已去掉——目标模式能不能用只由「哪个智能体开着他」决定，管理员不再有一个
     * 「全平台一键关掉」的旋钮（要关就逐个智能体关）。
     */
    public boolean enabledFor(com.wenqu.ai.model.Agent agent) {
        return agent == null || agent.getGoalEnabled() == null || agent.getGoalEnabled() == 1;
    }

    public long confirmTimeoutMs() {
        return configService.getLong("chat.goalTimeoutMs", 600_000L);
    }

    /**
     * 单条验收标准的验证上限（秒）。<b>这不是管理员旋钮</b>：它只防一个卡死的验证器把整条目标
     * 循环挂住（超时就记这一条 ERROR，其它条照验），所以不设配置项，只留这一个常量。
     */
    public static final int VERIFY_TIMEOUT_SECONDS = 60;

    public int verifyTimeoutSeconds() {
        return VERIFY_TIMEOUT_SECONDS;
    }

    // ==================== 用户动作 ====================

    /**
     * 设为目标。
     *
     * <p><b>两种入口</b>：
     * <ul>
     *   <li>{@code startNow=true}（{@code /goal 目标}、工具条按钮）：建行即 {@code RUNNING}，
     *       不弹确认卡、不等用户逐条拍板。没带标准时首轮让助手起草，回来自动采纳（用户可改）；</li>
     *   <li>{@code startNow=false}（旧确认卡路径）：建行进 {@code AWAITING_CONFIRMATION}，
     *       标准草稿等用户拍板后才开跑。存量目标与「另起目标」仍可用。</li>
     * </ul>
     *
     * <p>同会话已有非终态目标时按「换目标」处理：旧的收成 {@code ENDED_INCOMPLETE} 再起新的
     * ——历史与旧验证留在旧行里可审计（框架那边也只在 beginTask 换 taskId 时清场）。
     * <p>没有预算入参：目标不设轮数/token/工具/时长上限，跑不跑得完只看验收标准验没验过、
     * 以及你什么时候按暂停或就此收尾。
     */
    public Goal start(String sessionId, String uid, String agentId, String objective,
                      List<Map<String, Object>> criteriaInput, boolean startNow, RunChannel channel) {
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
            List<GoalBridge.Criterion> criteria = buildCriteria(criteriaInput);
            if (startNow) {
                // 用户发起 /goal 时若自己写了标准，那就是用户拍板；没写则留空，首轮由助手起草。
                List<GoalBridge.Criterion> confirmed = new ArrayList<>();
                for (GoalBridge.Criterion c : criteria) {
                    confirmed.add(new GoalBridge.Criterion(c.key(), c.id(), c.kind(), c.text(), "CONFIRMED",
                            "goal-start:" + goalId, c.verifierId(), c.verifierParams()));
                }
                criteria = confirmed;
            }
            Goal goal = new Goal();
            goal.setId(goalId);
            goal.setSessionId(sessionId);
            goal.setUserId(uid);
            goal.setAgentId(agentId);
            goal.setObjective(objective.strip());
            goal.setCriteriaJson(JSON.toJSONString(criteria));
            goal.setConstraintsJson(JSON.toJSONString(List.of()));
            goal.setStatus(startNow ? RUNNING : AWAITING_CONFIRMATION);
            goal.setStartedAt(startNow ? LocalDateTime.now() : null);
            goal.setUsedTurns(0);
            goal.setUsedTokens(0L);
            goal.setUsedToolCalls(0);
            goal.setUsedMs(0L);
            goal.setTurnCounter(0);
            goal.setCreatedAt(LocalDateTime.now());
            goalMapper.insert(goal);
            // 对话里留一行回声：这条目标在会话里说过什么，只有 c_ai_goal 一份的话，
            // 换过目标之后对话就读不出「什么时候定了什么目标」。它不是提问，是一句指令——
            // 前端据此挂命令标注，并撤掉这行的重发/重新生成（重发一句指令没有意义）
            echoGoalCommand(sessionId, goalId, objective.strip());
            if (!startNow) {
                // 确认卡复用 c_ai_tool_approval（tool_name=goalConfirm）：它已经是「带轮身份的持久待办 +
                // 终态 + 答复审计」的通用表，再立一张 approval 表就是第四份平行真值源
                openConfirmCard(goal, channel);
                log.info("[GOAL] 目标已创建待确认: goal={} session={} 标准 {} 条", goalId, sessionId,
                        criteria.size());
                emitState(goal, channel, null);
                return goal;
            }
            // 创建即开始：框架侧当场落 scope/标准，不等下一轮 submit 再补
            String syncWarn = null;
            io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, sessionId);
            GoalBridge.SyncResult r = goalBridge.beginOrUpdate(agent, uid, sessionId, goal,
                    criteria, List.of(), null);
            if (r.ok()) {
                persistFrameworkIds(goal, criteria, List.of(), r);
                goal.setStateRevision(r.revision());
                goal.setContractVersion(r.contractVersion());
            } else {
                syncWarn = GoalCopy.SYNC_WARN + "（" + r.reason() + "）";
            }
            goalMapper.updateById(goal);
            // 工件类标准钉指纹（纯检索/写作类目标用 goal:{id} 兜底：框架验证要求 binding 非空）
            pinSubjectIfNeeded(goal);
            // 钉完的 subjectVersion/contractVersion 也要落库：下一次 onTurnEnd 的 STALE 复判读的是库里这份
            goalMapper.updateById(goal);
            log.info("[GOAL] 目标已开始: goal={} session={} 标准 {} 条（{}）", goalId, sessionId,
                    criteria.size(), criteria.isEmpty() ? "待助手起草" : "用户已给");
            emitState(goal, channel, syncWarn);
            // 创建即开工：首轮输入会要求助手先起草标准（见 GoalCopy.startPrompt），不等用户再问一句
            kickContinuation(goal, channel);
            return goal;
        }
    }

    /**
     * 目标指令回声：把这句「设为目标」按普通用户消息落进对话，再补一列标记。
     * <p>失败只告警不抛：目标本身已经成立，回声只是对话里的一行留痕（与确认卡落库同一个取舍）。
     */
    private void echoGoalCommand(String sessionId, String goalId, String objective) {
        try {
            String messageId = sessionService.appendMessage(sessionId, "user", objective, null, null);
            sessionService.markGoalCommand(messageId, goalId);
        } catch (Exception e) {
            log.warn("[GOAL] 目标指令回声落库失败（不影响目标）: goal={} {}", goalId, e.getMessage());
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
            // 工件类标准钉指纹（纯检索/写作类目标用 goal:{id} 兜底：框架验证要求 binding 非空）
            pinSubjectIfNeeded(goal);
            // 钉完的 subjectVersion/contractVersion 也要落库：下一次 onTurnEnd 的 STALE 复判读的是库里这份
            goalMapper.updateById(goal);
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

    /** 继续：暂停或被「等你核验」拦下时都能恢复；重判目标/权限/工件版本都在下一轮开跑前做。 */
    public Goal resume(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            if (!PAUSED.equals(goal.getStatus()) && !WAITING_INPUT.equals(goal.getStatus())) {
                throw new IllegalStateException("这条目标不在暂停/待核验状态");
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

    /** 主动收尾：未完成清单随终态帧给用户，不悄然截断。 */
    public Goal finishIncomplete(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            finish(goal, ENDED_INCOMPLETE, "incomplete", channel);
            resolveConfirmCard(goal, "REJECTED", "用户主动收尾");
            return goal;
        }
    }

    /** 可以就地改验收标准的那几档：待确认那一档归确认卡管（卡片上逐条勾选就是它的编辑面） */
    private static final java.util.Set<String> EDITABLE = java.util.Set.of(
            RUNNING, VERIFYING, WAITING_INPUT, PAUSED);

    /**
     * 编辑进行中的目标：改验收标准。<b>目标原文不在这里改</b>——框架把原文钉在
     * task 身份上（同一 taskId 换原文直接抛 "Use a new task identity when changing the objective"），
     * 要换口径只能另起一条目标（{@link #start} 会把旧的收成「未完成收尾」）。
     *
     * <p>标准按整份新清单覆盖：原文一字未动的那条沿用框架 requirement 与它的验证记录；改过字句的
     * 和新写的都算新条目（用户在编辑器里打的字本身就是拍板，不再走一遍确认卡）；从清单里去掉的
     * 就地记 REJECTED 并留在原处——框架要读到这条 REJECTED 才不再把它当要求，验证历史也有地方挂。
     */
    public Goal edit(String goalId, String uid, List<Map<String, Object>> criteriaInput,
                     RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            if (!EDITABLE.contains(goal.getStatus())) {
                // 还没开跑的那三档不叫「已经结束」：它们的编辑面是确认卡（逐条拍板就是改标准），
                // 拿终态那句话去搪塞，读起来像目标早没了
                boolean notStarted = DRAFT.equals(goal.getStatus())
                        || CLARIFYING.equals(goal.getStatus())
                        || AWAITING_CONFIRMATION.equals(goal.getStatus());
                throw new IllegalStateException(notStarted
                        ? "这条目标还没开始，验收标准请在目标卡上逐条拍板"
                        : "这条目标已经结束了，改不了");
            }
            List<GoalBridge.Criterion> existing = readCriteria(goal);
            List<GoalBridge.Criterion> constraints = readConstraints(goal);
            if (criteriaInput != null && criteriaInput.size() > MAX_CRITERIA) {
                throw new IllegalArgumentException("验收标准最多 " + MAX_CRITERIA + " 条");
            }
            List<GoalBridge.Criterion> next = new ArrayList<>();
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (GoalBridge.Criterion c : existing) taken.add(c.key());
            if (criteriaInput != null) {
                for (Map<String, Object> raw : criteriaInput) {
                    if (raw == null) continue;
                    String text = raw.get("text") == null ? "" : String.valueOf(raw.get("text")).strip();
                    if (text.isBlank()) continue;
                    if (text.length() > 2000) throw new IllegalArgumentException("单条标准太长（上限 2000 字）");
                    String key = raw.get("key") == null ? null : String.valueOf(raw.get("key"));
                    GoalBridge.Criterion prev = key == null ? null : byKey(existing, key);
                    if (prev != null && prev.text().equals(text)) {
                        next.add(prev);
                        continue;
                    }
                    String verifierId = raw.get("verifierId") == null
                            ? (prev == null ? null : prev.verifierId()) : String.valueOf(raw.get("verifierId"));
                    next.add(new GoalBridge.Criterion(freshKey(taken), null, "ACCEPTANCE_CRITERION",
                            text, "CONFIRMED", null, verifierId, new LinkedHashMap<>()));
                }
            }
            for (GoalBridge.Criterion c : existing) {
                boolean kept = next.stream().anyMatch(x -> x.key().equals(c.key()));
                if (!kept && !"REJECTED".equals(c.status())) {
                    next.add(new GoalBridge.Criterion(c.key(), c.id(), c.kind(), c.text(),
                            "REJECTED", c.decisionSource(), c.verifierId(), c.verifierParams()));
                }
            }
            goal.setCriteriaJson(JSON.toJSONString(next));
            goalMapper.updateById(goal);
            String syncWarn = null;
            io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, goal.getSessionId());
            GoalBridge.SyncResult r = goalBridge.beginOrUpdate(agent, uid, goal.getSessionId(),
                    goal, next, constraints, goal.getSubjectVersion());
            if (r.ok()) {
                persistFrameworkIds(goal, next, constraints, r);
                goal.setStateRevision(r.revision());
                goal.setContractVersion(r.contractVersion());
            } else {
                syncWarn = GoalCopy.SYNC_WARN + "（" + r.reason() + "）";
            }
            goalMapper.updateById(goal);
            // 改完标准如果正卡在「等你核验」，用户这一次编辑就是「继续」的意思：转回进行中，
            // 下一轮带着新标准跑；暂停态不自动恢复（暂停是用户的动作，要他自己点继续）。
            if (WAITING_INPUT.equals(goal.getStatus())) {
                goal.setStatus(RUNNING);
                goalMapper.updateById(goal);
            }
            log.info("[GOAL] 目标已就地改订: goal={} 标准 {} 条（含作废 {} 条）", goalId, next.size(),
                    next.stream().filter(c -> "REJECTED".equals(c.status())).count());
            emitState(goal, channel, syncWarn);
            kickContinuation(goal, channel);
            return goal;
        }
    }

    private static GoalBridge.Criterion byKey(List<GoalBridge.Criterion> list, String key) {
        for (GoalBridge.Criterion c : list) {
            if (key.equals(c.key())) return c;
        }
        return null;
    }

    /** 标准条目的本地主键（只在 propose 进框架之前认人）：避开库里已有的那些，不靠时间戳防撞 */
    private static String freshKey(java.util.Set<String> taken) {
        int i = 1;
        String k;
        do {
            k = "k" + i++;
        } while (taken.contains(k));
        taken.add(k);
        return k;
    }

    // ==================== 引擎钩子 ====================

    /**
     * 每轮 submit 前：把库里的目标态覆盖写进框架活状态（设计稿 §5.2 的回显兜底那一条）。
     *
     * @return 本轮生效的目标（null=这条会话没有进行中的目标）
     */
    public Goal syncBeforeSubmit(String sessionId, String uid, String agentId) {
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
     * 轮收尾：记账 → 首轮采纳助手起草的标准 → 验收 → 人工核验关卡 → 续跑一拍。
     * <p>叫停/宽限中止时框架的 {@code ended()} 只认 completed/cancelled，{@code failed} 与挂在
     * 提问卡上的 {@code suspended} 都不算结束——所以目标循环退出前在这里自己结算，不指望框架兜。
     */
    public void onTurnEnd(String sessionId, String uid, String turnId, long machineMs, int toolCalls,
                          RunChannel channel) {
        Goal goal = activeGoal(sessionId);
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            boolean firstTurn = used(goal.getTurnCounter()) == 0;
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
            // 首轮起草：用户用 /goal 发起、还没有标准时，助手本轮提出的候选标准自动采纳，
            // 记为 CALLER（不是 USER）。用户可改可删；旧确认卡路径不会走到这里（它是 AWAITING_CONFIRMATION）。
            boolean adopted = false;
            if (firstTurn && RUNNING.equals(goal.getStatus())) {
                List<GoalBridge.Criterion> current = readCriteria(goal);
                boolean noConfirmed = current.stream().noneMatch(c -> c.confirmed() && c.acceptance());
                if (noConfirmed) {
                    io.agentscope.harness.agent.HarnessAgent agent = sessions.agentOf(uid, sessionId);
                    List<GoalBridge.Criterion> drafted = goalBridge.adoptDrafted(agent, uid, sessionId,
                            goal, current, MAX_CRITERIA);
                    if (!drafted.isEmpty()) {
                        current.addAll(drafted);
                        goal.setCriteriaJson(JSON.toJSONString(current));
                        adopted = true;
                        // decide 会推进框架的 contractVersion：库里的水位要当场跟上，
                        // 否则下一次 syncBeforeSubmit 一更新，刚采纳的人工结论会被判成 STALE
                        long[] wm = goalBridge.watermarks(sessions.agentOf(uid, sessionId), uid, sessionId);
                        if (wm != null) {
                            goal.setStateRevision(wm[0]);
                            goal.setContractVersion(wm[1]);
                        }
                        log.info("[GOAL] 助手起草的验收标准已自动采纳: goal={} {} 条", goal.getId(), drafted.size());
                    }
                }
            }
            // 验收（轮外）：每条标准彼此独立，一条 ERROR 不打回其它条
            if (java.util.Set.of(RUNNING, VERIFYING).contains(goal.getStatus())) {
                verifyAll(goal, uid, channel);
            }
            // 审核门：有标准、但剩下的全是人工核验（本轮又没有新采纳）→ 停下来等你逐条勾，
            // 不自转。人工标准永远验不出 PASSED，继续跑就是空烧 token；「继续」仍是用户的一句话。
            if (!adopted && RUNNING.equals(goal.getStatus())) {
                applyReviewGate(goal);
            }
            goalMapper.updateById(goal);
            if (ACHIEVED.equals(goal.getStatus())) {
                emitState(goal, channel, null);
                return;
            }
            emitState(goal, channel, null);
            // 续跑不在这里发起：这一轮还占着会话的跟随位（跟随位没释放时新的一轮会被「已有跟随者」挡掉）。
            // 由调用方在本轮彻底收尾之后调 {@link #continueAfterTurn}。
        }
    }

    /**
     * 把「只剩人工核验标准」的目标停在 {@code WAITING_INPUT}，等用户勾完再继续。
     * <p>没有标准也停：零标准目标永远验不出「完成」，自转只会变成模型每轮宣布完成、每轮再起一轮。
     * 用户在目标卡上补一条（编辑）或点「继续」即可重新开工。
     */
    private void applyReviewGate(Goal goal) {
        List<GoalBridge.Criterion> criteria = readCriteria(goal);
        boolean anyConfirmed = criteria.stream().anyMatch(c -> c.confirmed() && c.acceptance());
        if (!anyConfirmed) {
            goal.setStatus(WAITING_INPUT);
            goal.setEndReason(null);
            return;
        }
        List<GoalBridge.Criterion> pending = pendingCriteria(goal);
        if (!pending.isEmpty() && pending.stream().allMatch(GoalService::manualCriterion)) {
            goal.setStatus(WAITING_INPUT);
            goal.setEndReason(null);
        }
    }

    /** 还没拿到「当前有效 PASSED」的已确认标准（未验/失败/过期都算没过）。 */
    private List<GoalBridge.Criterion> pendingCriteria(Goal goal) {
        List<GoalBridge.Criterion> out = new ArrayList<>();
        for (GoalBridge.Criterion c : readCriteria(goal)) {
            if (!c.confirmed() || !c.acceptance()) continue;
            Map<String, Object> v = GoalRecords.latestVerification(goal, c.key());
            if (v == null || !"PASSED".equals(String.valueOf(v.get("outcome"))) || GoalRecords.isStale(goal, v)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 这条标准只能靠人工勾（没有配机器验证器）。 */
    private static boolean manualCriterion(GoalBridge.Criterion c) {
        return c.verifierId() == null || c.verifierId().isBlank()
                || GoalVerificationRegistry.MANUAL.equals(c.verifierId());
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
        int n = (int) used(goal.getTurnCounter()) + 1;
        List<GoalBridge.Criterion> pending = pendingCriteria(goal);
        boolean hasConfirmed = readCriteria(goal).stream().anyMatch(c -> c.confirmed() && c.acceptance());
        // 没有一条待验的验收标准就不自转。零标准的目标永远验不出「过」，自动续跑会变成
        // 「模型每轮都宣布完成、每轮都被再起一轮」——真机实测烧到 51 万 token、6 分钟才被我手动暂停。
        // 首轮照跑：用户刚定下目标，这一轮先让助手起草验收标准（见 GoalCopy.startPrompt）。
        if (pending.isEmpty() && used(goal.getTurnCounter()) > 0) {
            // 零标准目标被「继续」拉起时不能装成进行中：没有可验的东西，再跑一轮也判不出完成。
            // 停在「等你核验」，条子上的提示会告诉用户补一条标准（编辑后就自动继续）。
            if (!hasConfirmed) {
                goal.setStatus(WAITING_INPUT);
                goalMapper.updateById(goal);
                emitState(goal, channel, null);
            }
            log.info("[GOAL] 没有待验的验收标准，不再自转: goal={} 已跑 {} 轮", goal.getId(),
                    used(goal.getTurnCounter()));
            return;
        }
        String input = !hasConfirmed && used(goal.getTurnCounter()) == 0
                ? GoalCopy.startPrompt(goal.getObjective())
                : GoalCopy.continuation(goal.getObjective(), pending.stream().map(GoalBridge.Criterion::text).toList());
        log.info("[GOAL] 起第 {} 轮续跑: goal={} 未过标准 {} 条{}", n, goal.getId(), pending.size(),
                hasConfirmed ? "" : "（首轮先起草标准）");
        driver.continueTurn(goal, input, n);
    }

    // ==================== 验收（M2 的编排在这里，验证器在 GoalVerificationRegistry） ====================

    /** 用户点「现在验收」：全量验一遍，不管预算与轮次。 */
    public Map<String, Object> verifyNow(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            verifyAll(goal, uid, channel);
            if (RUNNING.equals(goal.getStatus())) applyReviewGate(goal);
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
                                    "还有 " + remainingCriteria(goal) + " 条标准没有验证凭据");
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
            Map<String, Object> manual = ManualCheckVerifier.verdict(outcome,
                    outcome.equals("PASSED") ? "用户确认达成" : "用户标记未达成");
            // 把勾选那一刻的绑定一起记下：工件/标准之后变了，这条结论要能判成 STALE，而不是一直算过
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("contractVersion", goal.getContractVersion());
            binding.put("subjectVersion", goal.getSubjectVersion());
            manual.put("binding", binding);
            GoalRecords.upsertVerification(goal, criterionKey, manual);
            // 人工核验器读的是库里最新那份（freshGoal）：这一笔必须先落库再验，否则它读到的是旧行，
            // 用户刚点的「达成」会被判成「还没勾」——这个先后顺序是死顺序。
            goalMapper.updateById(goal);
            // 勾完立刻按当前证据重判一次：用户点「达成这条」就是在说「这条好了」，
            // 还要他再点一次「现在验收」才看到达成，是把同一次表态拆成两步
            verifyAll(goal, uid, channel);
            boolean failed = "FAILED".equals(outcome);
            if (RUNNING.equals(goal.getStatus()) && !failed) {
                // 只勾了一部分：继续停在「等你核验」，别悄悄转成进行中
                applyReviewGate(goal);
            }
            goalMapper.updateById(goal);
            emitState(goal, channel, null);
            // 用户说「这条没过」：这就是让他接着改，当场再起一轮；之后仍会在审核门再停
            if (failed && RUNNING.equals(goal.getStatus())) {
                kickContinuation(goal, channel);
            }
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
        m.put("turnIndex", Math.max(1, used(goal.getTurnCounter())));
        m.put("syncWarn", syncWarn);
        m.put("firstTurnId", goal.getFirstTurnId() == null ? "" : goal.getFirstTurnId());
        m.put("lastTurnId", goal.getLastTurnId() == null ? "" : goal.getLastTurnId());
        m.put("live", sessions.peek(goal.getUserId(), goal.getSessionId()) != null);
        m.put("endReason", goal.getEndReason());
        m.put("hint", hintOf(goal));
        m.put("createdAt", iso(goal.getCreatedAt()));
        m.put("startedAt", iso(goal.getStartedAt()));
        m.put("endedAt", iso(goal.getEndedAt()));
        m.put("remainingMs", confirmTimeoutMs());
        return m;
    }

    /**
     * 条子上的那一句人话：只在「等你核验」这一档给。
     * <p>没有标准与有标准但全是人工核验，是两种不同的停法，要说的也是两件事：
     * 前者要用户补一条，后者要用户逐条勾；别混成一句「等你回答」。
     */
    private String hintOf(Goal goal) {
        if (!WAITING_INPUT.equals(goal.getStatus())) return null;
        boolean anyConfirmed = readCriteria(goal).stream().anyMatch(c -> c.confirmed() && c.acceptance());
        if (!anyConfirmed) return "助手还没有提出验收标准，补一条再继续";
        int left = pendingCriteria(goal).size();
        return left > 0
                ? "还有 " + left + " 条等你核验；勾完自动完成，也可以点继续再跑一轮"
                : "等你核验";
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
            row.put("drafted", c.decisionSource() != null
                    && c.decisionSource().startsWith(GoalBridge.DRAFT_SOURCE_PREFIX));
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

    /** 库里那一列可能是空的（历史行/手改过）：一律当 0，不让面板因为一个 null 崩掉 */
    private static long used(Integer v) {
        return v == null ? 0L : v.longValue();
    }

    private static long used(Long v) {
        return v == null ? 0L : v;
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

    // ==================== 内部：跑量账 ====================
    // 只有「已经跑了多少」这一本账，没有「最多能跑多少」那一本：目标不设预算。

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

    /** warn：与 {@link #emitState} 同一套去向。控制器侧的动作（继续/收尾）没有通道，走总线镜像。 */
    private void emitWarn(Goal goal, RunChannel channel, String msg) {
        if (channel != null) {
            channel.emit(RunEventType.WARN, msg);
            if (channel.emitter() instanceof com.wenqu.ai.service.CollectingSseEmitter) {
                chatRunBus.publishMirrorToSession(goal.getSessionId(), "warn", msg);
            }
        } else {
            chatRunBus.publishMirrorToSession(goal.getSessionId(), "warn", msg);
        }
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
}
