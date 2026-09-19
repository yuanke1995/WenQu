package com.wisesoft.wenqu.agents.backends.sandbox;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.repositories.AgentEnvRepository;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * 沙盒 provider（对应参考实现 backends/sandbox/provider.py 的 {@code ProvisionerSandboxProvider}）。
 *
 * <p>逐字对齐：sandbox 身份派生（sandbox_id_for_thread / _sandbox_key）、env 归一
 * （normalize_env / load_user_agent_env）、连接缓存与 keepalive（_thread_lock / _record_to_connection /
 * _should_touch / _touch_if_needed）、运行时作用域获取与释放（get / release / shutdown）、模块级单例
 * （get_sandbox_provider / init_sandbox_provider / shutdown_sandbox_provider）。
 *
 * <h3>必要替换</h3>
 * <ul>
 *   <li>{@code psycopg} 直连 PostgreSQL → {@link AgentEnvRepository#getByUid}（MySQL，逐方法翻译，
 *       见 repositories/agent_env_repository.py 迁移说明）。</li>
 *   <li>{@code yuxi.workspace.paths.workspace_uid_dirname} →
 *       {@link WorkspacePaths#workspaceUidDirname}（同一 SHA-256 命名空间算法）。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ul>
 *   <li>参考实现 {@code _thread_locks} 用 {@code WeakValueDictionary}（弱值字典，空闲作用域的锁随 GC 回收）；
 *       JVM 无原生弱值字典，本工程用 {@link ConcurrentHashMap} 持有强引用锁——同一 cache_key 返回同一把锁的
 *       语义保持一致，仅「空闲锁自动回收」这一 GC 内部行为属能力差异（不影响功能契约，对应参考单测
 *       test_sandbox_provider_discards_unused_thread_locks 在本工程跳过）。</li>
 *   <li>{@code postgres_conninfo()} 是 psycopg 专属辅助，已由 AgentEnvRepository 接管，本工程未照搬。</li>
 * </ul>
 */
@Service
public class ProvisionerSandboxProvider {

    private static final String PROVIDER_NAME = "provisioner";
    private static final String DEFAULT_PROVISIONER_URL = "http://sandbox-provisioner:8002";

    private final SandboxProvisionerClient client;
    private final AgentEnvRepository agentEnvRepository;
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> threadLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SandboxConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> lastTouchAt = new ConcurrentHashMap<>();
    private final int touchIntervalSeconds;

    private static volatile ProvisionerSandboxProvider instance;

    /** 运行期单例（对应参考实现模块级 _sandbox_provider + get_sandbox_provider）。 */
    public static ProvisionerSandboxProvider getSandboxProvider() {
        ProvisionerSandboxProvider current = instance;
        if (current != null) {
            return current;
        }
        throw new IllegalStateException("ProvisionerSandboxProvider 尚未初始化（需经 Spring 装配）");
    }

    public static void shutdownSandboxProvider() {
        ProvisionerSandboxProvider current = instance;
        instance = null;
        if (current != null) {
            current.shutdown();
        }
    }

    /** Spring 装配构造：自环境变量读取 token / url / 超时。 */
    public ProvisionerSandboxProvider(AgentEnvRepository agentEnvRepository) {
        this.client = new SandboxProvisionerClient(
                resolveProvisionerUrl(),
                sandboxProvisionerToken(),
                intEnv("SANDBOX_PROVISIONER_DELETE_TIMEOUT_SECONDS", 120),
                intEnv("SANDBOX_PROVISIONER_DELETE_TIMEOUT_SECONDS", 120));
        this.agentEnvRepository = agentEnvRepository;
        this.touchIntervalSeconds = intEnv("SANDBOX_KEEPALIVE_INTERVAL_SECONDS", 30);
        instance = this;
    }

    /** 仅供测试：绕过环境变量直接注入 client（对应参考单测的 ProvisionerSandboxProvider.__new__）。 */
    ProvisionerSandboxProvider(SandboxProvisionerClient client, AgentEnvRepository agentEnvRepository) {
        this.client = client;
        this.agentEnvRepository = agentEnvRepository;
        this.touchIntervalSeconds = 30;
    }

    // ==================== 模块级辅助（逐字对齐参考实现） ====================

    /** 对应 sandbox_provisioner_token()。 */
    public static String sandboxProvisionerToken() {
        String token = System.getenv("SANDBOX_PROVISIONER_TOKEN");
        if (token == null) {
            token = "";
        } else {
            token = token.strip();
        }
        if (token.length() < 32) {
            throw new IllegalArgumentException("SANDBOX_PROVISIONER_TOKEN must contain at least 32 characters");
        }
        return token;
    }

    /** 对应 sandbox_id_for_thread()。 */
    public static String sandboxIdForThread(String threadId, String uid) {
        String runtimeId = threadId == null ? "" : threadId.strip();
        String uidId = uid == null ? "" : uid.strip();
        String identity = uidId.isEmpty() ? runtimeId : uidId + ":" + runtimeId;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < digest.length && hex.length() < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 unavailable", exc);
        }
    }

    /** 对应 _sandbox_key()。 */
    static String sandboxKey(String uid, String threadId) {
        return (uid == null ? "" : uid) + "::" + (threadId == null ? "" : threadId);
    }

    /** 对应 normalize_env()。 */
    static Map<String, String> normalizeEnv(Map<String, Object> env) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (env == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : env.entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            Object value = entry.getValue();
            result.put(key, value == null ? "" : String.valueOf(value));
        }
        return result;
    }

    /** 对应 load_user_agent_env()（psycopg → AgentEnvRepository）。 */
    Map<String, String> loadUserAgentEnv(String uid) {
        if (agentEnvRepository == null) {
            return new java.util.LinkedHashMap<>();
        }
        com.wisesoft.wenqu.models.AgentEnv row = agentEnvRepository.getByUid(uid);
        if (row == null) {
            return new java.util.LinkedHashMap<>();
        }
        String raw = row.getEnv();
        if (raw == null || raw.isEmpty()) {
            return new java.util.LinkedHashMap<>();
        }
        JSONObject parsed;
        try {
            parsed = JSONObject.parseObject(raw);
        } catch (RuntimeException exc) {
            throw new RuntimeException("stored agent env for uid " + uid + " is not valid JSON", exc);
        }
        if (parsed == null) {
            return new java.util.LinkedHashMap<>();
        }
        Map<String, Object> rawMap = parsed;
        return normalizeEnv(rawMap);
    }

    private static String resolveProvisionerUrl() {
        String providerName = System.getenv("SANDBOX_PROVIDER");
        if (providerName != null && !providerName.strip().toLowerCase().equals(PROVIDER_NAME)) {
            throw new IllegalArgumentException("Only SANDBOX_PROVIDER=provisioner is supported.");
        }
        String url = System.getenv("SANDBOX_PROVISIONER_URL");
        if (url == null || url.strip().isEmpty()) {
            url = DEFAULT_PROVISIONER_URL;
        }
        return url.strip();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.strip().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exc) {
            return fallback;
        }
    }

    // ==================== 连接缓存与 keepalive ====================

    private ReentrantLock threadLock(String cacheKey) {
        ReentrantLock existing = threadLocks.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        ReentrantLock created = new ReentrantLock();
        ReentrantLock raced = threadLocks.putIfAbsent(cacheKey, created);
        return raced == null ? created : raced;
    }

    private SandboxConnection recordToConnection(
            String cacheKey, String threadId, String uid, SandboxRecord record) {
        SandboxConnection connection = new SandboxConnection(
                cacheKey,
                threadId,
                uid,
                record.getSandboxId(),
                record.getSandboxUrl(),
                record.getGeneration(),
                record.getWorkdirPath());
        connections.put(cacheKey, connection);
        lastTouchAt.put(cacheKey, (double) System.currentTimeMillis() / 1000.0);
        return connection;
    }

    private boolean shouldTouch(String cacheKey) {
        if (touchIntervalSeconds <= 0) {
            return false;
        }
        Double last = lastTouchAt.get(cacheKey);
        if (last == null) {
            return true;
        }
        return (System.currentTimeMillis() / 1000.0 - last) >= touchIntervalSeconds;
    }

    private boolean touchIfNeeded(SandboxConnection connection) {
        if (!shouldTouch(connection.getCacheKey())) {
            return true;
        }
        if (!client.touch(connection.getSandboxId())) {
            return false;
        }
        SandboxRecord record = client.discover(connection.getSandboxId());
        if (record == null) {
            return false;
        }
        if (!java.util.Objects.equals(record.getWorkdirPath(), connection.getWorkdirPath())) {
            throw new SandboxIdentityMismatchError("sandbox Workdir changed within one runtime scope");
        }
        connection.setSandboxUrl(record.getSandboxUrl());
        connection.setGeneration(record.getGeneration());
        return true;
    }

    // ==================== 运行时作用域获取 / 释放 ====================

    public SandboxConnection get(
            String threadId,
            String uid,
            boolean createIfMissing,
            boolean inheritEnv,
            String workdirPath) {
        String normalizedWorkdir = workdirPath == null || workdirPath.isEmpty()
                ? null
                : WorkspacePaths.normalizeWorkdirPath(workdirPath);
        String cacheKey = sandboxKey(uid, threadId);
        ReentrantLock lock = threadLock(cacheKey);
        lock.lock();
        try {
            SandboxConnection current = connections.get(cacheKey);
            if (current != null) {
                if (!java.util.Objects.equals(current.getUid(), uid)) {
                    throw new RuntimeException(
                            "sandbox scope " + cacheKey + " belongs to uid " + current.getUid() + ", not " + uid);
                }
                if (!java.util.Objects.equals(current.getWorkdirPath(), normalizedWorkdir)) {
                    throw new SandboxIdentityMismatchError("sandbox Workdir does not match the existing runtime scope");
                }
                try {
                    if (touchIfNeeded(current)) {
                        return current;
                    }
                    connections.remove(cacheKey);
                    lastTouchAt.remove(cacheKey);
                } catch (SandboxIdentityMismatchError exc) {
                    throw exc;
                } catch (RuntimeException exc) {
                    // keepalive 失败不致命：返回既有连接
                    return current;
                }
            }

            String sandboxId = sandboxIdForThread(threadId, uid);
            SandboxRecord record;
            if (createIfMissing) {
                record = client.create(
                        sandboxId,
                        threadId,
                        WorkspacePaths.workspaceUidDirname(uid),
                        loadUserAgentEnv(uid),
                        normalizedWorkdir,
                        inheritEnv);
                if (!java.util.Objects.equals(record.getWorkdirPath(), normalizedWorkdir)) {
                    throw new RuntimeException("created sandbox Workdir does not match requested scope");
                }
            } else {
                record = client.discover(sandboxId);
                if (record == null) {
                    return null;
                }
                if (!java.util.Objects.equals(record.getWorkdirPath(), normalizedWorkdir)) {
                    throw new RuntimeException("discovered sandbox Workdir does not match requested scope");
                }
            }

            return recordToConnection(cacheKey, threadId, uid, record);
        } finally {
            lock.unlock();
        }
    }

    public void release(
            String threadId,
            String uid,
            boolean clearCacheOnDeleteFailure,
            String workdirPath) {
        String normalizedWorkdir = workdirPath == null || workdirPath.isEmpty()
                ? null
                : WorkspacePaths.normalizeWorkdirPath(workdirPath);
        String cacheKey = sandboxKey(uid, threadId);
        ReentrantLock lock = threadLock(cacheKey);
        lock.lock();
        try {
            SandboxConnection connection = connections.get(cacheKey);
            String sandboxId;
            String generation;
            if (connection != null && !java.util.Objects.equals(connection.getWorkdirPath(), normalizedWorkdir)) {
                throw new SandboxIdentityMismatchError("sandbox Workdir does not match the existing runtime scope");
            }
            if (connection == null) {
                sandboxId = sandboxIdForThread(threadId, uid);
                SandboxRecord record = client.discover(sandboxId);
                if (record == null) {
                    return;
                }
                if (!java.util.Objects.equals(record.getWorkdirPath(), normalizedWorkdir)) {
                    throw new SandboxIdentityMismatchError("sandbox Workdir does not match the requested release scope");
                }
                generation = record.getGeneration();
            } else {
                sandboxId = connection.getSandboxId();
                generation = connection.getGeneration();
            }
            try {
                client.delete(sandboxId, generation);
            } catch (RuntimeException exc) {
                if (clearCacheOnDeleteFailure) {
                    connections.remove(cacheKey);
                    lastTouchAt.remove(cacheKey);
                }
                throw exc;
            }
            connections.remove(cacheKey);
            lastTouchAt.remove(cacheKey);
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        Map<String, SandboxConnection> snapshot = new java.util.LinkedHashMap<>(connections);
        connections.clear();
        lastTouchAt.clear();
        for (SandboxConnection connection : snapshot.values()) {
            try {
                client.delete(connection.getSandboxId(), connection.getGeneration());
            } catch (RuntimeException exc) {
                // keepalive 失败仅告警
                System.err.println("Failed to release sandbox " + connection.getSandboxId()
                        + " for " + connection.getCacheKey() + ": " + exc);
            }
        }
    }
}
