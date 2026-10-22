package com.wenqu.ai.agent.goal;

import com.wenqu.ai.service.ConfigService;
import org.springframework.stereotype.Component;

/**
 * 目标模式的运营参数（唯一出口）。
 *
 * <p>为什么单独一个类：这些值此前散落在 {@code GoalService} / {@code GoalScheduler} /
 * {@code GoalVerifier} 的调用点，改一个默认值要全仓搜；而且都没进 {@code config-schema.json}，
 * 设置页看不到。这里集中默认值、范围钳制与语义命名，配置键统一以 {@code chat.goal.} 开头。
 *
 * <p>刻意<b>不</b>收进来的：状态/阶段字符串、completion marker、Verifier 版本号、
 * 与数据库列宽对齐的截断长度、Continuation 的 prompt 预算（24 条/400 字）。
 * 那些是契约或 prompt 上限，不是运营旋钮。
 */
@Component
public class GoalSettings {

    /** 轮次预算默认值（设置页可改）。 */
    public static final int DEFAULT_MAX_ROUNDS = 40;

    private final ConfigService config;

    public GoalSettings(ConfigService config) {
        this.config = config;
    }

    // ==================== 推进与轮次 ====================

    public int maxRounds() {
        return clamp(config.getInt("chat.goal.maxRounds", DEFAULT_MAX_ROUNDS), 1, 500);
    }

    /** 一轮收尾到下一轮开跑之间的间隔（毫秒）。 */
    public long nextRoundDelayMs() {
        return clamp(config.getLong("chat.goal.nextRoundDelayMs", 2_000L), 100L, 120_000L);
    }

    /** 上面的秒级形式：plusSeconds 用；向上取整，避免 1500ms 被截成 1s。 */
    public int nextRoundDelaySeconds() {
        long ms = nextRoundDelayMs();
        return (int) Math.max(1L, (ms + 999L) / 1000L);
    }

    /** 完成检查轮最多跑几次。 */
    public int maxCompletionChecks() {
        return clamp(config.getInt("chat.goal.maxCompletionChecks", 2), 1, 10);
    }

    /** 连续完成申请未过 Verifier 的上限。 */
    public int maxVerifyFailures() {
        return clamp(config.getInt("chat.goal.maxVerifyFailures", 5), 1, 50);
    }

    /** 进程崩溃/租约过期后最多恢复几次。 */
    public int maxRecoveries() {
        return clamp(config.getInt("chat.goal.maxRecoveries", 3), 0, 20);
    }

    // ==================== 预算与恢复 ====================

    public int maxTokens() {
        return clamp(config.getInt("chat.goal.maxTokens", 2_000_000), 1_000, 100_000_000);
    }

    public long maxMs() {
        return clamp(config.getLong("chat.goal.maxMs", 7_200_000L), 1_000L, 86_400_000L);
    }

    public int maxToolCalls() {
        return clamp(config.getInt("chat.goal.maxToolCalls", 400), 1, 100_000);
    }

    public int maxRetries() {
        return clamp(config.getInt("chat.goal.maxRetries", 2), 0, 20);
    }

    /** 瞬时错误退避：base * 2^attempt，封顶 max。attempt 从 1 起。 */
    public long retryDelaySeconds(int attempt) {
        long base = clamp(config.getLong("chat.goal.retryBaseSeconds", 15L), 1L, 3_600L);
        long max = clamp(config.getLong("chat.goal.retryMaxSeconds", 120L), base, 86_400L);
        long delay = base * (1L << Math.min(4, Math.max(0, attempt)));
        return Math.min(max, delay);
    }

    /** 预算用到多少比例开始在 prompt 里要求收敛。 */
    public double softBudgetRatio() {
        return clamp(config.getDouble("chat.goal.softBudgetRatio", 0.8), 0.1, 1.0);
    }

    // ==================== 调度 ====================

    public int claimBatch() {
        return clamp(config.getInt("chat.goal.claimBatch", 5), 1, 100);
    }

    public int workers() {
        return clamp(config.getInt("chat.goal.workers", 4), 1, 64);
    }

    public long initialTickDelayMs() {
        return clamp(config.getLong("chat.goal.initialTickDelayMs", 1_000L), 0L, 60_000L);
    }

    public long tickMs() {
        return clamp(config.getLong("chat.goal.tickMs", 2_000L), 500L, 60_000L);
    }

    public int leaseSeconds() {
        return clamp(config.getInt("chat.goal.leaseSeconds", 300), 60, 3_600);
    }

    public int heartbeatStaleSeconds() {
        return clamp(config.getInt("chat.goal.heartbeatStaleSeconds", 90), 30, 600);
    }

    /** 队列排太久/心跳停了多久后界面显示「待恢复」。 */
    public int staleGraceSeconds() {
        return clamp(config.getInt("chat.goal.staleGraceSeconds", 90), 10, 3_600);
    }

    // ==================== 核验 ====================

    /** file 证据是否要求宿主侧真实存在（默认 false：沙盒文件在容器里，宿主查不到）。 */
    public boolean verifyLocalFiles() {
        return config.getBoolean("chat.goal.verifyLocalFiles", false);
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
