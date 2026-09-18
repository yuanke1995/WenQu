package com.wisesoft.wenqu.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * API 接流量前的核心依赖就绪探针。
 *
 * <p>由参考实现的 services/readiness_service.py 逐函数翻译：PostgreSQL/Redis/worker 健康租约
 * 三个探针并发执行（各自超时）、启动组件事实合并、短缓存 + single-flight 的结构化接流量事实。
 *
 * <p>必要替换：
 * <ul>
 *   <li>{@code pg_manager.get_async_session_context + SELECT 1} → {@link JdbcTemplate} 查询
 *       {@code SELECT 1}（业务库连通性等价探针；本工程业务库为 MySQL）。
 *   <li>{@code asyncio.gather} → {@link CompletableFuture} 并发（守护线程池）；
 *       {@code asyncio.wait_for} → {@code future.get(timeout)}。
 *   <li>{@code asyncio.shield(task)} → 直接 join 已注册的 future（调用方中断不会取消
 *       single-flight 中的计算，shield 语义等价）。
 *   <li>缓存 key 中的 {@code id(_probe_postgres)} 等函数身份在进程内恒定，等价于常量，
 *       不再进入 key；其余 key 成分（startup_complete + 组件快照）照搬。
 * </ul>
 */
@Service
public class ReadinessService {

    public static final double READINESS_PROBE_TIMEOUT_SECONDS =
            Double.parseDouble(System.getenv().getOrDefault("READINESS_PROBE_TIMEOUT_SECONDS", "2"));
    public static final double READINESS_CACHE_TTL_SECONDS =
            Double.parseDouble(System.getenv().getOrDefault("READINESS_CACHE_TTL_SECONDS", "1"));

    /** 当前队列没有完成启动且仍在续租的兼容 worker。 */
    public static class WorkerUnavailableError extends RuntimeException {
        public WorkerUnavailableError(String message) {
            super(message);
        }
    }

    private record ProbeResult(String status, String code) {
        private static final ProbeResult OK = new ProbeResult("ok", null);

        private static ProbeResult error(String code) {
            return new ProbeResult("error", code);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            if (code != null) {
                map.put("code", code);
            }
            return map;
        }
    }

    /** 组件快照行（name, status, required, code），对应参考实现的四元组。 */
    private record ComponentRow(String name, String status, boolean required, String code)
            implements Comparable<ComponentRow> {

        @Override
        public int compareTo(ComponentRow other) {
            int byName = name.compareTo(other.name);
            if (byName != 0) {
                return byName;
            }
            int byStatus = status.compareTo(other.status);
            if (byStatus != 0) {
                return byStatus;
            }
            int byRequired = Boolean.compare(required, other.required);
            if (byRequired != 0) {
                return byRequired;
            }
            return code.compareTo(other.code);
        }
    }

    /** 缓存条目（key, 过期时刻, 结果）。 */
    private record CacheEntry(CacheKey key, long expiresAtNanos, Map<String, Object> result) {}

    /** 缓存 key：startup_complete + 组件快照（探针方法身份在进程内恒定）。 */
    private record CacheKey(boolean startupComplete, List<ComponentRow> componentSnapshot) {}

    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "wenqu-readiness-probe");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    private volatile CacheEntry readinessCache;
    private final ConcurrentHashMap<CacheKey, CompletableFuture<Map<String, Object>>> readinessInflight =
            new ConcurrentHashMap<>();

    public ReadinessService(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    private void probePostgres() {
        // 验证业务数据库连接能够执行查询
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    private void probeRedis() {
        // 验证 Run 队列 Redis 能够响应命令
        redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
    }

    private void probeWorker() {
        // 验证兼容 AgentRun worker 的短 TTL 健康事实仍然存在
        List<Object[]> leases = new ArrayList<>();
        leases.add(new Object[] {
            RunQueueService.WORKER_HEALTH_KEY, (long) RunQueueService.WORKER_HEALTH_MAX_TTL_MS
        });
        leases.add(new Object[] {
            RunQueueService.WORKER_RECONCILIATION_HEALTH_KEY,
            RunQueueService.WORKER_RECONCILIATION_HEALTH_TTL_SECONDS * 1000L
        });
        leases.add(new Object[] {
            TaskQueueService.TASK_RECONCILIATION_HEALTH_KEY,
            TaskQueueService.TASK_RECONCILIATION_HEALTH_TTL_SECONDS * 1000L
        });
        for (Object[] lease : leases) {
            String key = (String) lease[0];
            long maxTtlMs = (Long) lease[1];
            String value = redis.opsForValue().get(key);
            Long ttlMs = redis.getExpire(key, TimeUnit.MILLISECONDS);
            if (value == null || value.isEmpty() || ttlMs == null || ttlMs <= 0 || ttlMs > maxTtlMs) {
                throw new WorkerUnavailableError("worker health lease missing or invalid");
            }
        }
    }

    private ProbeResult runProbe(Probe probe) {
        try {
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(probe::run, PROBE_EXECUTOR);
            future.get((long) (READINESS_PROBE_TIMEOUT_SECONDS * 1000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            return ProbeResult.error("timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProbeResult.error("InterruptedException");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return ProbeResult.error(cause == null ? "Exception" : cause.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            return ProbeResult.error(exception.getClass().getSimpleName());
        }
        return ProbeResult.OK;
    }

    private interface Probe {
        void run();
    }

    /**
     * 把启动组件状态规整为缓存 key，不包含异常消息或其他敏感值。
     *
     * <p>参考实现 sorted(tuple)；此处排序后列表等价。
     */
    private static List<ComponentRow> componentSnapshot(Map<String, Map<String, Object>> components) {
        List<ComponentRow> rows = new ArrayList<>();
        if (components != null) {
            for (Map.Entry<String, Map<String, Object>> entry : components.entrySet()) {
                if (!(entry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> component = entry.getValue();
                rows.add(new ComponentRow(
                        String.valueOf(entry.getKey()),
                        String.valueOf(component.get("status") == null ? "unknown" : component.get("status")),
                        Boolean.TRUE.equals(component.get("required")),
                        String.valueOf(component.get("code") == null ? "" : component.get("code"))));
            }
        }
        rows.sort(ComponentRow::compareTo);
        return rows;
    }

    /** 执行一次真实探针并合并启动组件事实。 */
    private Map<String, Object> computeReadiness(boolean startupComplete, List<ComponentRow> componentSnapshot) {
        CompletableFuture<ProbeResult> postgres =
                CompletableFuture.supplyAsync(() -> runProbe(this::probePostgres), PROBE_EXECUTOR);
        CompletableFuture<ProbeResult> redisProbe =
                CompletableFuture.supplyAsync(() -> runProbe(this::probeRedis), PROBE_EXECUTOR);
        CompletableFuture<ProbeResult> worker =
                CompletableFuture.supplyAsync(() -> runProbe(this::probeWorker), PROBE_EXECUTOR);

        ProbeResult postgresResult;
        ProbeResult redisResult;
        ProbeResult workerResult;
        try {
            postgresResult = postgres.get();
            redisResult = redisProbe.get();
            workerResult = worker.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("readiness 探针被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("readiness 探针执行失败", exception);
        }

        Map<String, Map<String, Object>> components = new LinkedHashMap<>();
        for (ComponentRow row : componentSnapshot) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("status", row.status());
            component.put("required", row.required());
            if (!row.code().isEmpty()) {
                component.put("code", row.code());
            }
            components.put(row.name(), component);
        }
        Map<String, Map<String, Object>> checks = new LinkedHashMap<>();
        checks.put(
                "startup",
                startupComplete
                        ? checkMap("ok", null)
                        : checkMap("error", "not_complete"));
        checks.put("postgres", postgresResult.toMap());
        checks.put("redis", redisResult.toMap());
        checks.put("worker", workerResult.toMap());

        boolean requiredComponentsReady = true;
        for (Map<String, Object> component : components.values()) {
            if (Boolean.TRUE.equals(component.get("required"))
                    && !"ok".equals(component.get("status"))) {
                requiredComponentsReady = false;
            }
        }
        boolean ready = requiredComponentsReady;
        for (Map<String, Object> check : checks.values()) {
            if (!"ok".equals(check.get("status"))) {
                ready = false;
            }
        }
        boolean degraded = false;
        for (Map<String, Object> component : components.values()) {
            if (!Boolean.TRUE.equals(component.get("required")) && "error".equals(component.get("status"))) {
                degraded = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "not_ready");
        result.put("degraded", degraded);
        result.put("checks", checks);
        result.put("components", components);
        return result;
    }

    private static Map<String, Object> checkMap(String status, String code) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        if (code != null) {
            map.put("code", code);
        }
        return map;
    }

    /**
     * 返回带短缓存与 single-flight 的结构化接流量事实。
     *
     * <p>startupComponents 形如 {名称: {status, required, code?}}（参考实现
     * {@code startup_components} 字典）；返回值为深拷贝，调用方可安全修改。
     */
    public Map<String, Object> getReadiness(boolean startupComplete, Map<String, Map<String, Object>> startupComponents)
            throws InterruptedException {
        List<ComponentRow> componentSnapshot = componentSnapshot(startupComponents);
        CacheKey cacheKey = new CacheKey(startupComplete, componentSnapshot);
        long now = System.nanoTime();
        CacheEntry cached = readinessCache;
        if (cached != null && cached.key().equals(cacheKey) && now < cached.expiresAtNanos()) {
            return deepCopy(cached.result());
        }

        CompletableFuture<Map<String, Object>> task =
                readinessInflight.computeIfAbsent(
                        cacheKey,
                        key ->
                                CompletableFuture.supplyAsync(
                                        () -> computeReadiness(startupComplete, componentSnapshot), PROBE_EXECUTOR));
        Map<String, Object> result;
        try {
            result = task.get();
        } catch (java.util.concurrent.ExecutionException exception) {
            readinessInflight.remove(cacheKey, task);
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("readiness 计算失败", exception);
        }
        if (task.isDone() && !task.isCompletedExceptionally()) {
            readinessInflight.remove(cacheKey, task);
        }

        readinessCache = new CacheEntry(
                cacheKey,
                System.nanoTime() + (long) (Math.max(0.0, READINESS_CACHE_TTL_SECONDS) * 1_000_000_000.0),
                result);
        return deepCopy(result);
    }

    /** 对应参考实现 copy.deepcopy(cached_result) 的嵌套 Map 拷贝。 */
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                value = deepCopy(nested);
            }
            copy.put(entry.getKey(), value);
        }
        return copy;
    }
}
