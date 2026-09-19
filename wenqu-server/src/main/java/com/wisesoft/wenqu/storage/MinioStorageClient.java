package com.wisesoft.wenqu.storage;

import com.wisesoft.wenqu.common.MinioUrls;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MinIO 存储客户端。
 *
 * <p>由参考实现的 storage/minio/client.py 逐方法翻译（MinIOClient → MinioStorageClient，
 * 避免与 SDK 的 MinioClient 同名）：桶管理与公开读策略、上传/下载/预签名/删除、
 * 按前缀清理与元数据列举、URL 临时文件下载。
 *
 * <p>必要替换：
 * <ul>
 *   <li>Python minio SDK → Java minio SDK 8.5.10（同一存储协议的官方客户端）。
 *   <li>{@code async a*} 变体（asyncio.to_thread 包装的同步方法）→ 直接提供同步方法，
 *       由调用方线程承担（本工程无事件循环）；语义与同步原方法一致。
 *   <li>{@code temp_file_from_url} 异步上下文管理器 → {@link TempFile} 实现
 *       AutoCloseable（try-with-resources 等价，close 即删除临时文件）。
 *   <li>{@code mimetypes.guess_type} → JDK FileNameMap + 参考实现的兜底表
 *       （mime 表随平台不同，未知扩展均落 application/octet-stream）。
 *   <li>{@code normalize_public_minio_url} 已在 {@link MinioUrls} 移植，此处不再重复。
 * </ul>
 */
@Slf4j
@Component
public class MinioStorageClient {

    /** 存储相关异常基类（对应 StorageError）。 */
    public static class StorageError extends RuntimeException {
        public StorageError(String message) {
            super(message);
        }

        public StorageError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 简化的上传结果。 */
    public record UploadResult(String url, String bucketName, String objectName) {}

    public static final Set<String> PUBLIC_READ_BUCKETS = Set.of("public");

    /** 知识库相关的 bucket 名称；images 使用私有 bucket，图片统一经后端鉴权代理访问。 */
    public static final Map<String, String> KB_BUCKETS = Map.of(
            "documents", "knowledgebases",
            "parsed", "knowledgebases",
            "images", "kb-images");

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String publicBaseUrl;
    private final String publicEndpoint;

    private volatile MinioClient client;

    public MinioStorageClient() {
        this.endpoint = envOrDefault("MINIO_URI", "http://minio:9000");
        this.accessKey = envOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        this.secretKey = envOrDefault("MINIO_SECRET_KEY", "minioadmin");
        this.publicBaseUrl = envOrDefault("MINIO_PUBLIC_URL", "/minio").replaceAll("/+$", "");

        // 设置公开访问端点
        if (System.getenv("RUNNING_IN_DOCKER") != null) {
            String hostIp = System.getenv("HOST_IP");
            hostIp = (hostIp == null || hostIp.strip().isEmpty()) ? "localhost" : hostIp.strip();
            if (hostIp.contains("://")) {
                hostIp = hostIp.substring(hostIp.indexOf("://") + 3);
            }
            hostIp = hostIp.replaceAll("/+$", "");
            this.publicEndpoint = hostIp + ":9000";
            log.debug("Docker MinIOClient public_endpoint: {}", this.publicEndpoint);
        } else {
            this.publicEndpoint = "localhost:9000";
            log.debug("Default_client: {}", this.publicEndpoint);
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    /** 获取 MinIO 客户端实例（懒加载，对应 client property）。 */
    private MinioClient client() {
        MinioClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    String endpoint = this.endpoint;
                    if (endpoint.contains("://")) {
                        endpoint = endpoint.substring(endpoint.indexOf("://") + 3);
                    }
                    current = MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(accessKey, secretKey)
                            .build();
                    client = current;
                }
            }
        }
        return current;
    }

    // ==================== 桶管理 ====================

    /** 确保存储桶存在。 */
    public boolean ensureBucketExists(String bucketName) {
        try {
            boolean created = false;
            if (!client().bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                client().makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                created = true;
                log.info("存储桶 '{}' 已创建", bucketName);
            }

            ensurePublicReadAccess(bucketName);

            if (created && PUBLIC_READ_BUCKETS.contains(bucketName)) {
                log.info("存储桶 '{}' 已配置为公开可读", bucketName);
            }
            return true;
        } catch (ErrorResponseException exc) {
            log.error("存储桶 '{}' 错误: {}", bucketName, exc.getMessage());
            throw new StorageError("Error with bucket '" + bucketName + "': " + exc.getMessage());
        } catch (Exception exc) {
            log.error("存储桶 '{}' 错误: {}", bucketName, exc.getMessage());
            throw new StorageError("Error with bucket '" + bucketName + "': " + exc.getMessage());
        }
    }

    /** 设置存储桶策略，允许公开读取对象。 */
    private void ensurePublicReadAccess(String bucketName) {
        if (!PUBLIC_READ_BUCKETS.contains(bucketName)) {
            return;
        }
        StringBuilder policy = new StringBuilder();
        policy.append("{\"Version\":\"2012-10-17\",\"Statement\":[{")
                .append("\"Effect\":\"Allow\",")
                .append("\"Principal\":{\"AWS\":[\"*\"]},")
                .append("\"Action\":[\"s3:GetObject\"],")
                .append("\"Resource\":[\"arn:aws:s3:::").append(bucketName).append("/*\"]}")
                .append("]}");
        try {
            client().setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(bucketName).config(policy.toString()).build());
        } catch (Exception exc) {
            log.warn("设置存储桶 '{}' 公共读取策略失败: {}", bucketName, exc.getMessage());
            throw new StorageError("无法设置存储桶公共访问策略: " + exc.getMessage());
        }
    }

    // ==================== 上传 ====================

    /** 上传文件到 MinIO。 */
    public UploadResult uploadFile(String bucketName, String objectName, byte[] data, String contentType) {
        try {
            ensureBucketExists(bucketName);

            String resolvedContentType =
                    contentType != null && !contentType.isEmpty() ? contentType : guessContentType(objectName);
            client().putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(resolvedContentType)
                    .build());

            String url;
            if (PUBLIC_READ_BUCKETS.contains(bucketName)) {
                url = publicBaseUrl + "/" + bucketName + "/" + urlEncodePath(objectName);
            } else {
                url = "http://" + publicEndpoint + "/" + bucketName + "/" + objectName;
            }
            return new UploadResult(url, bucketName, objectName);
        } catch (ErrorResponseException exc) {
            String errorMsg = "上传文件 '" + objectName + "' 失败: " + exc.getMessage();
            log.error(errorMsg);
            throw new StorageError(errorMsg);
        } catch (Exception exc) {
            String errorMsg = "上传文件 '" + objectName + "' 失败: " + exc.getMessage();
            log.error(errorMsg);
            throw new StorageError(errorMsg);
        }
    }

    /** 从文件路径上传文件。 */
    public UploadResult uploadFileFromPath(String bucketName, String objectName, String filePath) {
        try {
            byte[] data = Files.readAllBytes(Path.of(filePath));
            return uploadFile(bucketName, objectName, data, null);
        } catch (java.io.FileNotFoundException exc) {
            throw new StorageError("文件 '" + filePath + "' 不存在");
        } catch (Exception exc) {
            throw new StorageError("从路径上传文件失败: " + exc.getMessage());
        }
    }

    /** 根据文件名猜测 MIME 类型（JDK 表 + 参考实现兜底表）。 */
    static String guessContentType(String objectName) {
        String guessed = URLConnection.getFileNameMap().getContentTypeFor(objectName.toLowerCase());
        if (guessed != null) {
            return guessed;
        }
        String ext = objectName.contains(".")
                ? objectName.substring(objectName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        Map<String, String> contentTypes = new LinkedHashMap<>();
        contentTypes.put("md", "text/markdown");
        contentTypes.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        contentTypes.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        contentTypes.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        contentTypes.put("xls", "application/vnd.ms-excel");
        contentTypes.put("zip", "application/zip");
        contentTypes.put("webp", "image/webp");
        contentTypes.put("bmp", "image/bmp");
        contentTypes.put("tif", "image/tiff");
        contentTypes.put("tiff", "image/tiff");
        return contentTypes.getOrDefault(ext, "application/octet-stream");
    }

    /** object_name 的路径安全编码（对应 quote(object_name, safe='/')）。 */
    private static String urlEncodePath(String objectName) {
        StringBuilder builder = new StringBuilder();
        for (byte b : objectName.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || c == '/' || c == '-' || c == '_' || c == '.' || c == '~') {
                builder.append(c);
            } else {
                builder.append('%').append(String.format("%02X", b));
            }
        }
        return builder.toString();
    }

    // ==================== 下载 ====================

    /** 下载文件。 */
    public byte[] downloadFile(String bucketName, String objectName) {
        try (InputStream stream = client().getObject(
                GetObjectArgs.builder().bucket(bucketName).object(objectName).build())) {
            byte[] data = stream.readAllBytes();
            log.info("成功下载 '{}' 从存储桶 '{}'", objectName, bucketName);
            return data;
        } catch (ErrorResponseException exc) {
            if ("NoSuchKey".equals(exc.errorResponse().code())) {
                throw new StorageError("对象 '" + objectName + "' 在存储桶 '" + bucketName + "' 中不存在");
            }
            throw new StorageError("下载文件失败: " + exc.getMessage());
        } catch (Exception exc) {
            throw new StorageError("下载文件失败: " + exc.getMessage());
        }
    }

    /** 将 minio 放在内网访问，外部通过返回代理链接访问。 */
    public String getPresignedUrl(String bucketName, String objectName, int days) {
        try {
            return client().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName)
                    .expiry(days, java.util.concurrent.TimeUnit.DAYS)
                    .build());
        } catch (Exception exc) {
            throw new StorageError("生成预签名 URL 失败: " + exc.getMessage());
        }
    }

    // ==================== 删除 ====================

    /** 删除文件。 */
    public boolean deleteFile(String bucketName, String objectName) {
        try {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());
            log.info("成功删除 '{}' 从存储桶 '{}'", objectName, bucketName);
            return true;
        } catch (ErrorResponseException exc) {
            if ("NoSuchKey".equals(exc.errorResponse().code())) {
                log.warn("要删除的对象 '{}' 不存在", objectName);
                return false;
            }
            throw new StorageError("删除文件失败: " + exc.getMessage());
        } catch (Exception exc) {
            throw new StorageError("删除文件失败: " + exc.getMessage());
        }
    }

    /** 按前缀删除对象，返回删除数量。 */
    public int deleteObjectsByPrefix(String bucketName, String prefix) {
        int[] deletedCount = {0};
        try {
            for (var item : client().listObjects(
                    io.minio.ListObjectsArgs.builder().bucket(bucketName).prefix(prefix).recursive(true).build())) {
                client().removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(item.get().objectName())
                        .build());
                deletedCount[0] += 1;
            }
        } catch (ErrorResponseException exc) {
            if ("NoSuchBucket".equals(exc.errorResponse().code())) {
                log.warn("待清理的存储桶 '{}' 不存在", bucketName);
                return deletedCount[0];
            }
            throw new StorageError("删除对象前缀失败: " + bucketName + "/" + prefix + ": " + exc.getMessage());
        } catch (Exception exc) {
            throw new StorageError("删除对象前缀失败: " + bucketName + "/" + prefix + ": " + exc.getMessage());
        }
        return deletedCount[0];
    }

    /** 列出前缀下的对象元数据（过期清理用）。 */
    public List<Map<String, Object>> listObjectMetadata(String bucketName, String prefix) {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var item : client().listObjects(
                    io.minio.ListObjectsArgs.builder().bucket(bucketName).prefix(prefix).recursive(true).build())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("object_name", String.valueOf(item.get().objectName()));
                Instant lastModified = item.get().lastModified().toInstant();
                entry.put("last_modified", lastModified);
                result.add(entry);
            }
            return result;
        } catch (Exception exc) {
            throw new StorageError("列出对象前缀失败: " + bucketName + "/" + prefix + ": " + exc.getMessage());
        }
    }

    /** 删除 bucket（先删除所有对象，再删除 bucket）。 */
    public boolean deleteBucket(String bucketName) {
        try {
            deleteObjectsByPrefix(bucketName, "");
            client().removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
            log.info("成功删除 bucket: {}", bucketName);
            return true;
        } catch (ErrorResponseException exc) {
            if ("NoSuchBucket".equals(exc.errorResponse().code())) {
                log.warn("bucket 不存在: {}", bucketName);
                return false;
            }
            throw new StorageError("删除 bucket 失败: " + exc.getMessage());
        } catch (Exception exc) {
            throw new StorageError("删除 bucket 失败: " + exc.getMessage());
        }
    }

    // ==================== 元数据 ====================

    /** 检查文件是否存在。 */
    public boolean fileExists(String bucketName, String objectName) {
        try {
            client().statObject(StatObjectArgs.builder().bucket(bucketName).object(objectName).build());
            return true;
        } catch (ErrorResponseException exc) {
            if ("NoSuchKey".equals(exc.errorResponse().code())) {
                return false;
            }
            throw new StorageError("检查文件存在性失败: " + exc.getMessage());
        } catch (Exception exc) {
            throw new StorageError("检查文件存在性失败: " + exc.getMessage());
        }
    }

    /** 获取文件大小（字节），文件不存在时返回 null。 */
    public Long statFile(String bucketName, String objectName) {
        try {
            StatObjectResponse stat =
                    client().statObject(StatObjectArgs.builder().bucket(bucketName).object(objectName).build());
            return stat.size();
        } catch (ErrorResponseException exc) {
            if ("NoSuchKey".equals(exc.errorResponse().code())) {
                return null;
            }
            throw new StorageError("获取文件信息失败: " + exc.getMessage());
        } catch (Exception exc) {
            throw new StorageError("获取文件信息失败: " + exc.getMessage());
        }
    }

    // ==================== URL 临时文件 ====================

    /** 下载到临时文件的句柄；try-with-resources 结束后自动删除（对应上下文管理器）。 */
    public static final class TempFile implements AutoCloseable {

        private final Path path;
        private boolean closed;

        TempFile(Path path) {
            this.path = path;
        }

        public Path path() {
            return path;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                Files.deleteIfExists(path);
                log.info("已删除临时文件: {}", path);
            } catch (Exception exc) {
                log.warn("删除临时文件失败: {}", exc.getMessage());
            }
        }
    }

    /**
     * 从 MinIO URL 下载文件到临时文件（对应 temp_file_from_url；调用方以
     * try-with-resources 持有返回值，用毕自动清理）。
     */
    public TempFile tempFileFromUrl(String url, List<String> allowedExtensions) {
        // 验证 URL
        if (url == null || url.isEmpty()) {
            throw new StorageError("URL 不能为空");
        }
        url = url.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new StorageError("无效的 MinIO URL，只允许 http/https");
        }

        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (RuntimeException exc) {
            throw new StorageError("无效的 MinIO URL，只允许 http/https");
        }

        // 验证主机
        String endpointHost = this.endpoint.substring(this.endpoint.indexOf("://") + 3).split(":")[0];
        String urlHost = parsed.getHost() == null ? "" : parsed.getHost();
        String hostIpEnv = System.getenv("HOST_IP");
        String hostIp = hostIpEnv == null || hostIpEnv.isEmpty() ? "localhost" : hostIpEnv;
        if (!endpointHost.equals(urlHost) && !urlHost.equals(hostIp)) {
            throw new StorageError("不允许的外部 URL: " + urlHost);
        }

        // 检查路径遍历
        if (url.contains("..") || url.contains("\\")) {
            throw new StorageError("URL 包含路径遍历字符");
        }

        // 验证扩展名
        if (allowedExtensions != null && allowedExtensions.stream().noneMatch(url::endsWith)) {
            throw new StorageError("文件扩展名不符合要求，允许: " + String.join(", ", allowedExtensions));
        }

        // 解析 bucket 和 object name
        String rawPath = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        String[] pathParts = rawPath.substring(rawPath.startsWith("/") ? 1 : 0).split("/", 2);
        if (pathParts.length != 2) {
            throw new StorageError("无法解析 MinIO URL");
        }
        String bucketName = pathParts[0];
        String objectName = pathParts[1];

        // 下载文件
        byte[] fileData = downloadFile(bucketName, objectName);
        log.info("成功从 MinIO 下载文件: {} ({} bytes)", objectName, fileData.length);

        String suffix;
        if (allowedExtensions != null) {
            suffix = allowedExtensions.stream().filter(url::endsWith).findFirst().orElse(".tmp");
        } else {
            suffix = "." + (objectName.contains(".") ? objectName.substring(objectName.lastIndexOf('.') + 1) : "tmp");
        }

        try {
            Path tempPath = Files.createTempFile("minio-download", suffix);
            Files.write(tempPath, fileData);
            log.info("文件已下载到临时路径: {}", tempPath);
            return new TempFile(tempPath);
        } catch (Exception exc) {
            throw new StorageError("临时文件创建失败: " + exc.getMessage());
        }
    }

    // ==================== 模块级接口 ====================

    private static volatile MinioStorageClient defaultClient;

    /** 获取全局客户端实例（对应 get_minio_client 单例）。 */
    public static MinioStorageClient getMinioClient() {
        if (defaultClient == null) {
            synchronized (MinioStorageClient.class) {
                if (defaultClient == null) {
                    defaultClient = new MinioStorageClient();
                }
            }
        }
        return defaultClient;
    }

    /** 通过字节上传文件到 MinIO 并返回资源 URL（对应 aupload_file_to_minio）。 */
    public static String uploadFileToMinio(String bucketName, String fileName, byte[] data) {
        MinioStorageClient client = getMinioClient();
        UploadResult result = client.uploadFile(bucketName, fileName, data, null);
        return result.url();
    }
}
