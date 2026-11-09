package com.wenqu.ai.agentx.turn;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.session.AgentSession;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 跟随循环的运行状态与退出决策：本轮怎么起、怎么停、是否空转/超时/从未启动，以及迭代游标与播报基准。
 * <p>循环体仍由引擎驱动；这里收口状态与“任务结算后还要不要继续跟”的决策。
 */
public final class TurnFollowLoop {

    private boolean stopped;
    private boolean stalled;
    private boolean timedOut;
    private boolean launched;
    private boolean neverLaunched;

    private long cursor;
    private long emitFrom;
    private int announcedRefs;
    private final Set<String> announcedApprovals = new LinkedHashSet<>();
    private final long[] usageStamp = {-1, -1};
    private long lastStateProbeAt;

    public void seed(long cursor, long emitFrom) {
        this.cursor = cursor;
        this.emitFrom = emitFrom;
    }

    public void launch() {
        launched = true;
    }

    public void stop() {
        stopped = true;
    }

    public void stall() {
        stalled = true;
    }

    public void timeout() {
        timedOut = true;
    }

    public void markNeverLaunched() {
        neverLaunched = true;
    }

    public boolean stopped() {
        return stopped;
    }

    public boolean stalled() {
        return stalled;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public boolean launched() {
        return launched;
    }

    public boolean neverLaunched() {
        return neverLaunched;
    }

    public long cursor() {
        return cursor;
    }

    public void cursor(long cursor) {
        this.cursor = cursor;
    }

    public long emitFrom() {
        return emitFrom;
    }

    public int announcedRefs() {
        return announcedRefs;
    }

    public void announcedRefs(int announcedRefs) {
        this.announcedRefs = announcedRefs;
    }

    public Set<String> announcedApprovals() {
        return announcedApprovals;
    }

    /** 流式用量帧的「上次 transcript 条数 + 上次发包墙钟」；数组原地更新。 */
    public long[] usageStamp() {
        return usageStamp;
    }

    public long lastStateProbeAt() {
        return lastStateProbeAt;
    }

    public void lastStateProbeAt(long lastStateProbeAt) {
        this.lastStateProbeAt = lastStateProbeAt;
    }

    /** 连续无进展的终止只对「这一跑还没结算」生效；恰好答完的那一轮应按正常轮收。 */
    public static boolean shouldStopForStall(boolean stalled, boolean settled) {
        return stalled && !settled;
    }

    /** 整轮机器预算：达到上限即判尽；与循环退出条件同一判据。 */
    public static boolean budgetExhausted(long machineMs, long budgetMs) {
        return machineMs >= budgetMs;
    }

    /** 任务已结算后的复查钩子：drain、播报、放卡与任务读取仍由引擎提供。 */
    public interface SettledHooks {
        void pause(long millis);

        void drain();

        void emitRetrieved();

        Map<String, SessionEvent> pending();

        void announcePending(Map<String, SessionEvent> pending);

        AgentSession.Task latestTask(AgentSession.Task known);

        boolean mirrorHasPending();
    }

    public record SettledResult(boolean breakLoop, AgentSession.Task current) {
    }

    /** 主循环的外围依赖：所有 I/O 都从引擎注入，循环本身只负责顺序与状态。 */
    public interface Hooks {
        long now();

        long machineMs();

        void pause(long millis);

        long drain(long cursor, long emitFrom);

        boolean takePendingChanged();

        boolean takeTaskChanged();

        boolean takeMessageChanged();

        void emitLiveUsage(long[] usageStamp, boolean messageChanged);

        int emitRetrieved(int announcedRefs);

        boolean planSuperseded();

        void interrupt();

        void announcePending(Map<String, SessionEvent> pending);

        boolean stopRequested();

        void steerWrapUp();

        boolean takeWrapUp();

        boolean progressStalled();

        void warn(String text);

        void warnStall();

        boolean hasPending();

        Map<String, SessionEvent> pendingSnapshot();

        Map<String, SessionEvent> pending();

        boolean answerTimedOutAsk(Map<String, SessionEvent> pending);

        void beginHumanWait(Map<String, SessionEvent> pending);

        void endHumanWait();

        AgentSession.Task latestTask(AgentSession.Task known);

        boolean activeRunsEmpty();
    }

    public record RunContext(AgentSession.Task current, long machineBudgetMs, long launchDeadline,
                             long stateRecheckMs, TurnInteractionCoordinator interactions) {
    }

    public record LoopResult(AgentSession.Task current) {
    }

    /**
     * 跟随循环骨架：顺序与原 {@code followTurn} 的 while 体一致；所有 drain、发帧、落卡、
     * 会话查询都通过 {@link Hooks} 回到引擎，状态与退出判定留在这里。
     */
    public LoopResult run(RunContext ctx, Hooks hooks) {
        AgentSession.Task current = ctx.current();
        while (!budgetExhausted(hooks.machineMs(), ctx.machineBudgetMs())) {
            if (!launched && current != null && !"queued".equals(current.status())) launch();
            cursor = hooks.drain(cursor, emitFrom);
            boolean pendingChanged = hooks.takePendingChanged();
            boolean taskChanged = hooks.takeTaskChanged();
            hooks.emitLiveUsage(usageStamp, hooks.takeMessageChanged());
            announcedRefs = hooks.emitRetrieved(announcedRefs);
            if (hooks.planSuperseded()) {
                hooks.interrupt();
                break;
            }
            if (pendingChanged) {
                hooks.announcePending(hooks.pendingSnapshot());
            }
            if (hooks.stopRequested()) {
                hooks.interrupt();
                stop();
                break;
            }
            if ((current == null || !current.settled()) && hooks.takeWrapUp()) {
                hooks.steerWrapUp();
            }
            // 判据是「这一跑还没结算」：原实现用 current == null || !current.settled()，
            // 取反传进 shouldStopForStall 会让空转永远不停，只能靠机器预算兜底。
            if (shouldStopForStall(hooks.progressStalled(), current != null && current.settled())) {
                hooks.interrupt();
                stall();
                hooks.warnStall();
                break;
            }
            if (!launched && current != null && hooks.now() > ctx.launchDeadline()
                    && !hooks.hasPending() && hooks.activeRunsEmpty()) {
                markNeverLaunched();
                break;
            }
            if (hooks.hasPending()) {
                Map<String, SessionEvent> waiting = hooks.pendingSnapshot();
                java.util.List<Map<String, Object>> pendingDatas = new java.util.ArrayList<>();
                for (SessionEvent e : waiting.values()) {
                    pendingDatas.add(e.data() == null ? Map.of() : e.data());
                }
                TurnInteractionCoordinator.Decision decision = ctx.interactions().tick(pendingDatas,
                        () -> hooks.beginHumanWait(waiting), hooks::endHumanWait);
                if (decision == TurnInteractionCoordinator.Decision.WAIT) {
                    hooks.pause(300);
                    continue;
                }
                if (decision == TurnInteractionCoordinator.Decision.ASK_TIMEOUT
                        && hooks.answerTimedOutAsk(waiting)) {
                    hooks.warn("提问等待超时，已按「未作答」继续（不会替你选答案）");
                    ctx.interactions().askTimeoutAccepted(hooks::endHumanWait);
                    continue;
                }
                if (decision == TurnInteractionCoordinator.Decision.ASK_TIMEOUT
                        || decision == TurnInteractionCoordinator.Decision.STOP_TIMEOUT) {
                    hooks.warn(TurnInteractionCoordinator.timeoutWarning(ctx.interactions().kind()));
                    timeout();
                    break;
                }
            }
            ctx.interactions().settle(hooks::endHumanWait);
            if (taskChanged || hooks.now() - lastStateProbeAt >= ctx.stateRecheckMs()) {
                lastStateProbeAt = hooks.now();
                AgentSession.Task latest = hooks.latestTask(current);
                if (latest != null) current = latest;
            }
            if (current == null || current.settled()) {
                SettledResult settled = afterSettled(new SettledHooks() {
                    @Override
                    public void pause(long millis) {
                        hooks.pause(millis);
                    }

                    @Override
                    public void drain() {
                        cursor = hooks.drain(cursor, emitFrom);
                    }

                    @Override
                    public void emitRetrieved() {
                        announcedRefs = hooks.emitRetrieved(announcedRefs);
                    }

                    @Override
                    public Map<String, SessionEvent> pending() {
                        return hooks.pending();
                    }

                    @Override
                    public void announcePending(Map<String, SessionEvent> pending) {
                        hooks.announcePending(pending);
                    }

                    @Override
                    public AgentSession.Task latestTask(AgentSession.Task known) {
                        return hooks.latestTask(known);
                    }

                    @Override
                    public boolean mirrorHasPending() {
                        return hooks.hasPending();
                    }
                }, current);
                current = settled.current();
                if (settled.breakLoop()) break;
                continue;
            }
            hooks.pause(200);
        }
        return new LoopResult(current);
    }

    /**
     * 任务判结算后的最后一拍：先让出一拍补读迟到待办；有卡就继续跟，有新任务就切过去；
     * 都没有再补读一次，镜像仍有卡则继续，否则退出。顺序与引擎原实现一致。
     */
    public SettledResult afterSettled(SettledHooks hooks, AgentSession.Task current) {
        hooks.pause(150);
        hooks.drain();
        hooks.emitRetrieved();
        Map<String, SessionEvent> latePending = hooks.pending();
        if (!latePending.isEmpty()) {
            hooks.announcePending(latePending);
            return new SettledResult(false, current);
        }
        AgentSession.Task newer = hooks.latestTask(current);
        if (newer != null && !newer.settled()) {
            return new SettledResult(false, newer);
        }
        if (newer != null) current = newer;
        // 迟到的待办：任务判「结算」与待办出现之间没有原子边界，只复查一次会漏。
        // 这一次改走增量 drain + 镜像；等下去也有预算封顶，不会无限挂着。
        hooks.drain();
        if (hooks.mirrorHasPending()) {
            hooks.pause(200);
            return new SettledResult(false, current);
        }
        return new SettledResult(true, current);
    }
}
