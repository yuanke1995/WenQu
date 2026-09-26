package com.wisesoft.ai.service;

import com.wisesoft.ai.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.ai.sandbox.ProvisionerSandboxProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 沙盒生命周期与 backend 装配（本工程特有的那一层薄壳）。
 * <p>
 * 问渠新栈把沙盒 scope 挂在「一次 run」上：构图时创建、run 结束由 RunWorker 释放（
 * {@code ProvisionerSandboxProvider.release(runtimeScopeId, uid, …)}）。本工程没有 run 队列，
 * 会话是长生命周期的（同一会话多轮问答是常态，用户希望上一轮生成的文件还在），
 * 所以把 scope 从「run」上移到「会话」：
 * <ul>
 *   <li>scope id = 会话 id ⇒ 同一会话的每一轮问答复用同一个沙盒，文件跨轮保留；</li>
 *   <li>释放不靠「run 结束」，而是<b>空闲回收</b>（{@link #releaseIdle()}，由 {@code ScheduleCenter} 定期调用）
 *       ——否则每个用过沙盒的会话都会永久留一个容器。</li>
 * </ul>
 * <p>
 * {@code workdirPath} 传 {@code null}：那是新栈「项目工作目录」的落点，本工程没有项目/工作区概念，
 * 沙盒按用户级共享工作区落下（provisioner 用 {@code uid} 的路径安全目录名做隔离）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SandboxService {

    /** 空闲多久回收沙盒（分钟）；≤0 表示不自动回收。 */
    private static final String KEY_IDLE_RELEASE_MINUTES = "sandbox.idleReleaseMinutes";

    private final ProvisionerSandboxProvider provider;

    public SandboxService(ProvisionerSandboxProvider provider) {
        this.provider = provider;
    }

    /**
     * 取当前会话的沙盒 backend（懒创建：首次使用才向 provisioner 申请沙盒）。
     * <p>backend 本身只持有 provider 与 scope 标识，真正的连接由 provider 按 (uid, scope) 缓存，
     * 所以每次调用新建一个 backend 是廉价的，不需要再缓存一层。
     *
     * @param sessionId 会话 id（scope）；为空时抛错——没有 scope 就无法保证沙盒与会话一一对应
     * @param uid       归属用户（决定沙盒的持久卷子路径）
     */
    public ProvisionerSandboxBackend backend(String sessionId, String uid) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("沙盒需要会话上下文（sessionId）");
        }
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("沙盒需要用户上下文（uid）");
        }
        return new ProvisionerSandboxBackend(provider, sessionId, uid, null, true, true);
    }

    /** 立即释放某会话的沙盒（当前未接线到「删除会话」流程，故主要供排障与将来的钩子使用）。 */
    public void release(String sessionId, String uid) {
        provider.release(sessionId, uid, true, null);
    }

    /**
     * 回收空闲沙盒（由 {@code ScheduleCenter} 的「沙盒空闲回收」任务定期调用）。
     *
     * @return 实际回收的数量
     */
    public int releaseIdle() {
        int minutes = provider.idleReleaseMinutes();
        if (minutes <= 0) {
            return 0;
        }
        int released = provider.releaseIdle(minutes * 60L);
        if (released > 0) {
            log.info("[SANDBOX] 空闲回收 {} 个沙盒（空闲阈值 {} 分钟）", released, minutes);
        }
        return released;
    }

    /** 空闲回收阈值（分钟），供调度侧读取间隔之外的一致性校验。 */
    public int idleReleaseMinutes() {
        return provider.idleReleaseMinutes();
    }

    /** 空闲回收任务的执行间隔（毫秒，≤0 = 暂停）。 */
    public int cleanupIntervalMs() {
        return provider.cleanupIntervalMs();
    }

    /** 配置键名（供设置页文案与排障引用）。 */
    public static String idleReleaseMinutesKey() {
        return KEY_IDLE_RELEASE_MINUTES;
    }
}
