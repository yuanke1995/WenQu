package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.agent.goal.GoalContinuation;
import com.wenqu.ai.agent.goal.GoalErrorClass;
import com.wenqu.ai.agent.goal.GoalOutcome;
import com.wenqu.ai.agent.goal.GoalPhase;
import com.wenqu.ai.agent.goal.GoalPromptBuilder;
import com.wenqu.ai.agent.goal.GoalSettings;
import com.wenqu.ai.agent.goal.GoalProposal;
import com.wenqu.ai.agent.goal.GoalVerdict;
import com.wenqu.ai.agent.goal.GoalVerifier;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.mapper.GoalMapper;
import com.wenqu.ai.mapper.GoalRoundMapper;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.model.GoalRound;
import com.wenqu.ai.model.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 目标模式的任务运行时（问渠这一侧的全部「目标」控制面）。
 *
 * <p>与旧版的分工差异（2026-10-22 重构）：
 * <ul>
 *   <li><b>调度分离</b>：状态迁移不再由「轮终回调直接起下一轮」驱动。{@link #claimReady} 由
 *       {@code GoalScheduler} 周期调用，用 DB 租约条件更新抢到唯一执行权；引擎只负责跑一轮，
 *       跑完调 {@link #onRoundSettled} 写结果。</li>
 *   <li><b>权威状态</b>：{@link GoalContinuation} 保存目标/阶段/计划/事实/产物/失败尝试/缺口，
 *       每轮提示词由 {@link GoalPromptBuilder} 从它生成，不再靠聊天历史猜进度。</li>
 *   <li><b>证据完成</b>：模型通过 {@code goal_submit} 只提交申请；{@link GoalVerifier} 逐条核验
 *       证据后才允许 {@link #ACHIEVED}。文本 marker 只作为「申请」的兼容入口，不再直接算完成。</li>
 *   <li><b>预算/进展/恢复</b>：Token/时长/工具调用/轮次硬预算；连续无进展转暂停；
 *       租约过期由 {@link #recoverExpiredLeases} 对账后恢复或转暂停。</li>
 * </ul>
 *
 * <p>对外阶段仍是四档（RUNNING/PAUSED/ACHIEVED/CANCELLED）加 FAILED 与等待态；内部阶段见
 * {@link GoalPhase}。同会话至多一条未结束目标。
 *
 * @author yuanke
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);

    // ==================== 对外阶段 ====================

    /** 已入队/正在自动推进（UI 都读作「进行中」） */
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    /** 停下可继续（用户停止/受阻/无进展/异常） */
    public static final String PAUSED = "PAUSED";
    /** 证据核验通过 */
    public static final String ACHIEVED = "ACHIEVED";
    /** 预算耗尽/不可恢复失败（可继续，但预算不重置） */
    public static final String FAILED = "FAILED";
    /** 用户删除 */
    public static final String CANCELLED = "CANCELLED";
    /** 等待人工审批（内部阶段，状态仍归 RUNNING） */
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    /** 等待外部事件/异步工具 */
    public static final String WAITING_EVENT = "WAITING_EVENT";
    /** 正在核验完成申请 */
    public static final String VERIFYING = "VERIFYING";

    /** 同会话「还归用户管」的目标：有这些状态的行才算当前目标。 */
    private static final Set<String> CURRENT = Set.of(QUEUED, RUNNING, PAUSED, VERIFYING,
            WAITING_APPROVAL, WAITING_EVENT);
    /** 会被调度器继续推进的状态。 */
    private static final Set<String> ACTIVE = Set.of(QUEUED, RUNNING, VERIFYING,
            WAITING_APPROVAL, WAITING_EVENT);
    private static final Set<String> TERMINAL = Set.of(ACHIEVED, FAILED, CANCELLED);

    /**
     * 目标轮的轮 id 前缀（跨轮续跑的幂等键 {@code goal:{goalId}:{n}}）。
     * <p>站内通知按它认「这一轮属不属于某个目标」：目标轮不逐轮报「跑完了」，只由目标自己说话一次，
     * 否则一个长目标会把铃铛变成进度条。续跑发起口（框架引擎）与本类的跨轮入口都拼这一个前缀，
     * 不许各写一份字面量。
     */
    public static final String GOAL_TURN_ID_PREFIX = "goal:";

    /** 目标原文产品上限（与 c_ai_goal.objective 列宽对齐，不是运营旋钮）。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    // 与 schema.sql 列宽对齐的截断上限：是存储契约，不做配置。
    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final int MAX_BLOCKED_CHARS = 1000;
    private static final int MAX_PROPOSAL_SUMMARY_CHARS = 4000;
    private static final int MAX_FAILURE_CHARS = 500;
    private static final int MAX_FACT_ITEM_CHARS = 200;

    private final GoalMapper goalMapper;
    private final GoalRoundMapper roundMapper;
    private final com.wenqu.ai.mapper.GoalEvidenceMapper evidenceMapper;
    private final SessionEventService sessionEventService;
    private final ChatRunBus chatRunBus;
    private final com.wenqu.ai.agentx.FrameworkSessions sessions;
    private final SessionService sessionService;
    private final ArtifactService artifactService;
    private final GoalSettings settings;
    private final GoalVerifier verifier;
    private final GoalPromptBuilder promptBuilder;
    /** 目标进终态时喊人回来（旁路：写不进通知不影响目标本身）。跨轮不逐轮报，只在这一处说话 */
    private final TurnNotifyService turnNotify;

    /** 续跑驱动（引擎实现，构造期后绑）：控制流归调度器，这里只把一轮交给引擎。 */
    private volatile GoalTurnDriver turnDriver;

    /** 每目标一把锁：用户动作与轮次结算不许并发。跨实例由 DB 租约兜底。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public GoalService(GoalMapper goalMapper, GoalRoundMapper roundMapper,
                       com.wenqu.ai.mapper.GoalEvidenceMapper evidenceMapper,
                       SessionEventService sessionEventService, ChatRunBus chatRunBus,
                       com.wenqu.ai.agentx.FrameworkSessions sessions,
                       SessionService sessionService, ArtifactService artifactService,
                       GoalSettings settings, GoalVerifier verifier,
                       GoalPromptBuilder promptBuilder, TurnNotifyService turnNotify) {
        this.goalMapper = goalMapper;
        this.roundMapper = roundMapper;
        this.evidenceMapper = evidenceMapper;
        this.sessionEventService = sessionEventService;
        this.chatRunBus = chatRunBus;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.settings = settings;
        this.verifier = verifier;
        this.promptBuilder = promptBuilder;
        this.turnNotify = turnNotify;
    }

    /** 续跑驱动（引擎侧实现）。由装配处显式绑定——引擎是那里 new 的，不归 Spring 管。 */
    public void bindTurnDriver(GoalTurnDriver driver) {
        this.turnDriver = driver;
    }

    /** 跨轮续跑的发起口（实现里做 submit + 跟随，见 AgentscopeChatEngine#submitGoalTurn）。 */
    public interface GoalTurnDriver {
        void continueTurn(Goal goal, String input, int n);
    }

    /** 这个智能体开不开目标模式（资源级三态：null=开）。 */
    public boolean enabledFor(com.wenqu.ai.model.Agent agent) {
        return agent == null || agent.getGoalEnabled() == null || agent.getGoalEnabled() == 1;
    }

    // ==================== 用户动作 ====================

    /**
     * 设为目标：建行即入队，调度器在一个节拍内起首轮。
     * <p>同会话已有未结束目标时不允许再建：要么改它、要么先删/等它做完。
     */
    public Goal start(String sessionId, String uid, String agentId, String objective, RunChannel channel) {
        String text = normalizeObjective(objective);
        synchronized (lockFor("session:" + sessionId)) {
            Goal current = currentGoal(sessionId);
            if (current != null) {
                throw new IllegalStateException("这条会话已有目标，先改它、继续它或删除它");
            }
            LocalDateTime now = LocalDateTime.now();
            Goal goal = new Goal();
            goal.setId(UUID.randomUUID().toString().replace("-", ""));
            goal.setSessionId(sessionId);
            goal.setUserId(uid);
            goal.setAgentId(agentId);
            goal.setObjective(text);
            goal.setStatus(RUNNING);
            goal.setPhase(GoalPhase.QUEUED);
            goal.setObjectiveVersion(0);
            goal.setTurnCounter(0);
            goal.setUsedTurns(0);
            goal.setUsedTokens(0L);
            goal.setUsedToolCalls(0);
            goal.setUsedMs(0L);
            goal.setAttempt(0);
            goal.setNoProgressRounds(0);
            goal.setRecoveryCount(0);
            goal.setBudgetRounds(settings.maxRounds());
            goal.setBudgetTokens(settings.maxTokens());
            goal.setBudgetMs(settings.maxMs());
            goal.setBudgetToolCalls(settings.maxToolCalls());
            goal.setRowVersion(0L);
            goal.setNextRunAt(now);
            goal.setCreatedAt(now);
            goal.setStartedAt(now);
            GoalContinuation c = GoalContinuation.of(text);
            c.setObjectiveVersion(0);
            goal.setContinuationJson(c.toJson());
            goalMapper.insert(goal);
            // 对话里留一行回声：换过目标之后，光靠 c_ai_goal 一行读不出「什么时候定了什么目标」
            echoGoalCommand(sessionId, goal.getId(), text);
            log.info("[GOAL] 目标已入队: goal={} session={} 预算 轮次={} token={}",
                    goal.getId(), sessionId, goal.getBudgetRounds(), goal.getBudgetTokens());
            emitState(goal, channel);
            return goal;
        }
    }

    /** 目标指令回声：把这句「设为目标」按普通用户消息落进对话，再补一列标记。 */
    private void echoGoalCommand(String sessionId, String goalId, String objective) {
        try {
            String messageId = sessionService.appendMessage(sessionId, "user", objective, null, null);
            sessionService.markGoalCommand(messageId, goalId);
        } catch (Exception e) {
            log.warn("[GOAL] 目标指令回声落库失败（不影响目标）: goal={} {}", goalId, e.getMessage());
        }
    }

    /** 停止（DSH: pause）：停下自动续跑，状态与进度都留着；正在跑的目标轮会被打断。 */
    public Goal pause(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            if (PAUSED.equals(goal.getStatus())) return goal;
            if (!ACTIVE.contains(goal.getStatus())) {
                throw new IllegalStateException("只有进行中的目标可以停止");
            }
            if (roundMapper.runningOf(goalId) != null) interruptRunningGoalTurn(goal);
            goal.setStatus(PAUSED);
            goal.setPhase(GoalPhase.QUEUED);
            goal.setEndReason("stopped");
            goal.setBlockedReason(null);
            goal.setWaitingReason(null);
            goal.setNextRunAt(null);
            goalMapper.updateById(goal);
            releaseLease(goal);
            emitState(goal, channel);
            log.info("[GOAL] 目标已停止: goal={}", goalId);
            return goal;
        }
    }

    /** 继续（DSH: resume）：从停下那一轮之后接着跑；受阻原因/结果一并清掉。 */
    public Goal resume(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            if (RUNNING.equals(goal.getStatus()) || QUEUED.equals(goal.getStatus())) {
                // 幂等「继续」：进程重启后 UI 可能显示「待恢复」但状态仍是 RUNNING。没有在飞的轮时，
                // 把下一轮时间拨到现在并放掉可能残留的旧租约，让调度器重新接手；有轮在飞则不动。
                if (roundMapper.runningOf(goalId) == null) {
                    // 只在「确实没人跟」时放租约：进程重启/心跳停了才放；租约还新鲜时别把
                    // 正在装配实例的 worker 踢掉（那会让它抢跟随位失败、白等 30 秒）。
                    if (isStale(goal)) releaseLease(goal);
                    goal.setNextRunAt(LocalDateTime.now());
                    goalMapper.updateById(goal);
                    emitState(goal, channel);
                }
                return goal;
            }
            if (!PAUSED.equals(goal.getStatus()) && !FAILED.equals(goal.getStatus())) {
                throw new IllegalStateException("这条目标不在暂停状态");
            }
            goal.setStatus(RUNNING);
            goal.setPhase(GoalPhase.QUEUED);
            goal.setEndReason(null);
            goal.setBlockedReason(null);
            goal.setOutcomeJson(null);
            goal.setErrorClass(null);
            goal.setWaitingReason(null);
            goal.setAttempt(0);
            goal.setNextRunAt(LocalDateTime.now());
            goalMapper.updateById(goal);
            emitState(goal, channel);
            log.info("[GOAL] 目标继续: goal={} 已跑 {} 轮", goalId, goal.getTurnCounter());
            return goal;
        }
    }

    /** 改目标原文（DSH: edit）：版本递增；正在跑的下一轮用新原文，在飞那一轮不受影响。 */
    public Goal edit(String goalId, String uid, String objective, RunChannel channel) {
        Goal goal = own(goalId, uid);
        String text = normalizeObjective(objective);
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            if (!CURRENT.contains(goal.getStatus())) {
                throw new IllegalStateException("这条目标已经结束了，改不了");
            }
            goal.setObjective(text);
            goal.setObjectiveVersion(goal.getObjectiveVersion() == null ? 1 : goal.getObjectiveVersion() + 1);
            GoalContinuation c = continuation(goal);
            c.setObjective(text);
            c.setObjectiveVersion(goal.getObjectiveVersion());
            c.setForceReplan(true);
            c.setCompletionCheck(false);
            c.setCompletionCheckCount(0);
            goal.setContinuationJson(c.toJson());
            // 不在请求线程里起轮：只把下一轮时间拨到「现在」，调度器一个节拍内接手。
            if (ACTIVE.contains(goal.getStatus())) {
                goal.setNextRunAt(LocalDateTime.now());
            }
            goalMapper.updateById(goal);
            emitState(goal, channel);
            log.info("[GOAL] 目标已改订: goal={} v={}", goalId, goal.getObjectiveVersion());
            return goal;
        }
    }

    /** 删除（DSH: clear）：目标从界面上撤下，历史与已跑出来的内容全部保留。 */
    public Goal clear(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            GoalRound running = roundMapper.runningOf(goalId);
            if (running != null) interruptRunningGoalTurn(goal);
            if (running != null) {
                running.setStatus(GoalRound.INTERRUPTED);
                running.setEndedAt(LocalDateTime.now());
                roundMapper.updateById(running);
            }
            finish(goal, CANCELLED, "cleared", channel);
            releaseLease(goal);
            log.info("[GOAL] 目标已删除: goal={}", goalId);
            return goal;
        }
    }

    // ==================== 调度器钩子 ====================

    /**
     * 抢一批可调度的目标（条件 UPDATE，affected=1 才算抢到）。
     * <p>由 {@link com.wenqu.ai.agent.goal.GoalScheduler} 周期调用。
     */
    public List<Goal> claimReady(String owner, int limit) {
        List<Goal> out = new ArrayList<>();
        for (Goal g : goalMapper.ready(limit)) {
            if (goalMapper.claim(g.getId(), owner, leaseSeconds()) == 1) {
                out.add(g);
            }
        }
        return out;
    }

    /** 续租：调度器每跳续一次，进程卡住时租约自然过期。 */
    public void renewLeases(String owner) {
        goalMapper.renewLeases(owner, leaseSeconds());
    }

    /**
     * 回收过期租约：崩溃/换副本后，把「有人拿着租约但心跳断了」的目标重新接管。
     * <p>执行中的轮先标 INTERRUPTED 再排下一轮；连续恢复超过阈值转 PAUSED，
     * 避免一个坏目标把预算烧在重跑上。
     */
    public void recoverExpiredLeases(String owner) {
        for (Goal g : goalMapper.expiredLeases(50)) {
            if (owner.equals(g.getLeaseOwner())) continue;
            if (goalMapper.claimExpired(g.getId(), owner, leaseSeconds()) != 1) continue;
            try {
                recoverOne(g.getId(), owner);
            } catch (Exception e) {
                log.warn("[GOAL] 过期租约恢复失败: goal={} {}", g.getId(), e.toString());
                releaseLease(g.getId(), owner);
            }
        }
        // 收尾没写完状态：next_run_at 空、租约也空。把它们重新排到「现在」。
        for (Goal g : goalMapper.stranded(50)) {
            synchronized (lockFor(g.getId())) {
                Goal fresh = reload(g.getId());
                if (!ACTIVE.contains(fresh.getStatus()) || fresh.getNextRunAt() != null) continue;
                if (fresh.getContinuationJson() == null || fresh.getContinuationJson().isBlank()) {
                    // 升级前的存量目标没有续跑上下文：自动重跑等于让模型从零再猜一遍。
                    // 如实转「待恢复」，用户点继续时再从 objective 建 Continuation。
                    fresh.setStatus(PAUSED);
                    fresh.setPhase(GoalPhase.QUEUED);
                    fresh.setEndReason("orphaned");
                    fresh.setBlockedReason("升级前的目标没有续跑上下文，已停下等确认");
                    fresh.setErrorClass(GoalErrorClass.ORPHANED.name());
                    fresh.setNextRunAt(null);
                    goalMapper.updateById(fresh);
                    emitState(fresh, null);
                    log.info("[GOAL] 存量目标缺续跑上下文，转待恢复: goal={}", fresh.getId());
                    continue;
                }
                fresh.setNextRunAt(LocalDateTime.now());
                goalMapper.updateById(fresh);
                log.info("[GOAL] 收尾遗留目标重新入队: goal={}", fresh.getId());
            }
        }
    }

    private void recoverOne(String goalId, String owner) {
        synchronized (lockFor(goalId)) {
            Goal goal = reload(goalId);
            if (!ACTIVE.contains(goal.getStatus())) {
                releaseLease(goal);
                return;
            }
            boolean executing = GoalPhase.EXECUTING.equals(goal.getPhase())
                    || GoalPhase.COMPLETION_CHECK.equals(goal.getPhase())
                    || GoalPhase.VERIFYING.equals(goal.getPhase())
                    || GoalPhase.RECOVERING.equals(goal.getPhase());
            GoalRound running = roundMapper.runningOf(goalId);
            if (executing || running != null) {
                if (running != null) {
                    LocalDateTime interruptedAt = LocalDateTime.now();
                    // 崩溃轮的 token 也是真花掉的：不记进预算，恢复后的预算会比实际成本小一截。
                    long interruptedTokens = running.getStartedAt() == null ? 0
                            : goalMapper.sumTokensBetween(goal.getSessionId(), running.getStartedAt(),
                                    interruptedAt);
                    running.setUsedTokens(interruptedTokens);
                    running.setStatus(GoalRound.INTERRUPTED);
                    running.setEndedAt(interruptedAt);
                    running.setSummary("执行中断（租约过期）");
                    roundMapper.updateById(running);
                    goal.setUsedTokens(nz(goal.getUsedTokens()) + interruptedTokens);
                    goal.setUsedTurns((int) nz(goal.getUsedTurns()) + 1);
                    goal.setLastTurnId(running.getTurnId());
                    if (goal.getFirstTurnId() == null) goal.setFirstTurnId(running.getTurnId());
                    if (interruptedTokens > 0) {
                        log.info("[GOAL] 中断轮补记 token: goal={} round={} tokens={}",
                                goalId, running.getN(), interruptedTokens);
                    }
                }
                int recoveries = ni(goal.getRecoveryCount()) + 1;
                goal.setRecoveryCount(recoveries);
                if (recoveries > settings.maxRecoveries()) {
                    goal.setStatus(PAUSED);
                    goal.setPhase(GoalPhase.QUEUED);
                    goal.setEndReason("orphaned");
                    goal.setBlockedReason("连续执行中断，已停下等人工确认");
                    goal.setErrorClass(GoalErrorClass.ORPHANED.name());
                    goal.setNextRunAt(null);
                    goal.setOutcomeJson(GoalOutcome.of("FAILED", "orphaned",
                            "连续执行中断，已停下等人工确认").toJson());
                    goalMapper.updateById(goal);
                    releaseLease(goal);
                    emitState(goal, null);
                    return;
                }
                goal.setStatus(RUNNING);
                goal.setPhase(GoalPhase.RECOVERING);
                goal.setEndReason(null);
                goal.setErrorClass(GoalErrorClass.ORPHANED.name());
                goal.setNextRunAt(LocalDateTime.now());
                goalMapper.updateById(goal);
                log.warn("[GOAL] 检测到执行中断，准备恢复: goal={} 第 {} 次", goalId, recoveries);
            } else {
                goal.setNextRunAt(LocalDateTime.now());
                goalMapper.updateById(goal);
            }
            releaseLease(goal);
            emitState(goal, null);
        }
    }

    /**
     * 调度器抢到租约后真正起一轮（在 worker 线程里跑）。
     * <p>抢到租约只代表「这条目标归我」，这里再复查状态与预算，然后建轮次行、生成提示词、
     * 交给引擎。引擎异步 submit + 跟随；轮次收尾走 {@link #onRoundSettled}。
     */
    public void runClaimed(Goal claimed, String owner) {
        Goal goal;
        try {
            goal = reload(claimed.getId());
        } catch (IllegalArgumentException e) {
            releaseLease(claimed.getId(), owner);
            return;
        }
        if (goal == null || !owner.equals(goal.getLeaseOwner())) return;
        if (!ACTIVE.contains(goal.getStatus())) {
            releaseLease(goalId(goal), owner);
            return;
        }
        if (WAITING_APPROVAL.equals(goal.getStatus()) || WAITING_EVENT.equals(goal.getStatus())) {
            // 等待态只能由人工/外部事件推动；调度器不反复抢它。把 next_run_at 清掉，
            // 避免每个节拍都抢一次再原样放回（空转 + 日志噪音）。
            goal.setNextRunAt(null);
            goalMapper.updateById(goal);
            releaseLease(goalId(goal), owner);
            return;
        }
        String budget = hardBudgetExceeded(goal);
        if (budget != null) {
            fail(goal, FAILED, "budget", budget, channelOf(goal));
            releaseLease(goalId(goal), owner);
            return;
        }
        try {
            launchRound(goal);
        } catch (Exception e) {
            log.warn("[GOAL] 起轮失败: goal={} {}", goalId(goal), e.toString());
            markContinueFailed(goal, e.getMessage());
            releaseLease(goalId(goal), owner);
        }
    }

    /** 建轮次行 + 生成提示词 + 交给引擎。调用方需持有租约。 */
    private void launchRound(Goal goal) {
        synchronized (lockFor(goal.getId())) {
            Goal fresh;
            try {
                fresh = reload(goal.getId());
            } catch (IllegalArgumentException e) {
                releaseLease(goal);
                return;
            }
            if (!ACTIVE.contains(fresh.getStatus())) {
                // 抢到租约之后、起轮之前被用户停止/删除：直接放掉，不再起一轮没人要的。
                releaseLease(fresh);
                return;
            }
            launchRoundLocked(fresh);
        }
    }

    private void launchRoundLocked(Goal goal) {
        GoalTurnDriver driver = this.turnDriver;
        if (driver == null) {
            markContinueFailed(goal, "续跑驱动未绑定");
            return;
        }
        GoalContinuation c = continuation(goal);
        int n = Math.max(ni(goal.getTurnCounter()), roundMapper.maxN(goal.getId())) + 1;
        int max = budgetRounds(goal);
        if (n > max) {
            fail(goal, FAILED, "budget", "轮次预算已用完（" + max + " 轮）；未完成项见任务状态", null);
            return;
        }
        boolean recovery = GoalPhase.RECOVERING.equals(goal.getPhase());
        boolean completionCheck = c.isCompletionCheck();
        // 进展指纹在开跑前落一次：收尾时比较，判断这一轮到底有没有新增事实/产物/计划。
        c.setProgressHash(progressHash(c));
        c.setNoProgressRounds(ni(goal.getNoProgressRounds()));
        c.setUpdatedAt(LocalDateTime.now());
        String input = completionCheck
                ? promptBuilder.completionCheckPrompt(goal, c, n, max)
                : promptBuilder.roundPrompt(goal, c, n, max, budgetNote(goal), recovery);
        String turnId = GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n;
        LocalDateTime now = LocalDateTime.now();

        GoalRound round = new GoalRound();
        round.setGoalId(goal.getId());
        round.setN(n);
        round.setStatus(GoalRound.RUNNING);
        round.setTurnId(turnId);
        round.setLeaseOwner(goal.getLeaseOwner());
        round.setInputHash(sha256(input));
        round.setPrompt(input);
        round.setStartedAt(now);
        round.setUsedTokens(0L);
        round.setUsedMs(0L);
        round.setUsedToolCalls(0);
        round.setCreatedAt(now);
        roundMapper.insert(round);

        goal.setTurnCounter(n);
        goal.setStatus(RUNNING);
        goal.setPhase(completionCheck ? GoalPhase.COMPLETION_CHECK : GoalPhase.EXECUTING);
        goal.setNextRunAt(null);
        goal.setEndReason(null);
        goal.setWaitingReason(null);
        goal.setLastTurnId(turnId);
        if (goal.getFirstTurnId() == null) goal.setFirstTurnId(turnId);
        if (goal.getStartedAt() == null) goal.setStartedAt(now);
        goal.setContinuationJson(c.toJson());
        goal.setLeaseUntil(now.plusSeconds(leaseSeconds()));
        goal.setHeartbeatAt(now);
        goalMapper.updateById(goal);

        log.info("[GOAL] 起第 {} 轮: goal={} recovery={} completionCheck={}",
                n, goal.getId(), recovery, completionCheck);
        try {
            driver.continueTurn(goal, input, n);
        } catch (RuntimeException e) {
            log.warn("[GOAL] 目标轮提交失败: goal={} n={} {}", goal.getId(), n, e.toString());
            // 不要把轮次先改成 FAILED 再交给 onRoundSettled——那边只处理 RUNNING 的轮，
            // 先改状态会让失败被静默吞掉、目标卡在 EXECUTING。交给它统一收口。
            round.setErrorClass(GoalErrorClass.UNKNOWN.name());
            round.setSummary(e.getMessage());
            onRoundSettled(goal.getSessionId(), goal.getUserId(), turnId, 0, 0, null,
                    "", false, false, e.getMessage());
        }
    }

    // ==================== 轮次结算 ====================

    /**
     * 一轮收尾（引擎在跟随循环里调）。记账 → 判完成/受阻 → 核验 → 排下一轮。
     *
     * @param turnId   应用层轮号 {@code goal:{id}:{n}}
     * @param answer   本轮最终正文（判兼容 marker 用）
     * @param healthy  这一轮是否正常跑完
     * @param stopped  用户是否按了停止
     * @param failure  失败原因（healthy=false 时）
     */
    public void onRoundSettled(String sessionId, String userId, String turnId, long machineMs,
                               int toolCalls, RunChannel channel, String answer, boolean healthy,
                               boolean stopped, String failure) {
        if (turnId == null || turnId.isBlank()) return;
        GoalRound round = roundMapper.byTurnId(turnId);
        if (round == null) {
            // 用户自己的轮：只处理「让位后自动接上」，不碰目标轮次。
            if (!isGoalTurn(turnId)) onUserTurnEnd(sessionId, userId, turnId);
            return;
        }
        Goal goal = get(round.getGoalId());
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            goal = reload(goal.getId());
            round = roundMapper.selectById(round.getId());
            if (round == null || !GoalRound.RUNNING.equals(round.getStatus())) return;
            // fencing：租约被新实例接管后，旧实例迟到的回调不能把状态写回去。
            if (round.getLeaseOwner() != null && goal.getLeaseOwner() != null
                    && !round.getLeaseOwner().equals(goal.getLeaseOwner())) {
                log.warn("[GOAL] 忽略过期执行者的轮终回调: goal={} roundOwner={} currentOwner={}",
                        goal.getId(), round.getLeaseOwner(), goal.getLeaseOwner());
                return;
            }
            // 用户删除/预算终止/核验完成之后，迟到的轮终回调不得把终态覆盖回去。
            if (TERMINAL.contains(goal.getStatus())) {
                round.setStatus(GoalRound.SETTLED);
                round.setEndedAt(LocalDateTime.now());
                roundMapper.updateById(round);
                releaseLease(goal);
                return;
            }
            // 用户已经暂停/让位/目标在等审批：轮终只把轮次收掉，不改用户刚定下的目标状态。
            // 不加这一道，被 interrupt 的那一轮会以「失败」回来把 preempted 覆盖成 turn-failed。
            if (PAUSED.equals(goal.getStatus())) {
                LocalDateTime pausedAt = LocalDateTime.now();
                long pausedTokens = round.getStartedAt() == null ? 0
                        : goalMapper.sumTokensBetween(sessionId, round.getStartedAt(), pausedAt);
                round.setStatus(GoalRound.SETTLED);
                round.setEndedAt(pausedAt);
                round.setSummary("目标已暂停，轮次收尾");
                round.setUsedTokens(pausedTokens);
                round.setUsedMs(Math.max(0L, machineMs));
                round.setUsedToolCalls(Math.max(0, toolCalls));
                goal.setUsedTokens(nz(goal.getUsedTokens()) + pausedTokens);
                goal.setUsedMs(nz(goal.getUsedMs()) + Math.max(0L, machineMs));
                goal.setUsedToolCalls((int) nz(goal.getUsedToolCalls()) + Math.max(0, toolCalls));
                roundMapper.updateById(round);
                goalMapper.updateById(goal);
                releaseLease(goal);
                emitState(goal, null);
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            long tokens = round.getStartedAt() == null ? 0
                    : goalMapper.sumTokensBetween(sessionId, round.getStartedAt(), now);
            long ms = Math.max(0L, machineMs);
            int tools = Math.max(0, toolCalls);

            round.setEndedAt(now);
            round.setUsedTokens(tokens);
            round.setUsedMs(ms);
            round.setUsedToolCalls(tools);
            round.setSummary(clip(answer, MAX_SUMMARY_CHARS));
            if (!healthy) round.setErrorClass(GoalErrorClass.of(failure).name());

            goal.setUsedTurns((int) nz(goal.getUsedTurns()) + 1);
            goal.setUsedMs(nz(goal.getUsedMs()) + ms);
            goal.setUsedToolCalls((int) nz(goal.getUsedToolCalls()) + tools);
            goal.setUsedTokens(nz(goal.getUsedTokens()) + tokens);
            goal.setLastTurnId(turnId);
            if (goal.getFirstTurnId() == null) goal.setFirstTurnId(turnId);

            if (stopped) {
                round.setStatus(GoalRound.SETTLED);
                round.setSummary("用户停止");
                roundMapper.updateById(round);
                if (ACTIVE.contains(goal.getStatus())) {
                    goal.setStatus(PAUSED);
                    goal.setPhase(GoalPhase.QUEUED);
                    goal.setEndReason("stopped");
                    goal.setNextRunAt(null);
                    goal.setOutcomeJson(GoalOutcome.of("CANCELLED", "stopped", "用户停止目标").toJson());
                }
                goalMapper.updateById(goal);
                releaseLease(goal);
                emitState(goal, channel);
                return;
            }

            if (!healthy) {
                round.setStatus(GoalRound.FAILED);
                roundMapper.updateById(round);
                handleFailure(goal, round, failure == null ? "本轮未正常收尾" : failure, channel);
                return;
            }

            round.setStatus(GoalRound.SETTLED);
            GoalContinuation c = continuation(goal);
            GoalProposal proposal = GoalProposal.fromJson(round.getProposalJson());

            // 兼容：模型没调 goal_submit，但按旧约定写了 marker。只当成「申请」，仍要过 Verifier。
            if (proposal == null) {
                String blocked = declaresBlocked(answer);
                if (blocked != null) {
                    proposal = new GoalProposal();
                    proposal.setBlocked(true);
                    proposal.setBlockedReason(blocked);
                } else if (declaresDone(answer)) {
                    proposal = new GoalProposal();
                    proposal.setSummary(stripMarkers(answer));
                    collectArtifacts(goal, c);
                    for (String a : c.getArtifacts()) {
                        proposal.getEvidence().add(new GoalProposal.Item(
                                com.wenqu.ai.model.GoalEvidence.KIND_ARTIFACT, a, "自动收集的产物"));
                    }
                }
            }

            if (proposal != null && proposal.isBlocked()) {
                round.setStatus(GoalRound.SETTLED);
                roundMapper.updateById(round);
                if (proposal.getRemaining() != null) c.getGaps().addAll(proposal.getRemaining());
                goal.setContinuationJson(c.toJson());
                pauseBlocked(goal, round, proposal.getBlockedReason(), channel);
                return;
            }

            if (proposal != null) {
                roundMapper.updateById(round);
                GoalVerdict verdict;
                try {
                    verdict = verifier.verify(goal, proposal, ni(round.getN()));
                } catch (Exception e) {
                    // 核验器自身异常不能把这一轮连同消息落库一起打挂：按缺口处理，下一轮可重试。
                    log.warn("[GOAL] 完成申请核验异常: goal={} {}", goal.getId(), e.toString());
                    verdict = GoalVerdict.fail(
                            java.util.List.of("证据核验过程异常：" + e.getMessage()), "verifier-error", "wenqu-goal-verifier-v1");
                }
                if (verdict.passed()) {
                    complete(goal, round, proposal, verdict, channel);
                } else {
                    // 验证失败不是结束，是新的 Observation：缺口写回续跑上下文，下一轮修。
                    c.getGaps().clear();
                    c.getGaps().addAll(verdict.gaps());
                    if (proposal.getRemaining() != null) c.getGaps().addAll(proposal.getRemaining());
                    c.setForceReplan(false);
                    c.setCompletionCheck(false);
                    c.setCompletionCheckCount(0);
                    c.setUpdatedAt(now);
                    goal.setContinuationJson(c.toJson());
                    goal.setStatus(RUNNING);
                    goal.setPhase(GoalPhase.QUEUED);
                    goal.setEndReason(null);
                    goal.setErrorClass(null);
                    int verifyAttempts = ni(goal.getAttempt()) + 1;
                    goal.setAttempt(verifyAttempts);
                    if (verifyAttempts >= settings.maxVerifyFailures()) {
                        goal.setContinuationJson(c.toJson());
                        fail(goal, FAILED, "verification-failed",
                                "连续 " + verifyAttempts + " 次完成申请未通过证据核验", channel);
                        return;
                    }
                    goal.setNextRunAt(now.plusSeconds(settings.nextRoundDelaySeconds()));
                    goal.setOutcomeJson(null);
                    goalMapper.updateById(goal);
                    releaseLease(goal);
                    emitState(goal, channel);
                    log.info("[GOAL] 完成申请未通过，排下一轮补缺口: goal={} gaps={} 第 {} 次",
                            goal.getId(), verdict.gaps().size(), verifyAttempts);
                }
                return;
            }

            // 没有完成申请：先看是不是「已交付但没提交」的完成检查触发点，再按进展/预算继续。
            Set<String> beforeArtifacts = new java.util.LinkedHashSet<>(c.getArtifacts());
            boolean progressed = applyProgress(goal, c, round);
            Set<String> newArtifacts = new java.util.LinkedHashSet<>(c.getArtifacts());
            newArtifacts.removeAll(beforeArtifacts);
            roundMapper.updateById(round);

            String budget = hardBudgetExceeded(goal);
            if (budget != null) {
                fail(goal, FAILED, "budget", budget, channel);
                return;
            }

            if (c.isCompletionCheck()) {
                // 这是「完成检查轮」的收尾：remaining 非空=明确未完成；否则视为没回答。
                int checks = c.getCompletionCheckCount() + 1;
                c.setCompletionCheckCount(checks);
                // 触发检查时 gaps 一定为空；所以检查轮收尾只要 gaps 非空，就是模型通过
                // goal_note(remaining) 明确回答了“还没完成”。不能用“本轮前后有没有变化”判断：
                // goal_note 是在轮内写入的，收尾时读到的已经是写后的值。
                if (!c.getGaps().isEmpty()) {
                    c.setCompletionCheck(false);
                    c.setCompletionCheckCount(0);
                    log.info("[GOAL] 完成检查回答未完成，进入补缺口轮: goal={} remaining={}",
                            goal.getId(), c.getGaps().size());
                } else {
                    int maxChecks = settings.maxCompletionChecks();
                    if (checks >= maxChecks) {
                        goal.setContinuationJson(c.toJson());
                        pauseCompletionUnconfirmed(goal, c, channel);
                        return;
                    }
                    c.setCompletionCheck(true);
                    c.setUpdatedAt(now);
                    goal.setContinuationJson(c.toJson());
                    goal.setStatus(RUNNING);
                    goal.setPhase(GoalPhase.COMPLETION_CHECK);
                    goal.setEndReason(null);
                    goal.setErrorClass(null);
                    goal.setNextRunAt(now.plusSeconds(settings.nextRoundDelaySeconds()));
                    goalMapper.updateById(goal);
                    releaseLease(goal);
                    emitState(goal, channel);
                    log.info("[GOAL] 完成检查未得到提交/缺口，再检查一次: goal={} {}/{}",
                            goal.getId(), checks, maxChecks);
                    return;
                }
            } else if (!newArtifacts.isEmpty() && c.getGaps().isEmpty() && declaresDelivery(answer)) {
                // 交付了产物、回答也像完成，但没提交：给一次便宜的确认机会，不直接判完成。
                // 产物存在 ≠ 目标满足（初稿/骨架也会产出），所以只触发检查，不自动 ACHIEVED。
                c.setCompletionCheck(true);
                c.setCompletionCheckCount(0);
                c.setUpdatedAt(now);
                goal.setContinuationJson(c.toJson());
                goal.setStatus(RUNNING);
                goal.setPhase(GoalPhase.COMPLETION_CHECK);
                goal.setEndReason(null);
                goal.setErrorClass(null);
                goal.setNextRunAt(now.plusSeconds(settings.nextRoundDelaySeconds()));
                goalMapper.updateById(goal);
                releaseLease(goal);
                emitState(goal, channel);
                log.info("[GOAL] 交付未提交，排完成检查轮: goal={} artifacts={}",
                        goal.getId(), newArtifacts.size());
                return;
            }

            if (progressed) {
                goal.setNoProgressRounds(0);
                goal.setLastProgressAt(now);
                goal.setAttempt(0);
                goal.setRecoveryCount(0);
                c.setNoProgressRounds(0);
                c.setForceReplan(false);
                c.setCompletionCheckCount(0);
            } else {
                int noProgress = ni(goal.getNoProgressRounds()) + 1;
                goal.setNoProgressRounds(noProgress);
                c.setNoProgressRounds(noProgress);
                c.setForceReplan(noProgress >= 2);
                if (noProgress >= 3) {
                    goal.setContinuationJson(c.toJson());
                    pauseNoProgress(goal, c, channel);
                    return;
                }
            }
            c.setCompletionCheck(false);
            c.setUpdatedAt(now);
            goal.setContinuationJson(c.toJson());
            goal.setStatus(RUNNING);
            goal.setPhase(GoalPhase.QUEUED);
            goal.setEndReason(null);
            goal.setErrorClass(null);
            goal.setNextRunAt(now.plusSeconds(settings.nextRoundDelaySeconds()));
            goalMapper.updateById(goal);
            releaseLease(goal);
            emitState(goal, channel);
            log.info("[GOAL] 第 {} 轮收尾，排下一轮: goal={} progress={}",
                    round.getN(), goal.getId(), progressed);
        }
    }

    /** 旧签名兼容（引擎在未传 failure 的路径调用）。 */
    public void onTurnEnd(String sessionId, String userId, String turnId, long machineMs, int toolCalls,
                          RunChannel channel, String answer, boolean turnHealthy, boolean stopped) {
        onRoundSettled(sessionId, userId, turnId, machineMs, toolCalls, channel, answer,
                turnHealthy, stopped, null);
    }

    /**
     * 用户自己那一轮开跑时调用：后台目标轮还在飞就让位（用户优先），目标转暂停。
     * <p>没有在飞的目标轮就不动——目标在队列里等调度，不需要为用户的一句话让位。
     */
    public void noteUserTurn(String sessionId, String uid, RunChannel channel) {
        Goal goal = currentGoal(sessionId);
        if (goal == null || !ACTIVE.contains(goal.getStatus())) return;
        if (roundMapper.runningOf(goal.getId()) == null) return;
        synchronized (lockFor(goal.getId())) {
            goal = reload(goal.getId());
            if (!ACTIVE.contains(goal.getStatus())) return;
            interruptRunningGoalTurn(goal);
            goal.setStatus(PAUSED);
            goal.setPhase(GoalPhase.QUEUED);
            goal.setEndReason("preempted");
            goal.setNextRunAt(null);
            goalMapper.updateById(goal);
            releaseLease(goal);
            emitState(goal, channel);
            log.info("[GOAL] 用户插话，目标循环让位转暂停: goal={}", goal.getId());
        }
    }

    /**
     * 用户那一轮收尾后接上目标（不再直接起轮，只把下一轮时间拨到现在，由调度器接手）。
     * <p>让位停下的目标在这里自动恢复，用户不必手动点继续。
     */
    public void onUserTurnEnd(String sessionId, String uid, String endedTurnId) {
        Goal goal = currentGoal(sessionId);
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            goal = reload(goal.getId());
            if (goal == null) return;
            boolean preempted = PAUSED.equals(goal.getStatus()) && "preempted".equals(goal.getEndReason());
            if (preempted) {
                goal.setStatus(RUNNING);
                goal.setPhase(GoalPhase.QUEUED);
                goal.setEndReason(null);
                log.info("[GOAL] 用户那一轮结束，目标自动接上: goal={}", goal.getId());
            }
            // 只有「在跑/已入队」的目标才接下一轮；用户自己停的、受阻的、无进展停下的都不许被
            // 一次普通聊天悄悄恢复——那会把用户的刹车撤销掉。
            if (!RUNNING.equals(goal.getStatus()) && !QUEUED.equals(goal.getStatus())) return;
            if (goal.getNextRunAt() == null && roundMapper.runningOf(goal.getId()) == null) {
                goal.setNextRunAt(LocalDateTime.now());
            }
            goalMapper.updateById(goal);
            releaseLease(goal);
            emitState(goal, null);
        }
    }

    /** 旧签名兼容。 */
    public void continueAfterTurn(String sessionId, String uid, String endedTurnId) {
        onUserTurnEnd(sessionId, uid, endedTurnId);
    }

    /** 后台轮被用户打断/自行失败：目标转 PAUSED（不是终态——回来可续）。 */
    public void markContinueFailed(Goal goal, String reason) {
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            Goal fresh;
            try {
                fresh = reload(goal.getId());
            } catch (IllegalArgumentException e) {
                return;
            }
            if (TERMINAL.contains(fresh.getStatus())) return;
            // 用户已经先一步停止/删除：不要用「后台轮没起来」把用户的动作覆盖掉。
            if (PAUSED.equals(fresh.getStatus()) && !"turn-failed".equals(fresh.getEndReason())) return;
            GoalRound running = roundMapper.runningOf(fresh.getId());
            if (running != null) {
                running.setStatus(GoalRound.FAILED);
                running.setEndedAt(LocalDateTime.now());
                running.setErrorClass(GoalErrorClass.of(reason).name());
                running.setSummary(clip(reason, MAX_SUMMARY_CHARS));
                roundMapper.updateById(running);
            }
            fresh.setStatus(PAUSED);
            fresh.setPhase(GoalPhase.QUEUED);
            fresh.setEndReason("turn-failed");
            fresh.setBlockedReason(clip(reason, MAX_BLOCKED_CHARS));
            fresh.setErrorClass(GoalErrorClass.of(reason).name());
            fresh.setNextRunAt(null);
            fresh.setOutcomeJson(GoalOutcome.of("FAILED", "turn-failed",
                    reason == null ? "目标轮未正常启动" : reason).toJson());
            goalMapper.updateById(fresh);
            releaseLease(fresh);
            emitState(fresh, null);
            log.warn("[GOAL] 后台续跑没起来: goal={} {}", fresh.getId(), reason);
        }
    }

    /** 这个轮号是不是「完成检查轮」（引擎据此收窄工具面）。 */
    public boolean isCompletionCheck(String turnId) {
        if (!isGoalTurn(turnId)) return false;
        Goal goal = get(goalIdOfTurn(turnId));
        if (goal == null) return false;
        GoalContinuation c = continuation(goal);
        return c != null && c.isCompletionCheck();
    }

    /** 目标轮是否还能提交（提交前复查：目标可能刚被用户删掉/停掉）。 */
    public boolean runnable(Goal goal) {
        if (goal == null) return false;
        Goal fresh = goalMapper.selectById(goal.getId());
        return fresh != null && ACTIVE.contains(fresh.getStatus())
                && (GoalPhase.EXECUTING.equals(fresh.getPhase())
                    || GoalPhase.COMPLETION_CHECK.equals(fresh.getPhase()));
    }

    // ==================== 模型工具入口 ====================

    /**
     * {@code goal_submit}：记录完成/受阻申请。不改终态——核验在轮次收尾做。
     *
     * @param turnId 当前轮号（从 AgentxTurnSlot 取）
     */
    public String recordProposal(String turnId, String summary, String evidenceJson,
                                 String remainingJson, Boolean blocked, String blockedReason) {
        GoalRound round = turnId == null ? null : roundMapper.byTurnId(turnId);
        if (round == null) return "当前没有进行中的目标轮次，目标申请未记录。";
        GoalProposal p = new GoalProposal();
        p.setSummary(clip(summary, MAX_PROPOSAL_SUMMARY_CHARS));
        p.setBlocked(Boolean.TRUE.equals(blocked));
        p.setBlockedReason(clip(blockedReason, MAX_SUMMARY_CHARS));
        p.setRoundN(round.getN());
        p.setEvidence(parseEvidence(evidenceJson));
        p.setRemaining(parseList(remainingJson));
        round.setProposalJson(p.toJson());
        round.setSummary(clip(summary, MAX_SUMMARY_CHARS));
        roundMapper.updateById(round);
        sessionEventService.append(goalSessionId(round.getGoalId()), goalUserId(round.getGoalId()),
                turnId, round.getGoalId(), "goal_verify",
                JSON.toJSONString(Map.of(
                        "blocked", p.isBlocked(),
                        "summary", p.getSummary() == null ? "" : p.getSummary(),
                        "evidence", p.getEvidence().size(),
                        "remaining", p.getRemaining().size())));
        if (p.isBlocked()) {
            return "已记录受阻申请。平台会在本轮收尾时判定是否暂停目标；不要为了让目标停下而把未完成说成完成。";
        }
        return "已收到完成申请（证据 " + p.getEvidence().size() + " 条）。平台会逐条核验；"
                + "未通过的缺口会进入下一轮。不要再重复提交同一份申请。";
    }

    /**
     * {@code goal_note}：记录计划/事实/产物/阶段。跨轮续跑只认这份状态，不认聊天历史。
     */
    public String recordNote(String turnId, String planJson, String factsJson, String artifactsJson,
                             String stage, String summary) {
        return recordNote(turnId, planJson, factsJson, artifactsJson, stage, summary, null);
    }

    /**
     * @param remainingJson 未完成/缺口（JSON 数组）；传空数组=明确回答“没有剩余缺口”，
     *                      传 null=本轮不表态（完成检查轮会据此判断模型有没有回答）
     */
    public String recordNote(String turnId, String planJson, String factsJson, String artifactsJson,
                             String stage, String summary, String remainingJson) {
        GoalRound round = turnId == null ? null : roundMapper.byTurnId(turnId);
        if (round == null) return "当前没有进行中的目标轮次，记录未保存。";
        Goal goal = get(round.getGoalId());
        if (goal == null) return "当前没有进行中的目标轮次，记录未保存。";
        synchronized (lockFor(goal.getId())) {
            goal = reload(goal.getId());
            GoalContinuation c = continuation(goal);
            c.merge(parseList(planJson), parseList(factsJson), parseList(artifactsJson),
                    normalizeStage(stage), summary);
            if (remainingJson != null) {
                c.getGaps().clear();
                c.getGaps().addAll(parseList(remainingJson));
                c.normalized();
            }
            goal.setContinuationJson(c.toJson());
            goalMapper.updateById(goal);
            sessionEventService.append(goal.getSessionId(), goal.getUserId(), turnId, goal.getId(),
                    "goal_note", JSON.toJSONString(Map.of(
                            "stage", c.getStage(),
                            "plan", c.getPlan().size(),
                            "facts", c.getFacts().size(),
                            "artifacts", c.getArtifacts().size(),
                            "remaining", c.getGaps().size(),
                            "completionCheck", c.isCompletionCheck())));
        }
        return "已记录。";
    }

    // ==================== 模型上下文 ====================

    /** 用户交互轮拼进模型输入的一句目标上下文；没有当前目标时返回空串。 */
    public String contextNoteFor(String sessionId) {
        Goal goal = currentGoal(sessionId);
        return goal == null ? "" : promptBuilder.contextNote(goal);
    }

    // ==================== 查询 ====================

    public Goal currentGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Goal goal = goalMapper.selectOne(new LambdaQueryWrapper<Goal>()
                    .eq(Goal::getSessionId, sessionId)
                    .in(Goal::getStatus, CURRENT)
                    .orderByDesc(Goal::getCreatedAt)
                    .last("limit 1"));
            return normalize(goal);
        } catch (Exception e) {
            log.warn("[GOAL] 当前目标查询失败: session={} {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 当前/最近一条目标（右栏与目标条回显，读 DB 不读框架） */
    public Goal latestGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Goal goal = goalMapper.selectOne(new LambdaQueryWrapper<Goal>()
                    .eq(Goal::getSessionId, sessionId)
                    .orderByDesc(Goal::getCreatedAt)
                    .last("limit 1"));
            return normalize(goal);
        } catch (Exception e) {
            log.warn("[GOAL] 最近目标查询失败: session={} {}", sessionId, e.getMessage());
            return null;
        }
    }

    public Goal get(String goalId) {
        return normalize(goalId == null ? null : goalMapper.selectById(goalId));
    }

    /** 当前目标（恢复端点用）：形状 {@code {goal: 视图|null}}。 */
    public Map<String, Object> pending(String sessionId, String uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        Goal goal = currentGoal(sessionId);
        out.put("goal", goal == null ? null : view(goal));
        return out;
    }

    /** 目标过程账本（按 goal_id 聚合；过程面板深链/断线增量用）。 */
    public List<Map<String, Object>> goalEvents(String sessionId, String userId, String goalId) {
        return goalEvents(sessionId, userId, goalId, 0);
    }

    public List<Map<String, Object>> goalEvents(String sessionId, String userId, String goalId, long afterId) {
        List<SessionEvent> rows = sessionEventService.listByGoalAfter(sessionId, userId, goalId, afterId, 0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionEvent e : rows) {
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

    /**
     * 前端视图模型（右栏、目标条、goal_state 帧共用同一份）。
     * <p>新增 phase/stale/budget/outcome/evidenceCount/lastEventId：前端据此区分「在跑」「等审批」
     * 「进程挂了待恢复」「证据核验通过」——不再靠 status 一档猜。
     */
    public Map<String, Object> view(Goal goal) {
        Map<String, Object> m = new LinkedHashMap<>();
        GoalContinuation c = continuation(goal);
        m.put("goalId", goal.getId());
        m.put("status", goal.getStatus());
        m.put("phase", goal.getPhase());
        m.put("stage", c == null ? null : c.getStage());
        m.put("objective", goal.getObjective());
        m.put("objectiveVersion", goal.getObjectiveVersion());
        m.put("round", nz(goal.getTurnCounter()));
        m.put("maxRounds", budgetRounds(goal));
        m.put("hint", hintOf(goal));
        m.put("endReason", goal.getEndReason());
        m.put("blockedReason", goal.getBlockedReason());
        m.put("waitingReason", goal.getWaitingReason());
        m.put("errorClass", goal.getErrorClass());
        m.put("attempt", nz(goal.getAttempt()));
        m.put("noProgressRounds", nz(goal.getNoProgressRounds()));
        m.put("live", isLive(goal));
        m.put("stale", isStale(goal));
        m.put("firstTurnId", goal.getFirstTurnId() == null ? "" : goal.getFirstTurnId());
        m.put("lastTurnId", goal.getLastTurnId() == null ? "" : goal.getLastTurnId());
        m.put("lastEventId", goal.getLastEventId());
        m.put("nextRunAt", iso(goal.getNextRunAt()));
        m.put("budget", budgetView(goal));
        m.put("outcome", outcomeOf(goal));
        m.put("evidenceCount", evidenceCount(goal.getId()));
        m.put("createdAt", iso(goal.getCreatedAt()));
        m.put("startedAt", iso(goal.getStartedAt()));
        m.put("endedAt", iso(goal.getEndedAt()));
        return m;
    }

    private String hintOf(Goal goal) {
        if (!PAUSED.equals(goal.getStatus()) && !FAILED.equals(goal.getStatus())) return null;
        if ("blocked".equals(goal.getEndReason()) || "round-limit".equals(goal.getEndReason())) return null;
        return GoalCopy.pausedHint(goal.getEndReason());
    }

    private boolean isLive(Goal goal) {
        if (!ACTIVE.contains(goal.getStatus())) return false;
        if (isStale(goal)) return false;
        return sessions.peek(goal.getUserId(), goal.getSessionId()) != null;
    }

    /** 进程还在但心跳停了 / 队列排太久：让 UI 说「待恢复」，而不是永远「进行中」。 */
    private boolean isStale(Goal goal) {
        if (!ACTIVE.contains(goal.getStatus())) return false;
        // 本进程里还有这条会话的常驻实例 = 这一轮有人跟，不判 stale（长轮次里内存里的
        // heartbeatAt 可能早就旧了，拿它判会把正常在跑的目标显示成「待恢复」）。
        if (sessions.peek(goal.getUserId(), goal.getSessionId()) != null) return false;
        LocalDateTime now = LocalDateTime.now();
        if (GoalPhase.EXECUTING.equals(goal.getPhase())
                || GoalPhase.COMPLETION_CHECK.equals(goal.getPhase())
                || GoalPhase.VERIFYING.equals(goal.getPhase())
                || GoalPhase.RECOVERING.equals(goal.getPhase())) {
            LocalDateTime beat = goal.getHeartbeatAt();
            return beat == null || beat.plusSeconds(settings.heartbeatStaleSeconds()).isBefore(now);
        }
        if (goal.getNextRunAt() == null && goal.getLeaseOwner() == null) {
            // 既没有下一轮时间、也没有人持租约：收尾没写完或调度器停了，界面应说「待恢复」。
            return true;
        }
        if (goal.getNextRunAt() != null && goal.getLeaseOwner() == null) {
            return goal.getNextRunAt().plusSeconds(settings.staleGraceSeconds()).isBefore(now);
        }
        return false;
    }

    private Map<String, Object> budgetView(Goal goal) {
        Map<String, Object> b = new LinkedHashMap<>();
        long tokens = nz(goal.getUsedTokens());
        long ms = nz(goal.getUsedMs());
        int tools = (int) nz(goal.getUsedToolCalls());
        int rounds = ni(goal.getTurnCounter());
        b.put("tokens", tokens);
        b.put("maxTokens", shown(budgetTokens(goal)));
        b.put("ms", ms);
        b.put("maxMs", shown(budgetMs(goal)));
        b.put("toolCalls", tools);
        b.put("maxToolCalls", shown(budgetToolCalls(goal)));
        b.put("rounds", rounds);
        b.put("maxRounds", shown(budgetRounds(goal)));
        b.put("percent", budgetRatio(goal));
        return b;
    }

    /**
     * 不限制的哨兵在出参里说回 0：目标行的 {@code budget_*} 列用 0 表达「用配置默认」，
     * 界面那侧 0 读作「这一项没有预算」（GoalCard 据此换一种说法，四项全 0 才整行不画）。
     * 不翻的话界面上是一行「预算：3/2147483647 轮」。
     */
    private static int shown(int budget) {
        return budget >= GoalSettings.UNLIMITED ? 0 : budget;
    }

    /** {@link #shown(int)} 的 BIGINT 版（token 与毫秒两项）。 */
    private static long shown(long budget) {
        return budget >= GoalSettings.UNLIMITED_LONG ? 0L : budget;
    }

    private Map<String, Object> outcomeOf(Goal goal) {
        GoalOutcome o = GoalOutcome.fromJson(goal.getOutcomeJson());
        if (o == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", o.getState());
        m.put("code", o.getCode());
        m.put("summary", o.getSummary());
        m.put("remaining", o.getRemaining());
        m.put("verifier", o.getVerifier());
        m.put("at", iso(o.getAt()));
        m.put("evidence", o.getEvidence());
        return m;
    }

    private int evidenceCount(String goalId) {
        try {
            Long n = evidenceMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .<com.wenqu.ai.model.GoalEvidence>lambdaQuery()
                            .eq(com.wenqu.ai.model.GoalEvidence::getGoalId, goalId)
                            .eq(com.wenqu.ai.model.GoalEvidence::getVerified,
                                    com.wenqu.ai.model.GoalEvidence.PASSED));
            return n == null ? 0 : n.intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 帧 ====================

    /** goal_state：账本（带 goal_id）+ 通道或总线。 */
    public void emitState(Goal goal, RunChannel channel) {
        String frame = JSON.toJSONString(view(goal));
        Long id = sessionEventService.append(goal.getSessionId(), goal.getUserId(), goal.getLastTurnId(),
                goal.getId(), "goal_state", frame);
        if (id != null) {
            goal.setLastEventId(id);
            try {
                // 只写游标列：整行 update 会把调度器刚抢到的 lease_owner/lease_until 覆盖成旧值。
                goalMapper.updateLastEventId(goal.getId(), id);
            } catch (Exception e) {
                log.warn("[GOAL] 记录事件游标失败 goal={}: {}", goal.getId(), e.getMessage());
            }
        }
        if (channel != null) {
            try {
                channel.emit(RunEventType.GOAL_STATE, frame);
            } catch (Exception e) {
                log.warn("[GOAL] 目标态帧下发失败（不影响状态）: goal={} {}", goal.getId(), e.getMessage());
            }
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
            try {
                channel.emit(RunEventType.GOAL_TURN, frame);
            } catch (Exception e) {
                log.warn("[GOAL] 目标轮帧下发失败（不影响执行）: goal={} {}", goal.getId(), e.getMessage());
            }
        } else {
            chatRunBus.publishToSession(goal.getSessionId(), "goal_turn", frame);
        }
    }

    // ==================== 内部：完成/失败 ====================

    private void complete(Goal goal, GoalRound round, GoalProposal proposal, GoalVerdict verdict,
                          RunChannel channel) {
        LocalDateTime now = LocalDateTime.now();
        round.setStatus(GoalRound.SETTLED);
        round.setEndedAt(now);
        round.setSummary(clip(proposal.getSummary(), MAX_SUMMARY_CHARS));
        roundMapper.updateById(round);

        GoalOutcome outcome = GoalOutcome.of("COMPLETED", "verified", proposal.getSummary());
        outcome.setEvidence(proposal.getEvidence());
        outcome.setRemaining(proposal.getRemaining());
        outcome.setVerifier(verdict.verifier());
        goal.setStatus(ACHIEVED);
        goal.setPhase(GoalPhase.DONE);
        goal.setEndReason("achieved");
        goal.setBlockedReason(null);
        goal.setWaitingReason(null);
        goal.setOutcomeJson(outcome.toJson());
        goal.setEndedAt(now);
        goal.setNextRunAt(null);
        goalMapper.updateById(goal);
        releaseLease(goal);
        appendOutcome(goal, outcome);
        emitState(goal, channel);
        // 一个能跑到达成的目标通常已经跑了好几轮、几十分钟——人早不在屏幕前了。
        // 目标轮的「跑完了」由这里统一说话（见 TurnNotifyService 里那条 goal: 前缀判据）
        turnNotify.onGoalTerminal(goal, true, null);
        log.info("[GOAL] 目标完成: goal={} 共 {} 轮", goal.getId(), nz(goal.getTurnCounter()));
    }

    private void pauseBlocked(Goal goal, GoalRound round, String reason, RunChannel channel) {
        LocalDateTime now = LocalDateTime.now();
        GoalContinuation c = continuation(goal);
        GoalOutcome outcome = GoalOutcome.of("BLOCKED", "blocked", reason);
        outcome.setRemaining(c.getGaps().isEmpty() ? c.getPlan() : c.getGaps());
        goal.setStatus(PAUSED);
        goal.setPhase(GoalPhase.QUEUED);
        goal.setEndReason("blocked");
        goal.setBlockedReason(clip(reason, MAX_BLOCKED_CHARS));
        goal.setWaitingReason(null);
        goal.setOutcomeJson(outcome.toJson());
        goal.setNextRunAt(null);
        goal.setEndedAt(now);
        goalMapper.updateById(goal);
        releaseLease(goal);
        appendOutcome(goal, outcome);
        emitState(goal, channel);
        // 受阻是要人补东西的：助手自己往下走不动了，等人回来给一句才能继续
        turnNotify.onGoalTerminal(goal, false, reason);
        log.info("[GOAL] 助手报告受阻，目标暂停: goal={} 原因={}", goal.getId(), reason);
    }

    private void pauseNoProgress(Goal goal, GoalContinuation c, RunChannel channel) {
        goal.setStatus(PAUSED);
        goal.setPhase(GoalPhase.QUEUED);
        goal.setEndReason("no-progress");
        goal.setNextRunAt(null);
        GoalOutcome outcome = GoalOutcome.of("FAILED", "no-progress",
                "连续 " + nz(goal.getNoProgressRounds()) + " 轮没有新增事实/产物/计划进展");
        outcome.setRemaining(c.getGaps().isEmpty() ? c.getPlan() : c.getGaps());
        goal.setOutcomeJson(outcome.toJson());
        goalMapper.updateById(goal);
        releaseLease(goal);
        appendOutcome(goal, outcome);
        emitState(goal, channel);
        // 通知说人话：那两句 detail 里的 goal_submit、remaining 缺口是框架词汇，用户看得懂的是
        // 「连着几轮没往前走」和「它自己也不确定做完了没」
        turnNotify.onGoalTerminal(goal, false, "连着几轮没有新的进展");
        log.info("[GOAL] 连续无进展，目标暂停: goal={} 轮次={}", goal.getId(), nz(goal.getNoProgressRounds()));
    }

    private void pauseCompletionUnconfirmed(Goal goal, GoalContinuation c, RunChannel channel) {
        goal.setStatus(PAUSED);
        goal.setPhase(GoalPhase.QUEUED);
        goal.setEndReason("completion-unconfirmed");
        goal.setNextRunAt(null);
        GoalOutcome outcome = GoalOutcome.of("FAILED", "completion-unconfirmed",
                "连续完成检查仍未提交 goal_submit，也没有给出 remaining 缺口");
        outcome.setRemaining(c.getGaps().isEmpty() ? c.getPlan() : c.getGaps());
        goal.setOutcomeJson(outcome.toJson());
        goalMapper.updateById(goal);
        releaseLease(goal);
        appendOutcome(goal, outcome);
        emitState(goal, channel);
        turnNotify.onGoalTerminal(goal, false, "助手反复检查也没确认这件事做完了");
        log.info("[GOAL] 完成检查连续无结果，目标暂停: goal={} 检查次数={}",
                goal.getId(), c.getCompletionCheckCount());
    }

    private void fail(Goal goal, String status, String reason, String detail, RunChannel channel) {
        GoalContinuation c = continuation(goal);
        GoalOutcome outcome = GoalOutcome.of("FAILED", reason, detail);
        outcome.setRemaining(c == null ? List.of() : (c.getGaps().isEmpty() ? c.getPlan() : c.getGaps()));
        goal.setStatus(status);
        goal.setPhase(GoalPhase.DONE);
        goal.setEndReason(reason);
        goal.setBlockedReason(clip(detail, MAX_BLOCKED_CHARS));
        goal.setOutcomeJson(outcome.toJson());
        goal.setEndedAt(LocalDateTime.now());
        goal.setNextRunAt(null);
        goalMapper.updateById(goal);
        releaseLease(goal);
        appendOutcome(goal, outcome);
        emitState(goal, channel);
        // detail 已经是给人看的那句（「轮次预算已用完（N 轮）」这种），reason 是内部档位词，别抄进通知
        turnNotify.onGoalTerminal(goal, false, detail);
        log.info("[GOAL] 目标终止: goal={} status={} reason={}", goal.getId(), status, reason);
    }

    private void handleFailure(Goal goal, GoalRound round, String failure, RunChannel channel) {
        GoalErrorClass cls = GoalErrorClass.of(failure);
        int attempt = ni(goal.getAttempt()) + 1;
        GoalContinuation cont = continuation(goal);
        if (cont != null) {
            cont.setFailedAttempts(trim(cont.getFailedAttempts(), failure));
            cont.setUpdatedAt(LocalDateTime.now());
            goal.setContinuationJson(cont.toJson());
        }
        round.setStatus(GoalRound.FAILED);
        round.setErrorClass(cls.name());
        round.setEndedAt(LocalDateTime.now());
        round.setSummary(clip(failure, MAX_SUMMARY_CHARS));
        roundMapper.updateById(round);
        goal.setAttempt(attempt);
        goal.setErrorClass(cls.name());
        int maxRetry = settings.maxRetries();
        if (cls.retryable() && attempt <= maxRetry) {
            long backoff = settings.retryDelaySeconds(attempt);
            goal.setStatus(RUNNING);
            goal.setPhase(GoalPhase.QUEUED);
            goal.setEndReason(null);
            goal.setNextRunAt(LocalDateTime.now().plusSeconds(backoff));
            goalMapper.updateById(goal);
            releaseLease(goal);
            emitState(goal, channel);
            log.warn("[GOAL] 本轮失败（{}），{}s 后重试 {}/{}: goal={}",
                    cls, backoff, attempt, maxRetry, goal.getId());
            return;
        }
        GoalContinuation c = continuation(goal);
        GoalOutcome outcome = GoalOutcome.of("FAILED", cls.name().toLowerCase(),
                "目标执行失败：" + clip(failure, MAX_FAILURE_CHARS));
        outcome.setRemaining(c == null ? List.of() : c.getGaps());
        goal.setStatus(PAUSED);
        goal.setPhase(GoalPhase.QUEUED);
        goal.setEndReason("turn-failed");
        goal.setBlockedReason(clip(failure, MAX_BLOCKED_CHARS));
        goal.setOutcomeJson(outcome.toJson());
        goal.setNextRunAt(null);
        goalMapper.updateById(goal);
        releaseLease(goal);
        appendOutcome(goal, outcome);
        emitState(goal, channel);
        log.warn("[GOAL] 目标转暂停（{}）: goal={} {}", cls, goal.getId(), failure);
    }

    private void appendOutcome(Goal goal, GoalOutcome outcome) {
        sessionEventService.append(goal.getSessionId(), goal.getUserId(), goal.getLastTurnId(),
                goal.getId(), "goal_outcome", outcome.toJson());
    }

    // ==================== 内部：进展/预算/续跑上下文 ====================

    private GoalContinuation continuation(Goal goal) {
        GoalContinuation c = GoalContinuation.fromJson(goal == null ? null : goal.getContinuationJson());
        if (c == null && goal != null) c = GoalContinuation.of(goal.getObjective());
        if (c != null && goal != null) {
            if (c.getObjective() == null || c.getObjective().isBlank()) c.setObjective(goal.getObjective());
            if (c.getObjectiveVersion() == 0 && goal.getObjectiveVersion() != null) {
                c.setObjectiveVersion(goal.getObjectiveVersion());
            }
        }
        return c;
    }

    private boolean applyProgress(Goal goal, GoalContinuation c, GoalRound round) {
        String before = c.getProgressHash();
        int artifactsBefore = c.getArtifacts().size();
        collectArtifacts(goal, c);
        int artifactsAdded = c.getArtifacts().size() - artifactsBefore;
        String after = progressHash(c);
        c.setProgressHash(after);
        boolean changed = before == null || !before.equals(after);
        if (artifactsAdded > 0) changed = true;
        return changed;
    }

    private void collectArtifacts(Goal goal, GoalContinuation c) {
        try {
            var artifacts = artifactService.listBySession(goal.getUserId(), goal.getSessionId(),
                    goal.getStartedAt());
            for (var a : artifacts) {
                if (a.getFilename() != null) c.getArtifacts().add(a.getFilename());
            }
            c.normalized();
        } catch (Exception e) {
            log.warn("[GOAL] 产物收集失败 goal={}: {}", goal.getId(), e.getMessage());
        }
    }

    private List<String> trim(List<String> list, String add) {
        if (add != null && !add.isBlank()) list.add(clip(add, MAX_FACT_ITEM_CHARS));
        while (list.size() > GoalContinuation.MAX_LIST) list.remove(0);
        return list;
    }

    private static String progressHash(GoalContinuation c) {
        if (c == null) return "";
        String raw = String.join("\u0001", c.getPlan()) + "\u0002"
                + String.join("\u0001", c.getFacts()) + "\u0002"
                + String.join("\u0001", c.getArtifacts()) + "\u0002"
                + String.join("\u0001", c.getGaps());
        return sha256(raw);
    }

    private String budgetNote(Goal goal) {
        double ratio = budgetRatio(goal);
        if (ratio >= settings.softBudgetRatio()) {
            return "预算已用 " + Math.round(ratio * 100) + "%。本轮优先交付可交付部分，"
                    + "在 goal_note 里写清 remaining/gaps；不要开启新的探索分支。";
        }
        return null;
    }

    private String hardBudgetExceeded(Goal goal) {
        if (nz(goal.getTurnCounter()) >= budgetRounds(goal)) {
            return "轮次预算已用完（" + budgetRounds(goal) + " 轮）";
        }
        if (nz(goal.getUsedTokens()) >= budgetTokens(goal)) {
            return "Token 预算已用完（已用 " + nz(goal.getUsedTokens()) + " / " + budgetTokens(goal) + "）";
        }
        if (nz(goal.getUsedMs()) >= budgetMs(goal)) {
            return "时长预算已用完（已用 " + (nz(goal.getUsedMs()) / 60_000) + " 分钟）";
        }
        if (nz(goal.getUsedToolCalls()) >= budgetToolCalls(goal)) {
            return "工具调用预算已用完（已用 " + nz(goal.getUsedToolCalls()) + " 次）";
        }
        return null;
    }

    private double budgetRatio(Goal goal) {
        double max = 0;
        max = Math.max(max, (double) nz(goal.getTurnCounter()) / Math.max(1, budgetRounds(goal)));
        max = Math.max(max, (double) nz(goal.getUsedTokens()) / Math.max(1, budgetTokens(goal)));
        max = Math.max(max, (double) nz(goal.getUsedMs()) / Math.max(1, budgetMs(goal)));
        max = Math.max(max, (double) nz(goal.getUsedToolCalls()) / Math.max(1, budgetToolCalls(goal)));
        return Math.min(1.0, max);
    }

    private int budgetRounds(Goal g) {
        return g != null && g.getBudgetRounds() != null && g.getBudgetRounds() > 0
                ? g.getBudgetRounds() : settings.maxRounds();
    }

    private long budgetTokens(Goal g) {
        return g != null && g.getBudgetTokens() != null && g.getBudgetTokens() > 0
                ? g.getBudgetTokens() : settings.maxTokens();
    }

    private long budgetMs(Goal g) {
        return g != null && g.getBudgetMs() != null && g.getBudgetMs() > 0
                ? g.getBudgetMs() : settings.maxMs();
    }

    private int budgetToolCalls(Goal g) {
        return g != null && g.getBudgetToolCalls() != null && g.getBudgetToolCalls() > 0
                ? g.getBudgetToolCalls() : settings.maxToolCalls();
    }

    // ==================== 内部：租约/锁 ====================

    private void releaseLease(Goal goal) {
        if (goal == null || goal.getLeaseOwner() == null) return;
        releaseLease(goal.getId(), goal.getLeaseOwner());
        goal.setLeaseOwner(null);
        goal.setLeaseUntil(null);
    }

    private void releaseLease(String goalId, String owner) {
        try {
            goalMapper.releaseLease(goalId, owner);
        } catch (Exception e) {
            log.warn("[GOAL] 释放租约失败 goal={}: {}", goalId, e.getMessage());
        }
    }

    private int leaseSeconds() {
        return settings.leaseSeconds();
    }

    private ReentrantLock lockFor(String id) {
        return locks.computeIfAbsent(id == null ? "" : id, k -> new ReentrantLock());
    }

    private Goal reload(String goalId) {
        Goal fresh = normalize(goalMapper.selectById(goalId));
        if (fresh == null) throw new IllegalArgumentException("目标不存在");
        return fresh;
    }

    private Goal own(String goalId, String uid) {
        Goal goal = get(goalId);
        if (goal == null) throw new IllegalArgumentException("目标不存在");
        if (uid != null && !uid.isBlank() && goal.getUserId() != null
                && !goal.getUserId().equals(uid)) {
            throw new IllegalStateException("只有发起人可以操作这条目标");
        }
        return goal;
    }

    // ==================== 内部：杂项 ====================

    /** 老行归一（只读内存，不写库）：上一版的中间态与终态映射到新状态机。 */
    private static Goal normalize(Goal goal) {
        if (goal == null) return null;
        String s = goal.getStatus();
        if (s == null) {
            goal.setStatus(PAUSED);
            s = PAUSED;
        }
        switch (s) {
            case "DRAFT", "CLARIFYING", "AWAITING_CONFIRMATION", "WAITING_INPUT" -> goal.setStatus(PAUSED);
            case "ENDED_INCOMPLETE" -> goal.setStatus(CANCELLED);
            default -> { /* 原样 */ }
        }
        if (TERMINAL.contains(goal.getStatus())) {
            // 存量库补列时 phase 默认 QUEUED，终态行必须按状态纠正，否则 UI/恢复会把它当成可调度目标。
            goal.setPhase(GoalPhase.DONE);
        } else if (goal.getPhase() == null || goal.getPhase().isBlank()) {
            goal.setPhase(switch (goal.getStatus()) {
                case RUNNING -> GoalPhase.EXECUTING;
                case VERIFYING -> GoalPhase.VERIFYING;
                case WAITING_APPROVAL -> GoalPhase.WAITING_APPROVAL;
                case WAITING_EVENT -> GoalPhase.WAITING_EVENT;
                default -> GoalPhase.QUEUED;
            });
        }
        if (goal.getObjectiveVersion() == null) goal.setObjectiveVersion(0);
        if (goal.getAttempt() == null) goal.setAttempt(0);
        if (goal.getNoProgressRounds() == null) goal.setNoProgressRounds(0);
        if (goal.getRecoveryCount() == null) goal.setRecoveryCount(0);
        if (goal.getRowVersion() == null) goal.setRowVersion(0L);
        return goal;
    }

    private static boolean isGoalTurn(String turnId) {
        return turnId != null && turnId.startsWith("goal:");
    }

    static String goalIdOfTurn(String turnId) {
        if (!isGoalTurn(turnId)) return null;
        String rest = turnId.substring("goal:".length());
        int colon = rest.indexOf(':');
        return colon > 0 ? rest.substring(0, colon) : rest;
    }

    /** 助手有没有在这一轮自报「做完了」：回答最后一行以完成标记开头算。 */
    static boolean declaresDone(String answer) {
        String last = lastNonBlankLine(answer);
        if (last == null) return false;
        String compact = last.replaceAll("[\\s*`#>_]+", "");
        return compact.startsWith(GoalCopy.DONE_MARKER);
    }

    /**
     * 回答里有没有明确的「已交付/已完成」语义（完成检查的触发条件之一）。
     * <p>刻意排除「未完成/还差/待补」等否定句：模型说“还差最后一步”不能被当成完成。
     * 它只决定“要不要起一次便宜的检查轮”，不决定终态——终态仍由 Verifier 判。
     */
    static boolean declaresDelivery(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String a = answer.toLowerCase();
        if (a.contains("未完成") || a.contains("没完成") || a.contains("还差")
                || a.contains("未交付") || a.contains("待补") || a.contains("尚未")
                || a.contains("不能交付") || a.contains("没有完成")) {
            return false;
        }
        return a.contains("已交付") || a.contains("已完成") || a.contains("定稿")
                || a.contains("已生成") || a.contains("已保存") || a.contains("已导出")
                || a.contains("已写入") || a.contains("完成并交付");
    }

    /** 助手有没有在这一轮自报「受阻」：最后一行以受阻标记开头算。 */
    static String declaresBlocked(String answer) {
        String last = lastNonBlankLine(answer);
        if (last == null) return null;
        String cleaned = stripDecorations(last);
        if (!cleaned.startsWith(GoalCopy.BLOCKED_MARKER)) return null;
        String reason = cleaned.substring(GoalCopy.BLOCKED_MARKER.length())
                .replaceFirst("^[*`\\s：:，,。.]+", "")
                .strip();
        return reason.isEmpty() ? "助手报告目标受阻" : reason;
    }

    /** 把收尾标记从回答里摘掉（落库/下发前调）。 */
    public static String stripMarkers(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        List<String> lines = new ArrayList<>(answer.lines().toList());
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) last = i;
        }
        if (last < 0) return answer;
        String line = lines.get(last);
        if (!declaresDone(line) && declaresBlocked(line) == null) return answer;
        lines.remove(last);
        return String.join("\n", lines).stripTrailing();
    }

    private static String lastNonBlankLine(String answer) {
        if (answer == null || answer.isBlank()) return null;
        String last = null;
        for (String line : answer.lines().toList()) {
            if (!line.isBlank()) last = line;
        }
        return last;
    }

    private static String stripDecorations(String line) {
        return line.strip()
                .replaceAll("^[*`#>_\\s]+", "")
                .replaceAll("[*`。！!，,；;.\\s]+$", "");
    }

    private static String normalizeObjective(String objective) {
        String text = objective == null ? "" : objective.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("目标不能为空");
        if (text.length() > MAX_OBJECTIVE_CHARS) {
            throw new IllegalArgumentException("目标太长（上限 " + MAX_OBJECTIVE_CHARS + " 字）");
        }
        return text;
    }

    private static String normalizeStage(String stage) {
        if (stage == null) return null;
        String s = stage.strip().toUpperCase();
        return switch (s) {
            case "EXPLORE", "PLAN", "EXECUTE", "VERIFY", "DELIVER" -> s;
            default -> null;
        };
    }

    private static List<String> parseList(String jsonOrText) {
        if (jsonOrText == null || jsonOrText.isBlank()) return new ArrayList<>();
        String s = jsonOrText.strip();
        if (s.startsWith("[")) {
            try {
                List<String> list = JSON.parseArray(s, String.class);
                return list == null ? new ArrayList<>() : list;
            } catch (Exception ignored) {
                // 不是字符串数组，可能是对象数组；让下面按行拆，至少不丢内容
            }
        }
        List<String> out = new ArrayList<>();
        for (String line : s.split("\\r?\\n")) {
            String v = line.strip().replaceFirst("^[-*\\d.、\\s]+", "");
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private static List<GoalProposal.Item> parseEvidence(String json) {
        List<GoalProposal.Item> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            List<GoalProposal.Item> items = JSON.parseArray(json, GoalProposal.Item.class);
            if (items != null) out.addAll(items);
            return out;
        } catch (Exception ignored) {
            // 兼容模型只给了文件名列表/一行文本
        }
        for (String s : parseList(json)) {
            out.add(new GoalProposal.Item(com.wenqu.ai.model.GoalEvidence.KIND_ARTIFACT, s, null));
        }
        return out;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String v = s.strip();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v.longValue();
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }


    // ==================== 内部：中断/终态 ====================

    /** 打断正在跑的目标轮（停止/删除/让位用户时）。实例不在本进程时静默跳过。 */
    private void interruptRunningGoalTurn(Goal goal) {
        try {
            io.agentscope.harness.agent.session.AgentSession session =
                    sessions.peek(goal.getUserId(), goal.getSessionId());
            if (session != null) session.interrupt();
        } catch (Exception e) {
            log.warn("[GOAL] 打断目标轮失败: goal={} {}", goal.getId(), e.getMessage());
        }
    }

    /** 用户删除：落终态、写 Outcome、释放租约。 */
    private void finish(Goal goal, String status, String endReason, RunChannel channel) {
        goal.setStatus(status);
        goal.setPhase(GoalPhase.DONE);
        goal.setEndReason(endReason);
        goal.setEndedAt(LocalDateTime.now());
        goal.setNextRunAt(null);
        goal.setWaitingReason(null);
        goal.setOutcomeJson(GoalOutcome.of("CANCELLED", endReason, "目标已删除").toJson());
        goalMapper.updateById(goal);
        releaseLease(goal);
        emitState(goal, channel);
        log.info("[GOAL] 目标终态: goal={} status={} reason={}", goal.getId(), status, endReason);
    }

    private static int ni(Integer v) {
        return v == null ? 0 : v;
    }

    // ==================== 兼容/占位 ====================

    private static String goalId(Goal goal) {
        return goal == null ? null : goal.getId();
    }

    private String goalSessionId(String goalId) {
        Goal g = get(goalId);
        return g == null ? null : g.getSessionId();
    }

    private String goalUserId(String goalId) {
        Goal g = get(goalId);
        return g == null ? null : g.getUserId();
    }

    private RunChannel channelOf(Goal goal) {
        return null;
    }
}
