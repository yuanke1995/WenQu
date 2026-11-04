package com.wenqu.ai.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 本机执行器的连接登记表。
 *
 * <p>执行器在用户电脑上主动长轮询，服务端<b>不开任何入站端口</b>；一次工具调用 =
 * 服务端把请求放进该执行器的队列 → 执行器取走并回传结果 → 等待方拿到 CompletableFuture。
 * 进程内登记（单实例部署）；多副本要换成 Redis 队列，先不做。
 */
@Service
public class ExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistry.class);

    /** 超过这个时间没来轮询就视为离线（执行器每 25s 一次长轮询，留 2 倍余量） */
    private static final long ONLINE_WINDOW_MS = 70_000;

    public record Device(String id, String uid, String name, String os) {
    }

    /** 一次待执行的调用；执行器侧只看到 op/args。 */
    public static final class Call {
        public final String id = UUID.randomUUID().toString().replace("-", "");
        public final String op;
        public final Map<String, Object> args;
        public final CompletableFuture<Result> future = new CompletableFuture<>();

        Call(String op, Map<String, Object> args) {
            this.op = op;
            this.args = args;
        }
    }

    public record Result(boolean ok, Map<String, Object> data, String code, String message) {
        public static Result ok(Map<String, Object> data) {
            return new Result(true, data, null, null);
        }

        public static Result fail(String code, String message) {
            return new Result(false, null, code, message);
        }
    }

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<Call>> queues = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Call> inflight = new ConcurrentHashMap<>();

    /** 用户在本机执行器上点「添加这台电脑」：签发一次性令牌（只回一次，库里存哈希由调用方负责）。 */
    public record Paired(Device device, String token) {
    }

    public Paired register(String uid, String name, String os) {
        String id = "ex-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        Device device = new Device(id, uid, name == null || name.isBlank() ? "我的电脑" : name, os);
        devices.put(id, device);
        queues.put(id, new LinkedBlockingQueue<>());
        lastSeen.put(id, System.currentTimeMillis());
        log.info("[EXECUTOR] 设备已登记 uid={} id={} name={}", uid, id, device.name());
        return new Paired(device, token);
    }

    public void restore(Device device) {
        devices.put(device.id(), device);
        queues.putIfAbsent(device.id(), new LinkedBlockingQueue<>());
    }

    public Device byId(String id) {
        return id == null ? null : devices.get(id);
    }

    public boolean online(String executorId) {
        Long seen = lastSeen.get(executorId);
        return seen != null && System.currentTimeMillis() - seen < ONLINE_WINDOW_MS;
    }

    /** 执行器长轮询：最多等 waitSeconds 秒，拿到一条调用就返回，超时返回 null。 */
    public Call poll(String executorId, int waitSeconds) throws InterruptedException {
        lastSeen.put(executorId, System.currentTimeMillis());
        BlockingQueue<Call> q = queues.get(executorId);
        if (q == null) return null;
        Call call = q.poll(Math.max(1, Math.min(waitSeconds, 30)), TimeUnit.SECONDS);
        if (call != null) inflight.put(call.id, call);
        lastSeen.put(executorId, System.currentTimeMillis());
        return call;
    }

    public boolean complete(String callId, Result result) {
        Call call = inflight.remove(callId);
        if (call == null) return false;
        call.future.complete(result);
        return true;
    }

    public boolean cancel(String callId, String reason) {
        return complete(callId, Result.fail("E_TIMEOUT", reason));
    }

    /**
     * 服务端发起一次工具调用并等待结果。
     *
     * @param timeoutMs 执行器侧的执行上限 + 网络余量；超时按 {@code E_TIMEOUT} 返回，不抛异常
     */
    public Result call(String executorId, String op, Map<String, Object> args, long timeoutMs) {
        if (!online(executorId)) {
            return Result.fail("E_EXECUTOR_OFFLINE", "本机执行器不在线，请在那台电脑上启动后再试");
        }
        BlockingQueue<Call> q = queues.get(executorId);
        if (q == null) {
            return Result.fail("E_EXECUTOR_OFFLINE", "本机执行器不在线");
        }
        Call call = new Call(op, args);
        if (!q.offer(call)) {
            return Result.fail("E_BUSY", "本机执行器繁忙，请稍后重试");
        }
        try {
            return call.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            inflight.remove(call.id);
            return Result.fail("E_TIMEOUT", "本机执行器超时未返回（" + op + "）");
        }
    }

    public void unregister(String executorId) {
        devices.remove(executorId);
        queues.remove(executorId);
        lastSeen.remove(executorId);
    }
}
