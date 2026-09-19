package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.common.UploadUtils;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.AgentRunRequestRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import com.wisesoft.wenqu.workspace.Workdir;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对话附件服务（services/attachment_service.py 全量移植）。
 *
 * <p>必要替换：FastAPI {@code UploadFile} → Spring {@link MultipartFile}（见
 * {@link UploadUtils}）；{@code asyncio.gather(..., return_exceptions=True)} →
 * 顺序删除并吞异常（本工程无事件循环，失败仅告警的语义一致）；
 * {@code HTTPException(status)} → {@link com.wisesoft.wenqu.common.BizException} 同码。
 */
@Slf4j
@Service
public class AttachmentService {

    /** 参考实现 ATTACHMENT_ALLOWED_EXTENSIONS: tuple[str, ...] = ()。 */
    public static final List<String> ATTACHMENT_ALLOWED_EXTENSIONS = List.of();
    public static final int MAX_ATTACHMENT_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    // TODO: 转 MARKDOWN的时候，不应该裁剪（参考实现原注释保留）
    public static final int MAX_ATTACHMENT_MARKDOWN_CHARS = 32_000;
    public static final String TMP_ATTACHMENT_PREFIX = "tmp/chat_attachments";
    public static final List<String> TMP_ATTACHMENT_PARSE_EXTENSIONS;
    public static final List<String> TMP_ATTACHMENT_IMAGE_EXTENSIONS =
            ParserCapabilities.IMAGE_FILE_EXTENSIONS;
    public static final Duration TMP_ATTACHMENT_TTL = Duration.ofHours(24);

    static {
        List<String> parseExtensions = new ArrayList<>(ParserCapabilities.PDF_FILE_EXTENSIONS);
        parseExtensions.addAll(ParserCapabilities.IMAGE_FILE_EXTENSIONS);
        TMP_ATTACHMENT_PARSE_EXTENSIONS = List.copyOf(parseExtensions);
    }

    private final ConversationRepository conversationRepository;
    private final WorkdirService workdirService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunRequestRepository agentRunRequestRepository;
    private final OptionsService optionsService;
    private final OcrService ocrService;

    public AttachmentService(
            ConversationRepository conversationRepository,
            WorkdirService workdirService,
            AgentRunRepository agentRunRepository,
            AgentRunRequestRepository agentRunRequestRepository,
            OptionsService optionsService,
            OcrService ocrService) {
        this.conversationRepository = conversationRepository;
        this.workdirService = workdirService;
        this.agentRunRepository = agentRunRepository;
        this.agentRunRequestRepository = agentRunRequestRepository;
        this.optionsService = optionsService;
        this.ocrService = ocrService;
    }

    private Conversation requireUserConversation(String threadId, String uid) {
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        if (conversation == null
                || !String.valueOf(conversation.getUid()).equals(uid)
                || "deleted".equals(conversation.getStatus())) {
            throw new com.wisesoft.wenqu.common.BizException(404, "对话线程不存在");
        }
        return conversation;
    }

    static TruncatedMarkdown truncateMarkdown(String markdown) {
        if (markdown.length() <= MAX_ATTACHMENT_MARKDOWN_CHARS) {
            return new TruncatedMarkdown(markdown, false);
        }
        String truncatedContent = markdown.substring(0, MAX_ATTACHMENT_MARKDOWN_CHARS - 100).stripTrailing();
        truncatedContent = truncatedContent
                + "\n\n[内容已截断，超出 " + MAX_ATTACHMENT_MARKDOWN_CHARS + " 字符限制]";
        return new TruncatedMarkdown(truncatedContent, true);
    }

    record TruncatedMarkdown(String markdown, boolean truncated) {}

    static String safeFileName(String fileName, String defaultName) {
        String value = fileName == null ? "" : fileName;
        // 参考 Path(file_name).name 在 POSIX 语义下只认 '/' 为分隔符；'\\' 在 name 之后才被替换为 '_'
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replace("/", "_").replace("\\", "_");
        value = value.replaceAll("^[ .]+|[ .]+$", "");
        return value.isEmpty() ? defaultName : value;
    }

    static String safeFileName(String fileName) {
        return safeFileName(fileName, "attachment.bin");
    }

    /** 生成附件在沙盒用户目录中的统一路径。 */
    static String makeAttachmentPath(String fileName) {
        fileName = safeFileName(fileName);
        String baseName = fileName;
        for (String ext : List.of(".docx", ".txt", ".html", ".htm", ".pdf", ".md")) {
            if (fileName.toLowerCase().endsWith(ext)) {
                baseName = fileName.substring(0, fileName.length() - ext.length());
                break;
            }
        }

        String safeName = baseName.replace("/", "_").replace("\\", "_");
        return safeName + ".md";
    }

    static String artifactUrl(String threadId, String virtualPath) {
        return "/api/chat/thread/" + threadId + "/artifacts/" + virtualPath.replaceFirst("^/+", "");
    }

    static String tmpAttachmentPrefix(String uid, String tmpFileId) {
        return TMP_ATTACHMENT_PREFIX + "/" + uid + "/" + tmpFileId;
    }

    /** 生成用户隔离的 tmp 对象路径。 */
    static TmpAttachmentObject makeTmpAttachmentObject(String uid, String fileName) {
        String tmpFileId = UUID.randomUUID().toString().replace("-", "");
        String safeName = safeFileName(fileName);
        return new TmpAttachmentObject(
                tmpFileId, tmpAttachmentPrefix(uid, tmpFileId) + "/original/" + safeName);
    }

    record TmpAttachmentObject(String tmpFileId, String objectName) {}

    static String makeTmpParsedObject(String uid, String tmpFileId, String fileName) {
        String safeName = safeFileName(fileName);
        String stem = PosixStem.stem(safeName);
        return tmpAttachmentPrefix(uid, tmpFileId) + "/parsed/" + (stem.isEmpty() ? "attachment" : stem) + ".md";
    }

    static String minioSource(String bucketName, String objectName) {
        // Python quote(object_name, safe='/')
        String encoded = com.wisesoft.wenqu.common.UrlQuote.quote(objectName, "/");
        return "minio://" + bucketName + "/" + encoded;
    }

    static String[] parseUserTmpObject(String objectName, String uid) {
        if (objectName == null || objectName.isEmpty() || objectName.contains("\\")) {
            throw new com.wisesoft.wenqu.common.BizException(400, "无效的临时附件路径");
        }

        String userPrefix = TMP_ATTACHMENT_PREFIX + "/" + uid + "/";
        if (!objectName.startsWith(userPrefix)) {
            throw new com.wisesoft.wenqu.common.BizException(403, "无权访问该临时附件");
        }

        String[] parts = objectName.substring(userPrefix.length()).split("/", -1);
        if (parts.length != 3) {
            throw new com.wisesoft.wenqu.common.BizException(400, "无效的临时附件路径");
        }
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new com.wisesoft.wenqu.common.BizException(400, "无效的临时附件路径");
            }
        }

        return parts;
    }

    static String[] requireTmpObjectSection(String objectName, String uid, String section, String tmpFileId) {
        String[] parts = parseUserTmpObject(objectName, uid);
        String currentTmpFileId = parts[0];
        String currentSection = parts[1];
        String objectFileName = parts[2];
        if (!currentSection.equals(section)
                || (tmpFileId != null && !currentTmpFileId.equals(tmpFileId))) {
            throw new com.wisesoft.wenqu.common.BizException(400, "无效的临时附件路径");
        }
        if ("parsed".equals(section)
                && !".md".equals(com.wisesoft.wenqu.common.PosixPathLite.suffixOf(objectFileName).toLowerCase())) {
            throw new com.wisesoft.wenqu.common.BizException(400, "无效的解析附件路径");
        }
        return new String[] {currentTmpFileId, objectFileName};
    }

    /** 按文件类型确定临时附件解析方式。 */
    String normalizeParseMethod(String fileName, String parseMethod, String defaultOcrEngine) {
        String suffix = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(fileName).toLowerCase();
        if (!TMP_ATTACHMENT_PARSE_EXTENSIONS.contains(suffix)) {
            throw new com.wisesoft.wenqu.common.BizException(400, "当前仅支持 PDF 和图片附件解析");
        }

        List<String> allowedMethods = new ArrayList<>(ParserCapabilities.getOcrEnginesForExtension(suffix));
        String method;
        if (TMP_ATTACHMENT_IMAGE_EXTENSIONS.contains(suffix)) {
            method = parseMethod != null ? parseMethod
                    : ("disable".equals(defaultOcrEngine) ? "rapid_ocr" : defaultOcrEngine);
            // 参考实现：图片的可选方式即 get_ocr_engines_for_extension(suffix)
        } else {
            method = parseMethod != null ? parseMethod : "disable";
            allowedMethods.add(0, "disable");
        }

        if (!allowedMethods.contains(method)) {
            String allowed = String.join(", ", allowedMethods);
            throw new com.wisesoft.wenqu.common.BizException(
                    400, "不支持的解析方法: " + method + "，可选: " + allowed);
        }
        return method;
    }

    /** 输出附件 API 结构，并从路径派生无需持久化的 URL。 */
    public static Map<String, Object> serializeAttachment(Map<String, Object> record, String threadId) {
        Object path = record.get("path");
        Object originalPath = record.get("original_path");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_id", record.get("file_id"));
        result.put("file_name", record.get("file_name"));
        result.put("file_type", record.get("file_type"));
        result.put("file_size", record.get("file_size") == null ? 0 : record.get("file_size"));
        result.put("status", record.get("status") == null ? "uploaded" : record.get("status"));
        result.put("uploaded_at", record.get("uploaded_at"));
        result.put("path", path);
        result.put("artifact_url", path instanceof String text ? artifactUrl(threadId, text) : null);
        result.put("original_path", originalPath);
        result.put(
                "original_artifact_url",
                originalPath instanceof String text ? artifactUrl(threadId, text) : null);
        result.put("request_id", record.get("request_id"));
        return result;
    }

    /** 通过受信任 no-follow 文件边界写入实时 Workdir（对应 _write_workdir_file）。 */
    private void writeWorkdirFile(Workdir workdir, String path, byte[] content) {
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("wenqu-attachment-", ".tmp");
            Files.write(tempPath, content);
            workdir.copyFileFromPath(path, tempPath.toString(), true);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // 参考实现 except FileNotFoundError: pass
                } catch (IOException ignored) {
                    // 清理失败不掩盖原始异常
                }
            }
        }
    }

    /** 将正式附件直接写入实时 Project Workdir（对应 _store_attachment）。 */
    Map<String, Object> storeAttachment(
            Workdir workdir,
            String fileId,
            String fileName,
            String fileType,
            byte[] fileContent,
            String parsedMarkdown) {
        fileName = safeFileName(fileName);
        String storageName = fileId + "_" + fileName;
        String originalScope = "/uploads/" + storageName;
        writeWorkdirFile(workdir, originalScope, fileContent);
        String originalPath = BackendPaths.runtimePathForWorkdirScope(workdir.getRelativePath(), originalScope);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("file_id", fileId);
        record.put("file_name", fileName);
        record.put("file_type", fileType);
        record.put("file_size", fileContent.length);
        record.put("status", "uploaded");
        record.put("uploaded_at", com.wisesoft.wenqu.common.DateTimeUtils.utcIsoformat());
        record.put("path", originalPath);
        record.put("original_path", originalPath);
        if (parsedMarkdown == null) {
            return record;
        }

        String markdownScope = "/uploads/attachments/" + makeAttachmentPath(storageName);
        String markdownPath = BackendPaths.runtimePathForWorkdirScope(workdir.getRelativePath(), markdownScope);
        try {
            writeWorkdirFile(workdir, markdownScope, parsedMarkdown.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exc) {
            workdir.delete(originalScope);
            throw exc;
        }
        record.put("status", "parsed");
        record.put("path", markdownPath);
        return record;
    }

    /** 尽力删除本批尚未提交的附件文件（对应 _rollback_stored_attachments）。 */
    private void rollbackStoredAttachments(Workdir workdir, List<Map<String, Object>> records) {
        for (Map<String, Object> record : records) {
            Set<Object> paths = new HashSet<>();
            paths.add(record.get("path"));
            paths.add(record.get("original_path"));
            for (Object pathObj : paths) {
                if (!(pathObj instanceof String path)) {
                    continue;
                }
                try {
                    String scope = BackendPaths.workdirScopeFromRuntimePath(workdir.getRelativePath(), path);
                    workdir.delete(scope);
                } catch (RuntimeException ignored) {
                    // 参考实现 except Exception: pass
                }
            }
        }
    }

    /** 上传时顺手清理当前用户 24 小时前遗留的临时附件（对应 _cleanup_expired_tmp_attachments）。 */
    void cleanupExpiredTmpAttachments(MinioStorageClient minioClient, String bucketName, String uid) {
        String prefix = TMP_ATTACHMENT_PREFIX + "/" + uid + "/";
        List<Map<String, Object>> objects;
        try {
            objects = minioClient.listObjectMetadata(bucketName, prefix);
        } catch (MinioStorageClient.StorageError exc) {
            log.warn("列出过期临时附件失败: uid={} error={}", uid, exc.getMessage());
            return;
        }

        Map<String, Instant> latestByTmpId = new LinkedHashMap<>();
        for (Map<String, Object> item : objects) {
            Object objectNameObj = item.get("object_name");
            Object lastModifiedObj = item.get("last_modified");
            if (!(objectNameObj instanceof String objectName)
                    || !(lastModifiedObj instanceof Instant modifiedAt)) {
                continue;
            }
            try {
                parseUserTmpObject(objectName, uid);
            } catch (com.wisesoft.wenqu.common.BizException ignored) {
                continue;
            }
            Instant current = modifiedAt;
            Instant previous = latestByTmpId.get(objectTmpId(objectName, uid));
            if (previous == null || current.isAfter(previous)) {
                latestByTmpId.put(objectTmpId(objectName, uid), current);
            }
        }

        Instant cutoff = Instant.now().minus(TMP_ATTACHMENT_TTL);
        List<String> expiredIds = latestByTmpId.entrySet().stream()
                .filter(entry -> !entry.getValue().isAfter(cutoff))
                .map(Map.Entry::getKey)
                .toList();
        for (String tmpFileId : expiredIds) {
            try {
                minioClient.deleteObjectsByPrefix(bucketName, tmpAttachmentPrefix(uid, tmpFileId) + "/");
            } catch (RuntimeException exc) {
                log.warn("清理过期临时附件失败: uid={} tmp_file_id={} error={}", uid, tmpFileId, exc.getMessage());
            }
        }
    }

    private String objectTmpId(String objectName, String uid) {
        return parseUserTmpObject(objectName, uid)[0];
    }

    /** 上传附件到用户隔离的 MinIO tmp 路径（对应 upload_tmp_attachment_view）。 */
    public Map<String, Object> uploadTmpAttachment(MultipartFile file, String currentUid) {
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty()) {
            throw new com.wisesoft.wenqu.common.BizException(400, "无法识别的文件名");
        }

        String fileName = safeFileName(file.getOriginalFilename());
        byte[] fileContent;
        try {
            fileContent = UploadUtils.readUploadWithLimit(
                    file,
                    MAX_ATTACHMENT_SIZE_BYTES,
                    "附件过大，当前仅支持 5 MB 以内的文件");
        } catch (UploadUtils.SizeLimitExceededException | IOException exc) {
            throw new com.wisesoft.wenqu.common.BizException(400, String.valueOf(exc.getMessage()));
        }

        int fileSize = fileContent.length;
        TmpAttachmentObject tmpObject = makeTmpAttachmentObject(currentUid, fileName);
        MinioStorageClient minioClient = MinioStorageClient.getInstance();
        String bucketName = minioClient.KB_BUCKETS.get("documents");
        MinioStorageClient.UploadResult uploadResult;
        try {
            uploadResult = minioClient.uploadFile(
                    bucketName, tmpObject.objectName(), fileContent, file.getContentType());
        } catch (MinioStorageClient.StorageError exc) {
            throw new com.wisesoft.wenqu.common.BizException(500, "临时附件上传失败: " + exc.getMessage());
        }
        cleanupExpiredTmpAttachments(minioClient, bucketName, currentUid);

        String suffix = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(fileName).toLowerCase();
        List<String> parseMethods = new ArrayList<>();
        if (TMP_ATTACHMENT_PARSE_EXTENSIONS.contains(suffix)) {
            parseMethods.addAll(ParserCapabilities.getOcrEnginesForExtension(suffix));
            if (ParserCapabilities.PDF_FILE_EXTENSIONS.contains(suffix)) {
                parseMethods.add(0, "disable");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_name", fileName);
        result.put("file_type", file.getContentType());
        result.put("file_size", fileSize);
        result.put("object_name", uploadResult.objectName());
        result.put("uploaded_at", com.wisesoft.wenqu.common.DateTimeUtils.utcIsoformat());
        result.put("parse_supported", !parseMethods.isEmpty());
        result.put("parse_methods", parseMethods);
        return result;
    }

    /** 解析用户 tmp 附件并把 markdown 写回 tmp（对应 parse_tmp_attachment_view）。 */
    public Map<String, Object> parseTmpAttachment(String objectName, String parseMethod, String currentUid) {
        MinioStorageClient minioClient = MinioStorageClient.getInstance();
        String bucketName = minioClient.KB_BUCKETS.get("documents");

        String[] section = requireTmpObjectSection(objectName, currentUid, "original", null);
        String tmpFileId = section[0];
        String safeName = section[1];
        String defaultOcrEngine = "rapid_ocr";
        if (parseMethod == null
                && TMP_ATTACHMENT_IMAGE_EXTENSIONS.contains(
                        com.wisesoft.wenqu.common.PosixPathLite.suffixOf(safeName).toLowerCase())) {
            defaultOcrEngine = String.valueOf(
                    optionsService.get(OptionsService.SYSTEM_OPTIONS).get("default_ocr_engine"));
        }
        String method = normalizeParseMethod(safeName, parseMethod, defaultOcrEngine);

        boolean truncated;
        MinioStorageClient.UploadResult uploadResult;
        try {
            String markdown = ocrService.parseDocument(
                    minioSource(bucketName, objectName), Map.of("ocr_engine", method));
            TruncatedMarkdown truncatedMarkdown = truncateMarkdown(markdown);
            truncated = truncatedMarkdown.truncated();
            String markdownText = truncatedMarkdown.markdown();
            String parsedObjectName = makeTmpParsedObject(currentUid, tmpFileId, safeName);
            uploadResult = minioClient.uploadFile(
                    bucketName,
                    parsedObjectName,
                    markdownText.getBytes(StandardCharsets.UTF_8),
                    "text/markdown; charset=utf-8");
        } catch (MinioStorageClient.StorageError exc) {
            throw new com.wisesoft.wenqu.common.BizException(400, "读取临时附件失败: " + exc.getMessage());
        } catch (com.wisesoft.wenqu.common.BizException exc) {
            throw exc;
        } catch (RuntimeException exc) {
            log.warn("Tmp attachment parse failed for {}: {}", safeName, exc.getMessage());
            throw new com.wisesoft.wenqu.common.BizException(400, "附件解析失败: " + exc.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parsed_object_name", uploadResult.objectName());
        result.put("parse_method", method);
        result.put("status", "parsed");
        result.put("truncated", truncated);
        return result;
    }

    /** 将选中的 tmp 附件正式关联到对话线程（对应 confirm_tmp_thread_attachments_view）。 */
    public Map<String, Object> confirmTmpThreadAttachments(
            String threadId, List<Map<String, Object>> attachments, String currentUid) {
        if (attachments == null || attachments.isEmpty()) {
            throw new com.wisesoft.wenqu.common.BizException(400, "请选择要添加的附件");
        }

        Conversation conversation = requireUserConversation(threadId, currentUid);
        WorkdirService.AuthorizedWorkdir binding =
                workdirService.resolveAuthorizedConversationWorkdir(conversation, currentUid);
        Workdir workdir = binding.workdir();
        MinioStorageClient minioClient = MinioStorageClient.getInstance();
        String bucketName = minioClient.KB_BUCKETS.get("documents");
        List<Map<String, Object>> addedRecords = new ArrayList<>();
        List<String> confirmedTmpIds = new ArrayList<>();
        try {
            for (Map<String, Object> item : attachments) {
                String objectName = item.get("object_name") == null ? "" : String.valueOf(item.get("object_name"));
                String[] section = requireTmpObjectSection(objectName, currentUid, "original", null);
                String tmpFileId = section[0];
                String fileName = section[1];
                byte[] fileContent;
                try {
                    fileContent = minioClient.downloadFile(bucketName, objectName);
                } catch (MinioStorageClient.StorageError exc) {
                    throw new com.wisesoft.wenqu.common.BizException(400, "读取临时附件失败: " + exc.getMessage());
                }

                if (fileContent.length > MAX_ATTACHMENT_SIZE_BYTES) {
                    int maxSizeMb = MAX_ATTACHMENT_SIZE_BYTES / (1024 * 1024);
                    throw new com.wisesoft.wenqu.common.BizException(
                            400, "附件过大，当前仅支持 " + maxSizeMb + " MB 以内的文件");
                }

                String parsedMarkdown = null;
                String parsedObjectName = item.get("parsed_object_name") == null
                        ? ""
                        : String.valueOf(item.get("parsed_object_name"));
                if (!parsedObjectName.isEmpty()) {
                    requireTmpObjectSection(parsedObjectName, currentUid, "parsed", tmpFileId);
                    String expectedParsedObject = makeTmpParsedObject(currentUid, tmpFileId, fileName);
                    if (!parsedObjectName.equals(expectedParsedObject)) {
                        throw new com.wisesoft.wenqu.common.BizException(400, "解析附件路径无效");
                    }
                    byte[] parsedBytes;
                    try {
                        parsedBytes = minioClient.downloadFile(bucketName, parsedObjectName);
                    } catch (MinioStorageClient.StorageError exc) {
                        throw new com.wisesoft.wenqu.common.BizException(400, "读取解析附件失败: " + exc.getMessage());
                    }
                    try {
                        parsedMarkdown = strictUtf8(parsedBytes);
                    } catch (RuntimeException exc) {
                        throw new com.wisesoft.wenqu.common.BizException(400, "解析附件内容不是有效的 Markdown 文本");
                    }
                }

                String fileId = UUID.randomUUID().toString().replace("-", "");
                Map<String, Object> attachmentRecord = storeAttachment(
                        workdir, fileId, fileName,
                        item.get("file_type") == null ? null : String.valueOf(item.get("file_type")),
                        fileContent, parsedMarkdown);
                addedRecords.add(attachmentRecord);
                confirmedTmpIds.add(tmpFileId);
            }
        } catch (RuntimeException exc) {
            rollbackStoredAttachments(workdir, addedRecords);
            throw exc;
        }

        try {
            conversationRepository.addAttachments(conversation.getId(), addedRecords);
        } catch (RuntimeException exc) {
            rollbackStoredAttachments(workdir, addedRecords);
            throw exc;
        }

        for (String tmpFileId : confirmedTmpIds) {
            try {
                minioClient.deleteObjectsByPrefix(bucketName, tmpAttachmentPrefix(currentUid, tmpFileId) + "/");
            } catch (RuntimeException exc) {
                log.warn("清理已确认临时附件失败: tmp_file_id={} error={}", tmpFileId, exc.getMessage());
            }
        }

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Map<String, Object> item : addedRecords) {
            serialized.add(serializeAttachment(item, threadId));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attachments", serialized);
        return result;
    }

    /** 列出指定对话线程的附件（对应 list_thread_attachments_view）。 */
    public Map<String, Object> listThreadAttachments(String threadId, String currentUid) {
        Conversation conversation = requireUserConversation(threadId, currentUid);
        List<Map<String, Object>> attachments = conversationRepository.getAttachments(conversation.getId());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("allowed_extensions",
                ATTACHMENT_ALLOWED_EXTENSIONS.stream().sorted().toList());
        limits.put("max_size_bytes", MAX_ATTACHMENT_SIZE_BYTES);
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Map<String, Object> item : attachments) {
            serialized.add(serializeAttachment(item, threadId));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attachments", serialized);
        result.put("limits", limits);
        return result;
    }

    /** 删除指定对话线程的附件（对应 delete_thread_attachment_view）。 */
    public Map<String, Object> deleteThreadAttachment(String threadId, String fileId, String currentUid) {
        Conversation conversation = requireUserConversation(threadId, currentUid);
        WorkdirService.AuthorizedWorkdir binding =
                workdirService.resolveAuthorizedConversationWorkdir(conversation, currentUid);
        Workdir workdir = binding.workdir();

        List<Map<String, Object>> existingAttachments = conversationRepository.lockAttachments(conversation.getId());
        Map<String, Object> targetAttachment = existingAttachments.stream()
                .filter(item -> fileId.equals(item.get("file_id")))
                .findFirst()
                .orElse(null);
        if (targetAttachment == null) {
            throw new com.wisesoft.wenqu.common.BizException(404, "附件不存在或已被删除");
        }

        Object requestIdObj = targetAttachment.get("request_id");
        if (requestIdObj instanceof String requestId && !requestId.isEmpty()) {
            com.wisesoft.wenqu.models.AgentRunRequest request =
                    agentRunRequestRepository.getByRequestId(requestId);
            if (request != null && "queued".equals(request.getStatus())) {
                throw new com.wisesoft.wenqu.common.BizException(409, "附件正在被请求使用，暂时不能删除");
            }
        }

        com.wisesoft.wenqu.models.AgentRun activeRun = agentRunRepository.getActiveRunByThreadForUser(
                conversation.getAgentId(), threadId, currentUid);
        if (activeRun != null) {
            throw new com.wisesoft.wenqu.common.BizException(409, "对话正在运行，暂时不能删除附件");
        }

        boolean removed = conversationRepository.removeAttachment(conversation.getId(), fileId);
        if (!removed) {
            throw new com.wisesoft.wenqu.common.BizException(404, "附件不存在或已被删除");
        }

        Set<Object> paths = new HashSet<>();
        paths.add(targetAttachment.get("path"));
        paths.add(targetAttachment.get("original_path"));
        for (Object pathObj : paths) {
            if (!(pathObj instanceof String path)) {
                continue;
            }
            try {
                String scope = BackendPaths.workdirScopeFromRuntimePath(workdir.getRelativePath(), path);
                workdir.delete(scope);
            } catch (com.wisesoft.wenqu.workspace.Workspace.NoSuchFileRuntime ignored) {
                // 参考实现 except FileNotFoundError: pass
            } catch (RuntimeException exc) {
                // PostgreSQL 已经移除 shipping 引用；残留文件仍留在用户可见 Workdir，后续可显式清理。
                log.warn("附件元数据已删除，但 Workdir 文件清理失败: thread={} path={} error={}",
                        threadId, path, exc.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "附件已删除");
        return result;
    }

    /** 参考 content.decode("utf-8")：严格模式（非法序列 → 无效 Markdown 文本）。 */
    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exc) {
            throw new IllegalArgumentException("invalid markdown encoding");
        }
    }

    /** pathlib.Path.stem 的最小实现。 */
    private static final class PosixStem {

        static String stem(String path) {
            String name = path;
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                return name;
            }
            return name.substring(0, dot);
        }
    }
}
