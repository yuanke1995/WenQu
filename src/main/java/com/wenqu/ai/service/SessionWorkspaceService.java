package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.executor.ExecutorFiles;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.model.Session;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 隐式会话（侧栏「会话」）的工作区后端解析：本机执行器目录 / 云端沙盒。
 *
 * <p>形态定案（2026-11-06）：工作区 = 用户自己的电脑。隐式会话不再默认建服务器容器，
 * 而是在执行器上给这条会话一个目录（{@code <执行器根>/WenQu/workspaces/<日期>-<标题或短id>}），
 * 文件工具经执行器执行；只有执行器不在线时才退回云端沙盒（文件在服务器，界面必须说明）。
 *
 * <p>解析结果首轮落库（{@code c_ai_session.ws_*}）后钉住：目录跟着会话走，不因为本轮换了
 * 哪台在线设备而搬文件。显式工作区（用户建的 selectable）完全不经过这里，沿用原有执行器路径。
 */
@Service
public class SessionWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceService.class);

    public static final String BACKEND_LOCAL = "local";
    public static final String BACKEND_CLOUD = "cloud";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SLUG_MAX = 24;
    private static final String DEFAULT_ROOT_BASE = "WenQu/workspaces";
    /** 建会话目录的上限：执行器在线但不响应时，不能把开轮按住（各次执行器调用自身是 60s 超时）。 */
    private static final long ENSURE_TIMEOUT_SECONDS = 5;

    /**
     * 一条会话的工作区后端。
     *
     * @param backend        local = 本机执行器目录；cloud = 服务器沙盒
     * @param executorId     local 时的执行器 id，cloud/未解析为 null
     * @param rootPath       local 时执行器上的绝对目录，cloud 为 null
     * @param fallbackNotice 本机本可用但这一轮退回了云端时的用户可见说明；null = 无需提示
     */
    public record Workspace(String backend, String executorId, String rootPath, String fallbackNotice) {

        public boolean local() {
            return BACKEND_LOCAL.equals(backend);
        }
    }

    private final SessionMapper sessionMapper;
    private final ProjectService projectService;
    private final ExecutorRegistry executorRegistry;
    private final com.wenqu.ai.executor.ExecutorService executorService;
    private final ExecutorFiles executorFiles;
    private final ConfigService configService;
    /** 执行器上的工作根（家目录）：每条会话都要拿它拼路径，按执行器缓存，失败即失效。 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> homeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SessionWorkspaceService(SessionMapper sessionMapper, ProjectService projectService,
                                   ExecutorRegistry executorRegistry,
                                   com.wenqu.ai.executor.ExecutorService executorService,
                                   ExecutorFiles executorFiles,
                                   ConfigService configService) {
        this.sessionMapper = sessionMapper;
        this.projectService = projectService;
        this.executorRegistry = executorRegistry;
        this.executorService = executorService;
        this.executorFiles = executorFiles;
        this.configService = configService;
    }

    /**
     * 解析这条会话的工作区后端（会按需在执行器上建目录并把结果落库）。
     *
     * @return {@code null} = 显式工作区（不归本服务管，调用方沿用原有本机工作区路径）；
     *         否则总是给出一档后端：本机执行器在线走 local，离线/失败走 cloud
     */
    public Workspace resolve(String sessionId, String uid) {
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) return null;
        Project project = projectService.projectOfSession(sessionId);
        if (project != null && !Project.SELECTION_IMPLICIT.equals(project.getSelectionStatus())) {
            return null;
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) return null;
        if (BACKEND_LOCAL.equals(session.getWsBackend())
                && session.getWsRoot() != null && !session.getWsRoot().isBlank()) {
            if (executorRegistry.online(session.getWsExecutorId())) {
                return new Workspace(BACKEND_LOCAL, session.getWsExecutorId(), session.getWsRoot(), null);
            }
            return new Workspace(BACKEND_CLOUD, null, null, FALLBACK_OFFLINE);
        }
        if (!configService.getBoolean("workspace.sessionLocal", true)) {
            return new Workspace(BACKEND_CLOUD, null, null, null);
        }
        String executorId = firstOnlineExecutorOf(uid);
        if (executorId == null) {
            // 没配过执行器的用户走云端是常态，不该每轮弹「本机离线」；配过但此刻不在线才提示
            boolean paired = !executorService.list(uid).isEmpty();
            return new Workspace(BACKEND_CLOUD, null, null, paired ? FALLBACK_OFFLINE : null);
        }
        try {
            String root = ensureRootBounded(session, executorId);
            persist(sessionId, executorId, root);
            log.info("[WORKSPACE] 会话目录就绪 uid={} sid={} executor={} root={}",
                    uid, sessionId, executorId, root);
            return new Workspace(BACKEND_LOCAL, executorId, root, null);
        } catch (Exception e) {
            log.warn("[WORKSPACE] 本机会话目录准备失败，本轮回退云端沙盒 sid={} executor={}: {}",
                    sessionId, executorId, e.toString());
            return new Workspace(BACKEND_CLOUD, null, null, FALLBACK_OFFLINE);
        }
    }

    /**
     * 建目录整体限时：执行器在线键还在但进程卡死时，各次 ops 自身是 60s 超时，
     * 不能把开轮路径按在那里（本轮退回云端，下一轮再试）。
     */
    private String ensureRootBounded(Session session, String executorId) throws Exception {
        java.util.concurrent.CompletableFuture<String> f =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> ensureRoot(session, executorId));
        try {
            return f.get(ENSURE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new IllegalStateException("建会话目录超时");
        }
    }

    /** 已经落库的本机后端（不探测、不建目录）：文件栏/预览取数用，避免「只是打开一条问答会话就建目录」。 */
    public Workspace existingLocal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !BACKEND_LOCAL.equals(session.getWsBackend())
                || session.getWsRoot() == null || session.getWsRoot().isBlank()) {
            return null;
        }
        return new Workspace(BACKEND_LOCAL, session.getWsExecutorId(), session.getWsRoot(), null);
    }

    /** 已钉住本机后端且执行器此刻在线：文件栏/预览只有在这是真的时才走执行器。 */
    public boolean localOnline(String sessionId) {
        Workspace ws = existingLocal(sessionId);
        return ws != null && executorRegistry.online(ws.executorId());
    }

    /** 本轮是否已经钉住本机后端（只看库，不建目录）：分档粘性判据用。 */
    public boolean pinnedLocal(String sessionId) {
        return existingLocal(sessionId) != null;
    }

    /** 已钉住的本机工作区根（只看库，不建目录）：提示词里要如实告诉模型它在哪台机器上跑。 */
    public String pinnedLocalRoot(String sessionId) {
        Workspace ws = existingLocal(sessionId);
        return ws == null ? null : ws.rootPath();
    }

    /**
     * 本机后端是否已就绪且执行器在线（不建目录）：问答链路的「文件系统可用」判据。
     * 与 {@link #resolve} 的区别是绝不产生副作用。
     */
    public boolean localReady(String sessionId) {
        Workspace ws = existingLocal(sessionId);
        return ws != null && executorRegistry.online(ws.executorId());
    }

    private static final String FALLBACK_OFFLINE =
            "本机执行器不在线，本轮文件改在云端沙盒（文件不在你的电脑上）；启动 wenqu-agent 后下一轮恢复本机目录";

    private String firstOnlineExecutorOf(String uid) {
        // 查库而不是内存表：服务刚重启时执行器还没轮询回来，内存表为空，会把本机静默判成云端
        for (Map<String, Object> row : executorService.list(uid)) {
            Object id = row.get("id");
            if (id != null && executorRegistry.online(String.valueOf(id))) {
                return String.valueOf(id);
            }
        }
        return null;
    }

    private String ensureRoot(Session session, String executorId) {
        String home = executorHome(executorId);
        String base = configService.get("workspace.sessionRootBase");
        if (base == null || base.isBlank()) base = DEFAULT_ROOT_BASE;
        String relative = trimSlashes(base) + "/" + dirNameOf(session);
        String cur = trimTrailing(home);
        for (String seg : relative.split("/")) {
            if (seg == null || seg.isBlank()) continue;
            String parent = cur;
            cur = cur + "/" + seg;
            // 已存在视为成功：不再先 stat（每多一次往返就多一份开轮等待）
            executorFiles.ensureDir(executorId, List.of(pseudoRoot(parent)), cur);
        }
        return cur;
    }

    /** 执行器上的工作根（默认主目录）。执行器 `list` 不带 path 时回的正是它。 */
    private String executorHome(String executorId) {
        String cached = homeCache.get(executorId);
        if (cached != null) return cached;
        Map<String, Object> data = executorFiles.call(executorId, List.of(), "list", Map.of(), false).data();
        Object path = data == null ? null : data.get("path");
        if (path == null || String.valueOf(path).isBlank()) {
            homeCache.remove(executorId);
            throw new IllegalStateException("执行器没有返回工作根目录");
        }
        String home = String.valueOf(path);
        homeCache.put(executorId, home);
        return home;
    }

    /**
     * 目录名：{@code yyyyMMdd-{标题或 session}-{短 id}}。
     * 标题只决定好不好找，唯一性由短 id 兜底；标题以后改了不迁移目录。
     */
    static String dirNameOf(Session session) {
        String day = (session.getCreateTime() == null
                ? java.time.LocalDate.now()
                : session.getCreateTime().toLocalDate()).format(DAY);
        return day + "-" + slug(session.getTitle()) + "-" + shortId(session.getId());
    }

    private static String slug(String title) {
        String s = title == null ? "" : title.trim();
        if (s.isEmpty() || "新对话".equals(s)) return "session";
        s = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        s = s.replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        if (s.length() > SLUG_MAX) s = s.substring(0, SLUG_MAX).replaceAll("-+$", "");
        return s.isEmpty() ? "session" : s;
    }

    private static String shortId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "unknown";
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    private void persist(String sessionId, String executorId, String root) {
        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getId, sessionId)
                .set(Session::getWsBackend, BACKEND_LOCAL)
                .set(Session::getWsExecutorId, executorId)
                .set(Session::getWsRoot, root));
    }

    private static ProjectRoot pseudoRoot(String path) {
        ProjectRoot r = new ProjectRoot();
        r.setAlias("workspace");
        r.setPath(path);
        r.setPrimaryRoot(ProjectRoot.PRIMARY);
        return r;
    }

    private static String trimTrailing(String p) {
        String s = p == null ? "" : p.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String trimSlashes(String p) {
        String s = trimTrailing(p);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }
}
