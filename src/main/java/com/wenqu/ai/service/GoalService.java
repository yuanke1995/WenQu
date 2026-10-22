package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.mapper.GoalMapper;
import com.wenqu.ai.model.Goal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标模式的状态机与编排（问渠这一侧的全部「目标」逻辑）。
 *
 * <p><b>形状对齐 DSH 的 goal</b>：一个跨轮的目标，加一个阶段；没有验收标准、没有人工确认、
 * 没有逐条核验。用户只说目标，系统自己续跑；助手完成时在回答最后一行写
 * {@link GoalCopy#DONE_MARKER}，平台当场停下（{@code ACHIEVED}）。受阻时写
 * {@link GoalCopy#BLOCKED_MARKER} 加原因，平台转 {@code PAUSED} 并保留原因。
 *
 * <p>用户的动作只剩三个：<b>改目标原文 / 停止（可再继续）/ 删除</b>。其余全自动——
 * 这就是「不要验收标准、不要用户确认」在产品上的落点。
 *
 * <p>续跑由引擎发起（{@link GoalTurnDriver}）：每轮收尾后，只要目标还在 {@code RUNNING}
 * 且没到安全帽，就起下一轮。轮数上限 {@link #MAX_GOAL_ROUNDS} 只兜底（默认 256，与 DSH 一致），
 * 不是给用户的预算，正常路径靠助手自报完成收尾。
 */
@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);

    // ==================== 状态机（对齐 DSH：active / paused / complete / cleared） ====================

    /** 正在自动推进（DSH: active + armed） */
    public static final String RUNNING = "RUNNING";
    /** 停下了，可继续（DSH: paused / active-but-disarmed；也承载「受阻」） */
    public static final String PAUSED = "PAUSED";
    /** 助手自报完成（DSH: complete） */
    public static final String ACHIEVED = "ACHIEVED";
    /** 用户删除（DSH: clear） */
    public static final String CANCELLED = "CANCELLED";

    /** 当前目标（同会话至多一条）：只有这两档算「还归用户管」 */
    private static final Set<String> CURRENT = Set.of(RUNNING, PAUSED);

    /** 自动续跑安全帽：与 DSH 的 defaultMaxGoalRounds 一致。只兜底，不是预算。 */
    public static final int MAX_GOAL_ROUNDS = 256;

    /** 目标原文产品上限（框架/模型都不适合一条无限长的目标） */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    private final GoalMapper goalMapper;
    private final SessionEventService sessionEventService;
    private final ChatRunBus chatRunBus;
    private final com.wenqu.ai.agentx.FrameworkSessions sessions;
    /** 目标指令回声要写进对话（c_ai_message），状态机本体不碰这张表 */
    private final SessionService sessionService;

    /** 续跑驱动（引擎实现，构造期后绑）：跨轮循环的控制流归框架，问渠只数轮数并发起下一轮 */
    private volatile GoalTurnDriver turnDriver;

    /** 每目标一把锁：状态迁移不许并发（同会话串行已经挡了一层，这里是控制器动作之间） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public GoalService(GoalMapper goalMapper, SessionEventService sessionEventService,
                       ChatRunBus chatRunBus, com.wenqu.ai.agentx.FrameworkSessions sessions,
                       SessionService sessionService) {
        this.goalMapper = goalMapper;
        this.sessionEventService = sessionEventService;
        this.chatRunBus = chatRunBus;
        this.sessions = sessions;
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
     * <p>平台级总闸已去掉——能不能用只由「哪个智能体开着他」决定。
     */
    public boolean enabledFor(com.wenqu.ai.model.Agent agent) {
        return agent == null || agent.getGoalEnabled() == null || agent.getGoalEnabled() == 1;
    }

    // ==================== 用户动作 ====================

    /**
     * 设为目标：建行即 RUNNING，并立刻起首轮。
     * <p>同会话已有未结束目标时不允许再建（DSH 同一条约束）：要么改它、要么先删/等它做完。
     * 旧前端可能带 criteria/startNow 字段，一律忽略——目标模式不再有验收标准。
     */
    public Goal start(String sessionId, String uid, String agentId, String objective, RunChannel channel) {
        String text = objective == null ? "" : objective.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("目标不能为空");
        if (text.length() > MAX_OBJECTIVE_CHARS) {
            throw new IllegalArgumentException("目标太长（上限 " + MAX_OBJECTIVE_CHARS + " 字）");
        }
        synchronized (lockFor(sessionId)) {
            Goal current = currentGoal(sessionId);
            if (current != null) {
                throw new IllegalStateException("这条会话已有目标，先改它、继续它或删除它");
            }
            Goal goal = new Goal();
            goal.setId(UUID.randomUUID().toString().replace("-", ""));
            goal.setSessionId(sessionId);
            goal.setUserId(uid);
            goal.setAgentId(agentId);
            goal.setObjective(text);
            goal.setStatus(RUNNING);
            goal.setTurnCounter(0);
            goal.setUsedTurns(0);
            goal.setUsedTokens(0L);
            goal.setUsedToolCalls(0);
            goal.setUsedMs(0L);
            goal.setCreatedAt(LocalDateTime.now());
            goal.setStartedAt(goal.getCreatedAt());
            goalMapper.insert(goal);
            // 对话里留一行回声：换过目标之后，光靠 c_ai_goal 一行读不出「什么时候定了什么目标」
            echoGoalCommand(sessionId, goal.getId(), text);
            log.info("[GOAL] 目标已开始: goal={} session={} 上限 {} 轮", goal.getId(), sessionId,
                    MAX_GOAL_ROUNDS);
            emitState(goal, channel);
            kickContinuation(goal, channel);
            return goal;
        }
    }

    /**
     * 目标指令回声：把这句「设为目标」按普通用户消息落进对话，再补一列标记。
     * <p>失败只告警不抛：目标本身已经成立，回声只是对话里的一行留痕。
     */
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
            // 锁内重读：从 own() 到这里之间，另一条动作（删除/收尾）可能已经改过这一行。
            // 拿旧对象 updateById 会把别人刚写下的状态覆盖回去（删了的目标又冒出来）。
            goal = reload(goalId);
            // 幂等：这一轮可能刚好在用户点停止的同时自己收尾成了 PAUSED（轮终判定与用户的动作赛跑）。
            // 已经停着就直接把当前状态交回去，不让用户看到一句「只有进行中的目标可以停止」。
            if (PAUSED.equals(goal.getStatus())) return goal;
            if (!RUNNING.equals(goal.getStatus())) {
                throw new IllegalStateException("只有进行中的目标可以停止");
            }
            goal.setStatus(PAUSED);
            goal.setEndReason("paused");
            goal.setBlockedReason(null);
            goalMapper.updateById(goal);
            interruptRunningGoalTurn(goal);
            emitState(goal, channel);
            log.info("[GOAL] 目标已停止: goal={}", goalId);
            return goal;
        }
    }

    /** 继续（DSH: resume）：从停下那一轮之后接着跑；受阻原因一并清掉。 */
    public Goal resume(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            // 幂等：连点两下「继续」时第二下不该报错
            if (RUNNING.equals(goal.getStatus())) return goal;
            if (!PAUSED.equals(goal.getStatus())) {
                throw new IllegalStateException("这条目标不在暂停状态");
            }
            goal.setStatus(RUNNING);
            goal.setEndReason(null);
            goal.setBlockedReason(null);
            goalMapper.updateById(goal);
            emitState(goal, channel);
            kickContinuation(goal, channel);
            log.info("[GOAL] 目标继续: goal={} 已跑 {} 轮", goalId, goal.getTurnCounter());
            return goal;
        }
    }

    /** 改目标原文（DSH: edit）：阶段不变；正在跑的下一轮会用新原文。 */
    public Goal edit(String goalId, String uid, String objective, RunChannel channel) {
        Goal goal = own(goalId, uid);
        String text = objective == null ? "" : objective.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("目标不能为空");
        if (text.length() > MAX_OBJECTIVE_CHARS) {
            throw new IllegalArgumentException("目标太长（上限 " + MAX_OBJECTIVE_CHARS + " 字）");
        }
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            if (!CURRENT.contains(goal.getStatus())) {
                throw new IllegalStateException("这条目标已经结束了，改不了");
            }
            goal.setObjective(text);
            goalMapper.updateById(goal);
            emitState(goal, channel);
            log.info("[GOAL] 目标已改订: goal={}", goalId);
            // 不在这里 kick：改目标很可能发生在某一轮正在跑的当中，再 submit 一轮会和那一轮撞车
            // （同会话跟随位只允许一个跟随者，撞车要么把新轮丢掉、要么两轮并行各落一条消息）。
            // 这一轮收尾时 continueAfterTurn 会带着新原文起下一轮；若此刻恰好没有轮在跑
            // （极窄的空档），下一次用户消息收尾时也会接上。
            return goal;
        }
    }

    /** 删除（DSH: clear）：目标从界面上撤下，历史与已跑出来的内容全部保留。 */
    public Goal clear(String goalId, String uid, RunChannel channel) {
        Goal goal = own(goalId, uid);
        synchronized (lockFor(goalId)) {
            goal = reload(goalId);
            interruptRunningGoalTurn(goal);
            finish(goal, CANCELLED, "cleared", channel);
            log.info("[GOAL] 目标已删除: goal={}", goalId);
            return goal;
        }
    }

    // ==================== 引擎钩子 ====================

    /**
     * 用户自己那一轮开跑时调用：后台目标轮还在飞就让位（用户优先），目标转暂停。
     * <p>让位是暂态：这一轮用户问答收尾后 {@link #continueAfterTurn} 会把目标自动接上
     * （{@code endReason=preempted} 是那条自动恢复的判据），用户不必手动点继续。
     */
    public void noteUserTurn(String sessionId, String uid, RunChannel channel) {
        Goal goal = currentGoal(sessionId);
        if (goal == null || !RUNNING.equals(goal.getStatus())) return;
        synchronized (lockFor(goal.getId())) {
            goal = reload(goal.getId());
            // 被打断的那一轮可能先一步把自己收成了 PAUSED（stopped/turn-failed）：那是同一次让位的
            // 另一条路径，仍要落成 preempted，用户这一轮收尾后才会自动接上。只有终态才真的不动。
            if (!RUNNING.equals(goal.getStatus()) && !PAUSED.equals(goal.getStatus())) return;
            interruptRunningGoalTurn(goal);
            goal.setStatus(PAUSED);
            goal.setEndReason("preempted");
            goalMapper.updateById(goal);
            emitState(goal, channel);
            log.info("[GOAL] 用户插话，目标循环让位转暂停: goal={}", goal.getId());
        }
    }

    /**
     * 本轮收尾：记账 → 判完成/受阻 → 判安全帽 → 发状态帧。
     * <p>只有 {@code RUNNING} 的目标在管这件事；用户暂停/删除后的收尾不再改状态。
     *
     * @param answer      本轮助手的最终正文（判完成标记/受阻标记用；失败轮可能是空串）
     * @param turnHealthy 这一轮是不是正常跑完的（额度/网关/没启动都算不正常）——不正常就停在原地，
     *                    不顺链续跑，否则一次配额错误会被滚成无限重试
     * @param stopped     用户是否按了停止：停止就是「这一轮到此为止」，不能自动再起一轮把它撤销掉
     */
    public void onTurnEnd(String sessionId, String uid, String turnId, long machineMs, int toolCalls,
                          RunChannel channel, String answer, boolean turnHealthy, boolean stopped) {
        Goal goal = currentGoal(sessionId);
        if (goal == null || !RUNNING.equals(goal.getStatus())) return;
        synchronized (lockFor(goal.getId())) {
            // 锁内重读：开轮时读到的 RUNNING 可能在等锁期间被用户停止/删除，不能拿旧对象覆盖回去
            goal = reload(goal.getId());
            if (!RUNNING.equals(goal.getStatus())) return;
            // 旧目标的轮收尾不能算到新目标头上：用户删掉旧目标、立刻建了新目标时，旧目标那一轮
            // 可能还在收尾。轮 id 里带着目标 id，不是当前目标的轮直接忽略。
            String goalOwner = goalIdOfTurn(turnId);
            if (goalOwner != null && !goalOwner.equals(goal.getId())) return;
            // 轮数在「这一轮真的被提交」时已经加过（see kickContinuation）：这里只记账。
            // 若在这里再加一次，用户插话打断的那一轮会既占号又被重号，续跑幂等键撞回同一轮。
            goal.setUsedTurns((int) used(goal.getUsedTurns()) + 1);
            goal.setUsedMs(used(goal.getUsedMs()) + Math.max(0L, machineMs));
            goal.setUsedToolCalls((int) used(goal.getUsedToolCalls()) + Math.max(0, toolCalls));
            if (goal.getFirstTurnId() == null) goal.setFirstTurnId(turnId);
            goal.setLastTurnId(turnId);
            goal.setUsedTokens(sumTokens(sessionId, goal.getStartedAt()));

            if (stopped) {
                goal.setStatus(PAUSED);
                goal.setEndReason("stopped");
                goalMapper.updateById(goal);
                emitState(goal, channel);
                log.info("[GOAL] 用户停止本轮，目标转暂停: goal={}", goal.getId());
                return;
            }
            if (!turnHealthy) {
                goal.setStatus(PAUSED);
                goal.setEndReason("turn-failed");
                goalMapper.updateById(goal);
                emitState(goal, channel);
                log.warn("[GOAL] 本轮未正常收尾，目标转暂停（续跑等用户点继续）: goal={}", goal.getId());
                return;
            }
            String blockedReason = declaresBlocked(answer);
            if (blockedReason != null) {
                goal.setStatus(PAUSED);
                goal.setEndReason("blocked");
                goal.setBlockedReason(blockedReason);
                goalMapper.updateById(goal);
                emitState(goal, channel);
                log.info("[GOAL] 助手报告受阻，目标暂停: goal={} 原因={}", goal.getId(), blockedReason);
                return;
            }
            if (declaresDone(answer)) {
                finish(goal, ACHIEVED, "achieved", channel);
                log.info("[GOAL] 目标完成: goal={} 共跑 {} 轮", goal.getId(), used(goal.getTurnCounter()));
                return;
            }
            if (used(goal.getTurnCounter()) >= MAX_GOAL_ROUNDS) {
                goal.setStatus(PAUSED);
                goal.setEndReason("round-limit");
                goalMapper.updateById(goal);
                emitState(goal, channel);
                log.info("[GOAL] 自动续跑到安全帽，停下: goal={} 已跑 {} 轮", goal.getId(),
                        used(goal.getTurnCounter()));
                return;
            }
            goalMapper.updateById(goal);
            emitState(goal, channel);
            // 续跑不在这里发起：这一轮还占着会话的跟随位（跟随位没释放时新的一轮会被「已有跟随者」挡掉）。
            // 由调用方在本轮彻底收尾之后调 {@link #continueAfterTurn}。
        }
    }

    /**
     * 本轮彻底收尾后接下一轮（引擎在跟随位释放之后调）。
     * <p>三个分支：
     * <ul>
     *   <li>目标还在跑 → 起下一轮；</li>
     *   <li>刚才那一轮是用户插话把目标挤停的（{@code preempted}）→ 自动恢复并接下一轮，
     *       不让「用户说了一句话」变成一次需要手动继续的操作；</li>
     *   <li>用户自己点的停止 → 不动。</li>
     * </ul>
     *
     * @param endedTurnId 刚刚收尾的那一轮 id：用来区分「用户轮收尾」与「目标轮收尾」
     */
    public void continueAfterTurn(String sessionId, String uid, String endedTurnId) {
        Goal goal = currentGoal(sessionId);
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            goal = reload(goal.getId());
            if (goal == null) return;
            // 同上：旧目标轮的收尾不能去驱动新目标
            String endedOwner = goalIdOfTurn(endedTurnId);
            if (endedOwner != null && !endedOwner.equals(goal.getId())) return;
            if (PAUSED.equals(goal.getStatus())
                    && "preempted".equals(goal.getEndReason())
                    && !isGoalTurn(endedTurnId)) {
                goal.setStatus(RUNNING);
                goal.setEndReason(null);
                goalMapper.updateById(goal);
                emitState(goal, null);
                log.info("[GOAL] 用户那一轮结束，目标自动接上: goal={}", goal.getId());
            }
            kickContinuation(goal, null);
        }
    }

    /** 后台轮被用户打断/自行失败：目标转 PAUSED（不是终态——回来可续）。 */
    public void markContinueFailed(Goal goal, String reason) {
        if (goal == null) return;
        synchronized (lockFor(goal.getId())) {
            Goal fresh = goalMapper.selectById(goal.getId());
            if (fresh == null || !RUNNING.equals(fresh.getStatus())) return;
            fresh.setStatus(PAUSED);
            fresh.setEndReason("turn-failed");
            goalMapper.updateById(fresh);
            // 这一跑没起来时没有轮终帧，状态得自己发一次，否则界面还停在「进行中」
            emitState(fresh, null);
            log.warn("[GOAL] 后台续跑没起来: goal={} {}", fresh.getId(), reason);
        }
    }

    /** 目标轮是否还能提交（提交前复查：目标可能刚被用户删掉/停掉）。 */
    public boolean runnable(Goal goal) {
        if (goal == null) return false;
        Goal fresh = goalMapper.selectById(goal.getId());
        return fresh != null && RUNNING.equals(fresh.getStatus());
    }

    private void kickContinuation(Goal goal, RunChannel channel) {
        GoalTurnDriver driver = this.turnDriver;
        if (driver == null) return;
        if (!RUNNING.equals(goal.getStatus())) return;
        int done = (int) used(goal.getTurnCounter());
        if (done >= MAX_GOAL_ROUNDS) {
            goal.setStatus(PAUSED);
            goal.setEndReason("round-limit");
            goalMapper.updateById(goal);
            emitState(goal, channel);
            return;
        }
        // 轮数在「开跑前」就占掉（DSH: roundsStarted 入账即计数）：这样被打断/让位的那一轮
        // 也占一个号，下一轮不会拿同一个幂等键 goal:{id}:{n} 再提交一次（那会撞回那一轮）。
        int n = done + 1;
        goal.setTurnCounter(n);
        goalMapper.updateById(goal);
        String input = GoalCopy.roundPrompt(goal.getObjective(), n, MAX_GOAL_ROUNDS);
        log.info("[GOAL] 起第 {} 轮续跑: goal={}", n, goal.getId());
        driver.continueTurn(goal, input, n);
    }

    /**
     * 把正在跑的目标轮打断（停止/删除/让位用户时）。实例不在本进程时静默跳过——
     * 那一轮不在本进程跑，下一轮开跑前会按库里状态重新判。
     */
    private void interruptRunningGoalTurn(Goal goal) {
        try {
            io.agentscope.harness.agent.session.AgentSession session =
                    sessions.peek(goal.getUserId(), goal.getSessionId());
            if (session != null) session.interrupt();
        } catch (Exception e) {
            log.warn("[GOAL] 打断目标轮失败: goal={} {}", goal.getId(), e.getMessage());
        }
    }

    // ==================== 模型上下文 ====================

    /**
     * 用户交互轮拼进模型输入的一句目标上下文；没有当前目标时返回空串。
     * <p>续跑轮由 {@link GoalCopy#roundPrompt} 自带完整目标，不经过这里。
     */
    public String contextNoteFor(String sessionId) {
        Goal goal = currentGoal(sessionId);
        if (goal == null) return "";
        return GoalCopy.contextNote(goal.getObjective());
    }

    // ==================== 查询 ====================

    /** 这条会话当前的目标（RUNNING/PAUSED；同会话至多一条） */
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

    /**
     * 当前目标（恢复端点用）：形状 {@code {goal: 视图|null}}，与
     * {@code /session/{id}/goal} 同源，前端两处取数不用各判形状。
     */
    public Map<String, Object> pending(String sessionId, String uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        Goal goal = currentGoal(sessionId);
        out.put("goal", goal == null ? null : view(goal));
        return out;
    }

    /** 目标过程账本（按 goal_id 聚合；过程面板深链用）。归属只认 userId。 */
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

    /**
     * 前端视图模型（右栏面板、目标条、goal_state 帧共用同一份）。
     * <p>字段刻意少：目标原文 + 阶段 + 跑了多少轮 + 为什么停。验收标准/验证记录/预算/倒计时
     * 全部退役——它们对应的用户操作也一并退役。
     */
    public Map<String, Object> view(Goal goal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goalId", goal.getId());
        m.put("status", goal.getStatus());
        m.put("objective", goal.getObjective());
        m.put("round", used(goal.getTurnCounter()));
        m.put("maxRounds", MAX_GOAL_ROUNDS);
        m.put("hint", hintOf(goal));
        m.put("endReason", goal.getEndReason());
        m.put("blockedReason", goal.getBlockedReason());
        m.put("live", sessions.peek(goal.getUserId(), goal.getSessionId()) != null);
        m.put("firstTurnId", goal.getFirstTurnId() == null ? "" : goal.getFirstTurnId());
        m.put("lastTurnId", goal.getLastTurnId() == null ? "" : goal.getLastTurnId());
        m.put("createdAt", iso(goal.getCreatedAt()));
        m.put("startedAt", iso(goal.getStartedAt()));
        m.put("endedAt", iso(goal.getEndedAt()));
        return m;
    }

    /**
     * 条子上那一句人话：只在「停着」且这句不是结构化原因时给。
     * <p>分工：轮数上限与受阻在「停因」那一行说（前端 goalBlockReasonOf，带轮次/原因），
     * 这里只补用户停止 / 出错 / 让位这类状态说明——两处都写就成了同一件事说两遍。
     */
    private String hintOf(Goal goal) {
        if (!PAUSED.equals(goal.getStatus())) return null;
        if ("blocked".equals(goal.getEndReason()) || "round-limit".equals(goal.getEndReason())) return null;
        return GoalCopy.pausedHint(goal.getEndReason());
    }

    // ==================== 帧 ====================

    /** goal_state：账本（带 goal_id）+ 通道或总线。channel 为空=控制器侧动作，只进总线。 */
    public void emitState(Goal goal, RunChannel channel) {
        String frame = JSON.toJSONString(view(goal));
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

    // ==================== 内部：标记判定 ====================

    /**
     * 助手有没有在这一轮自报「做完了」：回答最后一行以完成标记开头算。
     * <p>只认最后一行；剥掉行首行尾的 markdown 强调/引用装饰与句读——模型经常给标记加粗、
     * 加句号，或在标记后面接着列交付物。正文中间出现这个标记不算（那只是它在解释规则）。
     */
    static boolean declaresDone(String answer) {
        String last = lastNonBlankLine(answer);
        if (last == null) return false;
        // 完成标记只比「开头是不是它」：把标记内部的空格/加粗符号压掉（模型会写「【 目标完成 】」
        // 或「**【目标完成】**」），标记后面跟着的交付物清单不影响判定。
        String compact = last.replaceAll("[\\s*`#>_]+", "");
        return compact.startsWith(GoalCopy.DONE_MARKER);
    }

    /**
     * 助手有没有在这一轮自报「受阻」：最后一行以受阻标记开头算，标记后面的字就是原因。
     *
     * @return 受阻原因（可能为空串 = 助手只写了标记），没有受阻标记返回 null
     */
    static String declaresBlocked(String answer) {
        String last = lastNonBlankLine(answer);
        if (last == null) return null;
        // 原因要保住词间空格（英文/代码里的空格是语义），所以这里不复用「压掉全部空白」的归一化：
        // 只剥行首行尾的强调/引用装饰，再剥标记后面紧跟的标点。
        String cleaned = stripDecorations(last);
        if (!cleaned.startsWith(GoalCopy.BLOCKED_MARKER)) return null;
        String reason = cleaned.substring(GoalCopy.BLOCKED_MARKER.length())
                .replaceFirst("^[*`\\s：:，,。.]+", "")
                .strip();
        return reason.isEmpty() ? "助手报告目标受阻" : reason;
    }

    /**
     * 把收尾标记从回答里摘掉（落库/下发前调）：标记是给平台看的控制信号，不是给用户读的正文。
     * <p>只摘最后一行且仅当它是完成/受阻标记；正文中间出现的同样字样不动（那可能是引用/解释）。
     * 摘掉后末尾若有空行一并收掉，避免气泡尾部多一截空白。
     */
    public static String stripMarkers(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        java.util.List<String> lines = new java.util.ArrayList<>(answer.lines().toList());
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

    /** 剥掉行首行尾的空白与 markdown 强调/引用装饰（内侧空格保留），供标记识别用 */
    private static String stripDecorations(String line) {
        return line.strip()
                .replaceAll("^[*`#>_\\s]+", "")
                .replaceAll("[*`。！!，,；;.\\s]+$", "");
    }

    private static boolean isGoalTurn(String turnId) {
        return turnId != null && turnId.startsWith("goal:");
    }

    /** 从 {@code goal:{goalId}:{n}} 里取目标 id；用户轮的 turnId 返回 null。 */
    static String goalIdOfTurn(String turnId) {
        if (!isGoalTurn(turnId)) return null;
        String rest = turnId.substring("goal:".length());
        int colon = rest.indexOf(':');
        return colon > 0 ? rest.substring(0, colon) : rest;
    }

    // ==================== 内部：账户与终态 ====================

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

    private void finish(Goal goal, String status, String endReason, RunChannel channel) {
        goal.setStatus(status);
        goal.setEndReason(endReason);
        goal.setEndedAt(LocalDateTime.now());
        goalMapper.updateById(goal);
        emitState(goal, channel);
        log.info("[GOAL] 目标终态: goal={} status={} reason={}", goal.getId(), status, endReason);
    }

    // ==================== 内部：杂项 ====================

    /**
     * 老行的状态归一（只读内存，不写库）：上一版有验收标准/确认卡，库里可能还留着那几档。
     * <ul>
     *   <li>未结束的那几档（待确认/待核验/验证中/起草）→ {@code PAUSED}：升上来后不自动续跑，
     *       等用户自己决定继续或删除；</li>
     *   <li>旧终态 {@code ENDED_INCOMPLETE}/{@code FAILED} → {@code CANCELLED}（都已结束）。</li>
     * </ul>
     * <p>不写库：老行只影响显示，下一次用户动作（继续/删除）会把它落成新状态。
     */
    private static Goal normalize(Goal goal) {
        if (goal == null) return null;
        String s = goal.getStatus();
        if (s == null) {
            goal.setStatus(PAUSED);
            return goal;
        }
        switch (s) {
            case "DRAFT", "CLARIFYING", "AWAITING_CONFIRMATION", "WAITING_INPUT", "VERIFYING" ->
                    goal.setStatus(PAUSED);
            case "FAILED", "ENDED_INCOMPLETE" -> goal.setStatus(CANCELLED);
            default -> { /* RUNNING/PAUSED/ACHIEVED/CANCELLED 及未知值原样 */ }
        }
        return goal;
    }

    private Object lockFor(String goalId) {
        return locks.computeIfAbsent(goalId == null ? "" : goalId, k -> new Object());
    }

    /** 锁内重读（并归一老状态）：并发动作之间不许拿旧对象覆盖别人刚写下的行 */
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

    private static long used(Integer v) {
        return v == null ? 0L : v.longValue();
    }

    private static long used(Long v) {
        return v == null ? 0L : v;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
