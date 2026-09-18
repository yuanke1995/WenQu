package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.models.KnowledgeBase;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 知识库运行配置的 Redis 缓存。
 *
 * <p>由参考实现的 knowledge/cache.py 逐函数翻译：键前缀与 TTL、单库读写、按库串行化锁。
 *
 * <p>两处必要替换（非个人取舍，已标注）：
 * <ul>
 *   <li>键前缀取本系统的缓存命名空间（参考实现使用其项目名作为前缀）。前缀标识的是本系统
 *       在共享 Redis 中的键空间，写成外部项目名会让两套系统的缓存互相误命中。
 *   <li>分布式锁用 Redis 原语实现（SET NX PX + 值比对删除），因为参考实现依赖的 Python
 *       Redis 客户端锁对象在 Java 客户端无对应实现；超时与等待时长沿用其常量。
 * </ul>
 */
@Component
public class KnowledgeBaseCache {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseCache.class);

    /** 键前缀（必要替换：本系统命名空间）。 */
    public static final String KNOWLEDGE_BASE_CACHE_KEY_PREFIX = "wenqu:knowledge_base:";

    /** 缓存有效期（秒）。 */
    public static final long KNOWLEDGE_BASE_CACHE_TTL_SECONDS = 3600;

    /** 锁超时（秒）。 */
    public static final long KNOWLEDGE_BASE_CACHE_LOCK_TIMEOUT_SECONDS = 30;

    /** 锁等待（秒）。 */
    public static final long KNOWLEDGE_BASE_CACHE_LOCK_WAIT_SECONDS = 10;

    /** 锁轮询间隔（毫秒）。 */
    private static final long LOCK_POLL_INTERVAL_MS = 50;

    private final StringRedisTemplate redis;

    public KnowledgeBaseCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String cacheKey(String kbId) {
        return KNOWLEDGE_BASE_CACHE_KEY_PREFIX + kbId;
    }

    static String cacheLockKey(String kbId) {
        return cacheKey(kbId) + ":lock";
    }

    /**
     * 串行化单个知识库的缓存回填与持久化更新。
     *
     * <p>与参考实现的差异：其锁为异步上下文管理器且自动续期；本实现在锁超时内完成操作，
     * 超时未获取到锁时同样继续执行（与参考实现"等待超时后仍进入临界区"的行为一致）。
     */
    public AutoCloseable lock(String kbId) {
        String key = cacheLockKey(kbId);
        String token = java.util.UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + KNOWLEDGE_BASE_CACHE_LOCK_WAIT_SECONDS * 1000;
        boolean acquired = false;
        while (System.currentTimeMillis() < deadline) {
            Boolean ok =
                    redis.opsForValue()
                            .setIfAbsent(
                                    key,
                                    token,
                                    Duration.ofSeconds(KNOWLEDGE_BASE_CACHE_LOCK_TIMEOUT_SECONDS));
            if (Boolean.TRUE.equals(ok)) {
                acquired = true;
                break;
            }
            try {
                Thread.sleep(LOCK_POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!acquired) {
            log.warn("知识库缓存锁等待超时（继续执行）: kb_id={}", kbId);
        }
        final boolean held = acquired;
        return () -> {
            if (!held) {
                return;
            }
            // 仅删除自己持有的锁，避免误删其他实例的锁
            String current = redis.opsForValue().get(key);
            if (token.equals(current)) {
                redis.delete(key);
            }
        };
    }

    /** 将知识库记录转换为最小运行配置快照。 */
    public static JSONObject serializeKbConfig(KnowledgeBase row) {
        JSONObject additionalParams = parseObject(row.getAdditionalParams());
        additionalParams.remove("stats");
        JSONObject snapshot = new JSONObject();
        snapshot.put("kb_id", row.getKbId());
        snapshot.put("kb_type", row.getKbType() == null || row.getKbType().isEmpty() ? "milvus" : row.getKbType());
        snapshot.put("embedding_model_spec", row.getEmbeddingModelSpec());
        snapshot.put("query_params", row.getQueryParams());
        snapshot.put("additional_params", additionalParams);
        return snapshot;
    }

    /**
     * 读取单个知识库缓存；Redis 不可用或缓存非法时返回未命中。
     *
     * <p>与参考实现一致：解析失败或快照中的 kb_id 不匹配均视为未命中并回源。
     */
    public JSONObject getCachedKbConfig(String kbId) {
        try {
            String raw = redis.opsForValue().get(cacheKey(kbId));
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            JSONObject snapshot = JSON.parseObject(raw);
            if (snapshot == null || !kbId.equals(snapshot.getString("kb_id"))) {
                log.warn("Invalid knowledge base cache snapshot: kb_id={}", kbId);
                return null;
            }
            return snapshot;
        } catch (Exception exc) {
            log.warn("Failed to read knowledge base cache: kb_id={}: {}", kbId, exc.getMessage());
            return null;
        }
    }

    /** 写入单个知识库运行时快照；失败时由读取路径回源数据库。 */
    public void cacheKbConfig(KnowledgeBase row) {
        if (row == null || row.getKbId() == null) {
            return;
        }
        try {
            JSONObject snapshot = serializeKbConfig(row);
            redis.opsForValue()
                    .set(
                            cacheKey(row.getKbId()),
                            JSON.toJSONString(snapshot),
                            Duration.ofSeconds(KNOWLEDGE_BASE_CACHE_TTL_SECONDS));
        } catch (Exception exc) {
            log.warn("Failed to write knowledge base cache: kb_id={}: {}", row.getKbId(), exc.getMessage());
        }
    }

    /** 删除单个知识库运行时快照，失败时由调用方中止写操作。 */
    public void deleteCachedKbConfig(String kbId) {
        redis.delete(cacheKey(kbId));
    }

    private static JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSON.parseObject(json);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
