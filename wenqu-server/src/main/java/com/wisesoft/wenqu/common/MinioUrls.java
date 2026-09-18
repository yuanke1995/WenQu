package com.wisesoft.wenqu.common;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 对象存储公开地址的规范化。
 *
 * <p>由参考实现的 storage/minio/client.py 中 {@code normalize_public_minio_url} 逐行翻译：
 * 已经是本系统公开前缀的地址原样返回；指向对象存储 9000 端口且路径以 /public/ 开头的地址，
 * 改写为本系统对外前缀（环境变量 {@code MINIO_PUBLIC_URL}，缺省 {@code /minio}），
 * 查询串与片段保留。
 *
 * <p>说明：参考实现读的是 MINIO_PUBLIC_URL 环境变量；本工程沿用同一变量名，
 * 便于两套实现共用同一份部署配置。
 */
public final class MinioUrls {

    private static final String DEFAULT_PUBLIC_BASE_URL = "/minio";

    private static final String PUBLIC_PATH_PREFIX = "/minio/public/";

    private MinioUrls() {}

    /** 规范化公开地址；无法解析时原样返回。 */
    public static String normalizePublicMinioUrl(String value) {
        if (value == null || value.isEmpty() || value.startsWith(PUBLIC_PATH_PREFIX)) {
            return value;
        }
        URI parsed;
        try {
            parsed = new URI(value);
        } catch (URISyntaxException exc) {
            return value;
        }
        Integer port = parsed.getPort();
        String path = parsed.getPath() == null ? "" : parsed.getPath();
        if (port == null || port != 9000 || !path.startsWith("/public/")) {
            return value;
        }
        String publicBaseUrl = System.getenv("MINIO_PUBLIC_URL");
        if (publicBaseUrl == null || publicBaseUrl.isEmpty()) {
            publicBaseUrl = DEFAULT_PUBLIC_BASE_URL;
        }
        while (publicBaseUrl.endsWith("/")) {
            publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
        }
        StringBuilder normalized = new StringBuilder(publicBaseUrl).append(path);
        if (parsed.getQuery() != null) {
            normalized.append('?').append(parsed.getQuery());
        }
        if (parsed.getFragment() != null) {
            normalized.append('#').append(parsed.getFragment());
        }
        return normalized.toString();
    }
}
