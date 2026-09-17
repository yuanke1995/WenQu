package com.wisesoft.ai.service;

import com.wisesoft.ai.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 接口限流（Redis 固定窗口计数：INCR + 首次 EXPIRE）
 * <p>
 * - 维度：chat=按用户/IP 限频，upload=按用户/IP 限频（配置 ratelimit.chatPerMinute 等）
 * - 匿名请求（未登录）落到 IP 维度，避免匿名共享池互相挤兑
 * - 超限抛 BizException(429)，全局异常处理器转为 HTTP 429
 * - Redis 不可用时放行（限流是保护措施，不应比业务先挂）
 * - 孤儿键自愈：INCR 成功但 EXPIRE 失败的键（无 TTL）在下一次访问时补设过期，防止永久累积
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String PREFIX = "ai-doc:ratelimit:";
    /** 窗口长度默认值（秒）：ratelimit.windowSeconds 可配 */
    private static final int WINDOW_SECONDS_DEFAULT = 60;

    /** 限流窗口（秒），设置页可配 */
    private int windowSeconds() {
        return Math.max(1, configService.getInt("ratelimit.windowSeconds", WINDOW_SECONDS_DEFAULT));
    }

    private final StringRedisTemplate redisTemplate;
    private final ConfigService configService;

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
        String key = PREFIX + bucket + ":" + identity;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds()));
            } else {
                // 孤儿键自愈：INCR 成功但 EXPIRE 失败的键无 TTL，补设防永久累积
                Long ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl < 0) {
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds()));
                }
            }
        } catch (Exception e) {
            log.warn("[RateLimit] Redis 不可用，放行 (bucket={}): {}", bucket, e.getMessage());
            return;
        }
        if (count == null) {
            return;
        }
        if (count > limit) {
            long ttl;
            try {
                Long t = redisTemplate.getExpire(key);
                ttl = (t != null && t > 0) ? t : windowSeconds();
            } catch (Exception e) {
                ttl = windowSeconds();
            }
            log.info("[RateLimit] 触发限流 bucket={} identity={} count={}/{}", bucket, identity, count, limit);
            throw new BizException(429, "请求过于频繁，请 " + ttl + " 秒后重试");
        }
    }

    private int defaultLimit(String bucket) {
        return switch (bucket) {
            case "chat" -> 10;
            case "upload" -> 10;
            default -> 30;
        };
    }
}