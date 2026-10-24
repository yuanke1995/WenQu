package com.wenqu.ai.service;

import com.wenqu.ai.memory.MemoryBudget;
import com.wenqu.ai.memory.MemoryCappedFilesystem;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.sandbox.ProvisionerSandboxClient;
import com.wenqu.ai.sandbox.ProvisionerSandboxFilesystem;
import com.wenqu.ai.sandbox.ProvisionerSandboxProvider;
import com.wenqu.ai.sandbox.ProvisionerSandbox;
import com.wenqu.ai.sandbox.ProvisionerSandboxState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

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

    /**
     * 框架状态目录（框架的 workspace 根）。记忆就钉在这下面按 uid 分开——
     * 与 {@code AgentscopeChatEngine#buildAgent} 里 {@code .workspace(stateDir)} 同一个键同一份默认值，
     * 那边改了这里必须跟着改，否则「Path 口径」和「文件系统口径」又会对不上。
     */
    private static final String KEY_STATE_DIR = "chat.agentscopeStateDir";
    private static final String STATE_DIR_DEFAULT = "user-data/agentscope";

    /** 记忆文件下载上限（MB）：单份 MEMORY.md / 日账远小于此，超了说明读错了对象。 */
    private static final int MEMORY_MAX_FILE_MB = 64;

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
        // 共用目录按**工作区各自**决定（Project.sharedWorkdir，新建工作区默认开、「会话」栏恒关），
        // 全局那个键降级为应急总闸：一键把所有工作区退回会话级作用域。
        // 历史记录：2026-10-17 曾因真机遇到「带 workdir 建沙盒 404」而收成全局开关，2026-10-18 端到端
        // 复验未再复现（建沙盒 / 落盘 / 删容器重建 / 跨会话互读 四项均通过）；2026-10-24 起默认值
        // 翻转为开——「传进来的材料模型看不见」这个更大的坑压过了那个未复现的 404，总闸保留作退路。
        if (!configService.getBoolean("sandbox.projectWorkdirEnabled")) {
            return new SandboxScope(sessionId, null);
        }
        try {
            Project project = projectService.projectOfSession(sessionId);
            // 生效判据统一走 ProjectService#workdirShared（本区开关＋总闸一份实现，别在这里再抄一份）
            if (project != null && projectService.workdirShared(project)) {
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
     * 产框架侧的文件系统（全框架化 P1，{@code SandboxContext} 的必需搭档）。
     *
     * <p><b>为什么两者都要</b>：{@code SandboxContext} 只管生命周期，框架的文件与 shell 工具不看它——
     * {@code FilesystemTool} / {@code ShellExecuteTool} 在 <b>build 期</b>就从 builder 字段绑好了文件系统。
     * 真机验证过：只挂 {@code SandboxContext} 时，模型调 {@code execute("pwd")} 返回的是
     * <b>宿主项目目录</b>——工具静默跑在宿主上，看着成功实则没隔离。
     * 反过来 {@code AbstractFilesystem} 也替代不了 {@code SandboxContext}：框架只在 spec 路线上自己造
     * 一份默认沙箱上下文，问渠走的是自管形态（{@code externalSandbox}），不挂它就解析不到沙箱。
     *
     * <p>作用域与 {@link #frameworkSandboxContext} 同源（同一 {@link #scopeOf}），
     * 因此框架工具与问渠自研工具看到的是同一个容器、同一份工作区文件。
     */
    public AbstractFilesystem frameworkFilesystem(String sessionId, String uid, boolean unrestrictedWrites) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return null;
        }
        try {
            SandboxScope scope = scopeOf(sessionId);
            ProvisionerSandboxBackend backend = new ProvisionerSandboxBackend(
                    provider, scope.scopeId(), uid, scope.workdirPath(),
                    true, true, unrestrictedWrites);
            String sandboxId = ProvisionerSandboxProvider.sandboxIdForThread(scope.scopeId(), uid);
            ProvisionerSandboxFilesystem sandboxFs = new ProvisionerSandboxFilesystem(backend, sandboxId);
            return memoryRouted(sandboxFs, uid);
        } catch (Exception e) {
            log.warn("[SANDBOX] 框架文件系统装配失败 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 把记忆从沙箱文件系统里摘出来，钉回 uid 级（P3 遗留的收口）。
     *
     * <p><b>问题是什么</b>：框架的 {@code WorkspaceManager} 用 build 期那份
     * {@link AbstractFilesystem} 读写 {@code MEMORY.md} 与 {@code memory/*.md}，而 P1 之后那份就是沙箱
     * 文件系统——于是记忆跟着沙箱作用域走：开了工作区目录就按工作区、关了就按会话，
     * 且 P3 之前沉淀在 {@code user-data/agentscope/{uid}/} 的那份读不到（真机问过模型「以前让你做过
     * 某件事吗」，那条老记忆写得很细，模型答「记忆里检索不到」）。
     *
     * <p><b>为什么这样修</b>：框架给的 {@link RoutedSandboxFilesystem} 就是为这件事造的——
     * 类注释原文 *"Prefix-routed filesystem that preserves shell execution on the primary sandbox backend
     * … mount … {@code memory-stores/} routes alongside a SandboxBackedFilesystem without losing
     * {@code shell_execute}"*。把 {@code MEMORY.md} 与 {@code memory/} 两条前缀路由到宿主
     * {@link LocalFilesystem}，其余路径（工作区文件、{@code skills/}）照旧落沙箱，shell 也照旧走主沙箱。
     * 路由后记忆的宿主目录正好是 {@code user-data/agentscope/{uid}/}——与 P3 之前的老位置重合，
     * 所以<b>存量老记忆不用迁移就能读回来</b>。
     *
     * <p><b>为什么不用 CompositeFilesystem</b>：它不实现 {@code execute}，框架的 {@code ShellExecuteTool}
     * 要求 {@code filesystem instanceof AbstractSandboxFilesystem} 才会注册；直接套 composite 会把
     * shell 工具整个弄哑。
     *
     * <p><b>为什么两条前缀各给一个根</b>：路由会把命中的前缀<b>剥掉</b>再交给后端
     * （{@code CompositeFilesystem#routeForPath}：{@code memory/2026-10-18.md} → 后端路径
     * {@code /2026-10-18.md}）。若两条前缀共用一个根，日账会落到 {@code {uid}/2026-10-18.md}，
     * 与老布局 {@code {uid}/memory/2026-10-18.md} 错位（最小用例实测踩过）。故
     * {@code MEMORY.md} 路由到 {@code {uid}/}、{@code memory/} 路由到 {@code {uid}/memory/}，
     * 剥完前缀正好各归各位。
     *
     * <p>路由装配失败（拿到非法路径等）就退回不路由：记忆落沙箱是旧行为，比整轮没有文件系统好。
     */
    private AbstractFilesystem memoryRouted(ProvisionerSandboxFilesystem sandboxFs, String uid) {
        try {
            Path base = memoryRoot(uid);
            // MEMORY.md 走带硬上限的文件系统：框架的 consolidation prompt 只是建议，
            // 模型可以写到 57 KB；写入落盘时按 memory.maxMdTokens 裁掉，注入才不会只剩头尾。
            // 日账是 append-only 原始记录，不裁。
            AbstractFilesystem memoryMdFs =
                    new MemoryCappedFilesystem(base, MEMORY_MAX_FILE_MB, MemoryBudget.maxChars(configService));
            AbstractFilesystem memoryDirFs =
                    new LocalFilesystem(base.resolve("memory"), true, MEMORY_MAX_FILE_MB);
            return new RoutedSandboxFilesystem(sandboxFs,
                    java.util.Map.of("/MEMORY.md", memoryMdFs, "/memory/", memoryDirFs));
        } catch (RuntimeException e) {
            log.warn("[SANDBOX] 记忆路由装配失败，本轮记忆仍落沙箱 (uid={}): {}", uid, e.toString());
            return sandboxFs;
        }
    }

    /**
     * 记忆根：{@code stateDir/{uid}}。与 {@code WorkspaceManager#getMemoryDir} 的 Path 口径
     * （{@code workspace.resolve(uid).resolve("memory")}）落在同一处，两套访问才对得上。
     */
    private Path memoryRoot(String uid) {
        String raw = configService.get(KEY_STATE_DIR);
        if (raw == null || raw.isBlank()) raw = STATE_DIR_DEFAULT;
        return Path.of(raw).resolve(uid);
    }

    /**
     * 产框架侧的 {@link SandboxContext}（全框架化迁移 P1）。
     *
     * <p>按官网「自管沙箱实例（高级）」的 {@code externalSandbox} 模式：沙箱由问渠创建与持有，
     * 框架只在本次 call 期间借用，call 结束只 {@code stop()} 不 {@code shutdown()}。
     *
     * <p><b>作用域口径与 {@link #backend} 完全同源</b>——都走 {@link #scopeOf}，
     * 所以框架工具与自研工具看到的是同一个容器、同一份工作区文件。
     * 这是本方法存在的意义：scope 解析只应有一处真源，否则两套沙箱会各算各的。
     *
     * @param sessionId 会话 id（决定工作区/会话级作用域）
     * @param uid       归属用户（决定沙箱 id 与持久卷子路径）
     * @return 可直接 {@code RuntimeContext.put(SandboxContext.class, ctx)} 的上下文；不可用时返回 null
     */
    public SandboxContext frameworkSandboxContext(String sessionId, String uid, boolean unrestrictedWrites) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return null;
        }
        try {
            SandboxScope scope = scopeOf(sessionId);
            ProvisionerSandboxBackend backend = new ProvisionerSandboxBackend(
                    provider, scope.scopeId(), uid, scope.workdirPath(),
                    true, true, unrestrictedWrites);
            ProvisionerSandboxState state = new ProvisionerSandboxState();
            state.setThreadId(scope.scopeId());
            state.setUid(uid);
            state.setWorkdirPath(scope.workdirPath());
            state.setSandboxId(ProvisionerSandboxProvider.sandboxIdForThread(scope.scopeId(), uid));
            state.setSessionId(state.getSandboxId());
            state.setWorkspaceRootReady(true);

            ProvisionerSandbox sandbox = new ProvisionerSandbox(backend, state);
            sandbox.start();
            return SandboxContext.builder()
                    .client(new ProvisionerSandboxClient(provider))
                    .externalSandbox(sandbox)
                    .build();
        } catch (Exception e) {
            // 沙盒不可用不该让整轮问答失败：问渠侧的沙盒工具也会返回失败文本让模型自行调整。
            // 这里只不提供框架沙箱上下文，模型退回无沙盒工具的形态，与 P1 之前一致。
            log.warn("[SANDBOX] 框架沙箱上下文装配失败，本轮不挂框架沙箱 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
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
