package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.service.ChunkPresets;
import com.wisesoft.wenqu.storage.MinioStorageClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库通用工具（knowledge/utils/kb_utils.py 的部分移植）。
 *
 * <p>本批新增 {@code sanitize_processing_params}/{@code params_for_uploaded_document}/
 * {@code merge_processing_params}/{@code _normalize_source_path}（供知识库文件摄取编排消费）；
 * 此前已移植 {@code build_kb_image_proxy_url}/{@code is_minio_url}/{@code parse_minio_url}。
 */
public final class KbUtils {

    private KbUtils() {}

    /** 不应写入单文件元数据的参数键（对应参考 _DROPPED_PROCESSING_PARAM_KEYS）。 */
    private static final Set<String> DROPPED_PROCESSING_PARAM_KEYS = Set.of(
            "_preprocessed_map",
            "auto_index",
            "content_hashes",
            "file_sizes",
            "enable_ocr",
            "ocr_engine_config");

    /** 移除不应写入单文件元数据的参数（对应 sanitize_processing_params）。 */
    public static Map<String, Object> sanitizeProcessingParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!DROPPED_PROCESSING_PARAM_KEYS.contains(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /**
     * 将批量上传参数收敛为单个文档的处理参数（对应 params_for_uploaded_document）。
     * source_paths 中命中 item 时写入单文件的 source_path；source_paths 本身不入单文件参数。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> paramsForUploadedDocument(String item, Map<String, Object> params) {
        if (params == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> itemParams = new LinkedHashMap<>(params);
        Object sourcePaths = itemParams.remove("source_paths");
        if (sourcePaths instanceof Map<?, ?> sp && sp.get(item) != null) {
            itemParams.put("source_path", sp.get(item));
        }
        return itemParams;
    }

    /**
     * 合并处理参数：优先使用请求参数，缺失时使用元数据中的参数（对应 merge_processing_params）。
     */
    public static Map<String, Object> mergeProcessingParams(Map<String, Object> metadataParams,
                                                            Map<String, Object> requestParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (metadataParams != null) {
            merged.putAll(metadataParams);
        }
        if (requestParams != null) {
            merged.putAll(requestParams);
        }
        return merged;
    }

    /**
     * 归一化客户端传入的上传源路径，仅用于知识库文件树中的展示文件名（对应 _normalize_source_path）。
     * 反斜杠转斜杠、去掉开头 "./"、拒绝绝对路径与 ".." 父目录跳转。
     */
    public static String normalizeSourcePath(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String normalized = s.strip().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }
        String[] rawParts = normalized.split("/");
        StringBuilder filtered = new StringBuilder();
        for (String part : rawParts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            if (filtered.length() > 0) {
                filtered.append("/");
            }
            filtered.append(part);
        }
        String displayPath = filtered.toString();
        if (displayPath.length() > 512) {
            throw new IllegalArgumentException("source_path is too long");
        }
        return displayPath;
    }

    /**
     * 合并文件、请求中的 OCR 与分块参数（对应 resolve_processing_params）。
     *
     * <p>先按「请求优先」合并普通参数并剔除丢弃键，再叠加 {@link ChunkPresets#resolveChunkProcessingParams}
     * 的三层分块参数结果——参数解析只有这一处实现。
     */
    public static Map<String, Object> resolveProcessingParams(Map<String, Object> kbAdditionalParams,
                                                              Map<String, Object> fileProcessingParams,
                                                              Map<String, Object> requestParams) {
        Map<String, Object> mergedParams =
                sanitizeProcessingParams(mergeProcessingParams(fileProcessingParams, requestParams));
        Map<String, Object> merged = mergedParams == null ? new LinkedHashMap<>() : mergedParams;
        merged.putAll(ChunkPresets.resolveChunkProcessingParams(kbAdditionalParams, fileProcessingParams, requestParams));
        return merged;
    }

    /** 文件内容的 SHA-256 十六进制摘要（对应 calculate_content_hash）。 */
    public static String calculateContentHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data == null ? new byte[0] : data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** MinIO 文件名中的 13 位时间戳模式（对应 prepare_item_metadata 的 timestamp_pattern）。 */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(.+)_(\\d{13})(\\.[^.]+)$");

    /**
     * 准备文件元数据（对应 prepare_item_metadata）。URL 导入需先经预处理为 MinIO 文件。
     *
     * <p>必要替换：{@code str(time.time())} → 秒级双精度字符串（Java 无对应原语，语义一致：
     * 仅用于让同一来源多次添加产生不同 file_id）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> prepareItemMetadata(String item, String contentType, String kbId,
                                                          Map<String, Object> params) {
        Map<String, Object> safeParams = params;
        Object preprocessed = params == null ? null : params.get("_preprocessed_map");
        if (preprocessed instanceof Map<?, ?> preMap && preMap.get(item) instanceof Map<?, ?> preInfoRaw) {
            Map<String, Object> preInfo = (Map<String, Object>) preInfoRaw;
            String filename = preInfo.get("filename") == null ? item : String.valueOf(preInfo.get("filename"));
            String filenameDisplay = filename.length() > 500
                    ? filename.substring(0, 400) + "..." + filename.substring(filename.length() - 90)
                    : filename;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kb_id", kbId);
            metadata.put("filename", filenameDisplay);
            metadata.put("path", preInfo.get("path"));
            metadata.put("file_type", "html");
            metadata.put("status", "indexing");
            metadata.put("created_at", DateTimeUtils.utcIsoformat());
            metadata.put("file_id", "file_" + HashUtils.hashstr(item + nowSecondsText(), 6, false, null));
            metadata.put("content_hash", preInfo.get("content_hash"));
            metadata.put("size", preInfo.get("file_size"));
            metadata.put("parent_id", params.get("parent_id"));

            Map<String, Object> cleaned = sanitizeProcessingParams(params);
            Map<String, Object> effective = cleaned == null ? new LinkedHashMap<>() : cleaned;
            effective.put("content_type", "file");
            effective.put("original_source", item);
            metadata.put("processing_params", effective);
            return metadata;
        }

        if (!"file".equals(contentType)) {
            throw new IllegalArgumentException("Unsupported content_type: " + contentType);
        }
        if (!isMinioUrl(item)) {
            throw new IllegalArgumentException("File source must be a MinIO URL: " + item);
        }

        String objectName = parseMinioUrl(item)[1];
        int lastSlash = objectName.lastIndexOf('/');
        String filename = lastSlash < 0 ? objectName : objectName.substring(lastSlash + 1);

        Matcher matcher = TIMESTAMP_PATTERN.matcher(filename);
        String filenameDisplay = matcher.matches() ? matcher.group(1) + matcher.group(3) : filename;
        String sourcePath = params == null ? null : normalizeSourcePath(params.get("source_path"));
        if (sourcePath != null) {
            filenameDisplay = sourcePath;
        }

        int dot = filenameDisplay.lastIndexOf('.');
        String fileType = dot < 0 ? "" : filenameDisplay.substring(dot + 1).toLowerCase();
        String itemPath = item;

        String contentHash = null;
        if (params != null && params.get("content_hashes") instanceof Map<?, ?> hashes) {
            Object value = hashes.get(item);
            contentHash = value == null ? null : String.valueOf(value);
        }
        if (contentHash == null || contentHash.isEmpty()) {
            throw new IllegalArgumentException("Missing content_hash for file: " + item);
        }

        Object fileSizes = params == null ? null : params.get("file_sizes");
        Map<?, ?> sizeMap = fileSizes instanceof Map<?, ?> m ? m : Map.of();
        Object fileSize = sizeMap.get(item);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kb_id", kbId);
        metadata.put("filename", filenameDisplay);
        metadata.put("path", itemPath);
        metadata.put("file_type", fileType);
        metadata.put("status", "indexing");
        metadata.put("created_at", DateTimeUtils.utcIsoformat());
        metadata.put("file_id", "file_" + HashUtils.hashstr(itemPath + nowSecondsText(), 6, false, null));
        metadata.put("content_hash", contentHash);
        metadata.put("size", fileSize);
        metadata.put("parent_id", params == null ? null : params.get("parent_id"));
        if (params != null) {
            metadata.put("processing_params", sanitizeProcessingParams(params));
        }
        return metadata;
    }

    /** 统计修复的候选判定（对应 _should_repair_file_stats）：无状态或已索引状态。 */
    public static boolean shouldRepairFileStats(Map<String, Object> fileMeta) {
        Object status = fileMeta == null ? null : fileMeta.get("status");
        return status == null
                || com.wisesoft.wenqu.service.FileStatus.INDEXED_STATS_STATUSES.contains(String.valueOf(status));
    }

    /** 秒级时间戳文本（对应 str(time.time())）。 */
    private static String nowSecondsText() {
        return String.valueOf(System.currentTimeMillis() / 1000.0);
    }

    /** 构建知识库图片的后端鉴权代理 URL。 */
    public static String buildKbImageProxyUrl(String objectName) {
        int separator = objectName.indexOf('/');
        String kbId = separator < 0 ? objectName : objectName.substring(0, separator);
        String relativePath = separator < 0 ? "" : objectName.substring(separator + 1);
        if (kbId.isEmpty() || separator < 0 || !relativePath.startsWith("kb-images/")) {
            throw new IllegalArgumentException("知识库图片对象名必须符合 {kb_id}/kb-images/{filename} 格式");
        }
        // Python quote(relative_path, safe='/')：保留斜杠，其余字符百分号编码
        return "/api/knowledge/databases/" + kbId + "/images/"
                + com.wisesoft.wenqu.common.UrlQuote.quote(relativePath, "/");
    }

    /** 检测是否是本系统生成的 MinIO 存储 URL。 */
    public static boolean isMinioUrl(String filePath) {
        URI parsed = parseUri(filePath);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme();
        String host = parsed.getHost() == null ? "" : parsed.getHost();

        if (scheme.equals("minio")) {
            String pathPart = parsed.getRawPath() == null ? "" : parsed.getRawPath().replaceFirst("^/", "");
            return !host.isEmpty() && !pathPart.isEmpty();
        }

        if (!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty()) {
            return false;
        }

        String pathPart = parsed.getRawPath() == null ? "" : parsed.getRawPath().replaceFirst("^/", "");
        String[] pathParts = pathPart.split("/", 2);
        if (pathParts.length != 2) {
            return false;
        }

        Set<String> knownBuckets = new java.util.HashSet<>(MinioStorageClient.KB_BUCKETS.values());
        knownBuckets.addAll(MinioStorageClient.PUBLIC_READ_BUCKETS);
        return knownBuckets.contains(pathParts[0]);
    }

    /** 解析 MinIO URL，提取 bucket 名称和对象名称。 */
    public static String[] parseMinioUrl(String filePath) {
        URI parsed = parseUri(filePath);

        String bucketName;
        String objectName;
        if ("minio".equals(parsed.getScheme())) {
            bucketName = parsed.getHost();
            objectName = URLDecoder.decode(
                    parsed.getRawPath() == null ? "" : parsed.getRawPath().replaceFirst("^/", ""),
                    StandardCharsets.UTF_8);
        } else {
            objectName = parsed.getRawPath() == null ? "" : parsed.getRawPath().replaceFirst("^/", "");
            String[] pathParts = objectName.split("/", 2);
            if (pathParts.length > 1) {
                bucketName = pathParts[0];
                objectName = URLDecoder.decode(pathParts[1], StandardCharsets.UTF_8);
            } else {
                throw new IllegalArgumentException("无法解析 MinIO URL: " + filePath);
            }
        }

        if (bucketName == null || bucketName.isEmpty() || objectName.isEmpty()) {
            throw new IllegalArgumentException("无法解析 MinIO URL: " + filePath);
        }
        return new String[] {bucketName, objectName};
    }

    /** urlparse 对非法输入抛 ValueError → IllegalArgumentException。 */
    private static URI parseUri(String filePath) {
        try {
            return URI.create(filePath);
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("无法解析 MinIO URL: " + filePath);
        }
    }
}
