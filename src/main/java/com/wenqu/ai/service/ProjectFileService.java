package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorFiles;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.sandbox.SandboxPaths;
import com.wenqu.ai.util.PreviewTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 工作区文件：<b>用户本机目录</b>上的浏览、预览、下载，以及工作区记忆的读写。
 *
 * <p><b>文件在哪</b>：工作区 = 一台本机执行器上的一个或多个源文件夹（{@link ProjectRoot}）。
 * 服务器不挂载、不缓存、不落副本；每个动作都是一次执行器调用，路径由服务端按虚拟路径解析、
 * 再由执行器独立校验落在某个源文件夹内（两道围栏）。因此本类没有「宿主目录」概念，也没有
 * {@code Files.list} / {@code Files.move} 这类本地文件系统调用——只有会话沙盒文件清单
 * （没挂工作区的「会话」栏）仍读服务器目录。
 *
 * <p><b>虚拟路径</b>：{@code alias} 或 {@code alias/相对路径}。首段是源文件夹的别名（工作区内唯一），
 * 文件页顶层的每一项就是这些别名。对外 URL、预览 token、下载参数全用这一份，重启/换机器不变。
 *
 * <p><b>边界</b>：列表不显示隐藏文件（点开头、{@code node_modules}、{@code __pycache__}）；
 * 归档区一概不写。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ProjectFileService {

    private static final int MAX_ENTRIES = 300;
    private static final int MAX_TREE_DEPTH = 8;
    private static final int DOWNLOAD_CHUNK = 1024 * 1024;

    private static final String VIEW_PATH_PREFIX = "/ai/project-files/";
    private static final String TOKEN_SEP = "\n";
    private static final String SESSION_VIEW_MARK = "s";

    private final ProjectService projectService;
    private final ConfigService configService;
    private final ImageUrlSigner imageUrlSigner;
    private final ExecutorFiles executorFiles;

    public ProjectFileService(ProjectService projectService, ConfigService configService,
                              ImageUrlSigner imageUrlSigner,
                              ExecutorFiles executorFiles) {
        this.projectService = projectService;
        this.configService = configService;
        this.imageUrlSigner = imageUrlSigner;
        this.executorFiles = executorFiles;
    }

    // ==================== 浏览 ====================

    /**
     * 列出一层内容。
     *
     * @param dir 虚拟路径：空=所有源文件夹；{@code alias}=某个源的根；{@code alias/a/b}=源内子目录
     */
    public Map<String, Object> listWithMeta(String uid, String projectId, String dir) {
        Project p = projectService.detail(uid, projectId);
        List<ProjectRoot> roots = requireRoots(p);
        Map<String, Object> m = new LinkedHashMap<>();

        List<Map<String, Object>> entries = new ArrayList<>();
        int totalHere;
        String echoDir = "";
        if (dir == null || dir.isBlank()) {
            totalHere = roots.size();
            for (ProjectRoot r : roots) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("kind", "dir");
                e.put("name", r.getAlias());
                e.put("path", r.getAlias());
                e.put("size", 0L);
                e.put("mtime", 0L);
                e.put("isRoot", true);
                e.put("absPath", r.getPath());
                entries.add(e);
            }
        } else {
            Target at = target(p, roots, dir, false);
            echoDir = at.virtualPath;
            String abs = abs(at.root, at.rel);
            Map<String, Object> st = executorFiles.stat(executorId(p), roots, abs);
            if (!Boolean.TRUE.equals(st.get("isDir"))) {
                throw new IllegalArgumentException("不是一个文件夹：" + dir);
            }
            List<Map<String, Object>> raw = executorFiles.list(executorId(p), roots, abs);
            List<Map<String, Object>> dirs = new ArrayList<>();
            List<Map<String, Object>> files = new ArrayList<>();
            for (Map<String, Object> one : raw) {
                String name = str(one.get("name"));
                if (!shownName(name)) continue;
                if (Boolean.TRUE.equals(one.get("isSymlink"))) continue;
                boolean isDir = Boolean.TRUE.equals(one.get("isDir"));
                Map<String, Object> e = new LinkedHashMap<>();
                String rel = at.rel.isEmpty() ? name : at.rel + "/" + name;
                e.put("kind", isDir ? "dir" : "file");
                e.put("name", name);
                e.put("path", at.root.getAlias() + "/" + rel);
                e.put("size", isDir ? 0L : asLong(one.get("size")));
                e.put("mtime", asLong(one.get("mtimeMs")));
                if (!isDir) addViewUrl(e, p.getId(), at.root.getAlias() + "/" + rel, asLong(one.get("size")));
                (isDir ? dirs : files).add(e);
            }
            dirs.sort(Comparator.comparing(x -> str(x.get("name")).toLowerCase(Locale.ROOT)));
            files.sort(Comparator.comparingLong((Map<String, Object> x) -> asLong(x.get("mtime"))).reversed());
            entries.addAll(dirs);
            entries.addAll(files);
            totalHere = entries.size();
        }

        m.put("dir", echoDir);
        m.put("entries", entries.size() > MAX_ENTRIES ? new ArrayList<>(entries.subList(0, MAX_ENTRIES)) : entries);
        m.put("total", totalHere);
        m.put("truncated", totalHere > MAX_ENTRIES);
        return m;
    }

    /** 会话所属工作区的文件简报：随工作背景段注入 system prompt。任何失败返回 null。 */
    public String promptBriefOfSession(String sessionId) {
        try {
            Project project = projectService.projectOfSession(sessionId);
            if (project == null || !projectService.isLocal(project)) return null;
            List<ProjectRoot> roots = projectService.rootsOf(project);
            if (roots.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("\n\n【本机工作区】这个工作区绑定的是你电脑上的真实目录，文件工具直接读写它们：");
            List<String> parts = new ArrayList<>();
            for (ProjectRoot r : roots) {
                String head = r.getAlias() + "（" + r.getPath() + "）";
                List<String> names = new ArrayList<>();
                try {
                    List<Map<String, Object>> raw = executorFiles.list(executorId(project), roots, r.getPath());
                    for (Map<String, Object> e : raw) {
                        String name = str(e.get("name"));
                        if (name.isBlank() || !shownName(name)) continue;
                        names.add(Boolean.TRUE.equals(e.get("isDir")) ? name + "/" : name);
                        if (names.size() >= BRIEF_MAX_ENTRIES) break;
                    }
                } catch (Exception ignore) {
                    // 单个源读不到不影响其他源；这一条只报目录本身
                }
                parts.add(head + (names.isEmpty() ? "" : "，顶层：" + String.join("、", names)));
            }
            sb.append(String.join("；", parts));
            sb.append("。需要哪个文件的内容，用文件工具按路径读取即可；写/改/删只能在这些目录内。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[PROJECT] 本机工作区文件简报构建失败（本轮不注入）session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private static final int BRIEF_MAX_ENTRIES = 20;

    private void addViewUrl(Map<String, Object> e, String projectId, String virtualPath, long size) {
        String ext = PreviewTypes.extOf(str(e.get("name")));
        if (PreviewTypes.viewContentType(ext) != null && PreviewTypes.viewCapError(ext, size) == null) {
            e.put("viewUrl", imageUrlSigner.signUrlRequired(VIEW_PATH_PREFIX + encodeViewToken(projectId, virtualPath)));
        }
    }

    // ==================== 预览 / 读文本 ====================

    /** 预览结果：内容类型 + 字节 + 文件名。 */
    public record ViewFile(String contentType, byte[] bytes, String name) {
    }

    /**
     * 按签名 token 取文件（浏览器顶层导航，没有登录态，凭据是地址自己的 HMAC 签名）。
     * 工作区 token 的首段是 projectId，剩余段是虚拟路径；会话沙盒 token 首段是 {@code s}。
     */
    public ViewFile openView(String token) {
        String[] parts = decodeViewToken(token);
        if (SESSION_VIEW_MARK.equals(parts[0])) {
            String rest = parts[1];
            int sep = rest.indexOf(TOKEN_SEP);
            if (sep <= 0 || sep == rest.length() - 1) throw new IllegalArgumentException("预览地址无效");
            String sessionId = rest.substring(0, sep);
            String owner = projectService.uidOfSession(sessionId);
            if (owner == null || owner.isBlank()) throw new IllegalArgumentException("会话不存在或已删除");
            Project p = projectService.projectOfSession(sessionId);
            if (p != null && projectService.isLocal(p)) {
                return viewFromLocal(p, rest.substring(sep + 1));
            }
            Path file = resolveIn(sessionBase(owner, sessionId), rest.substring(sep + 1));
            return viewFromServer(file);
        }
        Project p = projectService.byId(parts[0]);
        if (p == null || !projectService.isLocal(p)) throw new IllegalArgumentException("工作区不存在或已删除");
        return viewFromLocal(p, parts[1]);
    }

    private ViewFile viewFromLocal(Project p, String virtualPath) {
        List<ProjectRoot> roots = requireRoots(p);
        Target t = target(p, roots, virtualPath, false);
        String abs = abs(t.root, t.rel);
        Map<String, Object> st = executorFiles.stat(executorId(p), roots, abs);
        if (Boolean.TRUE.equals(st.get("isDir"))) throw new IllegalArgumentException("这是一个文件夹，不能预览");
        String name = str(st.get("name"));
        long size = asLong(st.get("size"));
        String ext = PreviewTypes.extOf(name);
        String contentType = PreviewTypes.viewContentType(ext);
        if (contentType == null) throw new IllegalArgumentException("该文件类型不支持预览，请下载后查看");
        String capError = PreviewTypes.viewCapError(ext, size);
        if (capError != null) throw new IllegalArgumentException(capError);
        if (size > 10L * 1024 * 1024) throw new IllegalArgumentException("文件太大，不能在这里预览，请下载后查看");
        ExecutorFiles.ReadBytes bytes = executorFiles.readRange(
                executorId(p), roots, abs, 0, (int) size);
        return new ViewFile(contentType, bytes.bytes(), name);
    }

    private ViewFile viewFromServer(Path file) {
        String name = file.getFileName().toString();
        String ext = PreviewTypes.extOf(name);
        String contentType = PreviewTypes.viewContentType(ext);
        if (contentType == null) throw new IllegalArgumentException("该文件类型不支持预览，请下载后查看");
        String capError = PreviewTypes.viewCapError(ext, sizeOf(file));
        if (capError != null) throw new IllegalArgumentException(capError);
        try {
            return new ViewFile(contentType, Files.readAllBytes(file), name);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败：" + e.getMessage());
        }
    }

    /** 读一个文件的文本原文（预览台「读字」档）。 */
    public Map<String, Object> readText(String uid, String projectId, String relPath) {
        Project p = projectService.detail(uid, projectId);
        List<ProjectRoot> roots = requireRoots(p);
        Target t = target(p, roots, relPath, false);
        String abs = abs(t.root, t.rel);
        Map<String, Object> st = executorFiles.stat(executorId(p), roots, abs);
        if (Boolean.TRUE.equals(st.get("isDir"))) throw new IllegalArgumentException("这是一个文件夹，不能按文本预览");
        String name = str(st.get("name"));
        String ext = PreviewTypes.extOf(name);
        if (!PreviewTypes.isReadable(ext)) {
            throw new IllegalArgumentException("该文件类型不按文本预览，请下载后查看");
        }
        long size = asLong(st.get("size"));
        int want = (int) Math.min(size, PreviewTypes.TEXT_BODY_CAP);
        byte[] buf = executorFiles.readRange(executorId(p), roots, abs, 0, want).bytes();
        boolean truncated = size > buf.length;
        String content = new String(buf, StandardCharsets.UTF_8);
        if (truncated && !content.isEmpty() && content.charAt(content.length() - 1) == '\uFFFD') {
            content = content.substring(0, content.length() - 1);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filename", name);
        m.put("ext", ext);
        m.put("size", size);
        m.put("cap", (long) PreviewTypes.TEXT_BODY_CAP);
        m.put("truncated", truncated);
        m.put("content", content);
        return m;
    }

    // ==================== 工作区级记忆（独立于用户级记忆，存工作区根目录 .wenqu/MEMORY.md） ====================

    /** 工作区记忆文件名：放在工作区主根下的隐藏子目录，不污染项目真实文件、也易忽略。 */
    public static final String WORKSPACE_MEMORY_FILE = ".wenqu/MEMORY.md";
    private static final int WORKSPACE_MEMORY_READ_CAP = 32 * 1024; // 注入提示词前最多读 32KB

    /**
     * 读取工作区级记忆（{@code 主根/.wenqu/MEMORY.md}）。无工作区 / 未绑目录 / 文件不存在 / 执行器离线 → 返回空串。
     * 任何读失败一律吞掉返回空——记忆只是辅助上下文，绝不能因它拖累或中断一轮对话。
     */
    public String readWorkspaceMemory(String uid, String projectId) {
        try {
            Project p = projectService.detail(uid, projectId);
            if (!projectService.isLocal(p)) return "";
            List<ProjectRoot> roots = requireRoots(p);
            String abs = memoryAbs(roots);
            if (!executorFiles.online(p.getExecutorId())) return "";
            if (!executorFiles.exists(p.getExecutorId(), roots, abs)) return "";
            return executorFiles.readText(p.getExecutorId(), roots, abs, WORKSPACE_MEMORY_READ_CAP);
        } catch (Exception e) {
            log.warn("读取工作区记忆失败（视为空）：{}", e.getMessage());
            return "";
        }
    }

    /** 写回工作区级记忆（{@code 主根/.wenqu/MEMORY.md}），经执行器原子写。 */
    public void saveWorkspaceMemory(String uid, String projectId, String content) {
        Project p = writableProject(uid, projectId);
        if (!projectService.isLocal(p)) throw new IllegalArgumentException("这个工作区还没有绑定本机目录");
        List<ProjectRoot> roots = requireRoots(p);
        String abs = memoryAbs(roots);
        executorFiles.writeText(p.getExecutorId(), roots, abs, content == null ? "" : content);
    }

    private static String memoryAbs(List<ProjectRoot> roots) {
        ProjectRoot r = primary(roots);
        String base = r.getPath().replace('\\', '/');
        while (base.length() > 1 && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + WORKSPACE_MEMORY_FILE;
    }

    // ==================== 写侧 ====================

    /** 打开工作区主目录（执行器所在电脑上的系统文件管理器）。 */
    public void openInOS(String uid, String projectId) {
        openInOS(uid, projectId, null);
    }

    /**
     * 打开工作区里的某个文件夹（执行器所在电脑上的系统文件管理器）。
     *
     * <p>{@code path} 是文件栏那套虚拟路径（{@code 别名/相对路径}，裸别名=源文件夹本身），空值＝主目录。
     * 聊天页的文件栏要能精确到某一层子目录，所以这里不写死主目录；路径仍走 {@link #target} 认别名＋
     * 逐段校验，落到执行器那头的绝对路径只在 roots 里，越界在服务端就挡住了。
     */
    public void openInOS(String uid, String projectId, String path) {
        Project p = projectService.detail(uid, projectId);
        List<ProjectRoot> roots = requireRoots(p);
        Target t = target(p, roots, path, true);
        // 不先 stat 一遍：页面上点得到的只有目录行，多一次往返只会多一条失败路径
        // （stat 会拒软链，而源文件夹本身是软链时 openPath 打得开、stat 反倒会拦下来）
        executorFiles.openPath(executorId(p), roots, abs(t.root, t.rel));
    }

    // ==================== 下载 ====================

    /**
     * 一个可流式写出的下载。控制器先拿到文件名与大小设响应头，再调用 {@link Writer#writeTo}——
     * 大文件不整份进内存。
     */
    public record Download(String name, long size, Writer writer) {
    }

    public interface Writer {
        void writeTo(OutputStream out) throws IOException;
    }

    public Download download(String uid, String projectId, String relPath) {
        Project p = projectService.detail(uid, projectId);
        List<ProjectRoot> roots = requireRoots(p);
        Target t = target(p, roots, relPath, false);
        String abs = abs(t.root, t.rel);
        Map<String, Object> st = executorFiles.stat(executorId(p), roots, abs);
        if (Boolean.TRUE.equals(st.get("isDir"))) throw new IllegalArgumentException("这是一个文件夹");
        String name = str(st.get("name"));
        long size = asLong(st.get("size"));
        return new Download(name, size, out -> {
            long offset = 0;
            while (offset < size) {
                int want = (int) Math.min(DOWNLOAD_CHUNK, size - offset);
                ExecutorFiles.ReadBytes chunk = executorFiles.readRange(executorId(p), roots, abs, offset, want);
                if (chunk.bytes().length == 0) break;
                out.write(chunk.bytes());
                offset += chunk.bytes().length;
            }
        });
    }

    // ==================== 会话沙盒文件（没挂工作区的「会话」栏） ====================

    public Map<String, Object> listSessionFiles(String uid, String sessionId) {
        Project p = projectService.projectOfSession(sessionId);
        if (p != null && projectService.isLocal(p)) {
            List<ProjectRoot> roots = requireRoots(p);
            List<Map<String, Object>> files = new ArrayList<>();
            for (ProjectRoot r : roots) {
                for (Map<String, Object> row : executorFiles.tree(executorId(p), roots, r.getPath(),
                        MAX_ENTRIES, MAX_TREE_DEPTH)) {
                    if (Boolean.TRUE.equals(row.get("isDir"))) continue;
                    String virtualPath = r.getAlias() + "/" + str(row.get("path"));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "file");
                    m.put("path", virtualPath);
                    m.put("name", str(row.get("name")));
                    m.put("ext", PreviewTypes.extOf(str(row.get("name"))));
                    m.put("size", asLong(row.get("size")));
                    m.put("mtime", asLong(row.get("mtimeMs")));
                    addViewUrl(m, p.getId(), virtualPath, asLong(row.get("size")));
                    files.add(m);
                }
            }
            files.sort(Comparator.comparingLong((Map<String, Object> x) -> asLong(x.get("mtime"))).reversed());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("files", files.size() > MAX_ENTRIES ? new ArrayList<>(files.subList(0, MAX_ENTRIES)) : files);
            out.put("total", files.size());
            out.put("truncated", files.size() > MAX_ENTRIES);
            out.put("local", true);
            return out;
        }
        return listServerSessionFiles(uid, sessionId);
    }

    public Download downloadSessionFile(String uid, String sessionId, String relPath) {
        Project p = projectService.projectOfSession(sessionId);
        if (p != null && projectService.isLocal(p)) {
            return download(uid, p.getId(), relPath);
        }
        Path file = resolveIn(sessionBase(uid, sessionId), relPath);
        String name = file.getFileName().toString();
        long size = sizeOf(file);
        return new Download(name, size, out -> Files.copy(file, out));
    }

    // ==================== 内部：虚拟路径 / 根目录 ====================

    private record Target(ProjectRoot root, String rel, String virtualPath) {
    }

    private static String executorId(Project p) {
        return p.getExecutorId();
    }

    private List<ProjectRoot> requireRoots(Project p) {
        if (!projectService.isLocal(p)) {
            throw new IllegalArgumentException("这个工作区还没有绑定本机目录");
        }
        List<ProjectRoot> roots = projectService.rootsOf(p);
        if (roots.isEmpty()) throw new IllegalArgumentException("这个工作区还没有绑定本机目录");
        return roots;
    }

    private static ProjectRoot primary(List<ProjectRoot> roots) {
        for (ProjectRoot r : roots) {
            if (r.getPrimaryRoot() != null && r.getPrimaryRoot() == ProjectRoot.PRIMARY) return r;
        }
        return roots.get(0);
    }

    /**
     * 虚拟路径 → 源文件夹 + 相对路径。
     *
     * @param path        空 = 主目录根（rootAllowed=true 时）；否则首段必须是某个源的别名
     * @param rootAllowed 空路径是否允许（打开主目录时可以是空）
     */
    private static Target target(Project p, List<ProjectRoot> roots, String path, boolean rootAllowed) {
        String raw = path == null ? "" : path.trim().replace('\\', '/');
        while (raw.startsWith("/")) raw = raw.substring(1);
        while (raw.length() > 1 && raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty() || ".".equals(raw)) {
            if (!rootAllowed) throw new IllegalArgumentException("缺少文件路径");
            ProjectRoot r = primary(roots);
            return new Target(r, "", r.getAlias());
        }
        int slash = raw.indexOf('/');
        String alias = slash < 0 ? raw : raw.substring(0, slash);
        String rel = slash < 0 ? "" : raw.substring(slash + 1);
        ProjectRoot root = null;
        for (ProjectRoot r : roots) {
            if (alias.equals(r.getAlias())) {
                root = r;
                break;
            }
        }
        if (root == null) throw new IllegalArgumentException("不认识这个源文件夹：" + alias);
        if (!rel.isEmpty()) parseRel(rel, false);
        return new Target(root, rel, rel.isEmpty() ? alias : alias + "/" + rel);
    }

    private static String abs(ProjectRoot root, String rel) {
        String base = root.getPath().replace('\\', '/');
        while (base.length() > 1 && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return rel == null || rel.isEmpty() ? base : base + "/" + rel;
    }

    private static void parseRel(String rel, boolean rootAllowed) {
        String raw = rel == null ? "" : rel.trim().replace('\\', '/');
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) {
            if (rootAllowed) return;
            throw new IllegalArgumentException("缺少文件路径");
        }
        List<String> out = new ArrayList<>();
        for (String seg : raw.split("/")) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg) || seg.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("非法文件路径");
            }
            out.add(seg);
        }
        if (out.size() > MAX_TREE_DEPTH) throw new IllegalArgumentException("层级太深（最多 " + MAX_TREE_DEPTH + " 层）");
    }

    private static boolean shownName(String name) {
        return name != null && !name.isBlank() && !isHiddenName(name);
    }

    private static boolean isHiddenName(String name) {
        return name.startsWith(".") || name.equals("node_modules") || name.equals("__pycache__");
    }

    private Project writableProject(String uid, String projectId) {
        Project p = projectService.detail(uid, projectId);
        if (Project.STATUS_ARCHIVED.equals(p.getStatus())) {
            throw new IllegalArgumentException("工作区已归档：恢复后才能写这里的文件");
        }
        return p;
    }

    // ==================== 内部：token / 会话沙盒 ====================

    private static String encodeViewToken(String projectId, String relPath) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((projectId + TOKEN_SEP + relPath).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeViewToken(String token) {
        String raw;
        try {
            raw = new String(java.util.Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("预览地址无效");
        }
        int sep = raw.indexOf(TOKEN_SEP);
        if (sep <= 0 || sep == raw.length() - 1) throw new IllegalArgumentException("预览地址无效");
        return new String[]{raw.substring(0, sep), raw.substring(sep + 1)};
    }

    private Path sessionBase(String uid, String sessionId) {
        Path workspaceRoot = workspaceRootOf(uid);
        String id = sessionId == null ? "" : sessionId.strip();
        if (id.isEmpty()) throw new IllegalArgumentException("缺少会话标识");
        if (!SESSION_ID_RE.matcher(id).matches()) throw new IllegalArgumentException("非法会话标识");
        Path base = workspaceRoot.resolve("sessions").resolve(id).toAbsolutePath().normalize();
        if (!base.startsWith(workspaceRoot)) throw new IllegalArgumentException("非法会话标识");
        return base;
    }

    /** 本人 workspace 根（服务器会话沙盒目录的上级；DR 清单枚举时用）。 */
    public Path workspaceRootOf(String uid) {
        String root = configService.get("sandbox.userDataHostDir");
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir") + "/user-data";
        }
        return Path.of(root, "shared", SandboxPaths.uidDirname(uid), "workspace").toAbsolutePath().normalize();
    }

    private Map<String, Object> listServerSessionFiles(String uid, String sessionId) {
        Path base = sessionBase(uid, sessionId);
        Path workspaceRoot = base.getParent().getParent();
        Map<String, Object> m = new LinkedHashMap<>();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("沙盒目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(base)) {
            try (Stream<Path> walk = Files.walk(base, MAX_TREE_DEPTH)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !isHiddenRelative(base, p))
                        .sorted(Comparator.comparingLong(ProjectFileService::lastModifiedMillis).reversed())
                        .forEach(p -> files.add(toSessionEntry(base, p, sessionId)));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("沙盒目录暂时读不到（沙盒可能部署在另一台机器上）");
            }
        }
        m.put("files", files.size() > MAX_ENTRIES ? new ArrayList<>(files.subList(0, MAX_ENTRIES)) : files);
        m.put("total", files.size());
        m.put("truncated", files.size() > MAX_ENTRIES);
        return m;
    }

    private Map<String, Object> toSessionEntry(Path base, Path file, String sessionId) {
        String rel = base.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString();
        long size = sizeOf(file);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "file");
        m.put("path", rel);
        m.put("name", name);
        m.put("ext", PreviewTypes.extOf(name));
        m.put("size", size);
        m.put("mtime", lastModifiedMillis(file));
        String ext = PreviewTypes.extOf(name);
        if (PreviewTypes.viewContentType(ext) != null && PreviewTypes.viewCapError(ext, size) == null) {
            m.put("viewUrl", imageUrlSigner.signUrlRequired(
                    VIEW_PATH_PREFIX + encodeSessionViewToken(sessionId, rel)));
        }
        return m;
    }

    private static String encodeSessionViewToken(String sessionId, String relPath) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((SESSION_VIEW_MARK + TOKEN_SEP + sessionId + TOKEN_SEP + relPath)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static final java.util.regex.Pattern SESSION_ID_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static Path resolveIn(Path baseDir, String relPath) {
        if (relPath == null || relPath.isBlank()) throw new IllegalArgumentException("缺少文件路径");
        if (relPath.contains("\u0000")) throw new IllegalArgumentException("非法文件路径");
        Path base = baseDir.toAbsolutePath().normalize();
        Path target;
        try {
            target = base.resolve(relPath).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法文件路径");
        }
        if (!target.startsWith(base)) throw new IllegalArgumentException("非法文件路径");
        try {
            if (!target.toRealPath().startsWith(base.toRealPath())) throw new IllegalArgumentException("非法文件路径");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalArgumentException("文件不存在");
        } catch (IOException e) {
            throw new IllegalArgumentException("读不到这个文件");
        }
        if (!Files.isRegularFile(target)) throw new IllegalArgumentException("文件不存在");
        return target;
    }

    private static boolean isHiddenRelative(Path base, Path file) {
        Path rel = base.relativize(file);
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.startsWith(".") || s.equals("node_modules") || s.equals("__pycache__")) return true;
        }
        return false;
    }

    private static long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
