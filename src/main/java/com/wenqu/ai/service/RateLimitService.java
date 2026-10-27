package com.wenqu.ai.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.wenqu.ai.common.BizException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流。
 * <p>
 * - 主路径：Redis 固定窗口计数（INCR + 首次 EXPIRE，跨副本统一），维度由调用方给
 *   （chat=按用户/IP 限频，upload=按用户/IP 限频，配置 ratelimit.chatPerMinute 等）
 * - 桶键是「桶名 : 租户段 : 维度」（Phase 4）：同一出口 IP 后面可能站着两家客户，
 *   不分池就是一个租户的流量刷挂另一个租户的额度；匿名与未绑定线程不带租户段，理由见 {@code tenantSegment()}
 * - 匿名请求（未登录）落到 IP 维度，避免匿名共享池互相挤兑
 * - 超限抛 BizException(429)，全局异常处理器转为 HTTP 429
 * - <b>Redis 不可用不再放行</b>（Phase 0 P8）：降级到本进程 Bucket4j 令牌桶并照样拒绝超额。
 *   原先的 fail-open 等于「Redis 一抖，全平台限流同时消失」，正好是最大的 DoS 面；
 *   降级期由 Bucket4j 的 GCRA 令牌桶兜住量，跨副本合计会比配置值宽（每副本各限一份），但不会不限
 * - 孤儿键自愈：INCR 成功但 EXPIRE 失败的键（无 TTL）在下一次访问时补设过期，防止永久累积
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String PREFIX = "wenqu:ratelimit:";
    /** 窗口长度默认值（秒）：ratelimit.windowSeconds 可配 */
    private static final int WINDOW_SECONDS_DEFAULT = 60;
    /** 降级令牌桶按身份缓存，空闲 10 分钟回收（身份基数无上限，靠 Guava 的过期策略兜住内存） */
    private static final long LOCAL_BUCKET_IDLE_MINUTES = 10;
    /** 降级状态日志的节流（毫秒）——Redis 长时间不可用时不要每请求刷一行 */
    private static final long DEGRADED_LOG_INTERVAL_MS = 60_000;

    /** 限流窗口（秒），设置页可配 */
    private int windowSeconds() {
        return Math.max(1, configService.getInt("ratelimit.windowSeconds", WINDOW_SECONDS_DEFAULT));
    }

    private final StringRedisTemplate redisTemplate;
    private final ConfigService configService;

    private final Cache<String, Bucket> degradedBuckets = CacheBuilder.newBuilder()
            .expireAfterAccess(LOCAL_BUCKET_IDLE_MINUTES, TimeUnit.MINUTES)
            .maximumSize(20000)
            .build();
    private final java.util.concurrent.atomic.AtomicLong lastDegradedLog = new java.util.concurrent.atomic.AtomicLong();
    /** Redis 是否可用（运维可见性：不可用时限流按单副本令牌桶降级） */
    private volatile boolean redisAvailable = true;

    /**
     * 校验限流，超限抛 BizException(429)
     *
     * @param bucket   限流桶名（chat / upload，对应配置 ratelimit.{bucket}PerMinute）
     * @param identity 限流维度标识（user:xxx / ip:x.x.x.x）
     */
    public void checkRateLimit(String bucket, String identity) {
        boolean enabled = configService.getBoolean("ratelimit.enabled");
        int limit = configService.getInt("ratelimit." + bucket + "PerMinute", defaultLimit(bucket));
        log.debug("[RateLimit] check bucket={} identity={} enabled={} limit={}", bucket, identity, enabled, limit);
        if (!enabled) {
            return;
        }
        if (limit <= 0) {
            return; // 0 或负数 = 该桶不限流
        }
        int window = windowSeconds();
        String pool = bucket + ":" + tenantSegment() + identity;
        String key = PREFIX + pool;
        long count;
        try {
            Long c = redisTemplate.opsForValue().increment(key);
            if (c == null) {
                throw new IllegalStateException("Redis 返回空计数");
            }
            count = c;
            if (count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(window));
            } else {
                // 孤儿键自愈：INCR 成功但 EXPIRE 失败的键无 TTL，补设防永久累积
                Long ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl < 0) {
                    redisTemplate.expire(key, Duration.ofSeconds(window));
                }
            }
            markRedisUp();
        } catch (Exception e) {
            markRedisDown(e);
            // fail-closed：降级到本进程令牌桶，超额照样拒
            long waitSeconds = degradedBucket(pool, limit, window).tryConsume(1) ? 0 : refillSeconds(limit, window);
            if (waitSeconds > 0) {
                log.info("[RateLimit] 降级期触发限流 bucket={} identity={}（Redis 不可用，按本进程令牌桶拒绝）",
                        bucket, identity);
                throw new BizException(429, "请求过于频繁，请 " + waitSeconds + " 秒后重试");
            }
            return;
        }
        if (count > limit) {
            long ttl;
            try {
                Long t = redisTemplate.getExpire(key);
                ttl = (t != null && t > 0) ? t : window;
            } catch (Exception e) {
                ttl = window;
            }
            log.info("[RateLimit] 触发限流 bucket={} identity={} count={}/{}", bucket, identity, count, limit);
            throw new BizException(429, "请求过于频繁，请 " + ttl + " 秒后重试");
        }
    }

    /** Redis 当前是否可用（不可用时限流按单副本令牌桶降级） */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    /**
     * 租户分池段（Phase 4）：同一个办公网出口 IP 可以同时站着两家客户，不分池就是一家的流量能刷挂另一家的额度。
     * <p>匿名与未绑定线程<b>不带</b>这一段——它们落共享桶，而键名里少一段也正是「这条线程没绑租户」的可见痕迹，
     * 比悄悄并进 default 池好查（与 {@code TenantContext.current()} 不兜底的口径一致）。
     */
    private static String tenantSegment() {
        String t = com.wenqu.ai.util.TenantContext.current();
        return t == null ? "" : "t:" + t + ":";
    }

    /** 降级令牌桶：容量＝窗口配额，按 limit/window 匀速回填（Bucket4j 的 greedy 语义）；key 就是 Redis 那条池键，两路口径要同池 */
    private Bucket degradedBucket(String key, int limit, int windowSeconds) {
        try {
            return degradedBuckets.get(key, () -> Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(limit)
                            .refillGreedy(limit, Duration.ofSeconds(windowSeconds))
                            .build())
                    .build());
        } catch (Exception e) {
            // 建桶本身不该失败，真失败时按「拒」处理比放行安全
            throw new BizException(429, "服务繁忙，请稍后重试");
        }
    }

    /** 距下次可用令牌还要多久（给用户一个可等的数，而不是干巴巴一句「稍后」） */
    private long refillSeconds(int limit, int windowSeconds) {
        return Math.max(1, Math.round((double) windowSeconds / Math.max(1, limit)));
    }

    private void markRedisDown(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastDegradedLog.get();
        if ((redisAvailable || now - last >= DEGRADED_LOG_INTERVAL_MS) && lastDegradedLog.compareAndSet(last, now)) {
            redisAvailable = false;
            log.error("[RateLimit] Redis 不可用，限流降级为本进程令牌桶（超额仍拒绝，跨副本合计会比配置值宽）: {}",
                    e.getMessage());
        }
    }

    private void markRedisUp() {
        if (!redisAvailable) {
            redisAvailable = true;
            degradedBuckets.invalidateAll();
            log.info("[RateLimit] Redis 已恢复，限流回到跨副本统一窗口");
        }
    }

    private int defaultLimit(String bucket) {
        return switch (bucket) {
            case "chat" -> 10;
            case "upload" -> 10;
            // 免登录的公开写入端点：不能沿用「未知桶 30/分钟」的通用兜底——那是给登录用户用的量级，
            // 对一个没有登录态、谁都能打的入口来说太宽
            case "apply" -> 5;
            default -> 30;
        };
    }
}
