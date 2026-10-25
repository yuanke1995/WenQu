package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.util.RequestUser;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话轮与目标的站内通知产生方（「长轮跑完 / 跑挂 / 等人拍板」这三件事此前的空缺）。
 *
 * <p><b>为什么必须异步</b>：目标服务的跨轮结算整段挂在「按目标一把锁」里
 * （{@code GoalService#onRoundSettled} → {@code complete/fail/pauseBlocked}），同步写通知等于
 * 把一把业务锁压在两次数据库往返上；问答收口那一刻同样是用户在等的一帧。通知是旁路，
 * 写慢、写丢都不许反过来影响问答与目标，所以本类一切落库都投进单线程队列。
 *
 * <p><b>异步线程没有登录态</b>，所以归属一律按 sessionId 回查会话行
 * （{@code c_ai_session.user_id}），任何时候都不许在这里读 {@code RequestUser}：流水线线程与后台续跑
 * 池线程不重放登录态会回落 anonymous，而续跑重建命令时把空 uid 落成了字面量 {@code guest}
 * （{@code AgentscopeChatEngine#openForContinuation}）——{@code guest} 过得了
 * {@link NotificationService#create} 那道「无归属就跳过」的闸，通知会写进一个不存在的账号。
 *
 * <p><b>免登录分享轮不发</b>：访客身份是 {@code share-visitor:{随机}}，没有站内账号。
 *
 * <p><b>定时任务轮、工作流子轮、被咨询出去的子轮都不发</b>：前两档按
 * {@link ChatCommand#getSource()} 挡（{@code scheduled} / {@code workflow}），它们各有自己的终态通知
 * （{@code schedule.*} / {@code workflow.*}），再叠一条对话轮通知就是同一件事报两遍；
 * 咨询子轮按 {@link ChatCommand#getParentSessionId()} 挡——「问另一位助手」是父轮里的一步操作，
 * 为它单独钉一条会让一次咨询冒出两条通知。
 *
 * <p><b>目标轮不逐轮报完成</b>：目标横跨多轮，逐轮钉通知会把铃铛变成进度条。判据由调用方按轮 id 前缀
 * {@code goal:}（{@code GoalService#GOAL_TURN_ID_PREFIX}）传入，比在这里回查目标表更准
 * ——目标刚刚达成时 {@code currentGoal} 已返回 null，那一轮仍属目标轮。
 * 失败不受这条限制：目标跑挂了是要人处理的事，由 {@code goal.failed} 说。
 *
 * <p><b>短轮不报完成</b>：阈值 {@code notification.turnMinSeconds}（默认 60 秒机器耗时，不含等人读卡的时间）。
 * 三秒答完的轮，答案当场就在屏幕上，多一条通知只是噪音；能跑到一分钟以上的轮，人大概率已经切走或锁屏。
 * 人还在屏幕前却收到一条长轮完成通知，代价是一行已读——远小于把「回来看看」这件事整体丢掉。
 *
 * <p>与 {@link NotificationService#create} 同一条旁路纪律：一切异常只记 WARN，绝不上抛。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnNotifyService {

    private final NotificationService notifications;
    private final SessionService sessionService;
    private final ConfigService configService;

    /** 跳转落点：会话。{@code refId}=sessionId，{@code refSub}=轮 id（提问/审批类是那张卡的号） */
    private static final String REF_SESSION = "session";

    /** 队列上限：宁可丢通知也不堆内存——通知是旁路，积压说明数据库已经写不动了 */
    private static final int QUEUE_CAP = 200;

    /**
     * 单线程保序投递（同一目标的「停住」与「达成」不许倒着到）。
     * 满了丢最旧那条并记 WARN：投递侧的取舍与业务侧无关，不参与背压。
     */
    private final java.util.concurrent.ExecutorService executor =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(QUEUE_CAP),
                    r -> {
                        Thread t = new Thread(r, "turn-notify");
                        t.setDaemon(true);
                        return t;
                    },
                    (task, pool) -> {
                        log.warn("[NOTIFY] 通知队列已满（{}），丢弃最旧一条——通知是旁路，不参与背压",
                                pool.getQueue().size());
                        if (!pool.isShutdown()) {
                            pool.getQueue().poll();
                            pool.execute(task);
                        }
                    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ==================== 一轮收口 ====================

    /**
     * 一轮对话收口。
     *
     * @param cmd       本轮命令（取 sessionId 与发起方 source）
     * @param turnId    本轮 id
     * @param machineMs 机器耗时（墙钟扣掉等人读卡的时间，与界面「本轮用时」同一个数）
     * @param steps     本轮走过的操作步数
     * @param stopped   用户按了停止——不发：那是他自己按的，不需要被告知
     * @param awaiting  卡片还开着、这一轮暂时没人跟——不发：待决通知已经单独发过一条
     * @param failure   提前结束的原因原文，可为空（空=这一轮正常跑完）
     * @param hasBody   这一轮有没有留下可读的正文：有正文的提前结束是「干到一半停了」，
     *                  没正文的才是「没跑成」——两件事不能共用一句标题
     * @param goalTurn  这一轮是不是目标轮（由调用方按轮 id 前缀判，避免这里再拼一遍字面量）
     */
    public void onTurnSettled(ChatCommand cmd, String turnId, long machineMs, int steps,
                              boolean stopped, boolean awaiting, String failure,
                              boolean hasBody, boolean goalTurn) {
        String uid = turnUid(cmd);
        if (uid == null) return;
        // 平台级总闸放在最前面：关掉就要真的安静——只挡「跑完了」而继续发「没跑成」，
        // 等于交一个关不掉的开关给管理员
        if (!turnDoneEnabled()) return;
        String sessionId = cmd.getSessionId();
        boolean early = failure != null && !failure.isBlank();
        // 被自己按停的、以及卡片还开着等人答的，都不算「跑完了」这件事
        if (!early && (stopped || awaiting)) return;
        if (!early && goalTurn) return;
        String text = failure;
        submit("轮次通知", () -> {
            String title = sessionService.titleOf(sessionId);
            // 没有轮 id 就不设去重键：按空串去重会把<b>不同会话</b>的失败并成同一条（去重键不含会话）
            String turn = turnId == null ? "" : turnId;
            String dedup = turn.isBlank() ? null : "turn:" + turn;
            if (early) {
                notifications.create(uid, Notification.TYPE_CHAT_TURN_FAILED,
                        hasBody ? "这一轮提前停了" : "这一轮没跑成",
                        describe(title) + " · " + clip(text),
                        REF_SESSION, sessionId, dedup, turn);
                return;
            }
            if (machineMs < turnMinMs()) return;
            notifications.create(uid, Notification.TYPE_CHAT_TURN_DONE,
                    "这一轮跑完了",
                    describe(title) + " · 用时 " + durationMs(machineMs)
                            + (steps > 0 ? " · 走了 " + steps + " 步" : ""),
                    REF_SESSION, sessionId, dedup, turn);
        });
    }

    // ==================== 人在回路待决 ====================

    /** 智能体提问卡挂起等用户作答。{@code askText} 是题面（界面上那张卡问的就是那句问题）。 */
    public void onAskPending(ChatCommand cmd, String askId, String askText) {
        if (!turnDoneEnabled()) return;
        String uid = turnUid(cmd);
        if (uid == null) return;
        String sessionId = cmd.getSessionId();
        submit("提问待决通知", () -> notifications.create(uid, Notification.TYPE_TOOL_ASK,
                "智能体在等你回答",
                describe(sessionService.titleOf(sessionId))
                        + (askText == null || askText.isBlank() ? "" : " · " + clip(askText)),
                REF_SESSION, sessionId, "ask:" + askId, askId));
    }

    /** 工具审批卡挂起。{@code label} 必须走与工具卡同源的展示名，不许印裸工具名。 */
    public void onApprovalPending(ChatCommand cmd, String approvalId, String label) {
        if (!turnDoneEnabled()) return;
        String uid = turnUid(cmd);
        if (uid == null) return;
        String sessionId = cmd.getSessionId();
        submit("审批待决通知", () -> notifications.create(uid, Notification.TYPE_TOOL_APPROVAL,
                "有一个操作等你批准",
                describe(sessionService.titleOf(sessionId))
                        + (label == null || label.isBlank() ? "" : " · " + clip(label)),
                REF_SESSION, sessionId, "approval:" + approvalId, approvalId));
    }

    /** 计划确认卡挂起：确认后这一轮才动手，不确认会超时并停止本轮。 */
    public void onPlanPending(ChatCommand cmd, String planApprovalId) {
        if (!turnDoneEnabled()) return;
        String uid = turnUid(cmd);
        if (uid == null) return;
        String sessionId = cmd.getSessionId();
        submit("计划待确认通知", () -> notifications.create(uid, Notification.TYPE_PLAN_APPROVAL,
                "有一份计划等你确认",
                describe(sessionService.titleOf(sessionId)) + " · 确认后才开始执行",
                REF_SESSION, sessionId, "plan:" + planApprovalId, planApprovalId));
    }

    // ==================== 目标终态 ====================

    /**
     * 目标进入终态，或停下等人补东西。用户自己按停/删除的不在这里（那是他刚做的动作）。
     *
     * <p>Goal 是可变实体，调用线程正握着按目标的锁——<b>取完快照再投</b>，异步侧不许读活对象。
     *
     * @param achieved 达成还是停住（停住含预算耗尽、连续无进展、受阻）
     * @param reason   停住的原因，必须是给人看的那句；内部档位词（{@code budget} 这类）不许传进来
     */
    public void onGoalTerminal(Goal goal, boolean achieved, String reason) {
        if (goal == null || !turnDoneEnabled()) return;
        String sessionId = goal.getSessionId();
        String goalId = goal.getId();
        String objective = clip(goal.getObjective());
        String endReason = goal.getEndReason();
        String lastTurnId = goal.getLastTurnId();
        int turns = nz(goal.getTurnCounter());
        long usedMs = nz(goal.getUsedMs());
        String why = clip(reason);
        submit("目标终态通知", () -> {
            String uid = ownerUid(sessionId);
            if (uid == null) return;
            String meta = "跑了 " + turns + " 轮 · 用时 " + durationMs(usedMs);
            String content = (objective.isBlank()
                    ? describe(sessionService.titleOf(sessionId)) : objective) + " · " + meta
                    + (!achieved && !why.isBlank() ? " · " + why : "");
            notifications.create(uid,
                    achieved ? Notification.TYPE_GOAL_ACHIEVED : Notification.TYPE_GOAL_FAILED,
                    achieved ? "目标完成了" : "目标停住了",
                    content, REF_SESSION, sessionId,
                    // 去重键按「目标 + 停住的原因档」：同一目标反复报同一档才合并（受阻被调度器重扫时
                    // 不该刷屏），不同档是两个结论，不许并成一条
                    goalId + ":" + (achieved ? "achieved"
                            : (endReason == null || endReason.isBlank() ? "stopped" : endReason)),
                    lastTurnId);
        });
    }

    // ==================== 内部 ====================

    /** 投递一层：旁路的一切异常（含队列自身）都不许让调用方看见 */
    private void submit(String what, Runnable job) {
        try {
            executor.execute(() -> {
                try {
                    job.run();
                } catch (Exception e) {
                    log.warn("[NOTIFY] {}失败（旁路，不影响主链路）: {}", what, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[NOTIFY] {}投递失败: {}", what, e.getMessage());
        }
    }

    /** 问答与目标这一类通知的平台级出口（个人想收哪几类走个人静音偏好） */
    private boolean turnDoneEnabled() {
        return configService.getBoolean("notification.turnEnabled", true);
    }

    /** 报「跑完了」的最低机器耗时 */
    private long turnMinMs() {
        return Math.max(0L, configService.getInt("notification.turnMinSeconds", 60)) * 1000L;
    }

    /**
     * 这一轮有没有「该被通知的人」：先挡掉不该说话的发起方，再解析归属。
     * 一处挡齐三类（定时任务 / 工作流子智能体 / 被咨询出去的子轮），新增发起方时只需看这里。
     */
    private String turnUid(ChatCommand cmd) {
        if (cmd == null || cmd.getSessionId() == null || cmd.getSessionId().isBlank()) return null;
        String src = cmd.getSource();
        if (src != null && !src.isBlank() && !TurnLease.KIND_USER.equals(src)) return null;
        if (cmd.getParentSessionId() != null && !cmd.getParentSessionId().isBlank()) return null;
        return ownerUid(cmd.getSessionId());
    }

    /**
     * 会话归属人：{@code c_ai_session.user_id}，非登录态身份一律排除。
     * 返回 null = 没有可通知的账号（会话查不到 / 免登录访客 / 字面量兜底值）。
     */
    private String ownerUid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Session s = sessionService.sessionById(sessionId);
        if (s == null || s.getUserId() == null || s.getUserId().isBlank()) return null;
        String uid = s.getUserId();
        if (RequestUser.ANONYMOUS.equals(uid) || "guest".equals(uid)) return null;
        if (uid.startsWith(AgentShareService.VISITOR_UID_PREFIX)) return null;
        return uid;
    }

    /** 会话那一侧的说法：有标题报名字，没标题报泛称——不编名字（与 {@code SessionService#titleOf} 同口径） */
    private static String describe(String title) {
        return title == null || title.isBlank() ? "一条会话" : "会话「" + title + "」";
    }

    /** 耗时说法与界面「本轮用时」同词：秒 / 分+秒 / 小时+分 */
    static String durationMs(long ms) {
        long sec = Math.max(0L, ms) / 1000L;
        if (sec < 60) return sec + " 秒";
        long min = sec / 60;
        if (min < 60) return min + " 分 " + (sec % 60) + " 秒";
        return min / 60 + " 小时 " + (min % 60) + " 分";
    }

    /** 标题/正文的列宽截断由 NotificationService 管，这里只把明显过长的句子先收短 */
    private static String clip(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
