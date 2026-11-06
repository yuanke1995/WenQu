package com.wenqu.ai.executor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本机执行器的连接登记表（Redis 版）。
 *
 * <p>执行器在用户电脑上主动长轮询，服务端<b>不开任何入站端口</b>；一次工具调用 =
 * 往 Redis 队列放一条调用 → 任意副本把调用交给执行器 → 执行器回传结果 →
 * 发起调用的那个副本拿到 CompletableFuture。
 *
 * <p>为什么不再用进程内队列：多副本部署时负载均衡会把 poll 和 enqueue 分到不同实例，
 * 进程内队列永远取不到。现在队列、在线状态、ack、结果路由都在 Redis：
 * <ul>
 *   <li>调用方副本是这次调用的 <b>owner</b>，负责等待 future、续期“等待方租约”、超时取消；</li>
 *   <li>任意副本都能 poll 并交给执行器；执行器 ack 时由任意副本校验 owner 租约仍然有效；</li>
 *   <li>结果由任意副本接收，按 owner 投到 Redis 结果队列，owner 的后台泵完成 future。</li>
 * </ul>
 *
 * <p><b>进程重启的语义</b>：owner 重启后 future 已随进程消失，等待方租约在 45s 内过期；
 * 执行器 ack 时租约已失效，调用不会被执行——宁可这轮失败，也不让文件在“没人等结果”时被改。
 * 已发出的结果短暂保留在 Redis，便于排障，但不会再自动接回已死的那一轮。
 *
 * <p>队列上限、deadline、ack 重投与结果回投都沿用原单机版的语义，只是搬到了共享存储。
 */
@Service
public class ExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistry.class);

    /** 超过这个时间没来轮询/心跳就视为离线（执行器每 25s 轮询、20s 心跳，留 2 倍余量） */
    private static final long ONLINE_WINDOW_MS = 70_000;
    /** 单台设备的待执行上限 */
    private static final int QUEUE_CAPACITY = 512;
    /** 取出后多久没收到执行器 ack 就重新入队 */
    private static final long ACK_TIMEOUT_MS = 15_000;
    /** 等待方租约：owner 每 2s 续一次，15s 不续就认为这一轮的等待方已经不在了。
     *  服务重启后的“静默窗口”就是这 15 秒；执行器 ack 会在这之后一律被拒。 */
    private static final long WAITING_TTL_MS = 15_000;
    private static final long REPLICA_TTL_MS = 20_000;

    private static final Duration CALL_TTL = Duration.ofHours(2);
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    private static final String P = "wenqu:executor:";
    private static final String ONLINE_KEY = P + "online:";
    private static final String QUEUE_KEY = P + "queue:";
    private static final String CALL_KEY = P + "call:";
    private static final String WAITING_KEY = P + "waiting:";
    private static final String RESULTS_KEY = P + "results:";
    private static final String REPLICA_KEY = P + "replica:";

    /** 原子入队：队列到上限就拒绝，避免多副本并发下 LLEN 判断与 LPUSH 之间被插队。 */
    private static final DefaultRedisScript<Long> ENQUEUE = new DefaultRedisScript<>(
            "if redis.call('llen', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end "
                    + "redis.call('lpush', KEYS[1], ARGV[1]) "
                    + "redis.call('expire', KEYS[1], 7200) return 1",
            Long.class);

    public record Device(String id, String uid, String name, String os) {
    }

    public record Result(boolean ok, Map<String, Object> data, String code, String message) {
        public static Result ok(Map<String, Object> data) {
            return new Result(true, data, null, null);
        }

        public static Result fail(String code, String message) {
            return new Result(false, null, code, message);
        }
    }

    /** 一次待执行的调用；owner 是发起这次调用、持有等待 future 的副本。 */
    public static final class Call {
        public final String id;
        public final String executorId;
        public final String owner;
        public final String op;
        public final Map<String, Object> args;
        public final long deadlineMs;
        public final CompletableFuture<Result> future = new CompletableFuture<>();

        Call(String executorId, String owner, String op, Map<String, Object> args, long deadlineMs) {
            this(UUID.randomUUID().toString().replace("-", ""), executorId, owner, op, args, deadlineMs);
        }

        Call(String id, String executorId, String owner, String op, Map<String, Object> args, long deadlineMs) {
            this.id = id == null || id.isBlank()
                    ? UUID.randomUUID().toString().replace("-", "") : id;
            this.executorId = executorId;
            this.owner = owner;
            this.op = op;
            this.args = args;
            this.deadlineMs = deadlineMs;
        }

        JSONObject payload() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("executorId", executorId);
            o.put("owner", owner);
            o.put("op", op);
            o.put("args", args);
            o.put("deadline", deadlineMs);
            return o;
        }
    }

    public record Paired(Device device, String token) {
    }

    private final StringRedisTemplate redis;
    private final String replicaId;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    /** 本副本发起、正在等待结果的调用；future 只存在于这个进程里。 */
    private final Map<String, Call> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public ExecutorRegistry(StringRedisTemplate redis) {
        this.redis = redis;
        this.replicaId = resolveReplicaId();
    }

    private static String resolveReplicaId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "node";
        }
        return host + "#" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    void start() {
        refreshReplicaLease();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "executor-redis-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshReplicaLease, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::resultPump, 200, 200, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::deliveryReaper, 2, 2, TimeUnit.SECONDS);
        this.scheduler = scheduler;
    }

    private volatile ScheduledExecutorService scheduler;

    @PreDestroy
    void stop() {
        try {
            redis.delete(REPLICA_KEY + replicaId);
        } catch (Exception e) {
            log.warn("[EXECUTOR] 注销副本租约失败（交给 TTL）: {}", e.getMessage());
        }
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void refreshReplicaLease() {
        try {
            redis.opsForValue().set(REPLICA_KEY + replicaId, "1", Duration.ofMillis(REPLICA_TTL_MS));
        } catch (Exception e) {
            log.warn("[EXECUTOR] 副本租约续期失败: {}", e.getMessage());
        }
    }

    // ==================== 设备登记 / 在线 ====================

    public Paired register(String uid, String name, String os) {
        String id = "ex-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        Device device = new Device(id, uid, name == null || name.isBlank() ? "我的电脑" : name, os);
        devices.put(id, device);
        // 登记不等于在线：只有执行器发起轮询/心跳才更新在线键
        try {
            redis.delete(ONLINE_KEY + id);
        } catch (Exception e) {
            log.warn("[EXECUTOR] 清理在线键失败: {}", e.getMessage());
        }
        log.info("[EXECUTOR] 设备已登记 uid={} id={} name={}", uid, id, device.name());
        return new Paired(device, token);
    }

    public void restore(Device device) {
        devices.put(device.id(), device);
    }

    public Device byId(String id) {
        return id == null ? null : devices.get(id);
    }

    public boolean online(String executorId) {
        if (executorId == null || executorId.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(ONLINE_KEY + executorId));
        } catch (Exception e) {
            log.warn("[EXECUTOR] 在线状态查询失败（按离线处理）: {}", e.getMessage());
            return false;
        }
    }

    /** 执行器心跳/轮询时刷新共享在线键。 */
    public void heartbeat(String executorId) {
        if (executorId == null) return;
        try {
            redis.opsForValue().set(ONLINE_KEY + executorId, replicaId, Duration.ofMillis(ONLINE_WINDOW_MS));
        } catch (Exception e) {
            log.warn("[EXECUTOR] 在线键刷新失败: {}", e.getMessage());
        }
    }

    public void unregister(String executorId) {
        devices.remove(executorId);
        try {
            redis.delete(ONLINE_KEY + executorId);
            redis.delete(QUEUE_KEY + executorId);
        } catch (Exception e) {
            log.warn("[EXECUTOR] 清理设备 Redis 键失败: {}", e.getMessage());
        }
        for (Call call : List.copyOf(pending.values())) {
            if (executorId.equals(call.executorId)) {
                pending.remove(call.id, call);
                cancelMeta(call.id, "执行器已离线或被撤销");
                call.future.complete(Result.fail("E_EXECUTOR_OFFLINE", "本机执行器已离线或被撤销"));
            }
        }
    }

    // ==================== 调用方：入队 + 等待 ====================

    /**
     * 服务端发起一次工具调用并等待结果。
     *
     * @param timeoutMs 等待上限；执行器侧另收到比它少 1s 的 deadline，超时不会晚到落盘
     */
    public Result call(String executorId, String op, Map<String, Object> args, long timeoutMs) {
        if (!online(executorId)) {
            return Result.fail("E_EXECUTOR_OFFLINE", "本机执行器不在线，请在那台电脑上启动后再试");
        }
        long deadlineMs = System.currentTimeMillis() + Math.max(5_000, timeoutMs - 1_000);
        Call call = new Call(executorId, replicaId, op, args, deadlineMs);
        pending.put(call.id, call);
        try {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("executorId", executorId);
            meta.put("owner", replicaId);
            meta.put("op", op);
            meta.put("deadline", String.valueOf(deadlineMs));
            meta.put("createdAt", String.valueOf(System.currentTimeMillis()));
            meta.put("status", "pending");
            meta.put("acked", "0");
            meta.put("attempts", "0");
            meta.put("dispatchedAt", "0");
            redis.opsForHash().putAll(CALL_KEY + call.id, meta);
            redis.expire(CALL_KEY + call.id, CALL_TTL);
            // 等待方租约：owner 活着才续期；重启后过期，执行器 ack 会被拒，调用不会执行
            redis.opsForValue().set(WAITING_KEY + call.id, replicaId, Duration.ofMillis(WAITING_TTL_MS));
            Long queued = redis.execute(ENQUEUE, List.of(QUEUE_KEY + executorId),
                    call.payload().toJSONString(), String.valueOf(QUEUE_CAPACITY));
            if (queued == null || queued == 0L) {
                pending.remove(call.id, call);
                cancelMeta(call.id, "执行器繁忙");
                return Result.fail("E_BUSY", "本机执行器繁忙，请稍后重试");
            }
            return call.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(call.id, call);
            cancelMeta(call.id, "等待超时");
            return Result.fail("E_TIMEOUT", "本机执行器超时未返回（" + op + "）");
        } catch (Exception e) {
            pending.remove(call.id, call);
            cancelMeta(call.id, "调用通道异常");
            log.warn("[EXECUTOR] 调用失败 op={} executor={}: {}", op, executorId, e.getMessage());
            return Result.fail("E_IO", "执行器调用通道异常");
        }
    }

    // ==================== 执行器侧：轮询 / 心跳 / ack / 结果 ====================

    /**
     * 任意副本都能取调用：从共享队列右端取，校验 owner 租约与 deadline 后才交给执行器。
     * <p>这里刻意用短轮询 + sleep，而不是 Redis 阻塞弹出：阻塞命令会占住连接池连接，
     * 设备一多就把 Redis 连接耗尽；HTTP 长轮询的线程本来就要等。
     */
    public Call poll(String executorId, int waitSeconds) throws InterruptedException {
        return poll(executorId, waitSeconds, false);
    }

    /**
     * @param ackCapable 执行器是否支持 v2 的 ack 租约。旧 agent 没有 ack 端点，
     *                   不能按 ack 超时重投（否则 15s 后重复投递、旧 agent 可能重复执行）。
     */
    public Call poll(String executorId, int waitSeconds, boolean ackCapable) throws InterruptedException {
        long until = System.currentTimeMillis() + Math.max(1, Math.min(waitSeconds, 30)) * 1000L;
        String queue = QUEUE_KEY + executorId;
        // 执行器在场，哪怕队列空着也是在线；只在进入轮询时刷一次，别在 25s 里每 250ms 写一次 Redis
        heartbeat(executorId);
        while (System.currentTimeMillis() < until) {
            String raw;
            try {
                raw = redis.opsForList().rightPop(queue);
            } catch (Exception e) {
                log.warn("[EXECUTOR] 轮询 Redis 队列失败: {}", e.getMessage());
                throw new IllegalStateException("执行器队列不可用", e);
            }
            if (raw == null) {
                // 50ms 而不是 500ms：这是**每一次**执行器调用的固定附加延迟（建会话目录要连做
                // mkdir 几步，框架建实例时还要连查几个工作区文件，500ms 会叠成数秒）。
                // 空闲时多几次 Redis pop，对这个量级的设备数不算成本。
                Thread.sleep(50);
                continue;
            }
            JSONObject o;
            try {
                o = JSON.parseObject(raw);
            } catch (Exception e) {
                log.warn("[EXECUTOR] 丢弃不可解析的调用: {}", e.getMessage());
                continue;
            }
            String id = o.getString("id");
            Map<String, String> meta = callMeta(id);
            if (meta == null) continue;
            String status = meta.getOrDefault("status", "pending");
            if (!"pending".equals(status)) continue;
            String owner = meta.get("owner");
            if (!ownerAlive(owner)) {
                cancelMeta(id, "等待方已离线");
                continue;
            }
            long deadline = asLong(meta.get("deadline"));
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                cancelMeta(id, "等待时限已过");
                continue;
            }
            long attempts = asLong(meta.get("attempts")) + 1;
            redis.opsForHash().put(CALL_KEY + id, "dispatchedAt", String.valueOf(System.currentTimeMillis()));
            redis.opsForHash().put(CALL_KEY + id, "attempts", String.valueOf(attempts));
            redis.opsForHash().put(CALL_KEY + id, "acked", "0");
            redis.opsForHash().put(CALL_KEY + id, "ackCapable", ackCapable ? "1" : "0");
            redis.expire(CALL_KEY + id, CALL_TTL);
            Map<String, Object> args = o.getJSONObject("args") == null
                    ? Map.of() : o.getJSONObject("args");
            return new Call(id, o.getString("executorId"), owner, o.getString("op"), args,
                    o.getLongValue("deadline"));
        }
        return null;
    }

    /**
     * 执行器确认收到调用：必须 owner 的等待方租约仍在、调用仍未完成且未过 deadline。
     * <p>这是“服务重启不执行无等待方调用”的关键闸：owner 重启后 future 消失，等待方租约
     * 45 秒内过期，ack 一律拒绝，执行器据此不执行。
     */
    public boolean ack(String executorId, String callId) {
        Map<String, String> meta = callMeta(callId);
        if (meta == null) return false;
        if (executorId != null && !executorId.equals(meta.get("executorId"))) return false;
        if (!"pending".equals(meta.getOrDefault("status", "pending"))) return false;
        if (!ownerAlive(meta.get("owner"))) return false;
        long deadline = asLong(meta.get("deadline"));
        if (deadline > 0 && System.currentTimeMillis() >= deadline) return false;
        if (!Boolean.TRUE.equals(redis.hasKey(WAITING_KEY + callId))) return false;
        redis.opsForHash().put(CALL_KEY + callId, "acked", "1");
        redis.expire(CALL_KEY + callId, CALL_TTL);
        return true;
    }

    /**
     * 回传结果：任意副本接收，校验设备归属后按 owner 回投。
     * <p>owner 还在本进程等，就直接完成 future；不在（另一副本/已重启）就进 owner 的结果队列，
     * 由 owner 的 resultPump 完成。结果队列 10 分钟过期，够排障但不长期留原文。
     */
    public boolean complete(String executorId, String callId, Result result) {
        Map<String, String> meta = callMeta(callId);
        if (meta == null) return false;
        if (executorId != null && !executorId.equals(meta.get("executorId"))) return false;
        if ("completed".equals(meta.get("status"))) return false;
        String owner = meta.get("owner");
        redis.opsForHash().put(CALL_KEY + callId, "status", "completed");
        redis.opsForHash().put(CALL_KEY + callId, "completedAt", String.valueOf(System.currentTimeMillis()));
        redis.expire(CALL_KEY + callId, CALL_TTL);
        redis.delete(WAITING_KEY + callId);

        Call local = pending.remove(callId);
        if (local != null) {
            local.future.complete(result);
            return true;
        }
        JSONObject msg = new JSONObject();
        msg.put("callId", callId);
        msg.put("ok", result.ok());
        msg.put("data", result.data());
        msg.put("code", result.code());
        msg.put("message", result.message());
        redis.opsForList().leftPush(RESULTS_KEY + owner, msg.toJSONString());
        redis.expire(RESULTS_KEY + owner, RESULT_TTL);
        return true;
    }

    /** 取消一个调用（内部使用；执行器侧会因 ack 失败而跳过执行）。 */
    public boolean cancel(String callId, String reason) {
        Map<String, String> meta = callMeta(callId);
        if (meta == null) return false;
        String owner = meta.get("owner");
        cancelMeta(callId, reason);
        Call local = pending.remove(callId);
        if (local != null) {
            local.future.complete(Result.fail("E_TIMEOUT", reason));
        } else {
            JSONObject msg = new JSONObject();
            msg.put("callId", callId);
            msg.put("ok", false);
            msg.put("code", "E_TIMEOUT");
            msg.put("message", reason);
            redis.opsForList().leftPush(RESULTS_KEY + owner, msg.toJSONString());
            redis.expire(RESULTS_KEY + owner, RESULT_TTL);
        }
        return true;
    }

    // ==================== owner 后台：结果泵 / 投递租约 ====================

    private void resultPump() {
        try {
            for (;;) {
                String raw = redis.opsForList().rightPop(RESULTS_KEY + replicaId);
                if (raw == null) return;
                JSONObject msg = JSON.parseObject(raw);
                String callId = msg.getString("callId");
                Call call = callId == null ? null : pending.remove(callId);
                if (call == null) continue;   // owner 已重启/已超时：结果留档在 Redis，没人接
                Result result;
                if (msg.getBooleanValue("ok")) {
                    JSONObject data = msg.getJSONObject("data");
                    result = Result.ok(data == null ? Map.of() : data);
                } else {
                    result = Result.fail(msg.getString("code"), msg.getString("message"));
                }
                call.future.complete(result);
            }
        } catch (Exception e) {
            log.warn("[EXECUTOR] 结果回投处理异常: {}", e.getMessage());
        }
    }

    private void deliveryReaper() {
        long now = System.currentTimeMillis();
        for (Call call : List.copyOf(pending.values())) {
            try {
                Map<String, String> meta = callMeta(call.id);
                if (meta == null) {
                    // 元数据丢了（Redis 淘汰/清理）：这条 future 不可能再被结果唤醒，当场收尾
                    pending.remove(call.id, call);
                    call.future.complete(Result.fail("E_CANCELLED", "调用元数据已失效"));
                    continue;
                }
                if ("completed".equals(meta.get("status"))) {
                    // 结果可能正在 owner 的结果队列里，resultPump 会完成 future；这里不能提前删
                    continue;
                }
                if ("cancelled".equals(meta.get("status"))) {
                    pending.remove(call.id, call);
                    call.future.complete(Result.fail("E_TIMEOUT", "调用已取消"));
                    continue;
                }
                long deadline = asLong(meta.get("deadline"));
                if (deadline > 0 && now >= deadline) {
                    pending.remove(call.id, call);
                    cancelMeta(call.id, "等待超时");
                    call.future.complete(Result.fail("E_TIMEOUT", "本机执行器超时未返回（" + call.op + "）"));
                    continue;
                }
                // owner 活着：续等待方租约；这是执行器 ack 放行的依据
                redis.expire(WAITING_KEY + call.id, Duration.ofMillis(WAITING_TTL_MS));
                if ("1".equals(meta.get("acked"))) continue;
                // 旧 agent 不会 ack：不按 ack 超时重投，否则会重复执行；等它自己回结果或到 deadline
                if (!"1".equals(meta.getOrDefault("ackCapable", "0"))) continue;
                long dispatchedAt = asLong(meta.get("dispatchedAt"));
                if (dispatchedAt > 0 && now - dispatchedAt >= ACK_TIMEOUT_MS) {
                    Long queued = redis.execute(ENQUEUE, List.of(QUEUE_KEY + call.executorId),
                            call.payload().toJSONString(), String.valueOf(QUEUE_CAPACITY));
                    if (queued == null || queued == 0L) {
                        pending.remove(call.id, call);
                        cancelMeta(call.id, "执行器繁忙");
                        call.future.complete(Result.fail("E_BUSY", "本机执行器繁忙，请稍后重试"));
                    } else {
                        redis.opsForHash().put(CALL_KEY + call.id, "dispatchedAt", "0");
                        log.warn("[EXECUTOR] 调用 {} 未收到 ack，已重新入队", call.id);
                    }
                }
            } catch (Exception e) {
                log.warn("[EXECUTOR] 投递租约扫描异常 call={}: {}", call.id, e.getMessage());
            }
        }
    }

    // ==================== Redis 小工具 ====================

    private Map<String, String> callMeta(String callId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(CALL_KEY + callId);
            if (raw == null || raw.isEmpty()) return null;
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            log.warn("[EXECUTOR] 读取调用元数据失败 call={}: {}", callId, e.getMessage());
            return null;
        }
    }

    private boolean ownerAlive(String owner) {
        if (owner == null || owner.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(REPLICA_KEY + owner));
        } catch (Exception e) {
            return false;
        }
    }

    private void cancelMeta(String callId, String reason) {
        try {
            redis.opsForHash().put(CALL_KEY + callId, "status", "cancelled");
            redis.opsForHash().put(CALL_KEY + callId, "cancelReason", reason == null ? "" : reason);
            redis.expire(CALL_KEY + callId, CALL_TTL);
            redis.delete(WAITING_KEY + callId);
        } catch (Exception e) {
            log.warn("[EXECUTOR] 取消调用元数据失败 call={}: {}", callId, e.getMessage());
        }
    }

    private static long asLong(String v) {
        try {
            return v == null || v.isBlank() ? 0L : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
