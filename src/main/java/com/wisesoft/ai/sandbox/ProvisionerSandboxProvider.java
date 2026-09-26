package com.wisesoft.ai.sandbox;

import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.wisesoft.ai.service.ConfigService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * 沙盒 provider（从问渠新栈 {@code com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxProvider}
 * 移植；两者语义都逐字对齐蓝本 {@code backends/sandbox/provider.py}）。
 *
 * <p>保留原样：沙盒身份派生（{@link #sandboxIdForThread} / {@link #sandboxKey}）、连接缓存与 keepalive
 * （threadLocks / connections / lastTouchAt / touchIfNeeded）、运行时作用域获取与释放（get / release / shutdown）。
 *
 * <h3>移植时改掉的三处（都是环境差异，不是功能取舍）</h3>
 * <ul>
 *   <li><b>配置来源</b>：新栈读环境变量（{@code SANDBOX_PROVISIONER_URL} / {@code SANDBOX_PROVISIONER_TOKEN} /
 *       {@code SANDBOX_PROVISIONER_DELETE_TIMEOUT_SECONDS} / {@code SANDBOX_KEEPALIVE_INTERVAL_SECONDS}）；
 *       本工程配置的唯一来源是 {@code config-schema.json} + {@code ConfigService}（设置页可改），
 *       故全部改读 {@code sandbox.*} 配置项。</li>
 *   <li><b>token 校验时机</b>：新栈在构造期校验（缺 token 即启动失败，那是它 {@code required=True} 启动组件的既有语义）；
 *       本工程沙盒默认关闭，沿用「构造期 fail-closed」会让一个没启用沙盒的部署因为缺一个用不到的 token 而起不来，
 *       故推迟到<b>首次实际使用</b>时校验。校验口径不变：≥32 字符。</li>
 *   <li><b>去掉模块级单例</b>：新栈用 {@code static volatile instance} + {@code getSandboxProvider()} 静态取用；
 *       本工程的工具与清理任务都是 Spring bean，直接注入即可 —— 静态持有既无必要，也会让「谁先初始化」变成隐式约定。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ul>
 *   <li>{@code load_user_agent_env()}：参考实现与新栈按 uid 从 {@code agent_env} 表读该用户的智能体环境变量注入沙盒；
 *       <b>本工程没有「按用户的智能体环境变量」载体</b> ⇒ {@link #loadUserAgentEnv} 恒返回空表（不注入用户级 env），
 *       沙盒仍会拿到 provisioner 侧的全局 {@code sandbox.env}。</li>
 *   <li>{@code _thread_locks} 用 {@code WeakValueDictionary}（空闲锁随 GC 回收）；JVM 无原生弱值字典，本工程用
 *       {@link ConcurrentHashMap} 强引用 —— 「同一 cacheKey 得到同一把锁」的语义一致，仅「空闲锁自动回收」这一
 *       GC 内部行为不可对齐。</li>
 *   <li>{@code postgres_conninfo()} 是 psycopg 专属辅助，随 env 注入一并去掉。</li>
 * </ul>
 */
@Service
public class ProvisionerSandboxProvider {

    /** provisioner 默认地址：本机回环（回环在 macOS 系统代理的例外列表内，可避免出站请求被代理劫持）。 */
    private static final String DEFAULT_PROVISIONER_URL = "http://127.0.0.1:8002";

    /**
     * health / discover / touch 的请求超时（秒）。
     * <p>对应参考实现 {@code ProvisionerClient} 的 {@code timeout_seconds} 类默认值 20——参考实现只给
     * {@code delete_timeout_seconds} 留了环境变量，故这里也不做配置项。create 的 read 不设上限，delete 用 deleteTimeout。
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 20;

    private final ConfigService configService;

    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> threadLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SandboxConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> lastTouchAt = new ConcurrentHashMap<>();

    /** 懒构建的 provisioner 客户端：按（url, token, 删除超时）三元组缓存，配置改了下次使用即生效。 */
    private volatile SandboxProvisionerClient client;
    private volatile String clientKey;

    public ProvisionerSandboxProvider(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * provisioner 客户端（懒构建）。
     * <p>对应参考实现构造期建 client；本工程改成懒构建，一是让配置改动无需重启，二是让「没启用沙盒就不校验 token」
     * 成为可能（见类注释的 token 校验时机）。
     */
    private SandboxProvisionerClient client() {
        String url = provisionerUrl();
        String token = token();
        int deleteTimeout = configService.getInt("sandbox.deleteTimeoutSeconds", 120);
        String key = url + "|" + token + "|" + deleteTimeout;
        SandboxProvisionerClient current = client;
        if (current != null && key.equals(clientKey)) {
            return current;
        }
        synchronized (this) {
            if (client != null && key.equals(clientKey)) {
                return client;
            }
            SandboxProvisionerClient created = new SandboxProvisionerClient(
                    url, token, REQUEST_TIMEOUT_SECONDS, deleteTimeout);
            this.client = created;
            this.clientKey = key;
            return created;
        }
    }

        // ==================== 模块级辅助（逐字对齐参考实现） ====================

    /** 对应 sandbox_provisioner_token()：≥32 字符否则拒绝（fail-closed，口径与新栈一致）。 */
    public String token() {
        String token = configService.get("sandbox.token");
        token = token == null ? "" : token.strip();
        if (token.length() < 32) {
            throw new IllegalStateException(
                    "沙盒 provisioner token 未配置或不足 32 字符（系统设置 → 沙盒 → 访问令牌）");
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

    /**
     * 对应 load_user_agent_env()：注入该用户的智能体环境变量。
     *
     * <p><b>能力差异（显式标注）</b>：参考实现与新栈按 uid 查 {@code agent_env} 表并归一注入；本工程没有这个载体，
     * 故恒返回空表。返回类型与调用点保持不变，将来若加用户级环境变量载体，只需在这里补查询（配套的
     * {@code normalize_env} 归一逻辑此时再一并补回）。</p>
     */
    Map<String, String> loadUserAgentEnv(String uid) {
        return new java.util.LinkedHashMap<>();
    }

    /**
     * provisioner 服务地址（对应 resolve_provisioner_url）。
     * <p>参考实现还会校验 {@code SANDBOX_PROVIDER} 只接受 {@code provisioner}；本工程只有这一种后端，故不再需要该开关。
     */
    private String provisionerUrl() {
        String url = configService.get("sandbox.provisionerUrl");
        if (url == null || url.strip().isEmpty()) {
            return DEFAULT_PROVISIONER_URL;
        }
        return url.strip();
    }

    /** keepalive 间隔（秒，对应 SANDBOX_KEEPALIVE_INTERVAL_SECONDS；≤0 关闭 keepalive）。 */
    private int touchIntervalSeconds() {
        return configService.getInt("sandbox.keepaliveIntervalSeconds", 30);
    }

    /**
     * 沙盒内 user-data 虚拟根（对应 SANDBOX_VIRTUAL_PATH_PREFIX，默认 {@code /home/gem/user-data}）。
     * <p>它就是 {@link ProvisionerSandboxBackend} 的读写授权根：**只有该根之下可写**，skills 根只读。</p>
     */
    public String virtualPathPrefix() {
        String prefix = configService.get("sandbox.virtualPathPrefix");
        prefix = prefix == null ? "" : prefix.strip();
        if (prefix.isEmpty()) {
            prefix = "/home/gem/user-data";
        }
        return prefix.startsWith("/") ? prefix : "/" + prefix;
    }

    /** 沙盒内 skills 只读根（参考实现硬编码 {@code /home/gem/skills}，不受虚拟根配置影响）。 */
    public String virtualSkillsPath() {
        return "/home/gem/skills";
    }

    /** 单条命令的默认超时（秒，对应 SANDBOX_EXEC_TIMEOUT_SECONDS）。 */
    public int commandTimeoutSeconds() {
        return configService.getInt("sandbox.commandTimeoutSeconds", 180);
    }

    /** 单次命令输出上限（字节，对应 SANDBOX_MAX_OUTPUT_BYTES；超出按字节截断并标记 truncated）。 */
    public int maxOutputBytes() {
        return configService.getInt("sandbox.maxOutputBytes", 262144);
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
        if (touchIntervalSeconds() <= 0) {
            return false;
        }
        Double last = lastTouchAt.get(cacheKey);
        if (last == null) {
            return true;
        }
        return (System.currentTimeMillis() / 1000.0 - last) >= touchIntervalSeconds();
    }

    private boolean touchIfNeeded(SandboxConnection connection) {
        if (!shouldTouch(connection.getCacheKey())) {
            return true;
        }
        if (!client().touch(connection.getSandboxId())) {
            return false;
        }
        SandboxRecord record = client().discover(connection.getSandboxId());
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
                : SandboxPaths.normalizeWorkdirPath(workdirPath);
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
                record = client().create(
                        sandboxId,
                        threadId,
                        SandboxPaths.uidDirname(uid),
                        loadUserAgentEnv(uid),
                        normalizedWorkdir,
                        inheritEnv);
                if (!java.util.Objects.equals(record.getWorkdirPath(), normalizedWorkdir)) {
                    throw new RuntimeException("created sandbox Workdir does not match requested scope");
                }
            } else {
                record = client().discover(sandboxId);
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
                : SandboxPaths.normalizeWorkdirPath(workdirPath);
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
                SandboxRecord record = client().discover(sandboxId);
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
                client().delete(sandboxId, generation);
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

    /**
     * 回收空闲沙盒（本工程特有，新栈没有对应物）。
     * <p>新栈把沙盒 scope 挂在「一次 run」上，run 结束由 RunWorker 释放；本工程会话是长生命周期的，
     * scope 挂在会话上 ⇒ 不会有人天然释放，只能按空闲时长回收，否则每个用过沙盒的会话都会永久留一个容器。</p>
     *
     * @param ttlSeconds 空闲多久才回收（秒）
     * @return 实际回收的数量
     */
    public int releaseIdle(long ttlSeconds) {
        if (ttlSeconds <= 0 || connections.isEmpty()) {
            return 0;
        }
        double now = System.currentTimeMillis() / 1000.0;
        int released = 0;
        for (Map.Entry<String, SandboxConnection> entry : new java.util.LinkedHashMap<>(connections).entrySet()) {
            String cacheKey = entry.getKey();
            Double last = lastTouchAt.get(cacheKey);
            // 时间戳未知按「刚刚」处理：宁可晚回收，也不误删一个刚建好还没来得及 touch 的沙盒
            double idleFor = now - (last == null ? now : last);
            if (idleFor < ttlSeconds) {
                continue;
            }
            SandboxConnection connection = entry.getValue();
            try {
                client().delete(connection.getSandboxId(), connection.getGeneration());
            } catch (RuntimeException exc) {
                System.err.println("Failed to release idle sandbox " + connection.getSandboxId()
                        + " for " + cacheKey + ": " + exc);
            }
            // 删除失败也把缓存条目摘掉：连接可能已经不在了，留着只会每次重试都撞同一堵墙
            connections.remove(cacheKey);
            lastTouchAt.remove(cacheKey);
            released++;
        }
        return released;
    }

    /** 空闲多久回收沙盒（分钟；0 = 不自动回收）。 */
    public int idleReleaseMinutes() {
        return configService.getInt("sandbox.idleReleaseMinutes", 60);
    }

    /** 空闲回收任务的执行间隔（毫秒，≤0 = 暂停）。 */
    public int cleanupIntervalMs() {
        return configService.getInt("sandbox.cleanupIntervalMs", 600000);
    }

    /** 应用停止时回收本实例持有的沙盒（对应参考实现的 _shutdown_component 补偿链）。 */
    @PreDestroy
    public void shutdown() {
        Map<String, SandboxConnection> snapshot = new java.util.LinkedHashMap<>(connections);
        connections.clear();
        lastTouchAt.clear();
        for (SandboxConnection connection : snapshot.values()) {
            try {
                client().delete(connection.getSandboxId(), connection.getGeneration());
            } catch (RuntimeException exc) {
                // keepalive 失败仅告警
                System.err.println("Failed to release sandbox " + connection.getSandboxId()
                        + " for " + connection.getCacheKey() + ": " + exc);
            }
        }
    }
}
