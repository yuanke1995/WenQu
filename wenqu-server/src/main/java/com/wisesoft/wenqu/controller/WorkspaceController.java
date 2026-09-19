package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.UrlQuote;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseDetail;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseException;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.KnowledgeFilePreviewService;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.FilePreviewService;
import com.wisesoft.wenqu.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 个人工作区路由，逐端点对齐参考实现 {@code server/routers/workspace_router.py}
 * （两个同前缀 router 合并为一个，前缀 {@code /api/workspace}）。
 *
 * <p>响应契约照搬：
 * <ul>
 *   <li>树/搜索/写回/删除/建目录/上传 → JSON（服务层装配的 Map）；</li>
 *   <li>{@code /file} 与 {@code /knowledge/file} 的预览为图片/PDF 时 → 二进制流；
 *       知识库侧按参考实现 {@code _preview_response}：预览结果带 {@code binary} 标记时转为流
 *       （{@code Content-Disposition: inline} + 预览类型/文件名响应头，头名按本产品命名）；</li>
 *   <li>{@code /download} 与 {@code /knowledge/download} → {@code Content-Disposition: attachment}
 *       的流式响应；工作区下载的临时文件在流结束即删除（对应 {@code BackgroundTask(os.unlink, ...)}）。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * {@code UploadFile} → {@link MultipartFile}；{@code FileResponse}/{@code StreamingResponse} →
 * {@code ResponseEntity<StreamingBody>}；{@code urllib.parse.quote} → {@link UrlQuote#quote}；
 * {@code Query(ge/le)} 约束 → 显式校验（422）；参考实现知识库运行时模块的
 * 高层方法（{@code check_accessible}/{@code list_document_files}/{@code get_database_document_support}）
 * → 本工程 {@link KnowledgeBaseManager} 的同名方法。
 */
@Slf4j
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
@Tag(name = "workspace", description = "个人工作区")
public class WorkspaceController {

    private static final String KB_NOT_SUPPORT_DOCUMENTS_PREFIX = "Dify 知识库不支持";

    private final WorkspaceService workspaceService;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final KnowledgeFilePreviewService knowledgeFilePreviewService;
    private final UserRepository userRepository;

    // =========================================================================
    // === 工作区文件树与搜索 ===
    // =========================================================================

    @Operation(summary = "工作区目录树", description = "query: path / recursive / files_only / include_unbound_project_dirs")
    @GetMapping("/tree")
    public Map<String, Object> getWorkspaceTree(
            @Parameter(description = "工作区目录路径") @RequestParam(value = "path", defaultValue = "/") String path,
            @Parameter(description = "是否递归返回子目录文件") @RequestParam(value = "recursive", defaultValue = "false") boolean recursive,
            @Parameter(description = "是否仅返回文件") @RequestParam(value = "files_only", defaultValue = "false") boolean filesOnly,
            @Parameter(description = "Project 选目录时展示未绑定目录")
            @RequestParam(value = "include_unbound_project_dirs", defaultValue = "false") boolean includeUnboundProjectDirs) {
        String uid = AuthGuards.requireUser();
        return workspaceService.listWorkspaceTree(
                path, recursive, filesOnly, includeUnboundProjectDirs, uid);
    }

    @Operation(summary = "工作区文件搜索", description = "query: query")
    @GetMapping("/search")
    public Map<String, Object> searchWorkspaceFilesRoute(
            @Parameter(description = "搜索关键词") @RequestParam("query") String query) {
        String uid = AuthGuards.requireUser();
        return workspaceService.searchWorkspaceFiles(query, uid);
    }

    // =========================================================================
    // === 工作区文件读写 ===
    // =========================================================================

    @Operation(summary = "读取工作区文件预览", description = "文本返回 JSON；图片/PDF 返回二进制流")
    @GetMapping("/file")
    public ResponseEntity<Object> getWorkspaceFile(
            @Parameter(description = "工作区文件路径") @RequestParam("path") String path) {
        String uid = AuthGuards.requireUser();
        Object preview = workspaceService.readWorkspaceFileContent(path, uid);
        if (preview instanceof FilePreviewService.BinaryPreview binary) {
            return binaryResponse(binary);
        }
        return ResponseEntity.ok(preview);
    }

    @Operation(summary = "写回工作区文本文件", description = "body: path / content")
    @PutMapping("/file")
    public Map<String, Object> updateWorkspaceFile(@RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        String path = requireString(body, "path");
        String content = body == null ? null : String.valueOf(body.getOrDefault("content", ""));
        return workspaceService.writeWorkspaceFileContent(path, content, uid);
    }

    @Operation(summary = "删除工作区文件或目录", description = "query: path")
    @DeleteMapping("/file")
    public Map<String, Object> deleteWorkspaceFileRoute(
            @Parameter(description = "工作区文件或目录路径") @RequestParam("path") String path) {
        String uid = AuthGuards.requireUser();
        return workspaceService.deleteWorkspacePath(path, uid);
    }

    @Operation(summary = "创建工作区目录", description = "body: parent_path / name")
    @PostMapping("/directory")
    public Map<String, Object> createWorkspaceDirectoryRoute(@RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        String parentPath = requireString(body, "parent_path");
        String name = requireString(body, "name");
        return workspaceService.createWorkspaceDirectory(parentPath, name, uid);
    }

    @Operation(summary = "上传工作区文件", description = "multipart: parent_path / files[]")
    @PostMapping("/upload")
    public Map<String, Object> uploadWorkspaceFilesRoute(
            @Parameter(description = "父目录路径") @RequestParam("parent_path") String parentPath,
            @Parameter(description = "上传文件列表") @RequestParam("files") List<MultipartFile> files) {
        String uid = AuthGuards.requireUser();
        return workspaceService.uploadWorkspaceFiles(parentPath, files, uid);
    }

    @Operation(summary = "下载工作区文件", description = "流式下载；流结束即清理临时文件")
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadWorkspace(
            @Parameter(description = "工作区文件路径") @RequestParam("path") String path) {
        String uid = AuthGuards.requireUser();
        WorkspaceService.WorkspaceDownload download = workspaceService.downloadWorkspaceFile(path, uid);
        Path tempFile = download.tempFilePath();
        StreamingResponseBody body = outputStream -> {
            try (InputStream input = Files.newInputStream(tempFile)) {
                input.transferTo(outputStream);
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception cleanupError) {
                    log.warn("工作区下载临时文件清理失败: {}", tempFile, cleanupError);
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.getMediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + UrlQuote.quote(download.getFileName(), "/"))
                .body(body);
    }

    // =========================================================================
    // === 知识库文档视图（挂在工作区树上的只读入口） ===
    // =========================================================================

    @Operation(summary = "知识库文档树", description = "query: kb_id / parent_id / path_prefix / page / page_size / recursive / files_only")
    @GetMapping("/knowledge/tree")
    public Map<String, Object> getWorkspaceKnowledgeTree(
            @Parameter(description = "知识库 ID") @RequestParam("kb_id") String kbId,
            @Parameter(description = "父文件夹 ID") @RequestParam(value = "parent_id", required = false) String parentId,
            @Parameter(description = "虚拟目录路径前缀") @RequestParam(value = "path_prefix", required = false) String pathPrefix,
            @Parameter(description = "页码") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(value = "page_size", defaultValue = "100") int pageSize,
            @Parameter(description = "是否递归返回子目录文件") @RequestParam(value = "recursive", defaultValue = "false") boolean recursive,
            @Parameter(description = "是否仅返回文件") @RequestParam(value = "files_only", defaultValue = "false") boolean filesOnly) {
        String uid = AuthGuards.requireUser();
        if (page < 1) {
            throw new ApiHttpException(422, "page 不能小于 1");
        }
        if (pageSize < 1 || pageSize > 500) {
            throw new ApiHttpException(422, "page_size 必须在 1-500 之间");
        }
        ensureKnowledgeReadAccess(uid, kbId);
        ensureKnowledgeSupportsDocuments(kbId);
        Map<String, Object> data;
        try {
            data = knowledgeBaseManager.listDocumentFiles(
                    kbId, parentId, pathPrefix, null, page, pageSize, recursive, filesOnly, false);
        } catch (KnowledgeBaseException | IllegalArgumentException error) {
            throw knowledgeReadError(error);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        Object items = data.get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) map;
                    entries.add(workspaceKnowledgeEntry(kbId, row));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        result.put("readonly", true);
        result.put("page", data.get("page") == null ? page : data.get("page"));
        result.put("page_size", data.get("page_size") == null ? pageSize : data.get("page_size"));
        result.put("total", data.get("total") == null ? 0 : data.get("total"));
        result.put("has_more", Boolean.TRUE.equals(data.get("has_more")));
        result.put("parent_id", data.get("parent_id"));
        result.put("path_prefix", data.get("path_prefix") == null ? "" : data.get("path_prefix"));
        return result;
    }

    @Operation(summary = "知识库文件预览", description = "query: kb_id / file_id")
    @GetMapping("/knowledge/file")
    public ResponseEntity<Object> getWorkspaceKnowledgeFile(
            @Parameter(description = "知识库 ID") @RequestParam("kb_id") String kbId,
            @Parameter(description = "知识库文件 ID") @RequestParam("file_id") String fileId) {
        String uid = AuthGuards.requireUser();
        ensureKnowledgeReadAccess(uid, kbId);
        ensureKnowledgeSupportsDocuments(kbId);
        Map<String, Object> preview;
        try {
            preview = knowledgeFilePreviewService.readKnowledgeFilePreview(kbId, fileId);
        } catch (KnowledgeBaseException | IllegalArgumentException error) {
            throw knowledgeReadError(error);
        }
        return previewResponse(preview);
    }

    @Operation(summary = "下载知识库文件", description = "query: kb_id / file_id / variant(original|parsed)")
    @GetMapping("/knowledge/download")
    public ResponseEntity<byte[]> downloadWorkspaceKnowledgeFile(
            @Parameter(description = "知识库 ID") @RequestParam("kb_id") String kbId,
            @Parameter(description = "知识库文件 ID") @RequestParam("file_id") String fileId,
            @Parameter(description = "下载模式：original 或 parsed")
            @RequestParam(value = "variant", defaultValue = "original") String variant) {
        String uid = AuthGuards.requireUser();
        ensureKnowledgeReadAccess(uid, kbId);
        Map<String, Object> data;
        try {
            data = knowledgeBaseManager.getFileDownload(kbId, fileId, variant);
        } catch (KnowledgeBaseException | IllegalArgumentException error) {
            throw knowledgeReadError(error);
        }
        String filename = String.valueOf(data.get("filename"));
        byte[] content = (byte[]) data.get("content");
        String mediaType = data.get("media_type") == null
                ? "application/octet-stream" : String.valueOf(data.get("media_type"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + UrlQuote.quote(filename, "/"))
                .body(content);
    }

    // =========================================================================
    // === 内部工具 ===
    // =========================================================================

    /** 参考实现 _ensure_knowledge_read_access。 */
    private void ensureKnowledgeReadAccess(String uid, String kbId) {
        User user = userRepository.getByUid(uid);
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("uid", uid);
        userInfo.put("role", user == null ? null : user.getRole());
        userInfo.put("department_id", user == null ? null : user.getDepartmentId());
        if (!knowledgeBaseManager.checkAccessible(userInfo, kbId)) {
            throw new ApiHttpException(403, "Access denied");
        }
    }

    /** 参考实现 _ensure_knowledge_supports_documents。 */
    private void ensureKnowledgeSupportsDocuments(String kbId) {
        KnowledgeBaseManager.DatabaseDocumentSupport support =
                knowledgeBaseManager.getDatabaseDocumentSupport(kbId);
        if (support.database() == null) {
            throw new ApiHttpException(404, "知识库 " + kbId + " 不存在");
        }
        if (!support.supportsDocuments()) {
            KnowledgeBaseDetail detail = support.database();
            String label = detail.name() == null || detail.name().isEmpty()
                    ? detail.kbType() : detail.name();
            throw new ApiHttpException(501, label + " 不支持文件浏览");
        }
    }

    /** 参考实现 _raise_knowledge_read_error。 */
    private static ApiHttpException knowledgeReadError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = "知识库文件读取失败";
        }
        if (message.startsWith(KB_NOT_SUPPORT_DOCUMENTS_PREFIX)) {
            return new ApiHttpException(501, message);
        }
        return new ApiHttpException(400, message);
    }

    /** 参考实现 _workspace_knowledge_entry。 */
    private static Map<String, Object> workspaceKnowledgeEntry(String kbId, Map<String, Object> item) {
        boolean isDir = Boolean.TRUE.equals(item.get("is_folder"));
        boolean isVirtualFolder = Boolean.TRUE.equals(item.get("is_virtual_folder"));
        Object fileId = item.get("file_id");
        String pathPrefix = item.get("path_prefix") == null ? "" : String.valueOf(item.get("path_prefix"));

        String path;
        if (isVirtualFolder) {
            path = "/knowledge/" + kbId + "/virtual/" + UrlQuote.quote(pathPrefix, "");
        } else if (isDir) {
            path = "/knowledge/" + kbId + "/folder/" + fileId + "/";
        } else {
            path = "/knowledge/" + kbId + "/file/" + fileId;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", "knowledge");
        entry.put("kb_id", kbId);
        entry.put("file_id", fileId);
        entry.put("parent_id", item.get("parent_id"));
        entry.put("path", path);
        entry.put("virtual_path", path);
        entry.put("name", item.get("filename") == null || String.valueOf(item.get("filename")).isEmpty()
                ? fileId : item.get("filename"));
        entry.put("is_dir", isDir);
        entry.put("size", isDir ? 0L : asLong(item.get("file_size")));
        entry.put("modified_at", item.get("updated_at") != null
                ? item.get("updated_at")
                : (item.get("created_at") != null ? item.get("created_at") : ""));
        entry.put("readonly", true);
        entry.put("status", item.get("status") == null ? "done" : item.get("status"));
        entry.put("has_original_file", Boolean.TRUE.equals(item.get("has_original_file")));
        entry.put("has_parsed_markdown", Boolean.TRUE.equals(item.get("has_parsed_markdown")));
        entry.put("is_virtual_folder", isVirtualFolder);
        entry.put("path_prefix", pathPrefix);
        return entry;
    }

    /** 参考实现 _preview_response：带 {@code binary} 标记的预览转为二进制流。 */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> previewResponse(Map<String, Object> data) {
        if (Boolean.TRUE.equals(data.get("binary"))) {
            String filename = data.get("filename") == null ? "preview" : String.valueOf(data.get("filename"));
            String previewType = data.get("preview_type") == null
                    ? "unsupported" : String.valueOf(data.get("preview_type"));
            String mediaType = data.get("media_type") == null
                    ? "application/octet-stream" : String.valueOf(data.get("media_type"));
            byte[] content = data.get("content") instanceof byte[] bytes ? bytes : new byte[0];
            String quoted = UrlQuote.quote(filename, "/");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mediaType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + quoted)
                    .header("X-WenQu-Preview-Type", previewType)
                    .header("X-WenQu-Preview-Filename", quoted)
                    .body(content);
        }
        return ResponseEntity.ok((Object) data);
    }

    /** 工作区预览的二进制响应装配（与 {@link FilesystemController} 同一约定）。 */
    private static ResponseEntity<Object> binaryResponse(FilePreviewService.BinaryPreview binary) {
        String filename = binary.getFilename() == null ? "preview" : binary.getFilename();
        String quoted = UrlQuote.quote(filename, "/");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        binary.getMediaType() == null ? "application/octet-stream" : binary.getMediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + quoted)
                .header(binary.previewTypeHeaderName(), binary.getPreviewType())
                .header(binary.previewFilenameHeaderName(), quoted)
                .body(binary.getContent());
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            throw new ApiHttpException(422, key + " 不能为空");
        }
        return String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
