package com.wenqu.ai.agentx;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 跨轮瞬时状态的 Redis 落点（单副本可恢复语义）。
 *
 * <p>这些状态原来在 {@code FrameworkSessions} 的进程内 Map/Set 里：进程一重启，计划卡终态、
 * 「退回重出」意见、下一次强制压缩指令全丢。它们不需要跨副本协调，但必须活过重启——
 * 放 Redis + TTL，读时原样取回。
 *
 * <p>失败语义：Redis 不可用时直接抛出（框架会话状态本来就以 Redis 为硬依赖），
 * 不做内存兜底——多一份事实源只会让「重启后状态不一致」更难查。
 */
@Component
public class TransientSessionState {

    private static final Duration TTL = Duration.ofHours(2);
    private static final String PLAN_OUTCOME = "wenqu:plan:outcome:";
    private static final String PLAN_REVISING = "wenqu:plan:revising:";
    private static final String PLAN_FEEDBACK = "wenqu:plan:feedback:";
    private static final String COMPACT_REQUEST = "wenqu:compact:request:";
    private static final String COMPACT_REBUILD = "wenqu:compact:rebuild:";

    private final StringRedisTemplate redis;

    public TransientSessionState(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void putPlanOutcome(String sessionKey, String outcome) {
        if (sessionKey != null) set(PLAN_OUTCOME + sessionKey, outcome == null ? "reject" : outcome);
    }

    public String peekPlanOutcome(String sessionKey) {
        return sessionKey == null ? null : get(PLAN_OUTCOME + sessionKey);
    }

    /** 取走计划裁决并清掉同簇的退回状态（一次裁决只对应一次定格）。 */
    public String takePlanOutcome(String sessionKey) {
        if (sessionKey == null) return null;
        clearPlanRevision(sessionKey);
        return getAndDelete(PLAN_OUTCOME + sessionKey);
    }

    public void planRevising(String sessionKey, boolean revising, String feedback) {
        if (sessionKey == null) return;
        if (revising) {
            set(PLAN_REVISING + sessionKey, "1");
            if (feedback != null && !feedback.isBlank()) set(PLAN_FEEDBACK + sessionKey, feedback);
        } else {
            redis.delete(PLAN_REVISING + sessionKey);
        }
    }

    public boolean isPlanRevising(String sessionKey) {
        return sessionKey != null && get(PLAN_REVISING + sessionKey) != null;
    }

    public String peekPlanFeedback(String sessionKey) {
        return sessionKey == null ? null : get(PLAN_FEEDBACK + sessionKey);
    }

    public void clearPlanRevision(String sessionKey) {
        if (sessionKey != null) redis.delete(List.of(PLAN_REVISING + sessionKey, PLAN_FEEDBACK + sessionKey));
    }

    public void putCompactRequest(String sessionKey, String instruction) {
        if (sessionKey != null) set(COMPACT_REQUEST + sessionKey, instruction == null ? "" : instruction.trim());
    }

    public String takeCompactRequest(String sessionKey) {
        return sessionKey == null ? null : getAndDelete(COMPACT_REQUEST + sessionKey);
    }

    public void markRebuildAfterCompact(String sessionKey) {
        if (sessionKey != null) set(COMPACT_REBUILD + sessionKey, "1");
    }

    public boolean consumeRebuildAfterCompact(String sessionKey) {
        return sessionKey != null && getAndDelete(COMPACT_REBUILD + sessionKey) != null;
    }

    /** 会话销毁/清理：这一簇键一起删。 */
    public void clear(String sessionKey) {
        if (sessionKey == null) return;
        redis.delete(List.of(
                PLAN_OUTCOME + sessionKey, PLAN_REVISING + sessionKey, PLAN_FEEDBACK + sessionKey,
                COMPACT_REQUEST + sessionKey, COMPACT_REBUILD + sessionKey));
    }

    private void set(String key, String value) {
        redis.opsForValue().set(key, value, TTL);
    }

    private String get(String key) {
        return redis.opsForValue().get(key);
    }

    private String getAndDelete(String key) {
        return redis.opsForValue().getAndDelete(key);
    }
}
