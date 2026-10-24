package com.wenqu.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 守卫的 JVM 内实现（默认）：工作区 id → 当前租约的原子引用。
 *
 * <p><b>作用域与已知边界</b>（沿袭旧 {@code ProjectTurnMutex} 的口径）：锁在 JVM 内，单实例部署
 * 这就是全部；多副本时各副本各管各的，届时切 {@link RedisProjectTurnGuard}。
 *
 * <p><b>TTL 的角色</b>：单实例下轮次结束的释放靠流水线 finally（主路径，可靠），TTL 是兜底——
 * 轮次线程卡死不再把工作区锁到重启（旧计数制做不到）。默认 2 小时，刻意远大于任何一轮的
 * 合法上界（网页轮受整轮看门狗约束、定时任务/工作流轮受调用方 awaitDone 超时约束，都是分钟级），
 * 宁可卡死的锁多挂一会儿，也不让误过期把还在跑的轮放进来——那会重新打开「并发写同一份文件」
 * 的口子。续约 API（{@link #renew}）留给租约期需要超过默认值的场景与 Redis 实现对齐语义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "project.turnGuard.store", havingValue = "jvm", matchIfMissing = true)
public class JvmProjectTurnGuard implements ProjectTurnGuard {

    private final ConfigService configService;

    /** 工作区 id → 当前租约。按工作区常驻（工作区数量是个位数），不回收也不构成泄漏 */
    private final ConcurrentMap<String, AtomicReference<TurnLease>> leases = new ConcurrentHashMap<>();

    /** 租约默认期限：见类注释的取舍（远大于单轮合法上界的自愈兜底） */
    public static final long DEFAULT_LEASE_TTL_MS = 2 * 60 * 60 * 1000L;

    private long ttlMs() {
        long v = configService.getLong("project.turnGuard.leaseTtlMs", DEFAULT_LEASE_TTL_MS);
        return v > 0 ? v : DEFAULT_LEASE_TTL_MS;
    }

    @Override
    public boolean tryBegin(String projectId, TurnLease lease) {
        if (projectId == null || projectId.isBlank()) {
            return true;   // 不属任何工作区的会话：不受这把锁约束（会话级互斥已够）
        }
        AtomicReference<TurnLease> ref = leases.computeIfAbsent(projectId, k -> new AtomicReference<>());
        TurnLease withDeadline = new TurnLease(lease.turnId(), lease.sessionId(), lease.kind(),
                lease.name(), lease.uid(), lease.startedAt(), System.currentTimeMillis() + ttlMs());
        TurnLease prev = ref.get();
        if (prev != null && !prev.expired()) {
            return false;
        }
        // CAS 到 null（或把过期租约顶掉）：输了说明刚好有别人占上，按失败处理
        if (!ref.compareAndSet(prev, withDeadline)) {
            return false;
        }
        log.debug("[PROJECT] 占用工作区轮次锁 project={} turn={}（{}）", projectId, lease.turnId(), withDeadline.describe());
        return true;
    }

    @Override
    public void renew(String projectId, String turnId, long ttlMs) {
        if (projectId == null || projectId.isBlank()) return;
        AtomicReference<TurnLease> ref = leases.get(projectId);
        if (ref != null) {
            ref.updateAndGet(cur -> cur != null && turnId != null && turnId.equals(cur.turnId())
                    && !cur.expired() ? cur.renewed(System.currentTimeMillis() + ttlMs) : cur);
        }
    }

    @Override
    public void end(String projectId, String turnId) {
        if (projectId == null || projectId.isBlank()) return;
        AtomicReference<TurnLease> ref = leases.get(projectId);
        if (ref != null) {
            TurnLease prev = ref.get();
            if (prev != null && turnId != null && turnId.equals(prev.turnId())) {
                ref.compareAndSet(prev, null);
                log.debug("[PROJECT] 释放工作区轮次锁 project={} turn={}", projectId, turnId);
            }
        }
    }

    @Override
    public TurnLease current(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        AtomicReference<TurnLease> ref = leases.get(projectId);
        if (ref == null) return null;
        TurnLease cur = ref.get();
        if (cur == null) return null;
        if (cur.expired()) {
            // 死租约自愈：持有方没回来释放（轮次线程卡死），读到即清——下一个人不再陪等
            if (ref.compareAndSet(cur, null)) {
                log.warn("[FAIL-LOUD] 工作区轮次租约过期自动释放 project={} turn={}（{}，已占用 {}ms）",
                        projectId, cur.turnId(), cur.describe(), System.currentTimeMillis() - cur.startedAt());
            }
            return null;
        }
        return cur;
    }
}
