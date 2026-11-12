package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.ReplicaCoordination;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 单副本运行时的轮锁存储：Redis 租约 + 启动清理遗留锁。
 *
 * <p>为什么 TTL 是 130 分钟：单轮上限 2 小时（7200s），锁必须活过最长一轮；
 * 进程崩溃/重启后不靠 TTL 等，启动时直接把上次进程遗留的锁清掉——这是单副本才成立的语义，
 * 多副本会被 {@code DeploymentGuard} 拒绝启动。
 */
@Slf4j
@Service
public class RedisTurnStateStore implements TurnStateStore {

    private static final String PREFIX = "wenqu:turn:in-flight:";
    private static final Duration TTL = Duration.ofMinutes(130);

    private final ReplicaCoordination coordination;
    private final StringRedisTemplate redis;
    private final String mode;

    public RedisTurnStateStore(ReplicaCoordination coordination,
                               StringRedisTemplate redis,
                               @Value("${wenqu.deployment.mode:single}") String mode) {
        this.coordination = coordination;
        this.redis = redis;
        this.mode = mode;
    }

    @PostConstruct
    void clearStaleOnStartup() {
        if (!"single".equalsIgnoreCase(mode == null ? "" : mode.trim())) return;
        try {
            Set<String> keys = redis.keys(PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.warn("[TURN] 单副本启动清理上次进程遗留轮锁 {} 个", keys.size());
            }
        } catch (Exception e) {
            throw new IllegalStateException("单副本启动清理遗留轮锁失败（Redis 不可用）", e);
        }
    }

    @Override
    public boolean tryAcquire(String sessionId) {
        return coordination.acquireOrThrow(PREFIX + sessionId, TTL);
    }

    @Override
    public void release(String sessionId) {
        coordination.release(PREFIX + sessionId);
    }
}
