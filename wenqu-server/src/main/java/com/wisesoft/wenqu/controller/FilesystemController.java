package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.UrlQuote;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.service.FilePreviewService;
import com.wisesoft.wenqu.service.ViewerFilesystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Viewer 文件系统路由，逐端点对齐参考实现 {@code server/routers/filesystem_router.py}（前缀 {@code /viewer/filesystem}）。
 *
 * <p>响应契约照搬：
 * <ul>
 *   <li>树/文件内容/目录创建/上传/搜索/删除 → JSON（服务层装配的 Map）；</li>
 *   <li>文件内容为图片/PDF 二进制时 → 二进制流，响应头
 *       {@code Content-Disposition: inline; filename*=UTF-8''...} +
 *       {@code X-WenQu-Preview-Type} / {@code X-WenQu-Preview-Filename}
 *       （参考实现原为对标产品前缀的 {@code X-*-Preview-*}，按本产品命名替换，见 FilePreviewService 说明）；</li>
 *   <li>下载 → {@code Content-Disposition: attachment; filename*=UTF-8''...} 的流式响应，
 *       流结束即删除临时文件（对应参考实现的 {@code BackgroundTask(os.unlink, temp_path)}）。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * {@code UploadFile} → {@link MultipartFile}；{@code FileResponse} → {@code ResponseEntity<StreamingResponseBody>}；
 * {@code urllib.parse.quote} → {@link UrlQuote#quote}。
 */
@Slf4j
@RestController
@RequestMapping("/api/viewer/filesystem")
@RequiredArgsConstructor
@Tag(name = "viewer-filesystem", description = "Viewer 文件系统视图")
public class FilesystemController {

    private final ViewerFilesystemService viewerFilesystemService;

    @Operation(summary = "文件树", description = "线程工作目录树")
    @GetMapping("/tree")
    public Map<String, Object> getViewerTree(
            @Parameter(description = "线程 ID") @RequestParam("thread_id") String threadId,
            @Parameter(description = "目录路径") @RequestParam(value = "path", defaultValue = "/") String path) {
        String uid = AuthGuards.requireUser();
        return viewerFilesystemService.listViewerFilesystemTree(threadId, path, uid);
    }

    @Operation(summary = "读取文件预览", description = "文本返回 JSON；图片/PDF 返回二进制流")
    @GetMapping("/file")
    public ResponseEntity<Object> getViewerFile(
            @Parameter(description = "线程 ID") @RequestParam("thread_id") String threadId,
            @Parameter(description = "文件路径") @RequestParam("path") String path) {
        String uid = AuthGuards.requireUser();
        Object preview = viewerFilesystemService.readViewerFileContent(threadId, path, uid);
        if (preview instanceof FilePreviewService.BinaryPreview binary) {
            String filename = binary.getFilename() == null ? "preview" : binary.getFilename();
            String quoted = UrlQuote.quote(filename, "/");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            binary.getMediaType() == null ? "application/octet-stream" : binary.getMediaType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + quoted)
                    .header("X-WenQu-Preview-Type", binary.getPreviewType())
                    .header("X-WenQu-Preview-Filename", quoted)
                    .body(binary.getContent());
        }
        return ResponseEntity.ok(preview);
    }

    @Operation(summary = "删除文件", description = "实时删除 Workdir 内文件或目录")
    @DeleteMapping("/file")
    public Map<String, Object> deleteViewerFileRoute(
            @Parameter(description = "线程 ID") @RequestParam("thread_id") String threadId,
            @Parameter(description = "文件路径") @RequestParam("path") String path) {
        String uid = AuthGuards.requireUser();
        return viewerFilesystemService.deleteViewerFile(threadId, path, uid);
    }

    @Operation(summary = "创建目录", description = "body: thread_id / parent_path / name")
    @PostMapping("/directory")
    public Map<String, Object> createViewerDirectoryRoute(@RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        String threadId = body.get("thread_id") == null ? null : String.valueOf(body.get("thread_id"));
        String parentPath = body.get("parent_path") == null ? null : String.valueOf(body.get("parent_path"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        return viewerFilesystemService.createViewerDirectory(threadId, parentPath, name, uid);
    }

    @Operation(summary = "上传文件", description = "multipart：thread_id / parent_path / files[]")
    @PostMapping("/upload")
    public Map<String, Object> uploadViewerFilesRoute(
            @Parameter(description = "线程 ID") @RequestParam("thread_id") String threadId,
            @Parameter(description = "父目录路径") @RequestParam("parent_path") String parentPath,
            @Parameter(description = "上传文件列表") @RequestParam("files") List<MultipartFile> files) {
        String uid = AuthGuards.requireUser();
        return viewerFilesystemService.uploadViewerFiles(threadId, parentPath, files, uid);
    }

    @Operation(summary = "文件搜索", description = "线程工作目录内按关键词搜索")
    @GetMapping("/search")
    public Map<String, Object> searchViewerFilesRoute(
            @Parameter(description = "线程 ID") @RequestParam("thread_id") String threadId,
            @Parameter(description = "搜索关键词") @RequestParam("query") String query) {
        String uid = AuthGuards.requireUser();
        return viewerFilesystemService.searchViewerFiles(threadId, query, uid);
    }

    @Operation(summary = "下载文件", description = "流式下载；流结束即清理临时文件")
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadViewer(
            @Parameter(description = "线程 ID") @RequestParam("thread_id") String threadId,
            @Parameter(description = "文件路径") @RequestParam("path") String path) {
        String uid = AuthGuards.requireUser();
        ViewerFilesystemService.ViewerDownload download = viewerFilesystemService.downloadViewerFile(threadId, path, uid);
        Path tempFile = Path.of(download.getTempPath());
        StreamingResponseBody body = outputStream -> {
            try (InputStream input = Files.newInputStream(tempFile)) {
                input.transferTo(outputStream);
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception cleanupError) {
                    log.warn("临时下载文件清理失败: {}", tempFile, cleanupError);
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        download.getMediaType() == null ? "application/octet-stream" : download.getMediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + UrlQuote.quote(
                                download.getFileName() == null ? "download" : download.getFileName(), "/"))
                .body(body);
    }
}
