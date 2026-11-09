package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.memory.MemoryBudget;
import com.wenqu.ai.memory.MemoryCappedFilesystem;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.sandbox.ExecutorFilesystem;
import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.sandbox.ProvisionerSandboxClient;
import com.wenqu.ai.sandbox.ProvisionerSandboxFilesystem;
import com.wenqu.ai.sandbox.ProvisionerSandboxProvider;
import com.wenqu.ai.sandbox.ProvisionerSandbox;
import com.wenqu.ai.sandbox.ProvisionerSandboxState;
import com.wenqu.ai.sandbox.SandboxConnection;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *   <li>会话无工作区（存量会话、SYSTEM、没挂工作区）⇒ scope = <b>会话 id</b>，workdir 仍是 {@code null}。
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

    /**
     * 本会话这一轮是否走本机执行器目录（会按需解析并创建会话目录）。
     * 供引擎的「文件系统是否可用」判据用——本机后端没有 SandboxContext，不能拿 sandboxAttached 代判。
     */
    public boolean localSessionWorkspace(String sessionId, String uid) {
        SessionWorkspaceService.Workspace ws = sessionWorkspace == null
                ? null : sessionWorkspace.resolve(sessionId, uid);
        return ws != null && ws.local();
    }

    /** 本轮因执行器离线退回云端时给用户的一句说明；null=无需提示。 */
    public String sessionWorkspaceNotice(String sessionId, String uid) {
        SessionWorkspaceService.Workspace ws = sessionWorkspace == null
                ? null : sessionWorkspace.resolve(sessionId, uid);
        return ws == null ? null : ws.fallbackNotice();
    }

    /** 这条会话是否已经钉住本机后端（只读库、不探测、不建目录）：分档粘性用。 */
    public boolean pinnedLocalSessionWorkspace(String sessionId) {
        return sessionWorkspace != null && sessionWorkspace.pinnedLocal(sessionId);
    }

    /** 已钉住的本机工作区根（只读库、不探测、不建目录）：提示词装配期用，不能有建目录副作用。 */
    public String pinnedLocalWorkspaceRoot(String sessionId) {
        return sessionWorkspace == null ? null : sessionWorkspace.pinnedLocalRoot(sessionId);
    }

    /**
     * 工作区后端的实例键：框架实例把文件系统烤在 build 期，本机↔云端切换时必须重建。
     * 返回显式工作区标记（文件系统随工作区配置走，不在本方法判）、本机执行器+目录、或 cloud。
     */
    public String sessionWorkspaceKey(String sessionId, String uid) {
        SessionWorkspaceService.Workspace ws = sessionWorkspace == null
                ? null : sessionWorkspace.resolve(sessionId, uid);
        if (ws == null) return "project";
        return ws.local() ? "local:" + ws.executorId() + ":" + ws.rootPath() : "cloud";
    }

    /**
     * 聊天开轮时等沙盒装配的上限。超了就<b>本轮先不挂沙盒</b>，装配继续在后台跑完并进缓存，
     * 下一轮直接命中——问答本身的路由/检索/模型调用不等它。
     * <p>为什么必须有这道闸：装配要建沙盒容器（正常 3 秒，provisioner 侧健康检查最长 300 秒），
     * 而这一步在框架建实例之前，卡住时整轮一个字节都出不来（2026-11-05 线上：一次 create 的
     * 响应没回来，用户对着「正在溯源」干等 5 分半，模型调用根本没发生）。
     * <p>取 10 秒：正常创建 + 健康检查在本机实测不到 3 秒；真赶上基础设施慢，本轮退回 P1 之前
     * 的无沙盒形态，比让用户对着空屏等强。
     */
    private static final long ATTACH_TIMEOUT_MS = 10_000;

    /**
     * 沙盒装配线程池：固定 2 个 daemon 线程、有界队列。队列满时直接拒（AbortPolicy → 调用侧
     * 当场返回 null），<b>绝不回落到调用线程同步等</b>——那正是要避免的卡顿。
     * 线程里跑的是 HTTP create，超时后不取消任务：让它把容器建完、进 provider 缓存，下一轮可用。
     */
    private final ThreadPoolExecutor attachPool = new ThreadPoolExecutor(
            2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "sandbox-attach");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** 聊天档 scratch 目录只在首次使用时说一次，避免每轮刷日志。 */
    private volatile boolean scratchDirLogged;

    /**
     * 隐式会话「文件/执行意图 → 工作区档」的异步升级：首轮先走聊天档，后台建沙盒；
     * 下一轮 isReady 命中后自动切工作区档。独立单线程 + 有界队列 + 全局在飞上限，
     * 不占问答 attachPool；默认由 chat.asyncWorkspaceUpgrade 关闭。
     */
    private final ThreadPoolExecutor provisionPool = new ThreadPoolExecutor(
            1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
            r -> {
                Thread t = new Thread(r, "sandbox-provision");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicInteger provisionInFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<String> provisioning =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ProvisionerSandboxProvider provider;
    private final com.wenqu.ai.executor.ExecutorRegistry executorRegistry;
    /** 工作区解析：决定沙盒作用域与 workdir（无工作区时退回会话级，行为不变） */
    private final ProjectService projectService;
    /** 隐式会话的工作区后端：本机执行器目录 / 云端沙盒（离线兜底） */
    private final SessionWorkspaceService sessionWorkspace;
    /** 工作区目录开关（默认关，见 scopeOf 的注释） */
    private final ConfigService configService;
    /** 每轮文件改动：本机工作区的框架文件工具要带当前轮身份（执行器据此做改动前捕获） */
    private final ProjectTurnChangesService turnChanges;

    public SandboxService(ProvisionerSandboxProvider provider, ProjectService projectService,
                          ConfigService configService,
                          com.wenqu.ai.executor.ExecutorRegistry executorRegistry,
                          ProjectTurnChangesService turnChanges,
                          SessionWorkspaceService sessionWorkspace) {
        this.provider = provider;
        this.projectService = projectService;
        this.configService = configService;
        this.executorRegistry = executorRegistry;
        this.turnChanges = turnChanges;
        this.sessionWorkspace = sessionWorkspace;
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
    private SandboxScope scopeOf(String sessionId, String uid) {
        // 显式工作区走本机执行器，服务器沙盒只剩无工作区会话；
        // sandbox.userScope=true 时把无工作区会话收成用户级作用域——同一用户的多个会话复用同一个容器
        // （热池/热复用），文件落在用户级 user-data 里共享。默认关，保持旧会话级作用域。
        if (configService.getBoolean("sandbox.userScope", false)) {
            return new SandboxScope(uid, null);
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
        Project local = projectService.projectOfSession(sessionId);
        if (local != null && !Project.SELECTION_IMPLICIT.equals(local.getSelectionStatus())) {
            // 用户建的工作区只走本机执行器：这里再建服务器沙盒会把文件弄成两份
            if (!projectService.isLocal(local)) {
                throw new IllegalStateException("这个工作区还没有绑定本机目录：先在设置里添加源文件夹");
            }
            throw new IllegalStateException("本机工作区没有服务器沙盒：文件与命令都走你那台电脑上的执行器");
        }
        SandboxScope scope = scopeOf(sessionId, uid);
        return new ProvisionerSandboxBackend(provider, scope.scopeId(), uid, scope.workdirPath(),
                true, true, unrestrictedWrites, ProvisionerSandboxBackend.procTagFor(sessionId));
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
    /** 不知道本轮装配结果时的取法（技能清单等旁路）：按「沙盒可用」处理，与既有行为一致。 */
    public AbstractFilesystem frameworkFilesystem(String sessionId, String uid, boolean unrestrictedWrites) {
        return frameworkFilesystem(sessionId, uid, unrestrictedWrites, true);
    }

    public AbstractFilesystem frameworkFilesystem(String sessionId, String uid, boolean unrestrictedWrites,
                                                 boolean sandboxReady) {
        return frameworkFilesystem(sessionId, uid, unrestrictedWrites, sandboxReady, null);
    }

    /**
     * 带智能体记忆域的重载（对外能力第二批）：{@code memoryAgentId} 非空 = 本轮记忆路由到
     * {@code {uid}/agents/{agentId}} 专属根（智能体 memory_mode=专属/叠加，引擎侧已过总闸与三态判定）；
     * 空 = 用户根，与旧签名逐字同行为。
     */
    public AbstractFilesystem frameworkFilesystem(String sessionId, String uid, boolean unrestrictedWrites,
                                                 boolean sandboxReady, String memoryAgentId) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return null;
        }
        try {
            SandboxScope scope = scopeOf(sessionId, uid);
            // 工作区 = 用户本机目录：文件/命令工具直接落到那台电脑上的执行器。
            // 用户建的工作区若还没绑目录，宁可不给文件工具，也不退回服务器沙盒——否则用户以为在改本机，
            // 实际写进了服务器的一份副本。
            Project boundProject = projectService.projectOfSession(sessionId);
            boolean userWorkspace = boundProject != null
                    && !Project.SELECTION_IMPLICIT.equals(boundProject.getSelectionStatus());
            if (userWorkspace && !projectService.isLocal(boundProject)) {
                log.warn("[SANDBOX] 工作区未绑定本机目录，文件工具不可用 project={}", boundProject.getId());
                return null;
            }
            if (boundProject != null && projectService.isLocal(boundProject)) {
                java.util.List<ExecutorFilesystem.Root> roots = new java.util.ArrayList<>();
                for (com.wenqu.ai.model.ProjectRoot r : projectService.rootsOf(boundProject)) {
                    roots.add(new ExecutorFilesystem.Root(r.getAlias(), r.getPath(),
                            r.getPrimaryRoot() != null && r.getPrimaryRoot() == com.wenqu.ai.model.ProjectRoot.PRIMARY));
                }
                if (roots.isEmpty()) {
                    log.warn("[SANDBOX] 本机工作区没有源文件夹，文件工具不可用 project={}", boundProject.getId());
                    return null;
                }
                ExecutorFilesystem executorFs = new ExecutorFilesystem(
                        executorRegistry, boundProject.getExecutorId(), roots, unrestrictedWrites,
                        // 本机工作区：框架的写类调用带当前轮身份，执行器据此做「改动前捕获」（每轮文件改动）。
                        // 供给方调用期读取——文件系统实例可能被框架缓存跨轮复用，不能把 turnId 烘焙进来。
                        () -> turnChanges.currentTurnId(sessionId),
                        // 上一轮轮号：模型第一次写可能抢在服务端 turnStart 前，补拍快照时用它做用户改动对账
                        () -> turnChanges.currentPreviousTurnId(sessionId));
                return memoryRouted(executorFs, uid, memoryAgentId);
            }
            // 隐式会话的本机后端：工作区=用户电脑上的会话目录，不建容器。
            // 与显式本机工作区同形（ExecutorFilesystem + 轮身份），只是根目录由会话解析给出。
            SessionWorkspaceService.Workspace sessionWs = sessionWorkspace == null
                    ? null : sessionWorkspace.resolve(sessionId, uid);
            if (sessionWs != null && sessionWs.local()) {
                java.util.List<ExecutorFilesystem.Root> roots = java.util.List.of(
                        new ExecutorFilesystem.Root("workspace", sessionWs.rootPath(), true));
                ExecutorFilesystem executorFs = new ExecutorFilesystem(
                        executorRegistry, sessionWs.executorId(), roots, unrestrictedWrites,
                        () -> turnChanges.currentTurnId(sessionId),
                        () -> turnChanges.currentPreviousTurnId(sessionId));
                return memoryRouted(executorFs, uid, memoryAgentId);
            }
            if (!sandboxReady) {
                // 本轮装配超时/失败：别把沙盒文件系统交给框架。框架建实例时 WorkspaceManager.validate()
                // 会对它做一次 exists()，这一步恰好卡在仍在后台进行的 create 上（实测 33 秒，
                // provisioner 恢复才放行）。退回本地文件系统 = P1 之前的形态，本轮少几个文件工具。
                log.warn("[SANDBOX] 本轮沙盒未装配成功，框架文件系统退回本地（不挂沙盒）: session={}", sessionId);
                return null;
            }
            ProvisionerSandboxBackend backend = new ProvisionerSandboxBackend(
                    provider, scope.scopeId(), uid, scope.workdirPath(),
                    true, true, unrestrictedWrites, ProvisionerSandboxBackend.procTagFor(sessionId));
            String sandboxId = ProvisionerSandboxProvider.sandboxIdForThread(scope.scopeId(), uid);
            ProvisionerSandboxFilesystem sandboxFs = new ProvisionerSandboxFilesystem(backend, sandboxId);
            return memoryRouted(sandboxFs, uid, memoryAgentId);
        } catch (Exception e) {
            log.warn("[SANDBOX] 框架文件系统装配失败 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 聊天档文件系统：宿主上的会话级临时目录，不建容器、不执行命令。
     *
     * <p>为什么需要它：没有工作区的普通问答不应该为了「可能用到文件工具」而先付 3 秒建沙盒。
     * 但文件工具本身仍要可用（静态 demo / 清单 / 表格都能直接落盘），所以给一个受限的本地目录：
     * <ul>
     *   <li>根目录按 {@code user-data/chat-scratch/{uid}/{sessionId}} 隔离，写操作被
     *       {@link LocalFilesystem} 的 SANDBOXED 模式锚定在根内；</li>
     *   <li>MEMORY.md 与 memory/ 仍路由到 uid 级宿主目录，记忆注入/落盘行为不变；</li>
     *   <li>它不是 {@code AbstractSandboxFilesystem}，框架的 ShellExecuteTool 因此不会注册——
     *       「聊天档不执行命令」不是靠执行期拒绝，而是压根没有这把工具。</li>
     * </ul>
     * 需要命令/构建的会话走工作区档（见 {@code FrameworkSessions} 的沙盒分支）。
     */
    public AbstractFilesystem chatScratchFilesystem(String sessionId, String uid) {
        return chatScratchFilesystem(sessionId, uid, null);
    }

    /** 带智能体记忆域的重载（对外能力第二批）：memoryAgentId 非空 = 记忆路由到智能体专属根 */
    public AbstractFilesystem chatScratchFilesystem(String sessionId, String uid, String memoryAgentId) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return null;
        }
        try {
            Path root = chatScratchRoot().resolve(uid).resolve(sessionId);
            Files.createDirectories(root);
            if (!scratchDirLogged) {
                scratchDirLogged = true;
                log.info("[SANDBOX] 聊天档 scratch 目录: {}（多副本请确保为共享卷）",
                        chatScratchRoot().toAbsolutePath());
            }
            LocalFilesystem scratch = new LocalFilesystem(root, true, 64);
            Path memRoot = memoryRoot(uid, memoryAgentId);
            AbstractFilesystem memoryMd = new MemoryCappedFilesystem(
                    memRoot, MEMORY_MAX_FILE_MB, MemoryBudget.maxChars(configService));
            AbstractFilesystem memoryDir = new LocalFilesystem(
                    memRoot.resolve("memory"), true, MEMORY_MAX_FILE_MB);
            return new CompositeFilesystem(scratch, Map.of(
                    "/MEMORY.md", memoryMd,
                    "/memory/", memoryDir));
        } catch (Exception e) {
            // 文件系统装配失败不该让整轮失败：退回无文件工具，正文问答照常
            log.warn("[SANDBOX] 聊天档临时目录装配失败，本轮不挂文件工具 (session={}): {}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /** 每用户沙盒租约：超过 sandbox.maxPerUser 时循环回收该用户最久未用的连接，给当前作用域腾位。 */
    private void ensureUserLease(String uid, String currentScope) {
        int max = configService.getInt("sandbox.maxPerUser", 0);
        if (max <= 0) return;
        int count = provider.connectionsOf(uid).size();
        if (count < max) return;
        int evicted = 0;
        while (count >= max && provider.evictOldestForUser(uid, currentScope)) {
            count--;
            evicted++;
        }
        if (evicted > 0) {
            log.info("[SANDBOX] 用户 {} 沙盒租约达上限 {}，回收 {} 个最久未用连接",
                    uid, max, evicted);
        }
    }

    /** 隐式会话的沙盒是否已就绪（异步升级的下一轮判据）。只看已有连接，不创建、不阻塞。 */
    public boolean isReady(String sessionId, String uid) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return false;
        }
        try {
            SandboxScope scope = scopeOf(sessionId, uid);
            // 只查本副本已建好的连接：不取 provider 的 scope 锁——后台 create 正持着它，
            // 这里再 get/等锁就会把「异步升级」又变成同步等待。
            return provider.isConnected(scope.scopeId(), uid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 隐式会话命中文件/执行意图时调用：后台把沙盒建好，本轮先走聊天档不阻塞。
     * 下一轮 {@link #isReady} 命中后由引擎切工作区档；失败只记日志，下一轮仍可重试。
     */
    public void provisionAsync(String sessionId, String uid) {
        if (!configService.getBoolean("chat.asyncWorkspaceUpgrade", false)) return;
        submitProvision(sessionId, uid, "异步升级");
    }

    /**
     * 热池预热：把该用户的用户级沙盒提前建好。
     *
     * <p>必须 {@code sandbox.userScope=true}——作用域是 uid，之后同一用户任意无工作区会话都会命中
     * 这个容器；否则预建出来的是会话级容器，没人能复用，纯浪费。伪会话语义（{@code pool:<uid>}）
     * 只用来占位日志/进程标记，不会落到会话表。
     */
    public void prewarmUser(String uid) {
        if (!configService.getBoolean("sandbox.userScope", false)) return;
        if (uid == null || uid.isBlank()) return;
        submitProvision("pool:" + uid, uid, "热池预热");
    }

    /** 异步升级与热池预热共用的后台装配：单线程有界队列 + 全局在飞上限，去重键为 会话|用户。 */
    private void submitProvision(String sessionId, String uid, String label) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) return;
        String key = sessionId + "|" + uid;
        if (!provisioning.add(key)) return;
        int max = Math.max(1, ConfigDefaults.CHAT_ASYNC_WORKSPACE_MAX);
        if (provisionInFlight.incrementAndGet() > max) {
            provisionInFlight.decrementAndGet();
            provisioning.remove(key);
            return;
        }
        try {
            provisionPool.execute(() -> {
                long beganAt = System.currentTimeMillis();
                try {
                    SandboxContext ctx = frameworkSandboxContext(sessionId, uid, false);
                    if (ctx != null) {
                        log.info("[SANDBOX] {}装配完成 session={} 用时={}ms",
                                label, sessionId, System.currentTimeMillis() - beganAt);
                    } else {
                        log.warn("[SANDBOX] {}未完成（下一轮仍可重试） session={}", label, sessionId);
                    }
                } catch (Exception e) {
                    log.warn("[SANDBOX] {}装配失败（下一轮仍可重试） session={}: {}",
                            label, sessionId, e.toString());
                } finally {
                    provisionInFlight.decrementAndGet();
                    provisioning.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            provisionInFlight.decrementAndGet();
            provisioning.remove(key);
            log.info("[SANDBOX] {}队列已满，跳过本次装配 session={}", label, sessionId);
        }
    }

    /**
     * 清理聊天档宿主临时目录：按会话目录的最后修改时间，删掉超过
     * {@code cleanup.tempFileRetentionDays}（临时件档）的整棵目录（含用户空壳）。返回删除的会话目录数。
     *
     * <p>为什么必须定时清：聊天档不建容器，但每会话仍会落一个本地目录；不回收就会随会话数
     * 无限增长。只删「超期且非最近写入」的目录，活动会话在写文件时 mtime 会被刷新。
     */
    public int cleanupChatScratch() {
        int days = configService.getInt("cleanup.tempFileRetentionDays",
                ConfigDefaults.CLEANUP_TEMP_FILE_RETENTION_DAYS);
        if (days <= 0) {
            return 0;
        }
        Path root = chatScratchRoot();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 3600_000L;
        int removed = 0;
        try (Stream<Path> uids = Files.list(root)) {
            for (Path uidDir : uids.toList()) {
                if (!Files.isDirectory(uidDir)) continue;
                try (Stream<Path> sessions = Files.list(uidDir)) {
                    for (Path sessionDir : sessions.toList()) {
                        if (!Files.isDirectory(sessionDir)) continue;
                        long mtime = Files.getLastModifiedTime(sessionDir).toMillis();
                        if (mtime > cutoff) continue;
                        deleteRecursively(sessionDir);
                        removed++;
                    }
                }
                try (Stream<Path> left = Files.list(uidDir)) {
                    if (left.findAny().isEmpty()) Files.deleteIfExists(uidDir);
                }
            }
        } catch (Exception e) {
            log.warn("[SANDBOX] 聊天档临时目录清理失败（下次节拍重试）: {}", e.toString());
        }
        if (removed > 0) {
            log.info("[SANDBOX] 聊天档临时目录清理 {} 个（保留 {} 天）", removed, days);
        }
        return removed;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
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
    private AbstractFilesystem memoryRouted(
            io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem sandboxFs, String uid,
            String memoryAgentId) {
        try {
            Path base = memoryRoot(uid, memoryAgentId);
            // MEMORY.md 走带硬上限的文件系统：框架的 consolidation prompt 只是建议，
            // 模型可以写到 57 KB；写入落盘时按 MEMORY.md 容量上限裁掉，注入才不会只剩头尾。
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
        return memoryRoot(uid, null);
    }

    /** 记忆根：memoryAgentId 非空 = 智能体专属根 {stateDir}/{uid}/agents/{agentId}（与 MemoryPaths.agentRoot 同一口径） */
    private Path memoryRoot(String uid, String memoryAgentId) {
        String raw = configService.get(KEY_STATE_DIR);
        if (raw == null || raw.isBlank()) raw = STATE_DIR_DEFAULT;
        Path p = Path.of(raw).resolve(uid);
        if (memoryAgentId != null && !memoryAgentId.isBlank()) {
            // agentId 是平台生成的标识串（UUID/雪花），与 MemoryPaths.requireUid 同一白名单防 ../ 穿越
            if (!memoryAgentId.matches("[A-Za-z0-9_@.\\-]+")) {
                throw new IllegalArgumentException("非法的智能体记忆目录: " + memoryAgentId);
            }
            p = p.resolve("agents").resolve(memoryAgentId);
        }
        return p;
    }

    /** 聊天档宿主临时目录根：多副本部署必须把它指到共享卷，否则同一会话切副本会看不到文件。 */
    private Path chatScratchRoot() {
        String raw = configService.get("chat.scratchDir");
        if (raw == null || raw.isBlank()) raw = "user-data/chat-scratch";
        return Path.of(raw);
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
            // 隐式会话的本机后端：文件与命令走执行器，不建服务器容器。
            SessionWorkspaceService.Workspace sessionWs = sessionWorkspace == null
                    ? null : sessionWorkspace.resolve(sessionId, uid);
            if (sessionWs != null && sessionWs.local()) {
                return null;
            }
            // 显式工作区走本机执行器（或尚未绑目录），不需要服务器沙盒；此前这里仍会建一个容器，
            // 文件却全部走执行器，等于白建。直接返回 null，FrameworkSessions 的 sandboxAttached 会置 false。
            Project bound = projectService.projectOfSession(sessionId);
            if (bound != null && !Project.SELECTION_IMPLICIT.equals(bound.getSelectionStatus())) {
                return null;
            }
            SandboxScope scope = scopeOf(sessionId, uid);
            // 每用户租约上限：当前作用域还没有连接才核算，已有连接直接复用，不能因为一句普通轮
            // 把用户另一个正在用的沙盒顶掉。超出上限按 LRU 回收最久未用的那条（文件在宿主卷上不丢）。
            if (!provider.isConnected(scope.scopeId(), uid)) {
                ensureUserLease(uid, scope.scopeId());
            }
            ProvisionerSandboxBackend backend = new ProvisionerSandboxBackend(
                    provider, scope.scopeId(), uid, scope.workdirPath(),
                    true, true, unrestrictedWrites, ProvisionerSandboxBackend.procTagFor(sessionId));
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
     * {@link #frameworkSandboxContext} 的有界版：聊天开轮只等 {@link #ATTACH_TIMEOUT_MS}。
     *
     * <p>超时不取消任务——装配线程继续把容器建出来并进 provider 缓存，下一轮命中即成。
     * 这样「基础设施慢/卡」的代价只落在第一轮少几个文件工具上，而不是把整条问答按在那里。
     */
    public SandboxContext frameworkSandboxContextBounded(String sessionId, String uid, boolean unrestrictedWrites) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return null;
        }
        Future<SandboxContext> future;
        try {
            future = attachPool.submit(() -> frameworkSandboxContext(sessionId, uid, unrestrictedWrites));
        } catch (RejectedExecutionException e) {
            log.warn("[SANDBOX] 沙盒装配队列已满，本轮不挂沙盒: session={}", sessionId);
            return null;
        }
        try {
            return future.get(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 打标：后台那次 create 还在飞，本轮其余沙盒取用（工具调用/框架 build）直接失败，
            // 不要再去抢它持有的 scope 锁；create 返回时标记自动摘掉。
            provider.markAssemblyPending(sessionId, uid);
            log.warn("[SANDBOX] 沙盒装配超过 {}ms，本轮先不挂沙盒（装配继续在后台，下一轮可用）: session={}",
                    ATTACH_TIMEOUT_MS, sessionId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("[SANDBOX] 沙盒装配失败，本轮不挂沙盒: session={} {}", sessionId, e.getCause());
            return null;
        }
    }

    /** 停服务时把还在跑的装配线程放掉（daemon 不拦退出，这里只是让热重启/测试干净）。 */
    @jakarta.annotation.PreDestroy
    public void shutdownAttachPool() {
        attachPool.shutdownNow();
        provisionPool.shutdownNow();
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
            SandboxScope scope = scopeOf(sessionId, uid);
            return provider.get(scope.scopeId(), uid, false, true, scope.workdirPath()) != null;
        } catch (Exception e) {
            log.debug("[SANDBOX] 探测沙盒失败 (session={}): {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 取「不创建」的沙盒 backend：供浏览端点使用。沙盒未运行时首次连接即抛
     * RuntimeException（sandbox is unavailable）——调用方自行转成可读状态，不产生容器。
     * <p>刻意<b>不带进程归属标记</b>：这条口子只读不跑用户的命令，「后台进程」那一路走的也是
     * {@code executeUntracked}（扫描进程自己出现在扫描结果里，那张卡就永远关不掉）。
     */
    public ProvisionerSandboxBackend backendIfPresent(String sessionId, String uid) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("沙盒需要会话上下文（sessionId）");
        }
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("沙盒需要用户上下文（uid）");
        }
        Project local = projectService.projectOfSession(sessionId);
        if (local != null && !Project.SELECTION_IMPLICIT.equals(local.getSelectionStatus())) {
            // 用户建的工作区只走本机执行器：这里再建服务器沙盒会把文件弄成两份
            if (!projectService.isLocal(local)) {
                throw new IllegalStateException("这个工作区还没有绑定本机目录：先在设置里添加源文件夹");
            }
            throw new IllegalStateException("本机工作区没有服务器沙盒：文件与命令都走你那台电脑上的执行器");
        }
        SandboxScope scope = scopeOf(sessionId, uid);
        return new ProvisionerSandboxBackend(provider, scope.scopeId(), uid, scope.workdirPath(), false, true);
    }

    /**
     * 本进程里该用户<b>还活着</b>的沙盒 backend（不创建、不探 provisioner，只读连接缓存）。
     *
     * <p>给「侧栏后台进程标记」这类要廉价轮询的批量取数用：没有活容器时返回空表，一个请求都不发。
     * 每个 backend 的工作目录按<b>缓存里那份连接</b>回填——作用域一旦定了工作区，工作目录就是它的一部分，
     * 传错会让 provider 认成「同一个作用域两个身份」直接抛错。
     *
     * <p><b>边界</b>：连接缓存在进程内 ⇒ 多副本部署下只看得到本副本建过/发现过的容器。
     */
    public List<ProvisionerSandboxBackend> liveBackends(String uid) {
        List<ProvisionerSandboxBackend> out = new ArrayList<>();
        for (SandboxConnection conn : provider.liveConnections(uid)) {
            out.add(new ProvisionerSandboxBackend(provider, conn.getThreadId(), uid,
                    conn.getWorkdirPath(), false, true));
        }
        return out;
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
        SandboxScope scope = scopeOf(sessionId, uid);
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
