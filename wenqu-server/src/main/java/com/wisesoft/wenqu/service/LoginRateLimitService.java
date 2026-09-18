package com.wisesoft.wenqu.service;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

/**
 * 登录失败的 IP 级限速。
 *
 * <p>由参考实现的 services/login_rate_limit_service.py 逐函数翻译：账号级锁定
 * （User.login_failed_count）只按账号累计失败，攻击者知道用户名后可以反复用错误密码
 * 把该账号持续锁死。本模块在 Redis 中按「IP+账号」与「IP 全局」两个维度做滑动窗口
 * 失败计数，与账号级锁定叠加；计数跨 worker、跨重启生效。middleware 中的内存级
 * 每 IP 尝试节流保留作为快速防线。
 *
 * <p>信任前提：客户端 IP 优先取 X-Forwarded-For 首段（与访问日志一致），生产
 * 部署需由反向代理覆盖/剥离客户端自带的 XFF，否则攻击者可伪造 XFF 绕过限速。
 *
 * <p>必要替换：
 * <ul>
 *   <li>异步 Redis 客户端 → {@link StringRedisTemplate} 的 ZSet 操作（同一组命令）。
 *   <li>缓存键前缀取本系统命名空间（前缀标识本系统在共享 Redis 中的键空间）。
 *   <li>{@code request.client.host} → {@link HttpServletRequest#getRemoteAddr()}。
 * </ul>
 */
@Service
public class LoginRateLimitService {

    /** 滑动窗口与阈值：单 IP+账号组合 10 次失败、单 IP 全局 30 次失败（10 分钟内）。 */
    public static final int LOGIN_FAILURE_WINDOW_SECONDS = 600;
    /** 组合阈值高于账号锁定阈值（5 次），保证正常用户先触发账号锁定提示而非 IP 限速。 */
    public static final int LOGIN_FAILURE_IP_ACCOUNT_MAX = 10;
    public static final int LOGIN_FAILURE_IP_MAX = 30;

    private static final String IP_KEY_PREFIX = "wenqu:login-failure:ip:";
    private static final String IP_ACCOUNT_KEY_PREFIX = "wenqu:login-failure:ipacct:";

    private final StringRedisTemplate redis;

    public LoginRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String ipKey(String ip) {
        return IP_KEY_PREFIX + ip;
    }

    private static String ipAccountKey(String ip, String identifier) {
        return IP_ACCOUNT_KEY_PREFIX + sha1Hex(ip + "|" + identifier);
    }

    /** 提取客户端 IP，与访问日志一致优先信任 X-Forwarded-For 首段。 */
    public static String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("x-forwarded-for");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].strip();
        }
        if (request.getRemoteAddr() != null) {
            return request.getRemoteAddr();
        }
        return "unknown";
    }

    /** 限速检查结果：是否放行与需等待的秒数。 */
    public record RateLimitResult(boolean allowed, int retryAfterSeconds) {}

    /** 检查登录是否被限速；被限速时 retryAfter 为需要等待的秒数。 */
    public RateLimitResult checkLoginRateLimit(String ip, String identifier) {
        double now = System.currentTimeMillis() / 1000.0;
        int window = LOGIN_FAILURE_WINDOW_SECONDS;
        record Limit(String key, int maxFailures) {}
        List<Limit> limits = List.of(
                new Limit(ipAccountKey(ip, identifier), LOGIN_FAILURE_IP_ACCOUNT_MAX),
                new Limit(ipKey(ip), LOGIN_FAILURE_IP_MAX));
        for (Limit limit : limits) {
            redis.opsForZSet().removeRangeByScore(limit.key(), 0, now - window);
            Long count = redis.opsForZSet().zCard(limit.key());
            if (count == null || count < limit.maxFailures()) {
                continue;
            }
            Set<ZSetOperations.TypedTuple<String>> oldest =
                    redis.opsForZSet().rangeWithScores(limit.key(), 0, 0);
            if (oldest == null || oldest.isEmpty()) {
                continue;
            }
            Double oldestScore = oldest.iterator().next().getScore();
            int retryAfter = (int) (oldestScore + window - now) + 1;
            return new RateLimitResult(false, Math.max(1, Math.min(window, retryAfter)));
        }
        return new RateLimitResult(true, 0);
    }

    /** 记录一次登录失败到两个维度的滑动窗口。 */
    public void recordLoginFailure(String ip, String identifier) {
        double now = System.currentTimeMillis() / 1000.0;
        int window = LOGIN_FAILURE_WINDOW_SECONDS;
        String member = now + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        for (String key : Set.of(ipAccountKey(ip, identifier), ipKey(ip))) {
            redis.opsForZSet().removeRangeByScore(key, 0, now - window);
            redis.opsForZSet().add(key, member, now);
            redis.expire(key, Duration.ofSeconds(window));
        }
    }

    /** 登录成功后清除 IP+账号维度的失败计数；IP 维度保留其他账号的记录。 */
    public void clearLoginFailures(String ip, String identifier) {
        redis.delete(ipAccountKey(ip, identifier));
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 不可用", exception);
        }
    }
}
