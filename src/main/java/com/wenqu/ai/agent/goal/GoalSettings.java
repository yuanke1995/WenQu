package com.wenqu.ai.agent.goal;

import com.wenqu.ai.config.ConfigDefaults;
import org.springframework.stereotype.Component;

/**
 * 目标模式的运营参数（唯一出口）。
 *
 * <p>为什么单独一个类：这些值此前散落在 {@code GoalService} / {@code GoalScheduler} /
 * {@code GoalVerifier} 的调用点，改一个默认值要全仓搜。这里集中默认值、范围钳制与语义命名。
 *
 * <p>预算类参数已退役为代码常量（2026-11-14）：深度执行设置面板整体下线，
 * 自动推进轮数上限恒为 {@link #MAX_ROUNDS}=20；token/时长/工具调用三项硬预算
 * 整个机制随设置退役（目标行 budget_* 列已随 V15 迁移删除）。
 *
 * <p>刻意<b>不</b>收进来的：状态/阶段字符串、completion marker、Verifier 版本号、
 * 与数据库列宽对齐的截断长度、Continuation 的 prompt 预算（24 条/400 字）。
 * 那些是契约或 prompt 上限，不是运营旋钮。
 */
@Component
public class GoalSettings {

    /** 自动推进轮数上限：一个目标最多自动跑几轮（代码常量，不再可配）。 */
    public static final int MAX_ROUNDS = 20;

    /** 完成检查/核验失败两项的常量默认是 0=不限制，走 MAX_VALUE 哨兵表达。 */
    private static final int UNLIMITED = Integer.MAX_VALUE;

    // ==================== 推进与轮次 ====================

    /** 一轮收尾到下一轮开跑之间的间隔（毫秒）。 */
    public long nextRoundDelayMs() {
        return clamp(ConfigDefaults.GOAL_NEXT_ROUND_DELAY_MS, 100L, 120_000L);
    }

    /** 上面的秒级形式：plusSeconds 用；向上取整，避免 1500ms 被截成 1s。 */
    public int nextRoundDelaySeconds() {
        long ms = nextRoundDelayMs();
        return (int) Math.max(1L, (ms + 999L) / 1000L);
    }

    /** 完成检查轮最多跑几次；0=不限制（默认）。 */
    public int maxCompletionChecks() {
        int v = ConfigDefaults.GOAL_MAX_COMPLETION_CHECKS;
        return v <= 0 ? UNLIMITED : clamp(v, 1, 10);
    }

    /** 连续完成申请未过 Verifier 的上限；0=不限制（默认）。 */
    public int maxVerifyFailures() {
        int v = ConfigDefaults.GOAL_MAX_VERIFY_FAILURES;
        return v <= 0 ? UNLIMITED : clamp(v, 1, 50);
    }

    /** 进程崩溃/租约过期后最多恢复几次。 */
    public int maxRecoveries() {
        return clamp(ConfigDefaults.GOAL_MAX_RECOVERIES, 0, 20);
    }

    // ==================== 预算与恢复 ====================

    public int maxRetries() {
        return clamp(ConfigDefaults.GOAL_MAX_RETRIES, 0, 20);
    }

    /** 瞬时错误退避：base * 2^attempt，封顶 max。attempt 从 1 起。 */
    public long retryDelaySeconds(int attempt) {
        long base = clamp(ConfigDefaults.GOAL_RETRY_BASE_SECONDS, 1L, 3_600L);
        long max = clamp(ConfigDefaults.GOAL_RETRY_MAX_SECONDS, base, 86_400L);
        long delay = base * (1L << Math.min(4, Math.max(0, attempt)));
        return Math.min(max, delay);
    }

    /** 预算用到多少比例开始在 prompt 里要求收敛。 */
    public double softBudgetRatio() {
        return clamp(ConfigDefaults.GOAL_SOFT_BUDGET_RATIO, 0.1, 1.0);
    }

    // ==================== 调度 ====================

    public int claimBatch() {
        return clamp(ConfigDefaults.GOAL_CLAIM_BATCH, 1, 100);
    }

    public int workers() {
        return clamp(ConfigDefaults.GOAL_WORKERS, 1, 64);
    }

    public long initialTickDelayMs() {
        return clamp(ConfigDefaults.GOAL_INITIAL_TICK_DELAY_MS, 0L, 60_000L);
    }

    public long tickMs() {
        return clamp(ConfigDefaults.GOAL_TICK_MS, 500L, 60_000L);
    }

    public int leaseSeconds() {
        return clamp(ConfigDefaults.GOAL_LEASE_SECONDS, 60, 3_600);
    }

    public int heartbeatStaleSeconds() {
        return clamp(ConfigDefaults.GOAL_HEARTBEAT_STALE_SECONDS, 30, 600);
    }

    /** 队列排太久/心跳停了多久后界面显示「待恢复」。 */
    public int staleGraceSeconds() {
        return clamp(ConfigDefaults.GOAL_STALE_GRACE_SECONDS, 10, 3_600);
    }

    // ==================== 核验 ====================

    /** file 证据是否要求宿主侧真实存在（默认 false：沙盒文件在容器里，宿主查不到）。 */
    public boolean verifyLocalFiles() {
        return ConfigDefaults.GOAL_VERIFY_LOCAL_FILES;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
