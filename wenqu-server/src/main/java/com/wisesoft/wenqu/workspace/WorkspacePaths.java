package com.wisesoft.wenqu.workspace;

import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.SafeFiles;
import com.wisesoft.wenqu.common.SafeFiles.SymlinkPathException;
import com.wisesoft.wenqu.config.RuntimePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace 路径契约与目录物化。
 *
 * <p>由参考实现的 workspace/paths.py 逐函数翻译：thread_id/Workdir 路径校验、
 * managed Workdir 归一化、UID 的文件系统安全目录名、用户 Workspace 的创建与
 * 默认 Agent 上下文文件初始化、managed Workdir 的分配与物化。
 *
 * <p>必要替换（能力差异，已标注）：
 * <ul>
 *   <li>目录 fd（{@code dir_fd + O_NOFOLLOW}）→ {@link SafeFiles} 的逐组件
 *       NOFOLLOW 校验（见其类注解的 TOCTOU 说明）。
 *   <li>{@code os.stat(name, dir_fd=..., follow_symlinks=False)} →
 *       {@link Files#exists(Path, LinkOption...)}。
 *   <li>环境变量按本产品命名（{@code WENQU_USER_DATA_DIR}）。
 * </ul>
 */
public final class WorkspacePaths {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePaths.class);

    public static final String WORKSPACE_DIR_NAME = "workspace";
    public static final String WORKSPACE_AGENTS_DIR_NAME = "agents";
    public static final String WORKDIR_PROJECTS_DIR_NAME = "projects";

    /** Workspace 初始化时写入的默认 Agent 上下文文件（键序与默认内容照搬）。 */
    public static final Map<String, String> WORKSPACE_AGENT_CONTEXT_FILES;

    static {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("AGENTS.md", "# AGENTS\n\n以下是约束 Agent 行为的一些要求\n");
        files.put("USER.md", "# USER\n\n以下是有关用户的一些信息\n");
        files.put("MEMORY.md", "# MEMORY\n\n以下是 Agent 需要记住的一些信息\n");
        WORKSPACE_AGENT_CONTEXT_FILES = java.util.Collections.unmodifiableMap(files);
    }

    private static final Pattern SAFE_ID_RE = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern MANAGED_WORKDIR_NAME_RE =
            Pattern.compile("^(?<timestamp>\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})_[0-9a-f]{8}(?:-[1-9]\\d*)?$");
    private static final DateTimeFormatter MANAGED_WORKDIR_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss").withResolverStyle(ResolverStyle.STRICT);

    private WorkspacePaths() {}

    // ==================== 校验与归一化 ====================

    /** 校验 thread_id（安全字符集）。 */
    public static String validateThreadId(String threadId) {
        String value = threadId == null ? "" : threadId.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("thread_id is required");
        }
        if (!SAFE_ID_RE.matcher(value).matches()) {
            throw new IllegalArgumentException("thread_id contains invalid characters");
        }
        return value;
    }

    /** 规范化数据库中的 UserWorkspace 相对 Workdir 路径。 */
    public static String normalizeWorkdirPath(String workdirPath) {
        String raw = workdirPath == null ? "" : workdirPath.strip();
        PosixPathLite pure = PosixPathLite.parse(raw);
        if (raw.isEmpty() || pure.isAbsolute() || raw.contains("\\") || raw.contains("://")) {
            throw new IllegalArgumentException("workdir_path must be a relative POSIX path");
        }
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("workdir_path contains invalid path components");
            }
        }
        return pure.asPosix();
    }

    /** 规范化服务端管理的 Workdir 路径，并兼容既有 UUID 目录。 */
    public static String normalizeManagedWorkdirPath(String workdirPath) {
        String errorMessage = "managed workdir_path must use a supported projects/<managed-id>";
        PosixPathLite pure;
        try {
            pure = PosixPathLite.parse(normalizeWorkdirPath(workdirPath));
        } catch (IllegalArgumentException exc) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (pure.partCount() != 2 || !WORKDIR_PROJECTS_DIR_NAME.equals(pure.part(0))) {
            throw new IllegalArgumentException(errorMessage);
        }

        String workdirName = pure.part(1);
        UUID workdirId = parseUuidLenient(workdirName);
        if (workdirId == null) {
            var matcher = MANAGED_WORKDIR_NAME_RE.matcher(workdirName);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(errorMessage);
            }
            try {
                // 校验时间戳合法（对应 strptime 拒绝越界值）
                java.time.LocalDateTime.parse(matcher.group("timestamp"), MANAGED_WORKDIR_TIMESTAMP_FORMAT);
            } catch (RuntimeException exc) {
                throw new IllegalArgumentException(errorMessage);
            }
            return pure.asPosix();
        }
        return WORKDIR_PROJECTS_DIR_NAME + "/" + workdirId;
    }

    /**
     * 宽松解析 UUID：Python {@code uuid.UUID(str)} 接受 36 位连字符形式与 32 位纯十六进制，
     * {@link UUID#fromString} 只接受前者，此处补齐后者。
     */
    private static UUID parseUuidLenient(String value) {
        String text = value == null ? "" : value.strip();
        try {
            return UUID.fromString(text);
        } catch (RuntimeException ignored) {
            // 尝试 32 位纯十六进制形式
        }
        if (text.length() == 32 && text.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return UUID.fromString(
                    text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16)
                            + "-" + text.substring(16, 20) + "-" + text.substring(20));
        }
        return null;
    }

    /**
     * 返回逻辑 UID 的路径安全、稳定目录名。
     *
     * <p>数据库与 OIDC subject 标识可能包含 {@code :} 等合法身份字符但不适合做路径组件；
     * 旧的简单 UID 保留原目录名，其余值只在文件系统边界使用带命名空间的 SHA-256 摘要。
     */
    public static String workspaceUidDirname(String uid) {
        String value = uid == null ? "" : uid.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("uid is required");
        }
        if (SAFE_ID_RE.matcher(value).matches()) {
            return value;
        }
        return "uid-" + SafeFiles.sha256Hex(value);
    }

    // ==================== 目录定位 ====================

    /** 返回一个用户 Workspace 文件的共享宿主侧目录。 */
    public static Path globalUserDataDir(String uid) {
        String safeUid = workspaceUidDirname(uid);
        return RuntimePaths.getUserDataDir().resolve("shared").resolve(safeUid);
    }

    /** 返回用户级实时 Workspace 根。 */
    public static Path userWorkspaceDir(String uid) {
        return globalUserDataDir(uid).resolve(WORKSPACE_DIR_NAME);
    }

    /** 解析当前用户 Workdir，拒绝任意 symlink 路径组件。 */
    public static Path userWorkdirHostDir(String uid, String workdirPath) {
        String normalized = normalizeWorkdirPath(workdirPath);
        PosixPathLite pure = PosixPathLite.parse(normalized);
        List<String> parts = pure.parts();
        openWorkspaceDirectory(uid, parts);
        Path target = userWorkspaceDir(uid);
        for (String part : parts) {
            target = target.resolve(part);
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workdir_path does not reference an existing directory");
        }
        return target;
    }

    // ==================== 分配与物化 ====================

    /** 按上海时间与 Project ID 分配未占用的 managed Workdir 路径。 */
    public static String allocateDefaultUserWorkdirPath(String uid, String projectId, OffsetDateTime allocatedAt) {
        String canonicalProjectId = String.valueOf(parseUuidCanonical(projectId));
        java.time.ZonedDateTime moment = DateTimeUtils.ensureShanghai(
                allocatedAt != null ? allocatedAt : DateTimeUtils.shanghaiNow().toOffsetDateTime());
        String timestamp = moment.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String baseName = timestamp + "_" + canonicalProjectId.substring(0, 8);
        int suffix = 0;
        while (true) {
            String name = suffix == 0 ? baseName : baseName + "-" + suffix;
            if (!userWorkspaceEntryExists(uid, name)) {
                return WORKDIR_PROJECTS_DIR_NAME + "/" + name;
            }
            suffix += 1;
        }
    }

    /** Python uuid.UUID(str) 的规范化字符串（小写 36 位连字符形式）。 */
    private static UUID parseUuidCanonical(String projectId) {
        UUID parsed = parseUuidLenient(projectId);
        if (parsed == null) {
            throw new IllegalArgumentException("invalid project id: " + projectId);
        }
        return parsed;
    }

    /** 以 no-follow 方式判断 managed Workdir 名称是否已被占用。 */
    private static boolean userWorkspaceEntryExists(String uid, String workdirName) {
        Path workspace;
        try {
            workspace = SafeFiles.openDirectory(
                    RuntimePaths.getUserDataDir(),
                    List.of("shared", workspaceUidDirname(uid), WORKSPACE_DIR_NAME),
                    false);
        } catch (NoSuchFileException exc) {
            return false;
        } catch (IOException exc) {
            throw new IllegalStateException("无法打开用户 Workspace: " + exc.getMessage(), exc);
        }

        Path projects;
        try {
            projects = SafeFiles.openDirectory(workspace, List.of(WORKDIR_PROJECTS_DIR_NAME), false);
        } catch (NoSuchFileException exc) {
            return false;
        } catch (IOException exc) {
            throw new IllegalStateException("无法打开 projects 目录: " + exc.getMessage(), exc);
        }
        return Files.exists(projects.resolve(workdirName), LinkOption.NOFOLLOW_LINKS);
    }

    /** 物化已提交数据库绑定的 canonical Workdir。 */
    public static void ensureBoundUserWorkdir(String uid, String workdirPath) {
        String normalized = normalizeManagedWorkdirPath(workdirPath);
        List<String> parts = PosixPathLite.parse(normalized).parts();
        try {
            openWorkspaceDirectory(uid, parts);
            return;
        } catch (IllegalArgumentException | IllegalStateException exc) {
            if (!isMissingDirectoryCause(exc)) {
                throw exc;
            }
        }
        ensureUserWorkspace(uid);
        Path workspace = openUserWorkspace(uid, false);
        try {
            SafeFiles.openDirectory(workspace, parts, true);
        } catch (IOException exc) {
            throw new IllegalStateException("无法物化 Workdir 目录: " + exc.getMessage(), exc);
        }
    }

    /** 参考实现只捕获目录缺失（FileNotFoundError）；Java 侧按异常消息区分。 */
    private static boolean isMissingDirectoryCause(RuntimeException exc) {
        Throwable cause = exc.getCause() != null ? exc.getCause() : exc;
        return cause instanceof NoSuchFileException
                || (cause instanceof IOException ioException && ioException.getMessage() != null
                        && ioException.getMessage().contains("No such file"));
    }

    /** 逐层以 no-follow 打开 UserWorkspace 目录。 */
    private static void openWorkspaceDirectory(String uid, List<String> parts) {
        Path directory = openUserWorkspace(uid, false);
        try {
            SafeFiles.openDirectory(directory, parts, false);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 从配置根逐层打开 uid 的 Workspace，拒绝中间 symlink（create 时含目录创建）。 */
    private static Path openUserWorkspace(String uid, boolean create) {
        Path root = RuntimePaths.getUserDataDir();
        if (create) {
            // 对应参考实现 root.mkdir(parents=True, exist_ok=True)
            try {
                Files.createDirectories(root);
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
        }
        try {
            return SafeFiles.openDirectory(
                    root,
                    List.of("shared", workspaceUidDirname(uid), WORKSPACE_DIR_NAME),
                    create);
        } catch (SymlinkPathException | SafeFiles.NotDirectoryPathException exc) {
            throw new IllegalArgumentException("UserWorkspace 路径包含符号链接或非目录组件", exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 通过已校验的 Workspace 目录初始化 Agent 上下文文件。 */
    private static void ensureWorkspaceDefaultFiles(Path workspaceDirectory) {
        Path agentsDirectory;
        try {
            agentsDirectory = SafeFiles.openDirectory(
                    workspaceDirectory, List.of(WORKSPACE_AGENTS_DIR_NAME), true);
        } catch (IOException exc) {
            log.warn("工作区默认 Agents 目录初始化失败: {}", exc.getMessage());
            return;
        }
        for (Map.Entry<String, String> entry : WORKSPACE_AGENT_CONTEXT_FILES.entrySet()) {
            String filename = entry.getKey();
            String defaultContent = entry.getValue();
            Path file = agentsDirectory.resolve(filename);
            try {
                Files.createFile(
                        file,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (java.nio.file.FileAlreadyExistsException exc) {
                continue;
            } catch (IOException exc) {
                log.warn("工作区默认 {} 初始化失败: {}", filename, exc.getMessage());
                continue;
            }
            try {
                Files.write(file, defaultContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException | IOException exc) {
                // 写入失败：清理半成品文件后抛出（与参考实现一致）
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 清理失败不掩盖原始异常
                }
                throw new IllegalStateException("工作区默认文件写入失败: " + filename, exc);
            }
        }
    }

    /** 创建用户级 Workspace 与默认 Agent 上下文文件。 */
    public static void ensureUserWorkspace(String uid) {
        Path workspaceDirectory = openUserWorkspace(uid, true);
        ensureWorkspaceDefaultFiles(workspaceDirectory);
    }
}
