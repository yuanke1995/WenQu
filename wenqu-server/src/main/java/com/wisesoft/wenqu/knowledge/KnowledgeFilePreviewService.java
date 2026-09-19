package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.common.FilePreviewUtils;
import com.wisesoft.wenqu.common.PreviewResult;
import com.wisesoft.wenqu.models.KnowledgeFile;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Knowledge 文件的 MinIO 预览与持久化 Office 缓存（knowledge/preview.py 全量移植）。
 *
 * <p>逐函数对应关系：
 * <ul>
 *   <li>{@link #readKnowledgeFilePreview} ← read_knowledge_file_preview</li>
 *   <li>{@link #readOfficePdfPreview} ← _read_office_pdf_preview</li>
 *   <li>{@link #getMinioFileSize} ← _get_minio_file_size</li>
 *   <li>{@link #readMinioBytes} ← _read_minio_bytes</li>
 *   <li>{@link #parseMinioPath} ← _parse_minio_path</li>
 * </ul>
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>异步 IO（{@code await minio_client.astat_file/adownload_file/aupload_file}）→ 同步阻塞调用
 *       {@link MinioStorageClient#statFile}/{@link MinioStorageClient#downloadFile}/{@link MinioStorageClient#uploadFile}
 *       （Spring MVC 阻塞栈，语义一致）。
 *   <li>{@code get_minio_client()} 模块单例 → 容器 bean {@link MinioStorageClient}。
 *   <li>预览渲染与 Office 转换复用已移植的 {@link FilePreviewUtils}（render_preview /
 *       convert_office_to_pdf / is_office_pdf_preview_file / preview_too_large /
 *       MAX_BINARY_PREVIEW_SIZE_BYTES），与本工程 workspace 预览同一套实现。
 *   <li>Office PDF 缓存：参考实现写 {@code {kb_id}/preview/{file_id}.pdf} 到 parsed 桶；
 *       本工程照搬同一对象名与桶（缓存语义一致，与 workspace 的本地磁盘缓存不同）。
 *   <li>{@code OfficePreviewConversionError} → 包装为 {@code IllegalArgumentException}
 *       （参考实现 {@code raise ValueError(str(exc)) from exc}）。
 *   <li>返回值：参考实现为 dict（content 可能是 bytes 或 str）；本工程用
 *       {@code Map<String, Object>}，bytes 原样放入 content 键，由上层序列化决定编码。
 * </ul>
 */
@Service
public class KnowledgeFilePreviewService {

    private final KnowledgeFileRepository fileRepository;
    private final MinioStorageClient minioStorageClient;

    public KnowledgeFilePreviewService(KnowledgeFileRepository fileRepository,
                                       MinioStorageClient minioStorageClient) {
        this.fileRepository = fileRepository;
        this.minioStorageClient = minioStorageClient;
    }

    /** 从 Knowledge metadata 与 MinIO 对象生成只读文件预览（对应 read_knowledge_file_preview）。 */
    public Map<String, Object> readKnowledgeFilePreview(String kbId, String fileId) {
        KnowledgeFile fileRecord = fileRepository.getByFileId(fileId);
        if (fileRecord == null || !kbId.equals(fileRecord.getKbId())) {
            throw new IllegalArgumentException("File " + fileId + " not found");
        }
        if (Boolean.TRUE.equals(fileRecord.getIsFolder())) {
            throw new IllegalArgumentException("Cannot preview a folder");
        }

        String filename = firstNonBlank(fileRecord.getFilename(), fileRecord.getOriginalFilename(), fileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", "knowledge");
        response.put("kb_id", kbId);
        response.put("file_id", fileId);
        response.put("filename", filename);
        response.put("readonly", true);

        String originalPath = firstNonBlank(fileRecord.getMinioUrl(), fileRecord.getPath());
        if (originalPath == null) {
            response.put("content", null);
            response.put("preview_type", "unsupported");
            response.put("supported", false);
            response.put("message", "文件没有可预览的原始内容");
            return response;
        }

        Long fileSize = fileRecord.getFileSize();
        if (fileSize == null) {
            fileSize = getMinioFileSize(originalPath);
        }
        if (fileSize != null && fileSize > FilePreviewUtils.MAX_BINARY_PREVIEW_SIZE_BYTES) {
            response.putAll(FilePreviewUtils.previewTooLarge().payload());
            return response;
        }

        if (FilePreviewUtils.isOfficePdfPreviewFile(filename)) {
            byte[] pdfContent = readOfficePdfPreview(kbId, fileId, filename, originalPath);
            response.put("content", pdfContent);
            int dot = filename.lastIndexOf('.');
            String stem = dot < 0 ? "" : filename.substring(0, dot);
            response.put("filename", (stem.isEmpty() ? fileId : stem) + ".pdf");
            response.put("media_type", "application/pdf");
            response.put("preview_type", "pdf");
            response.put("supported", true);
            response.put("message", null);
            response.put("binary", true);
            return response;
        }

        byte[] rawContent = readMinioBytes(originalPath);
        if (rawContent.length > FilePreviewUtils.MAX_BINARY_PREVIEW_SIZE_BYTES) {
            response.putAll(FilePreviewUtils.previewTooLarge().payload());
            return response;
        }
        PreviewResult result = FilePreviewUtils.renderPreview(filename, rawContent);
        if (result.getContent() instanceof byte[] bytes) {
            response.put("content", bytes);
            response.put("media_type", result.getMediaType());
            response.put("preview_type", result.getPreviewType());
            response.put("supported", result.isSupported());
            response.put("message", result.getMessage());
            response.put("binary", true);
            return response;
        }
        response.putAll(result.payload());
        return response;
    }

    /** 读取或生成 Office → PDF 预览（对应 _read_office_pdf_preview）。 */
    private byte[] readOfficePdfPreview(String kbId, String fileId, String filename, String originalPath) {
        String bucketName = MinioStorageClient.KB_BUCKETS.get("parsed");
        String objectName = kbId + "/preview/" + fileId + ".pdf";
        if (minioStorageClient.statFile(bucketName, objectName) != null) {
            return minioStorageClient.downloadFile(bucketName, objectName);
        }

        byte[] rawContent = readMinioBytes(originalPath);
        byte[] pdfContent;
        try {
            pdfContent = FilePreviewUtils.convertOfficeToPdf(filename, rawContent);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(String.valueOf(exception.getMessage()), exception);
        }
        minioStorageClient.uploadFile(bucketName, objectName, pdfContent, "application/pdf");
        return pdfContent;
    }

    /** 对象大小（对应 _get_minio_file_size）。 */
    private Long getMinioFileSize(String filePath) {
        String[] parsed = parseMinioPath(filePath);
        return minioStorageClient.statFile(parsed[0], parsed[1]);
    }

    /** 读取对象字节（对应 _read_minio_bytes）。 */
    private byte[] readMinioBytes(String filePath) {
        String[] parsed = parseMinioPath(filePath);
        return minioStorageClient.downloadFile(parsed[0], parsed[1]);
    }

    /** 校验并解析 MinIO 路径（对应 _parse_minio_path）。 */
    static String[] parseMinioPath(String filePath) {
        if (filePath == null || filePath.isEmpty() || !KbUtils.isMinioUrl(filePath)) {
            throw new IllegalArgumentException("Invalid MinIO path format: " + filePath);
        }
        return KbUtils.parseMinioUrl(filePath);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
