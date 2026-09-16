package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片访问签名（HMAC-SHA256）
 * 生产环境（AI_IMAGES_AUTH_ENABLED=true）下图片 URL 带 expire + sig，
 * 拦截器校验签名与有效期，防止未授权枚举下载文档截图
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ImageUrlSigner {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final AppProperties properties;

    public ImageUrlSigner(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * 是否启用图片鉴权
     */
    public boolean isEnabled() {
        return properties.getImages().isAuthEnabled();
    }

    /**
     * 为图片路径生成带签名与过期时间的 URL（原路径追加 ?expire=&sig=）
     * 例如 /ai/images/{docId}/0.png → /ai/images/{docId}/0.png?expire=1785...&sig=xxxx
     * 签名基准为去掉 query 的路径（与拦截器 request.getRequestURI() 一致）：
     * 原 URL 若自带参数也能通过鉴权，否则签出的 URL 会被拦截器判 401
     */
    public String signUrl(String url) {
        if (!isEnabled()) {
            return url;
        }
        long expire = Instant.now().getEpochSecond() + properties.getImages().getAuthExpireSeconds();
        int q = url == null ? -1 : url.indexOf('?');
        String sigBase = q > 0 ? url.substring(0, q) : url;
        String sig = sign(sigBase, expire);
        return url + (q > 0 ? "&" : "?") + "expire=" + expire + "&sig=" + sig;
    }

    /**
     * 批量签名图片 URL 列表（保留原始顺序；未开启鉴权时原样返回）。
     * 用于 SSE 下发/接口响应等"展示层"路径——库里与缓存中始终存原始 URL，
     * 每次响应时重新签名，避免签名过期。
     */
    public List<String> signUrls(List<String> urls) {
        if (!isEnabled() || urls == null || urls.isEmpty()) {
            return urls;
        }
        return urls.stream().map(this::signUrl).toList();
    }

    /**
     * 为引用来源列表（sources）中的 images 字段批量签名（响应副本，不改动入参）。
     * 引用弹窗直接 <img> 加载这些 URL，未签名会被鉴权拦截返回 401。
     */
    public List<Map<String, Object>> signSourceImages(List<Map<String, Object>> sources) {
        if (!isEnabled() || sources == null || sources.isEmpty()) {
            return sources;
        }
        List<Map<String, Object>> out = new ArrayList<>(sources.size());
        for (Map<String, Object> src : sources) {
            if (src == null) continue;
            Object imgs = src.get("images");
            if (imgs instanceof List<?> list && !list.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>(src);
                copy.put("images", list.stream().map(String::valueOf).map(this::signUrl).toList());
                out.add(copy);
            } else {
                out.add(src);
            }
        }
        return out;
    }

    /**
     * 校验图片请求的签名与有效期
     *
     * @param path   请求路径（如 /images/{docId}/0.png，不含 context-path）
     * @param expire 过期时间戳（秒）
     * @param sig    签名
     */
    public boolean verify(String path, long expire, String sig) {
        if (sig == null || sig.isBlank()) {
            return false;
        }
        if (expire <= 0 || Instant.now().getEpochSecond() > expire) {
            return false;
        }
        String expected = sign(path, expire);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String data, long expire) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            mac.update(data.getBytes(StandardCharsets.UTF_8));
            mac.update(Long.toString(expire).getBytes(StandardCharsets.UTF_8));
            byte[] raw = mac.doFinal();
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("图片签名失败", e);
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private String secretKey() {
        // 签名密钥派生自 trusted-token（不额外引入配置项）
        String token = properties.getTrustedToken();
        return token == null || token.isBlank() ? "ai-doc-image-signer" : token + ":image";
    }
}
