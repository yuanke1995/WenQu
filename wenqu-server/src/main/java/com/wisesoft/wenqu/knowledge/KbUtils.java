package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.storage.MinioStorageClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 知识库通用工具（knowledge/utils/kb_utils.py 的部分移植）。
 *
 * <p>本批仅移植解析器链路所需的 {@code build_kb_image_proxy_url}/{@code is_minio_url}/
 * {@code parse_minio_url}；其余函数随知识库服务模块一并移植（部分移植，非静默省略）。
 */
public final class KbUtils {

    private KbUtils() {}

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
