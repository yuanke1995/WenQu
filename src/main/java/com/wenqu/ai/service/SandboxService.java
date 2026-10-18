package com.wenqu.ai.service;

import com.wenqu.ai.model.Project;
import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.sandbox.ProvisionerSandboxProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 沙盒生命周期与 backend 装配（本工程特有的那一层薄壳）。
 *
 * <p><b>作用域：工作区优先，会话兜底。</b>沙盒 identity 由 {@code sha256(uid + ":" + scopeId)} 决定
 * （见 {@code ProvisionerSandboxProvider#sandboxIdForThread}），所以「换一个 scopeId」就等于
 * 「换一个容器 + 换一个 workdir」，不需要任何新机制：
 * <ul>
 *   <li>会话有工作区 ⇒ scope = <b>工作区 id</b>，workdir = 工作区的 {@code projects/{工作区 id}}。
 *       同一工作区的多个会话因此共用同一个容器与同一个工作区——这是「围绕一件事反复干活」
 *       这件事在盘上的落地；容器空闲回收后 workdir 仍在，<b>文件不丢</b>；</li>
 *   <li>会话无工作区（存量会话、匿名、没挂工作区）⇒ scope = <b>会话 id</b>，workdir 仍是 {@code null}。
 *       <b>与本次改动之前逐字相同</b>，保证不建工作区的用户行为零变化。</li>
 * </ul>
 *
 * <p>释放不靠「run 结束」（本工程没有 run 队列），而是<b>空闲回收</b>
 * （{@link #releaseIdle()}，由 {@code ScheduleCenter} 定期调用）——否则每个用过沙盒的会话都会永久留一个容器。
 *
 * <p><b>已知取舍（如实记录，不是待修 bug）</b>：同一工作区下<b>并发</b>的多个会话共用同一容器，
 * 彼此的 shell 进程与文件系统可见。会话级已有一轮互斥（{@code ChatRuntime#tryBeginTurn}），
 * 跨会话没有。当前不补工作区级互斥——真出现并发工作流需求时，补法是在 {@code c_ai_project} 上加一把
 * 轮级锁，而不是再拆沙盒。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SandboxService {

    /** 空闲多久回收沙盒（分钟）；≤0 表示不自动回收。 */
    private static final String KEY_IDLE_RELEASE_MINUTES = "sandbox.idleReleaseMinutes";

    private final ProvisionerSandboxProvider provider;
    /** 工作区解析：决定沙盒作用域与 workdir（无工作区时退回会话级，行为不变） */
    private final ProjectService projectService;
    /** 工作区目录开关（默认关，见 scopeOf 的注释） */
    private final ConfigService configService;

    public SandboxService(ProvisionerSandboxProvider provider, ProjectService projectService,
                          ConfigService configService) {
        this.provider = provider;
        this.projectService = projectService;
        this.configService = configService;
    }

    /**
     * 沙盒的作用域与工作目录。
     *
     * @param scopeId     交给 provider 的作用域标识（工作区 id / 会话 id），决定容器与缓存键
     * @param workdirPath 工作区目录（相对 user-data 根）；null = 沿用用户级共享工作区（旧行为）
     */
    private record SandboxScope(String scopeId, String workdirPath) {
    }

    /**
     * 解析本轮沙盒落在哪个作用域。
     * <p>解析失败一律退回会话级——沙盒是"能干活"的能力，不该因为工作区归属读不到而整轮不可用。
     */
    private SandboxScope scopeOf(String sessionId) {
        // 工作区目录默认关闭：想让新装的用户「什么都不配」也能用，回会话级目录的行为与引入工作区之前逐字相同。
        // 开关本身已在「系统设置 → 沙盒 → 工作区共用目录」暴露给管理员，需要跨会话共用文件时自行开启。
        // 历史记录：2026-10-17 曾因真机遇到「带 workdir 建沙盒 404」而收成开关，2026-10-18 端到端复验
        // 未再复现（建沙盒 / 落盘 / 删容器重建 / 跨会话互读 四项均通过），404 未复现的原因尚未定位，
        // 故保留开关而非直接写死开启——真出问题时还能一键退回会话级作用域。
        if (!configService.getBoolean("sandbox.projectWorkdirEnabled")) {
            return new SandboxScope(sessionId, null);
        }
        try {
            Project project = projectService.projectOfSession(sessionId);
            if (project != null && project.getId() != null && !project.getId().isBlank()) {
                return new SandboxScope(project.getId(), project.getWorkdirPath());
            }
        } catch (Exception e) {
            log.warn("[SANDBOX] 工作区作用域解析失败，退回会话级 (session={}): {}", sessionId, e.getMessage());
        }
        return new SandboxScope(sessionId, null);
    }

    /**
     * 取当前会话的沙盒 backend（懒创建：首次使用才向 provisioner 申请沙盒）。
     * <p>backend 本身只持有 provider 与 scope 标识，真正的连接由 provider 按 (uid, scope) 缓存，
     * 所以每次调用新建一个 backend 是廉价的，不需要再缓存一层。
     *
     * @param sessionId 会话 id；为空时抛错——没有会话就没有归属与作用域可解析
     * @param uid       归属用户（决定沙盒的持久卷子路径）
     */
    public ProvisionerSandboxBackend backend(String sessionId, String uid) {
        return backend(sessionId, uid, false);
    }

    /**
     * 取当前会话的沙盒 backend，并带上本轮的写入边界。
     *
     * @param unrestrictedWrites P2 权限档位的 {@code danger-full-access}：true 时写类工具不再限定在
     *                           沙盒根目录内。默认 false——**沙盒的文件写入边界本来就在这个类这一层**，
     *                           档位要改变它必须经这里传进来，否则「全自主」只是个标签。
     */
    public ProvisionerSandboxBackend backend(String sessionId, String uid, boolean unrestrictedWrites) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("沙盒需要会话上下文（sessionId）");
        }
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("沙盒需要用户上下文（uid）");
        }
        SandboxScope scope = scopeOf(sessionId);
        return new ProvisionerSandboxBackend(provider, scope.scopeId(), uid, scope.workdirPath(),
                true, true, unrestrictedWrites);
    }

    /**
     * 探测当前会话的沙盒是否在运行（discover 不创建——浏览面板绝不能有「看一眼就把容器拉起来」的副作用）。
     * 探测失败（provisioner 不可达等）按不可用处理，返回 false 不抛错。
     */
    public boolean available(String sessionId, String uid) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return false;
        }
        try {
            SandboxScope scope = scopeOf(sessionId);
            return provider.get(scope.scopeId(), uid, false, true, scope.workdirPath()) != null;
        } catch (Exception e) {
            log.debug("[SANDBOX] 探测沙盒失败 (session={}): {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 取「不创建」的沙盒 backend：供浏览端点使用。沙盒未运行时首次连接即抛
     * RuntimeException（sandbox is unavailable）——调用方自行转成可读状态，不产生容器。
     */
    public ProvisionerSandboxBackend backendIfPresent(String sessionId, String uid) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("沙盒需要会话上下文（sessionId）");
        }
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("沙盒需要用户上下文（uid）");
        }
        SandboxScope scope = scopeOf(sessionId);
        return new ProvisionerSandboxBackend(provider, scope.scopeId(), uid, scope.workdirPath(), false, true);
    }

    /** 沙盒内用户数据虚拟根（可读写区，浏览面板的默认起点；默认 /home/gem/user-data）。 */
    public String userDataRoot() {
        return provider.virtualPathPrefix();
    }

    /**
     * 立即释放某会话的沙盒。
     * <p><b>注意作用域</b>：工作区会话的沙盒是按工作区 id 缓存的，必须用同一个作用域释放，
     * 否则「按会话 id 释放」会找不到容器而静默留下一个（这正是本类最容易写错的一处）。
     */
    public void release(String sessionId, String uid) {
        SandboxScope scope = scopeOf(sessionId);
        provider.release(scope.scopeId(), uid, true, scope.workdirPath());
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
