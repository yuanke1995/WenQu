package com.wenqu.ai.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备授权码配对（gh auth login / aws sso login 同款形状）。
 *
 * <p>方向是<b>执行器发起</b>：本机进程申请一个 6 位码并把链接打在自己终端上，用户在浏览器里
 * 登录自己的问渠账号确认，令牌只回给执行器。令牌因此不经过命令行、shell 历史和环境变量。
 *
 * <p>待确认的码只存进程内、10 分钟过期：服务重启后未完成的配对自动失效，重新启动执行器拿新码即可。
 * 确认前服务端不签发任何设备身份，库里也就没有这台电脑。
 */
@Service
public class ExecutorPairingService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorPairingService.class);

    private static final long TTL_MS = 10 * 60_000L;
    private static final long FAILURE_WINDOW_MS = 10 * 60_000L;
    /** 同一账号 10 分钟内最多试错这么多次，防止遍历 6 位码 */
    private static final int MAX_FAILURES = 10;
    /** 同一 IP 每分钟最多申请多少个授权码：匿名端点不能让一个人把待确认表灌满 */
    private static final int MAX_CREATES_PER_IP_PER_MINUTE = 6;
    /** 同一 IP 同时待确认的授权码上限 */
    private static final int MAX_PENDING_PER_IP = 20;
    /** 去掉了 0/O/1/I 等易混字符：码要能照着终端手输 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();

    public enum Status { PENDING, APPROVED, EXPIRED, CONSUMED }

    public record Info(String userCode, String name, String os, long expiresAt, Status status) {
    }

    public record Redeemed(String token, String executorId, String name, Status status) {
    }

    public static class PairingException extends RuntimeException {
        private final int code;

        public PairingException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private static final class Session {
        final String deviceCode;
        final String userCode;
        final String name;
        final String os;
        final String ip;
        final long expiresAt;
        volatile String uid;
        volatile boolean consumed;

        Session(String deviceCode, String userCode, String name, String os, String ip, long expiresAt) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.name = name;
            this.os = os;
            this.ip = ip;
            this.expiresAt = expiresAt;
        }

        Status status(long now) {
            if (consumed) return Status.CONSUMED;
            if (now > expiresAt) return Status.EXPIRED;
            return uid == null ? Status.PENDING : Status.APPROVED;
        }
    }

    private final Map<String, Session> byDeviceCode = new ConcurrentHashMap<>();
    private final Map<String, Session> byUserCode = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    /** IP → 最近一分钟的申请时间；匿名申请限频用。 */
    private final Map<String, Deque<Long>> createHits = new ConcurrentHashMap<>();
    /** IP → 当前待确认数量；防止单 IP 占满全局待确认表。 */
    private final Map<String, Integer> pendingByIp = new ConcurrentHashMap<>();

    /** 匿名申请：留一个上限，避免被灌爆内存（真正配对只需要极少数几个待确认码） */
    private static final int MAX_PENDING = 1000;

    private final ExecutorService executorService;

    public ExecutorPairingService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /** 执行器启动时申请：deviceCode 给执行器轮询用，userCode 给人在浏览器里照着输。 */
    public Map<String, Object> create(String name, String os) {
        return create(name, os, null);
    }

    public Map<String, Object> create(String name, String os, String ip) {
        sweep();
        String ipKey = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        checkCreateRate(ipKey);
        if (pendingByIp.getOrDefault(ipKey, 0) >= MAX_PENDING_PER_IP) {
            throw new PairingException(429, "这个地址待确认的授权码太多了，请稍后再试");
        }
        if (byDeviceCode.size() >= MAX_PENDING) {
            throw new PairingException(429, "待确认的授权码太多了，请稍后再试");
        }
        long now = System.currentTimeMillis();
        String userCode;
        do {
            userCode = randomUserCode();
        } while (byUserCode.containsKey(userCode));
        String deviceCode = HexFormat.of().formatHex(randomBytes(24));
        Session s = new Session(deviceCode, userCode,
                trim(name, 100, "我的电脑"), trim(os, 20, ""), ipKey,
                now + TTL_MS);
        byDeviceCode.put(deviceCode, s);
        byUserCode.put(userCode, s);
        pendingByIp.merge(ipKey, 1, Integer::sum);
        log.info("[EXECUTOR] 授权码已生成 name={} code={}", s.name, userCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceCode", deviceCode);
        data.put("userCode", userCode);
        data.put("expiresIn", TTL_MS / 1000);
        return data;
    }

    /** 浏览器侧：显示这是哪台电脑，确认前先让用户看清。 */
    public Info lookup(String userCode, String uid) {
        return info(requirePending(userCode, uid));
    }

    /** 浏览器侧确认：绑定到当前账号，等执行器把结果取走。 */
    public Info approve(String userCode, String uid) {
        Session s = requirePending(userCode, uid);
        s.uid = uid;
        log.info("[EXECUTOR] 授权码已确认 code={} uid={} name={}", s.userCode, uid, s.name);
        return info(s);
    }

    /** 执行器侧轮询：确认后才签发令牌，且只签一次。 */
    public Redeemed redeem(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return new Redeemed(null, null, null, Status.EXPIRED);
        }
        Session s = byDeviceCode.get(deviceCode.trim());
        if (s == null) return new Redeemed(null, null, null, Status.EXPIRED);
        synchronized (s) {
            Status status = s.status(System.currentTimeMillis());
            if (status != Status.APPROVED) {
                if (status == Status.EXPIRED || status == Status.CONSUMED) remove(s);
                return new Redeemed(null, null, s.name, status);
            }
            ExecutorRegistry.Paired paired = executorService.register(s.uid, s.name, s.os);
            s.consumed = true;
            remove(s);
            log.info("[EXECUTOR] 已按授权码签发设备令牌 id={} uid={} name={}",
                    paired.device().id(), s.uid, s.name);
            return new Redeemed(paired.token(), paired.device().id(), s.name, Status.APPROVED);
        }
    }

    private Session requirePending(String userCode, String uid) {
        String code = normalize(userCode);
        if (code.isEmpty()) throw new PairingException(400, "请填写授权码");
        checkThrottle(uid);
        Session s = byUserCode.get(code);
        long now = System.currentTimeMillis();
        if (s == null || s.status(now) == Status.EXPIRED || s.status(now) == Status.CONSUMED) {
            noteFailure(uid);
            throw new PairingException(404, "授权码不存在或已过期，请在终端里重新启动执行器");
        }
        if (s.uid != null && !s.uid.equals(uid)) {
            noteFailure(uid);
            throw new PairingException(409, "这个授权码已被其他账号确认");
        }
        failures.remove(uid);
        return s;
    }

    private static Info info(Session s) {
        return new Info(s.userCode, s.name, s.os, s.expiresAt, s.status(System.currentTimeMillis()));
    }

    /** 匿名申请限频：滑动一分钟窗口，按 IP 计。 */
    private void checkCreateRate(String ip) {
        Deque<Long> q = createHits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000L) q.removeFirst();
            if (q.size() >= MAX_CREATES_PER_IP_PER_MINUTE) {
                throw new PairingException(429, "授权码申请过于频繁，请稍后再试");
            }
            q.addLast(now);
        }
    }

    private void checkThrottle(String uid) {
        if (uid == null) return;
        Deque<Long> q = failures.get(uid);
        if (q == null) return;
        synchronized (q) {
            long now = System.currentTimeMillis();
            while (!q.isEmpty() && now - q.peekFirst() > FAILURE_WINDOW_MS) q.removeFirst();
            if (q.size() >= MAX_FAILURES) {
                throw new PairingException(429, "授权码尝试次数过多，请过几分钟再试");
            }
        }
    }

    private void noteFailure(String uid) {
        if (uid == null) return;
        Deque<Long> q = failures.computeIfAbsent(uid, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Session s : byDeviceCode.values()) {
            if (s.status(now) == Status.EXPIRED) remove(s);
        }
    }

    private void remove(Session s) {
        boolean removed = byDeviceCode.remove(s.deviceCode, s);
        byUserCode.remove(s.userCode, s);
        if (removed && s.ip != null) {
            pendingByIp.computeIfPresent(s.ip, (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    private String randomUserCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String normalize(String userCode) {
        if (userCode == null) return "";
        return userCode.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String trim(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
