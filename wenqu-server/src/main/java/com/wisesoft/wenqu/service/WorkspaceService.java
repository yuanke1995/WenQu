package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.FilePreviewUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.SafeFiles;
import com.wisesoft.wenqu.common.UploadUtils;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.workspace.Workspace;
import com.wisesoft.wenqu.workspace.WorkspaceErrors;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户个人工作区的读写服务（services/workspace_service.py 全量移植）。
 *
 * <p>逐函数翻译：个人工作区内的文件名搜索、目录树列举（含「未绑定 Project 目录隐藏」过滤）、
 * 有界读取、预览渲染、文本编辑写回、删除、创建目录、多文件上传（失败回滚）、流式下载。
 * 所有文件访问经 {@link Workspace} 的 no-follow 边界。
 *
 * <p>常量照搬：{@code EDITABLE_WORKSPACE_SUFFIXES}、{@code MAX_WORKSPACE_UPLOAD_FILES}=50、
 * {@code MAX_WORKSPACE_DOWNLOAD_SIZE_BYTES}=1 GiB、{@code WORKSPACE_SEARCH_MAX_RESULTS}=100、
 * {@code WORKSPACE_SCOPE_ROOT="/"}；上传上限复用 {@code utils/upload_utils.MAX_UPLOAD_SIZE_BYTES}（100 MB）。
 *
 * <p>必要替换 / 平台差异（显式标注，非遗漏）：
 * <ul>
 *   <li>FastAPI {@code HTTPException(status_code, detail)} → {@link ApiHttpException}
 *       （响应体同为 {@code {"detail": ...}}，状态码与文案逐字照搬）；
 *       {@code UploadFile} → {@link MultipartFile}；{@code FileResponse}/{@code StreamingResponse}
 *       属传输层 → 服务层返回中立载体（文本/不支持预览为 {@code Map}，二进制预览为
 *       {@link FilePreviewService.BinaryPreview}，下载为 {@link WorkspaceDownload}）。
 *   <li>{@code asyncio.to_thread} 的协程卸载 → 直接同步调用（本服务为阻塞式 Spring Bean 方法，
 *       输出一致，仅执行线程不同）。
 *   <li>临时文件名前缀按本产品命名替换（本工程为
 *       {@code wenqu-workspace-download-}/{@code wenqu-workspace-upload-}）。
 *   <li>{@code str(upload.filename)} 取 basename 用 POSIX 语义（只按 {@code /} 切分，与
 *       {@code pathlib.Path} 在 POSIX 上一致）；{@code Path(clean).name != clean} 同样只按 {@code /} 判断。
 *   <li><b>异常粒度差</b>：参考实现由 {@code open_regular_file_fd} 区分
 *       {@code IsADirectoryError}（目录）与 {@code PermissionError}（符号链接/非普通文件），
 *       本工程 {@link SafeFiles#openRegularFile} 对这两种都抛 {@code FileNotFoundException}，
 *       经 {@link Workspace} 统一包为 {@code IllegalStateException}。此处按 {@code SafeFiles} 的两条
 *       固定消息还原 403/400 的区分（见 {@link #isPermissionDeniedCause}）；其余无法区分的一般 IO 故障
 *       仍按「当前路径不是文件」处理（与既有 {@code ViewerFilesystemService} 的约定一致）。
 *   <li>参考实现按 {@code db: AsyncSession} 依赖注入取 Project 仓储 → 本工程构造注入
 *       {@link ProjectRepository}。
 * </ul>
 */
@Service
public class WorkspaceService {

    /** 允许在个人工作区内编辑的文件后缀（参考实现 EDITABLE_WORKSPACE_SUFFIXES）。 */
    private static final Set<String> EDITABLE_WORKSPACE_SUFFIXES =
            Set.of(".md", ".markdown", ".mdx", ".txt");

    /** 单次上传大小上限（参考实现 MAX_WORKSPACE_UPLOAD_SIZE_BYTES = MAX_UPLOAD_SIZE_BYTES）。 */
    private static final long MAX_WORKSPACE_UPLOAD_SIZE_BYTES = UploadUtils.MAX_UPLOAD_SIZE_BYTES;

    /** 单次上传文件数量上限（参考实现 MAX_WORKSPACE_UPLOAD_FILES）。 */
    private static final int MAX_WORKSPACE_UPLOAD_FILES = 50;

    /** 单文件下载大小上限（参考实现 MAX_WORKSPACE_DOWNLOAD_SIZE_BYTES）。 */
    private static final long MAX_WORKSPACE_DOWNLOAD_SIZE_BYTES = 1024L * 1024 * 1024;

    /** 搜索返回条数上限，避免超大工作区一次性返回过多结果（参考实现 WORKSPACE_SEARCH_MAX_RESULTS）。 */
    private static final int WORKSPACE_SEARCH_MAX_RESULTS = 100;

    /** 工作区授权根（参考实现 WORKSPACE_SCOPE_ROOT）。 */
    private static final String WORKSPACE_SCOPE_ROOT = "/";

    private static final int SEARCH_MAX_DIRECTORIES = 600;
    private static final int SEARCH_MAX_DEPTH = 15;
    private static final int SEARCH_MAX_ENTRIES_PER_DIRECTORY = 500;
    private static final int SEARCH_MAX_SCANNED_ENTRIES = 10_000;

    /** 递归列举时一次扫描的结果上限（参考实现 list_workspace_tree 的 max_results=5000）。 */
    private static final int TREE_RECURSIVE_MAX_RESULTS = 5000;

    private static final String UPLOAD_TOO_LARGE_MESSAGE = "文件过大，当前仅支持 100 MB 以内的文件";

    private final FilePreviewService filePreviewService;
    private final ProjectRepository projectRepository;

    public WorkspaceService(FilePreviewService filePreviewService, ProjectRepository projectRepository) {
        this.filePreviewService = filePreviewService;
        this.projectRepository = projectRepository;
    }

    // ==================== 搜索 / 列举 ====================

    /** 按文件名在个人工作区内递归搜索，仅返回文件条目（参考实现 search_workspace_files）。 */
    public Map<String, Object> searchWorkspaceFiles(String query, String uid) {
        String normalizedQuery = (query == null ? "" : query).strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return entriesResult(List.of());
        }

        Workspace backend = workspaceBackend(uid);
        List<Map<String, Object>> matches;
        try {
            matches = backend.searchAuthorizedTree(
                    WORKSPACE_SCOPE_ROOT,
                    normalizedQuery,
                    false,
                    Set.of(),
                    false,
                    WORKSPACE_SEARCH_MAX_RESULTS,
                    SEARCH_MAX_DIRECTORIES,
                    SEARCH_MAX_DEPTH,
                    SEARCH_MAX_ENTRIES_PER_DIRECTORY,
                    SEARCH_MAX_SCANNED_ENTRIES);
        } catch (Workspace.NoSuchFileRuntime exc) {
            return entriesResult(List.of());
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> item : matches) {
            entries.add(entryFromMetadata(String.valueOf(item.get("path")), item));
        }
        return entriesResult(entries);
    }

    /** 列举工作区目录树（参考实现 list_workspace_tree）。 */
    public Map<String, Object> listWorkspaceTree(
            String path,
            boolean recursive,
            boolean filesOnly,
            boolean includeUnboundProjectDirs,
            String uid) {
        Workspace backend = workspaceBackend(uid);
        String workspacePath = workspacePath(path);
        List<Map<String, Object>> entries;
        try {
            if (recursive) {
                List<Map<String, Object>> scanned = backend.searchAuthorizedTree(
                        workspacePath,
                        "",
                        !filesOnly,
                        Set.of(),
                        false,
                        TREE_RECURSIVE_MAX_RESULTS,
                        SEARCH_MAX_DIRECTORIES,
                        SEARCH_MAX_DEPTH,
                        SEARCH_MAX_ENTRIES_PER_DIRECTORY,
                        SEARCH_MAX_SCANNED_ENTRIES);
                entries = new ArrayList<>();
                for (Map<String, Object> item : scanned) {
                    entries.add(entryFromMetadata(String.valueOf(item.get("path")), item));
                }
            } else {
                entries = listWorkspaceDirectory(backend, workspacePath, filesOnly);
            }
        } catch (Workspace.NoSuchFileRuntime exc) {
            return entriesResult(List.of());
        } catch (Workspace.PermissionDeniedRuntime exc) {
            if (exc.getCause() instanceof SafeFiles.NotDirectoryPathException) {
                throw new ApiHttpException(400, "当前路径不是目录");
            }
            throw new ApiHttpException(403, "Access denied");
        }
        if (!includeUnboundProjectDirs) {
            entries = filterProjectTreeEntries(entries, uid);
        }
        return entriesResult(entries);
    }

    /** 隐藏未归属 selectable Project 的 projects 子树（参考实现 _filter_project_tree_entries）。 */
    private List<Map<String, Object>> filterProjectTreeEntries(List<Map<String, Object>> entries, String uid) {
        List<Map<String, Object>> pairs = new ArrayList<>();
        List<List<String>> pathParts = new ArrayList<>();
        boolean hasNestedProjectEntry = false;
        for (Map<String, Object> entry : entries) {
            List<String> parts = strippedSegments(entry.get("path"));
            pairs.add(entry);
            pathParts.add(parts);
            if (isProjectsPath(parts) && !"projects".equals(joinPosix(parts))) {
                hasNestedProjectEntry = true;
            }
        }
        if (!hasNestedProjectEntry) {
            return entries;
        }

        List<String> visiblePaths = projectRepository.listSelectableWorkdirPathsForUser(uid);
        List<List<String>> selectedProjectPaths = new ArrayList<>();
        for (String visible : visiblePaths) {
            List<String> parts = strippedSegments(visible);
            if (isProjectsPath(parts)) {
                selectedProjectPaths.add(parts);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            if (isVisibleProjectPath(pathParts.get(i), selectedProjectPaths)) {
                result.add(pairs.get(i));
            }
        }
        return result;
    }

    // ==================== 读取 ====================

    /** 在 no-follow Workspace 边界内读取知识库导入文件（参考实现 read_workspace_file_bytes）。 */
    public WorkspaceFileBytes readWorkspaceFileBytes(String path, String uid) {
        Workspace backend = workspaceBackend(uid);
        String workspacePath = workspacePath(path);
        byte[] content;
        try {
            content = backend.readAuthorizedFile(workspacePath, MAX_WORKSPACE_UPLOAD_SIZE_BYTES);
        } catch (WorkspaceErrors.FileTransferLimitError exc) {
            throw new ApiHttpException(400, "文件过大，当前仅支持 100 MB 以内的工作区文件");
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "工作区文件不存在: " + path);
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        } catch (IllegalStateException exc) {
            if (isPermissionDeniedCause(exc.getCause())) {
                throw new ApiHttpException(403, "Access denied");
            }
            throw new ApiHttpException(400, "当前路径不是文件: " + path);
        }
        return new WorkspaceFileBytes(posixBasename(workspacePath), content);
    }

    /** 读取工作区文件内容并渲染为预览响应（参考实现 read_workspace_file_content）。 */
    public Object readWorkspaceFileContent(String path, String uid) {
        Workspace backend = workspaceBackend(uid);
        String workspacePath = workspacePath(path);
        byte[] rawContent;
        try {
            rawContent = backend.readAuthorizedFile(workspacePath, FilePreviewUtils.MAX_BINARY_PREVIEW_SIZE_BYTES);
        } catch (WorkspaceErrors.FileTransferLimitError exc) {
            return FilePreviewUtils.previewTooLarge().payload();
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "文件不存在");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        } catch (IllegalStateException exc) {
            if (isPermissionDeniedCause(exc.getCause())) {
                throw new ApiHttpException(403, "Access denied");
            }
            throw new ApiHttpException(400, "当前路径不是文件");
        }

        try {
            return filePreviewService.renderFilePreview(
                    path,
                    rawContent,
                    "workspace:" + uid + ":" + workspacePath);
        } catch (FilePreviewUtils.OfficePreviewConversionError exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }
    }

    // ==================== 写入 ====================

    /** 覆盖写入工作区内的可编辑文本文件（参考实现 write_workspace_file_content）。 */
    public Map<String, Object> writeWorkspaceFileContent(String path, String content, String uid) {
        Workspace backend = workspaceBackend(uid);
        String workspacePath = workspacePath(path);
        if (!EDITABLE_WORKSPACE_SUFFIXES.contains(PosixPathLite.suffixOf(workspacePath).toLowerCase(Locale.ROOT))) {
            throw new ApiHttpException(400, "当前文件类型不支持编辑");
        }

        byte[] rawContent;
        try {
            rawContent = backend.readAuthorizedFile(workspacePath, MAX_WORKSPACE_UPLOAD_SIZE_BYTES);
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "文件不存在");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            // FileTransferLimitError 继承 IllegalArgumentException，参考实现同样归入此分支 → 403
            throw new ApiHttpException(403, "Access denied");
        } catch (IllegalStateException exc) {
            if (isPermissionDeniedCause(exc.getCause())) {
                throw new ApiHttpException(403, "Access denied");
            }
            throw new ApiHttpException(400, "当前路径是目录");
        }
        FilePreviewUtils.DetectResult detected = FilePreviewUtils.detectPreviewType(path, rawContent);
        if ((!"markdown".equals(detected.previewType()) && !"text".equals(detected.previewType()))
                || !detected.supported()) {
            throw new ApiHttpException(400, "当前文件类型不支持编辑");
        }
        if (!isUtf8(rawContent)) {
            throw new ApiHttpException(400, "当前文件不是 UTF-8 文本");
        }

        Map<String, Object> item;
        try {
            item = backend.writeAuthorizedFile(workspacePath, utf8Bytes(content));
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "文件不存在");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        } catch (IllegalStateException exc) {
            if (isPermissionDeniedCause(exc.getCause())) {
                throw new ApiHttpException(403, "Access denied");
            }
            throw new ApiHttpException(400, "当前路径是目录");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("path", normalizeWorkspacePath(path).asPosix());
        result.put("entry", entryFromMetadata(workspacePath, item));
        return result;
    }

    /** 删除工作区内的文件或目录（参考实现 delete_workspace_path）。 */
    public Map<String, Object> deleteWorkspacePath(String path, String uid) {
        Workspace backend = workspaceBackend(uid);
        String workspacePath = workspacePath(path);
        if (WORKSPACE_SCOPE_ROOT.equals(workspacePath)) {
            throw new ApiHttpException(400, "工作区根目录不允许删除");
        }

        try {
            backend.deleteAuthorizedPath(workspacePath, WORKSPACE_SCOPE_ROOT);
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "文件不存在");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("path", normalizeWorkspacePath(path).asPosix());
        return result;
    }

    /** 在工作区内创建单层目录（参考实现 create_workspace_directory）。 */
    public Map<String, Object> createWorkspaceDirectory(String parentPath, String name, String uid) {
        Workspace backend = workspaceBackend(uid);
        String directoryName = validateChildName(name, "文件夹名");
        String virtualParent = workspacePath(parentPath);
        String target = virtualParent.replaceAll("/+$", "") + "/" + directoryName;

        Map<String, Object> item;
        try {
            item = backend.createAuthorizedDirectory(virtualParent, directoryName, WORKSPACE_SCOPE_ROOT);
        } catch (Workspace.AlreadyExistsRuntime exc) {
            throw new ApiHttpException(400, "同名文件或文件夹已存在");
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "目标目录不存在");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("entry", entryFromMetadata(target, item));
        return result;
    }

    /** 把一组上传文件写入工作区目录；任一失败即回滚本次已写入的文件（参考实现 upload_workspace_files）。 */
    public Map<String, Object> uploadWorkspaceFiles(String parentPath, List<MultipartFile> files, String uid) {
        if (files == null || files.isEmpty()) {
            throw new ApiHttpException(400, "请选择至少一个文件");
        }
        if (files.size() > MAX_WORKSPACE_UPLOAD_FILES) {
            throw new ApiHttpException(400, "一次最多上传 " + MAX_WORKSPACE_UPLOAD_FILES + " 个文件");
        }

        Workspace backend = workspaceBackend(uid);
        String parent = workspacePath(parentPath);
        Map<String, Object> parentStat;
        try {
            parentStat = backend.statAuthorizedPath(parent, WORKSPACE_SCOPE_ROOT);
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "目标目录不存在");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        }
        if (!Boolean.TRUE.equals(parentStat.get("is_dir"))) {
            throw new ApiHttpException(400, "目标路径不是目录");
        }

        Set<String> seenNames = new HashSet<>();
        List<String> targets = new ArrayList<>();
        for (MultipartFile file : files) {
            String originalName = file == null ? null : file.getOriginalFilename();
            String fileName = validateChildName(posixBasename(originalName), "文件名");
            if (!seenNames.add(fileName)) {
                throw new ApiHttpException(400, "选择的文件中存在重复文件名: " + fileName);
            }
            targets.add(parent.replaceAll("/+$", "") + "/" + fileName);
        }

        List<Map<String, Object>> completedEntries = new ArrayList<>();
        List<String> completedTargets = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                Map<String, Object> item = writeWorkspaceUpload(files.get(i), backend, targets.get(i));
                completedTargets.add(targets.get(i));
                completedEntries.add(item);
            }
        } catch (ApiHttpException exc) {
            // 参考实现 except HTTPException：回滚本次已落盘的文件，异常继续上抛
            for (String target : completedTargets) {
                try {
                    backend.deleteAuthorizedPath(target, WORKSPACE_SCOPE_ROOT);
                } catch (RuntimeException cleanupError) {
                    // contextlib.suppress(OSError) 的对等：清理失败不掩盖原始异常
                }
            }
            throw exc;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < completedEntries.size(); i++) {
            entries.add(entryFromMetadata(completedTargets.get(i), completedEntries.get(i)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("entries", entries);
        return result;
    }

    /** 把工作区普通文件有界下载到服务临时文件（参考实现 download_workspace_file）。 */
    public WorkspaceDownload downloadWorkspaceFile(String path, String uid) {
        Workspace backend = workspaceBackend(uid);
        String workspacePath = workspacePath(path);
        String fileName = posixBasename(workspacePath);
        if (fileName.isEmpty()) {
            fileName = "download";
        }
        String mediaType = FilePreviewUtils.detectMediaType(fileName, null);

        Path tempPath;
        try {
            tempPath = Files.createTempFile(
                    "wenqu-workspace-download-", PosixPathLite.suffixOf(fileName));
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        try {
            backend.downloadAuthorizedFileToPath(
                    workspacePath, tempPath.toString(), MAX_WORKSPACE_DOWNLOAD_SIZE_BYTES);
        } catch (Workspace.NoSuchFileRuntime exc) {
            deleteQuietly(tempPath);
            throw new ApiHttpException(404, "文件不存在");
        } catch (WorkspaceErrors.FileTransferLimitError exc) {
            deleteQuietly(tempPath);
            throw new ApiHttpException(413, "文件超过下载大小限制");
        } catch (Workspace.PermissionDeniedRuntime | IllegalArgumentException exc) {
            deleteQuietly(tempPath);
            throw new ApiHttpException(403, "Access denied");
        } catch (IllegalStateException exc) {
            deleteQuietly(tempPath);
            if (isPermissionDeniedCause(exc.getCause())) {
                throw new ApiHttpException(403, "Access denied");
            }
            // IsADirectoryError 与其他 (PermissionError, NotADirectoryError, ValueError) 同归 403
            throw new ApiHttpException(403, "Access denied");
        }
        return new WorkspaceDownload(tempPath.toString(), mediaType, fileName);
    }

    // ==================== 内部工具 ====================

    /** 物化并返回 uid 级 no-follow 文件系统（参考实现 _workspace_backend）。 */
    private Workspace workspaceBackend(String uid) {
        Workspace backend = new Workspace(uid);
        try {
            WorkspacePaths.ensureUserWorkspace(uid);
        } catch (IllegalStateException | IllegalArgumentException exc) {
            throw new ApiHttpException(403, "Access denied");
        }
        return backend;
    }

    /** 归一化工作区 scope 路径（参考实现 _normalize_workspace_path）。 */
    private static PosixPathLite normalizeWorkspacePath(String path) {
        String rawPath = (path == null ? "" : path).strip();
        if (rawPath.isEmpty()) {
            rawPath = "/";
        }
        if (!rawPath.startsWith("/")) {
            rawPath = "/" + rawPath;
        }
        PosixPathLite normalized = PosixPathLite.parse(rawPath);
        if (normalized.partsContain("..")) {
            throw new ApiHttpException(403, "Access denied");
        }
        return normalized;
    }

    /** 参考实现 _workspace_path：归一化后的 POSIX 字符串。 */
    private static String workspacePath(String path) {
        return normalizeWorkspacePath(path).asPosix();
    }

    /** 校验单个路径组件名（参考实现 _validate_child_name）。 */
    private static String validateChildName(String name, String fieldName) {
        String cleanName = (name == null ? "" : name).strip();
        if (cleanName.isEmpty()) {
            throw new ApiHttpException(422, fieldName + " 不能为空");
        }
        if (".".equals(cleanName) || "..".equals(cleanName)
                || cleanName.contains("/") || cleanName.contains("\\")) {
            throw new ApiHttpException(422, fieldName + " 不能包含路径分隔符");
        }
        if (!posixBasename(cleanName).equals(cleanName)) {
            throw new ApiHttpException(422, fieldName + " 不能包含路径分隔符");
        }
        return cleanName;
    }

    /** 把 Workspace 元数据装配为工作区条目（参考实现 _entry_from_metadata）。 */
    private static Map<String, Object> entryFromMetadata(String workspacePath, Map<String, Object> item) {
        boolean isDir = Boolean.TRUE.equals(item.get("is_dir"));
        String displayPath = PosixPathLite.parse(workspacePath).asPosix();
        if (isDir && !"/".equals(displayPath) && !displayPath.endsWith("/")) {
            displayPath = displayPath + "/";
        }
        String virtualPath = BackendPaths.runtimeUserDataPath(displayPath);
        if (isDir && !"/".equals(displayPath)) {
            virtualPath = virtualPath + "/";
        }
        String name = posixBasename(displayPath.replaceAll("/+$", ""));
        if (name.isEmpty()) {
            name = "工作区";
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", displayPath);
        entry.put("virtual_path", virtualPath);
        entry.put("name", name);
        entry.put("is_dir", isDir);
        entry.put("size", isDir ? 0L : asLong(item.get("size")));
        entry.put("modified_at", isoOrEmpty(item.get("modified_at")));
        return entry;
    }

    /** 目录项排序：目录在前，再按名称忽略大小写（参考实现 _sort_entries）。 */
    private static List<Map<String, Object>> sortEntries(List<Map<String, Object>> entries) {
        List<Map<String, Object>> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparing((Map<String, Object> item) -> !Boolean.TRUE.equals(item.get("is_dir")))
                .thenComparing(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT)));
        return sorted;
    }

    /** 列举单层目录并装配为条目（参考实现 _list_workspace_directory）。 */
    private static List<Map<String, Object>> listWorkspaceDirectory(
            Workspace backend, String target, boolean filesOnly) {
        List<Map<String, Object>> children =
                backend.listAuthorizedDirectory(target, WORKSPACE_SCOPE_ROOT);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> child : children) {
            String childPath = target.replaceAll("/+$", "") + "/" + child.get("name");
            if (!filesOnly || !Boolean.TRUE.equals(child.get("is_dir"))) {
                entries.add(entryFromMetadata(childPath, child));
            }
        }
        return sortEntries(entries);
    }

    /** 写入单个上传文件（参考实现 _write_workspace_upload）。 */
    private static Map<String, Object> writeWorkspaceUpload(
            MultipartFile file, Workspace backend, String target) {
        Path tempPath;
        try {
            tempPath = Files.createTempFile("wenqu-workspace-upload-", "");
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        try {
            try {
                UploadUtils.writeUploadToPath(
                        file, tempPath, MAX_WORKSPACE_UPLOAD_SIZE_BYTES, UPLOAD_TOO_LARGE_MESSAGE);
            } catch (UploadUtils.SizeLimitExceededException exc) {
                throw new ApiHttpException(400, exc.getMessage());
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            try {
                return backend.uploadAuthorizedFileFromPath(target, tempPath.toString(), false, false);
            } catch (Workspace.AlreadyExistsRuntime exc) {
                throw new ApiHttpException(400, "同名文件或文件夹已存在");
            } catch (Workspace.PermissionDeniedRuntime exc) {
                // 参考实现此分支为 400（而非其他位置的 403），文案取异常原文
                throw new ApiHttpException(400, exc.getMessage());
            } catch (IllegalArgumentException exc) {
                throw new ApiHttpException(400, exc.getMessage());
            }
        } finally {
            deleteQuietly(tempPath);
        }
    }

    /** 参考实现 utc_isoformat_from_timestamp(float(x or 0)) or ""。 */
    private static String isoOrEmpty(Object value) {
        double seconds = 0d;
        if (value instanceof Number number) {
            seconds = number.doubleValue();
        }
        String iso = DateTimeUtils.utcIsoformatFromTimestamp(seconds);
        return iso == null ? "" : iso;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 只按 POSIX 分隔符取 basename（对应 pathlib 在 POSIX 上的 Path(...).name）。 */
    private static String posixBasename(String value) {
        String text = value == null ? "" : value;
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    /** pathlib {@code PurePosixPath(str(x).strip("/"))} 的组件列表。 */
    private static List<String> strippedSegments(Object value) {
        String raw = value == null ? "" : String.valueOf(value).strip();
        String trimmed = raw.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : trimmed.split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static boolean isProjectsPath(List<String> parts) {
        return !parts.isEmpty() && "projects".equals(parts.get(0));
    }

    private static String joinPosix(List<String> parts) {
        return String.join("/", parts);
    }

    private static boolean isRelativeTo(List<String> candidate, List<String> ancestor) {
        return candidate.size() >= ancestor.size()
                && candidate.subList(0, ancestor.size()).equals(ancestor);
    }

    /** 参考实现 _filter_project_tree_entries 内层 is_visible。 */
    private static boolean isVisibleProjectPath(List<String> candidate, List<List<String>> selected) {
        if (!isProjectsPath(candidate) || "projects".equals(joinPosix(candidate))) {
            return true;
        }
        for (List<String> selectedPath : selected) {
            if (candidate.equals(selectedPath)
                    || isRelativeTo(candidate, selectedPath)
                    || isRelativeTo(selectedPath, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 参考实现 {@code open_regular_file_fd} 对「符号链接」「非普通文件」抛 {@code PermissionError}、
     * 对「目录」抛 {@code IsADirectoryError}；{@link SafeFiles#openRegularFile} 两者都抛
     * {@code FileNotFoundException}，故按其固定消息还原 PermissionError 语义（消息为 SafeFiles 内常量）。
     */
    private static boolean isPermissionDeniedCause(Throwable cause) {
        if (!(cause instanceof FileNotFoundException)) {
            return false;
        }
        String message = cause.getMessage();
        return "symlink paths are not allowed".equals(message)
                || "only regular files are allowed".equals(message);
    }

    private static boolean isUtf8(byte[] content) {
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (java.nio.charset.CharacterCodingException exc) {
            return false;
        }
    }

    private static byte[] utf8Bytes(String content) {
        return (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 参考实现 contextlib.suppress(FileNotFoundError)：清理失败不掩盖原始结果
        }
    }

    private static Map<String, Object> entriesResult(List<Map<String, Object>> entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        return result;
    }

    /** 参考实现 read_workspace_file_bytes 的返回 (name, bytes)。 */
    public record WorkspaceFileBytes(String filename, byte[] content) {}

    /** 对应参考实现 FileResponse：承载下载临时文件路径与响应元数据（控制器据其装配并清理）。 */
    public static final class WorkspaceDownload {
        private final String tempPath;
        private final String mediaType;
        private final String fileName;

        public WorkspaceDownload(String tempPath, String mediaType, String fileName) {
            this.tempPath = tempPath;
            this.mediaType = mediaType;
            this.fileName = fileName;
        }

        public String getTempPath() {
            return tempPath;
        }

        public String getMediaType() {
            return mediaType;
        }

        public String getFileName() {
            return fileName;
        }

        /** Content-Disposition: attachment; filename*=UTF-8''<percent-encoded>（参考实现 urllib.parse.quote）。 */
        public String contentDisposition() {
            return "attachment; filename*=UTF-8''" + FilePreviewService.urlEncodeUtf8(fileName);
        }

        /** 供控制器以 Path 形式取用临时文件。 */
        public Path tempFilePath() {
            return Path.of(tempPath);
        }

        /** 供测试与路由直接落盘使用。 */
        public byte[] readBytes() {
            try {
                return Files.readAllBytes(tempFilePath());
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
        }
    }
}
