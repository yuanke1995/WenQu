package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.FilePreviewUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.UploadUtils;
import com.wisesoft.wenqu.workspace.Workdir;
import com.wisesoft.wenqu.workspace.Workspace;
import com.wisesoft.wenqu.workspace.WorkspaceErrors;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * AgentPanel Viewer 的实时 Project Workdir 文件服务（services/viewer_filesystem_service.py 全量移植）。
 *
 * <p>逐函数翻译：Viewer 只接受 Workdir scope、授权打开 Workdir、列举/搜索/读取预览/下载/删除/
 * 创建目录/上传。所有访问经 scope 转换后委托 {@link Workdir} 的 no-follow 边界。
 *
 * <p>必要替换 / 能力差异（显式标注，非遗漏）：
 * <ul>
 *   <li>FastAPI {@code HTTPException(status_code, detail)} → {@link BizException}(statusCode, detail)；
 *       {@code RuntimeError} → {@link IllegalStateException}（与既有 WorkdirService 约定一致）。</li>
 *   <li>FastAPI {@code StreamingResponse}/{@code FileResponse} 属传输层；服务层返回中立载体
 *       —— 文本/不支持预览为 {@code Map}，图片/ PDF 预览为 {@link FilePreviewService#renderFilePreview}
 *       返回的 {@code BinaryPreview}，下载为 {@link ViewerDownload}（控制器据其装配响应并负责临时文件清理，
 *       对应参考实现的 {@code BackgroundTask(os.unlink, temp_path)}）。</li>
 *   <li>临时文件名前缀固定为 {@code wenqu-viewer-}（与本项目命名一致）。</li>
 *   <li>Python {@code asyncio.to_thread(...)} 同步调用直接内联（本服务为阻塞式 Bean 方法）。</li>
 *   <li>{@code mimetypes.guess_type} → {@link java.net.URLConnection#guessContentTypeFromName}（按文件名推断）。</li>
 * </ul>
 */
@Service
public class ViewerFilesystemService {

    private static final int SEARCH_MAX_RESULTS = 100;
    private static final int SEARCH_MAX_DIRECTORIES = 600;
    private static final long MAX_VIEWER_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final long MAX_VIEWER_DOWNLOAD_BYTES = 1024L * 1024 * 1024;

    private final WorkdirService workdirService;
    private final FilePreviewService filePreviewService;

    public ViewerFilesystemService(WorkdirService workdirService, FilePreviewService filePreviewService) {
        this.workdirService = workdirService;
        this.filePreviewService = filePreviewService;
    }

    /** 按 thread_id + 当前用户授权打开持久化 Workdir（参考实现 _viewer_state）。 */
    private WorkdirService.AuthorizedWorkdir viewerState(String threadId, String uid) {
        return workdirService.resolveAuthorizedWorkdir(threadId, uid);
    }

    /** Viewer 只接受 Workdir scope，不接受 Backend runtime 绝对路径（参考实现 _validate_viewer_path）。 */
    private void validateViewerPath(WorkdirService.AuthorizedWorkdir access, String path) {
        if (BackendPaths.isRuntimePath(path)) {
            throw new BizException(403, "Access denied");
        }
        try {
            access.workdir().resolvePath(path);
        } catch (IllegalArgumentException exc) {
            throw new BizException(403, "Access denied");
        }
    }

    /** 把 Workspace 条目装配为 Viewer 作用域条目（参考实现 _entry）。 */
    private Map<String, Object> entry(
            WorkdirService.AuthorizedWorkdir access, String parentScope, Map<String, Object> item) {
        String scope = (parentScope.replaceAll("/+$", "") + "/" + item.get("name"));
        if (!scope.startsWith("/")) {
            scope = "/" + scope;
        }
        boolean isDir = Boolean.TRUE.equals(item.get("is_dir"));
        String runtimePath = BackendPaths.runtimePathForWorkdirScope(access.workdirPath(), scope);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", isDir ? scope + "/" : scope);
        result.put("name", String.valueOf(item.get("name")));
        result.put("is_dir", isDir);
        result.put("size", item.get("size") instanceof Number
                ? ((Number) item.get("size")).longValue()
                : 0L);
        result.put("modified_at", isoOrEmpty((Number) item.get("modified_at")));
        result.put("artifact_url", isDir
                ? null
                : "/api/chat/thread/" + access.threadId() + "/artifacts/" + runtimePath.substring(1));
        return result;
    }

    /** 目录列举并装配为排序后的 Viewer 条目（参考实现 _list_directory）。 */
    private List<Map<String, Object>> listDirectory(WorkdirService.AuthorizedWorkdir access, String scope) {
        List<Map<String, Object>> items;
        try {
            items = access.workdir().listDirectory(scope);
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        } catch (Workspace.NoSuchFileRuntime | IllegalArgumentException exc) {
            throw new BizException(404, "目录不存在");
        } catch (IllegalStateException exc) {
            throw new BizException(404, "目录不存在");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> item : items) {
            entries.add(entry(access, scope, item));
        }
        entries.sort(Comparator.comparing(
                (Map<String, Object> e) -> Boolean.FALSE.equals((Boolean) e.get("is_dir")))
                .thenComparing(e -> String.valueOf(e.get("name")).toLowerCase()));
        return entries;
    }

    /** 列出实时 Project Workdir；根路径 `/` 直接表示 Workdir 根（参考实现 list_viewer_filesystem_tree）。 */
    public Map<String, Object> listViewerFilesystemTree(String threadId, String path, String uid) {
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        validateViewerPath(access, path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", listDirectory(access, path));
        return result;
    }

    /** 在实时 Workdir 内按文件名搜索（参考实现 search_viewer_files）。 */
    public Map<String, Object> searchViewerFiles(String threadId, String query, String uid) {
        String normalizedQuery = (query == null ? "" : query).strip().toLowerCase();
        if (normalizedQuery.isEmpty()) {
            return Map.of("entries", List.of());
        }
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        List<Map<String, Object>> matches;
        try {
            matches = access.workdir().search(
                    normalizedQuery,
                    true,
                    Set.of(),
                    false,
                    SEARCH_MAX_RESULTS,
                    SEARCH_MAX_DIRECTORIES,
                    15,
                    500,
                    10_000);
        } catch (Workspace.NoSuchFileRuntime | IllegalArgumentException exc) {
            throw new BizException(404, "目录不存在");
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        } catch (IllegalStateException exc) {
            throw new BizException(404, "目录不存在");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> item : matches) {
            String parent = purePosixParent(String.valueOf(item.get("path")));
            entries.add(entry(access, parent, item));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        return result;
    }

    /** 从实时 Workdir 读取预览（参考实现 read_viewer_file_content）。 */
    public Object readViewerFileContent(String threadId, String path, String uid) {
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        validateViewerPath(access, path);
        byte[] rawContent;
        try {
            rawContent = access.workdir().readFile(path, FilePreviewUtils.MAX_BINARY_PREVIEW_SIZE_BYTES);
        } catch (WorkspaceErrors.FileTransferLimitError exc) {
            return FilePreviewUtils.previewTooLarge().payload();
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        } catch (Workspace.NoSuchFileRuntime | IllegalArgumentException exc) {
            throw new BizException(404, "文件不存在");
        } catch (IllegalStateException exc) {
            throw new BizException(400, "路径不是普通文件");
        }
        try {
            return filePreviewService.renderFilePreview(
                    path,
                    rawContent,
                    "viewer:" + access.uid() + ":" + access.workdirPath() + ":" + path);
        } catch (FilePreviewUtils.OfficePreviewConversionError exc) {
            throw new BizException(400, exc.getMessage());
        }
    }

    /** 从实时 Workdir 流式下载普通文件（参考实现 download_viewer_file）。 */
    public ViewerDownload downloadViewerFile(String threadId, String path, String uid) {
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        validateViewerPath(access, path);
        String tempPath;
        try {
            tempPath = downloadToTemp(access.workdir(), path, MAX_VIEWER_DOWNLOAD_BYTES);
        } catch (WorkspaceErrors.FileTransferLimitError exc) {
            throw new BizException(413, "文件超过下载大小限制");
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        } catch (Workspace.NoSuchFileRuntime | IllegalArgumentException exc) {
            throw new BizException(404, "文件不存在");
        } catch (IllegalStateException exc) {
            throw new BizException(400, "路径不是普通文件");
        }
        String fileName = PosixPathLite.nameOf(path);
        if (fileName.isEmpty()) {
            fileName = "download";
        }
        String mediaType = java.net.URLConnection.guessContentTypeFromName(fileName);
        if (mediaType == null) {
            mediaType = "application/octet-stream";
        }
        return new ViewerDownload(tempPath, mediaType, fileName);
    }

    /** 实时删除 Workdir 内文件或目录（参考实现 delete_viewer_file）。 */
    public Map<String, Object> deleteViewerFile(String threadId, String path, String uid) {
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        String normalized = PosixPathLite.parse(path).asPosix();
        validateViewerPath(access, normalized);
        if ("/".equals(normalized)) {
            throw new BizException(400, "Project Workdir 根目录不允许删除");
        }
        try {
            access.workdir().delete(normalized);
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new BizException(404, "文件不存在");
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("path", normalized);
        return result;
    }

    /** 实时创建 Workdir 目录（参考实现 create_viewer_directory）。 */
    public Map<String, Object> createViewerDirectory(String threadId, String parentPath, String name, String uid) {
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        validateViewerPath(access, parentPath);
        String cleanName = (name == null ? "" : name).strip();
        Map<String, Object> metadata;
        try {
            metadata = access.workdir().createDirectory(parentPath, cleanName);
        } catch (IllegalArgumentException exc) {
            throw new BizException(400, exc.getMessage());
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new BizException(404, "目录不存在");
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        }
        Map<String, Object> entryMap = new LinkedHashMap<>();
        entryMap.put("path", parentPath.replaceAll("/+$", "") + "/" + cleanName + "/");
        entryMap.put("name", cleanName);
        entryMap.put("is_dir", true);
        entryMap.put("size", metadata.get("size") instanceof Number
                ? ((Number) metadata.get("size")).longValue()
                : 0L);
        entryMap.put("modified_at", isoOrEmpty((Number) metadata.get("modified_at")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entry", entryMap);
        return result;
    }

    /** 把用户上传直接写入实时 Workdir（参考实现 upload_viewer_files）。 */
    public Map<String, Object> uploadViewerFiles(String threadId, String parentPath, List<MultipartFile> files, String uid) {
        WorkdirService.AuthorizedWorkdir access = viewerState(threadId, uid);
        validateViewerPath(access, parentPath);
        List<String> fileNames = new ArrayList<>();
        for (MultipartFile upload : files) {
            fileNames.add(PosixPathLite.nameOf(upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename()));
        }
        if (fileNames.stream().anyMatch(n -> n.isEmpty() || ".".equals(n) || "..".equals(n))) {
            throw new BizException(400, "无法识别的文件名");
        }
        if (fileNames.size() != new java.util.HashSet<>(fileNames).size()) {
            throw new BizException(409, "同一次上传不能包含重名文件");
        }
        Set<String> existingNames;
        try {
            existingNames = access.workdir().listDirectory(parentPath).stream()
                    .map(e -> String.valueOf(e.get("name")))
                    .collect(Collectors.toSet());
        } catch (Workspace.PermissionDeniedRuntime exc) {
            throw new BizException(403, "Access denied");
        } catch (Workspace.NoSuchFileRuntime | IllegalArgumentException exc) {
            throw new BizException(404, "目录不存在");
        } catch (IllegalStateException exc) {
            throw new BizException(404, "目录不存在");
        }
        Set<String> collisions = new java.util.HashSet<>(existingNames);
        collisions.retainAll(fileNames);
        if (!collisions.isEmpty()) {
            throw new BizException(409, "文件已存在: " + String.join(", ", new TreeSet<>(collisions)));
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile upload = files.get(i);
            String fileName = fileNames.get(i);
            Path tempPath;
            try {
                tempPath = Files.createTempFile("wenqu-viewer-upload-", PosixPathLite.suffixOf(fileName));
            } catch (IOException exc) {
                throw new IllegalStateException(exc);
            }
            try {
                try {
                    UploadUtils.writeUploadToPath(upload, tempPath, MAX_VIEWER_UPLOAD_BYTES, "文件过大");
                } catch (UploadUtils.SizeLimitExceededException exc) {
                    throw new BizException(413, exc.getMessage());
                } catch (IOException exc) {
                    throw new IllegalStateException(exc);
                }
                String target = parentPath.replaceAll("/+$", "") + "/" + fileName;
                Map<String, Object> metadata;
                try {
                    metadata = access.workdir().copyFileFromPath(target, tempPath.toString(), false);
                } catch (IllegalStateException exc) {
                    if (exc.getCause() instanceof FileAlreadyExistsException) {
                        throw new BizException(409, "文件已存在: " + fileName);
                    }
                    throw exc;
                } catch (Workspace.PermissionDeniedRuntime exc) {
                    throw new BizException(403, "Access denied");
                } catch (Workspace.NoSuchFileRuntime exc) {
                    throw new BizException(404, "目录不存在");
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", fileName);
                item.putAll(metadata);
                entries.add(entry(access, parentPath, item));
            } finally {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                    // 清理失败不掩盖原始结果
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        return result;
    }

    /** 把 Workdir 文件有界下载到服务临时文件（参考实现 _download_to_temp）。 */
    private String downloadToTemp(Workdir workdir, String path, long maxBytes) {
        Path tempPath;
        try {
            tempPath = Files.createTempFile("wenqu-viewer-", PosixPathLite.suffixOf(path));
        } catch (IOException exc) {
            throw new IllegalStateException(exc);
        }
        try {
            workdir.copyFileToPath(path, tempPath.toString(), maxBytes);
        } catch (RuntimeException exc) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
                // 清理失败不掩盖原始异常
            }
            throw exc;
        }
        return tempPath.toString();
    }

    /** pathlib.PurePosixPath(path).parent（参考实现 search_viewer_files 中的父目录换算）。 */
    private static String purePosixParent(String path) {
        String p = path.replaceAll("/+$", "");
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return p.substring(0, idx);
    }

    /** 参考实现 utc_isoformat_from_timestamp(x or 0) or ""：null 输入按 0 处理，转换失败回退空串。 */
    private static String isoOrEmpty(Number value) {
        Number v = value == null ? 0L : value;
        String iso = DateTimeUtils.utcIsoformatFromTimestamp(v);
        return iso == null ? "" : iso;
    }

    /** 对应参考实现 FileResponse：承载下载临时文件路径与响应元数据（控制器据其装配并清理）。 */
    public static final class ViewerDownload {
        private final String tempPath;
        private final String mediaType;
        private final String fileName;

        public ViewerDownload(String tempPath, String mediaType, String fileName) {
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
    }
}
