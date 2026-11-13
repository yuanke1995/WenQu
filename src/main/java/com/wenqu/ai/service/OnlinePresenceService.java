package com.wenqu.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线会话登记（Redis）：令牌无状态，但「最近一次请求」要有个共享落点，
 * 否则「谁在线」只能拿最近登录时间冒充。
 *
 * <p>口径（与产品确认）：
 * <ul>
 *   <li>一次带令牌的请求 = 一次「在活动」；<b>24 小时没有任何请求即离线</b>；</li>
 *   <li>同一个 24 小时窗口同时是令牌的<b>可滑动有效期</b>——请求不断就一直是有效登录态，
 *       闲置满 24 小时下一次请求直接 401，必须重新登录；</li>
 *   <li>会话按「账号 + 会话号」落键（jti；存量令牌按令牌指纹兜底），所以退出登录只回收当前设备，
 *       不影响同账号其他设备。</li>
 * </ul>
 *
 * <p>键形态：
 * <ul>
 *   <li>{@code wenqu:presence:session:{uid}:{sid}} —— 会话存活标记，TTL 24h，每次请求续期；</li>
 *   <li>{@code wenqu:presence:index} / {@code wenqu:presence:index:{tenant}} ——
 *       uid → 最后活跃毫秒数的 ZSET，供「在线人数」和「最后活跃」列查询；按租户存一份，
 *       租户管理员只看到本租户，平台管理员看全局。</li>
 * </ul>
 *
 * <p>失败语义：Redis 不可用时<b>不阻断鉴权</b>（按 JWT 原语义放行），在线数据降级为「暂不可用」——
 * 不能让缓存故障把所有人挡在门外。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class OnlinePresenceService {

    /** 在线窗口 = 会话滑动有效期：最近一次请求距今超过它即离线 */
    public static final Duration WINDOW = Duration.ofHours(24);

    /** 索引键比窗口多留几天，避免没人访问时残留；真正的淘汰按分数做 */
    private static final Duration INDEX_TTL = Duration.ofDays(3);

    /** 在线索引写节流：同一账号每分钟最多写一次分数（「最后活跃」误差 ≤1 分钟，Redis 写入可忽略） */
    private static final long INDEX_WRITE_MS = 60_000L;

    /** 会话存活检查的进程内缓存：30 秒内同一会话不重复问 Redis */
    private static final long CHECK_CACHE_MS = 30_000L;

    private static final String SESSION_PREFIX = "wenqu:presence:session:";
    private static final String INDEX_ALL = "wenqu:presence:index";
    private static final String INDEX_TENANT_PREFIX = "wenqu:presence:index:";

    private final StringRedisTemplate redis;

    /** uid|sid → 上次做存活检查的时刻 */
    private final Map<String, Long> checkedAt = new ConcurrentHashMap<>();
    /** uid → 上次写在线索引的时刻 */
    private final Map<String, Long> indexedAt = new ConcurrentHashMap<>();
    private final AtomicBoolean redisWarned = new AtomicBoolean();

    public OnlinePresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 一次带令牌的请求：确认会话仍在 24h 窗口内，并把「最后活跃」记到在线索引。
     *
     * @param tenantId 账号归属租户（可空，空则不写租户索引）
     * @param issuedAtSeconds 令牌 iat；窗口内缺失的会话键按 iat 判断是否还能自愈重建
     * @return false = 会话已闲置满 24 小时，调用方应拒绝该令牌
     */
    public boolean touch(String tenantId, String uid, String sid, long issuedAtSeconds) {
        if (uid == null || uid.isBlank()) return true;
        long now = System.currentTimeMillis();
        String checkKey = uid + '|' + sid;
        Long last = checkedAt.get(checkKey);
        if (last == null || now - last >= CHECK_CACHE_MS) {
            if (!refreshSession(uid, sid, issuedAtSeconds, now)) return false;
            checkedAt.put(checkKey, now);
            pruneChecks(now);
        }
        markIndex(tenantId, uid, now);
        return true;
    }

    /** 退出登录：只回收当前这条会话（其他设备不受影响），并从在线索引摘掉（其他设备下一次请求会补回来） */
    public void offline(String uid, String sid, String tenantId) {
        if (uid == null || uid.isBlank()) return;
        try {
            if (sid != null && !sid.isBlank()) redis.delete(sessionKey(uid, sid));
            removeFromIndex(uid, tenantId);
        } catch (Exception e) {
            warnOnce(e);
        }
        checkedAt.remove(uid + '|' + sid);
        indexedAt.remove(uid);
    }

    /** 全端失效（强制下线 / 改密 / 重置密码）：会话代数已 +1，索引这里同步摘掉，立刻显示离线 */
    public void offlineAll(String uid, String tenantId) {
        if (uid == null || uid.isBlank()) return;
        try {
            removeFromIndex(uid, tenantId);
        } catch (Exception e) {
            warnOnce(e);
        }
        indexedAt.remove(uid);
        String prefix = uid + '|';
        checkedAt.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * 在线快照：在线人数 + 指定 uid 的最后活跃时刻。
     *
     * @param platform true=平台视图（全局索引）；否则按租户索引
     * @param uids 当页成员；为空只算人数
     * @return {@code {windowHours, count, users:{uid: ISO 时间}}}；读取失败时 count=null、users 为空并带 warning
     */
    public Map<String, Object> snapshot(boolean platform, String tenantId, List<String> uids) {
        String index = (platform || tenantId == null || tenantId.isBlank())
                ? INDEX_ALL : INDEX_TENANT_PREFIX + tenantId;
        long now = System.currentTimeMillis();
        double cutoff = now - WINDOW.toMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowHours", WINDOW.toHours());
        try {
            // 顺手淘汰窗口外的成员：在线列表不靠定时任务，读的时候收一次就够
            redis.opsForZSet().removeRangeByScore(index, 0, cutoff);
            Long count = redis.opsForZSet().count(index, cutoff, now + 60_000);
            out.put("count", count == null ? 0L : count);
            Map<String, String> users = new LinkedHashMap<>();
            if (uids != null) {
                for (String uid : uids) {
                    if (uid == null || uid.isBlank()) continue;
                    Double score = redis.opsForZSet().score(index, uid);
                    if (score != null && score >= cutoff) users.put(uid, isoTime(score.longValue()));
                }
            }
            out.put("users", users);
        } catch (Exception e) {
            warnOnce(e);
            out.put("count", null);
            out.put("users", Map.of());
            out.put("warning", "在线数据暂不可用（Redis 读取失败）");
        }
        return out;
    }

    /**
     * 会话号：令牌的 jti；存量令牌（签发时还没 jti）用令牌指纹兜底——
     * 同一枚令牌稳定映射到同一条会话，退出登录照样只回收它自己。
     */
    public static String sessionId(Map<String, Object> claims, String token) {
        Object jti = claims == null ? null : claims.get("jti");
        if (jti != null && !String.valueOf(jti).isBlank()) return String.valueOf(jti);
        return fingerprint(token);
    }

    // ==================== 内部 ====================

    private boolean refreshSession(String uid, String sid, long issuedAtSeconds, long now) {
        String key = sessionKey(uid, sid);
        try {
            // EXPIRE 返回 0 说明键已不在：要么闲置满 24 小时，要么是还没登记过的新令牌
            if (Boolean.TRUE.equals(redis.expire(key, WINDOW))) return true;
            // 新令牌 / 部署前签发的老令牌：iat 在窗口内就自愈重建，超过窗口一律按过期处理
            long ageMs = issuedAtSeconds <= 0 ? Long.MAX_VALUE : now - issuedAtSeconds * 1000L;
            if (ageMs > WINDOW.toMillis()) return false;
            redis.opsForValue().set(key, String.valueOf(now), WINDOW);
            return true;
        } catch (Exception e) {
            warnOnce(e);
            return true;
        }
    }

    private void markIndex(String tenantId, String uid, long now) {
        Long last = indexedAt.get(uid);
        if (last != null && now - last < INDEX_WRITE_MS) return;
        indexedAt.put(uid, now);
        pruneIndexed(now);
        try {
            redis.opsForZSet().add(INDEX_ALL, uid, now);
            redis.expire(INDEX_ALL, INDEX_TTL);
            if (tenantId != null && !tenantId.isBlank()) {
                String key = INDEX_TENANT_PREFIX + tenantId;
                redis.opsForZSet().add(key, uid, now);
                redis.expire(key, INDEX_TTL);
            }
        } catch (Exception e) {
            warnOnce(e);
        }
    }

    private void removeFromIndex(String uid, String tenantId) {
        redis.opsForZSet().remove(INDEX_ALL, uid);
        if (tenantId != null && !tenantId.isBlank()) {
            redis.opsForZSet().remove(INDEX_TENANT_PREFIX + tenantId, uid);
        }
    }

    private static String sessionKey(String uid, String sid) {
        return SESSION_PREFIX + uid + ':' + (sid == null || sid.isBlank() ? "legacy" : sid);
    }

    private static String fingerprint(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(token).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("t");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "legacy";
        }
    }

    private static String isoTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).toString();
    }

    private void pruneChecks(long now) {
        if (checkedAt.size() <= 20_000) return;
        checkedAt.entrySet().removeIf(e -> now - e.getValue() >= CHECK_CACHE_MS);
    }

    private void pruneIndexed(long now) {
        if (indexedAt.size() <= 20_000) return;
        indexedAt.entrySet().removeIf(e -> now - e.getValue() >= INDEX_WRITE_MS);
    }

    /** Redis 故障只提示一次，别让每个请求刷一行日志 */
    private void warnOnce(Exception e) {
        if (redisWarned.compareAndSet(false, true)) {
            log.warn("[PRESENCE] Redis 在线登记不可用，在线数据降级为不可见：{}", e.toString());
        }
    }
}
