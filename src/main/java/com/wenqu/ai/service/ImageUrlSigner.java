package com.wenqu.ai.service;

import com.wenqu.ai.config.AppProperties;
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
    private final AuthService authService;

    public ImageUrlSigner(AppProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
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
     * <p><b>已带签名时先剥旧参数再重签</b>：签名是一次性凭据，对同一个 URL 换发（对话页长时间
     * 停留后调 {@code /artifact/refresh-sign}）时若直接追加，query 会堆成
     * {@code ?expire=..&sig=..&expire=..&sig=..}——{@code getParameter} 只取第一个，
     * 于是永远验的是那个已过期的旧签名，卡片依旧 401。故先剥掉 {@code expire}/{@code sig} 再签。
     */
    public String signUrl(String url) {
        if (!isEnabled()) {
            return url;
        }
        long expire = Instant.now().getEpochSecond() + properties.getImages().getAuthExpireSeconds();
        // 签名基准 = 纯路径（剥掉**整个** query）。必须是纯路径：拦截器验签时拿到的是
        // request.getRequestURI()，它按定义不含 query；若把业务参数也算进基准，带参文件
        // （…?v=2）的签名永远对不上拦截器的路径，直接 401。
        int q = url == null ? -1 : url.indexOf('?');
        String sigBase = q > 0 ? url.substring(0, q) : url;
        String sig = sign(sigBase, expire);
        // 输出保留业务参数（只剥掉旧的 expire/sig），再把新签名追加到末尾。
        // 拼接符按**清理后**的串判断：原串带签名参数时它必有 '?'，但清理后可能已无 query，
        // 硬拼 '?' 会得到 "…csv?expire=.."（重复）或"…csv&expire=.."（参数接到路径后）的畸形串。
        String clean = stripAuthParams(url);
        String joiner = clean.indexOf('?') >= 0 ? "&" : "?";
        return clean + joiner + "expire=" + expire + "&sig=" + sig;
    }

    /** 剥掉 URL 上的签名参数（expire/sig），其余业务查询参数原样保留；null/空原样返回 */
    private String stripAuthParams(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return url;
        StringBuilder sb = new StringBuilder(url.substring(0, q));
        for (String part : url.substring(q + 1).split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq > 0 ? part.substring(0, eq) : part;
            // 对齐拦截器 getParameter("expire"/"sig") 的取参语义：只认真这两个键
            if ("expire".equals(k) || "sig".equals(k)) continue;
            sb.append(sb.indexOf("?") >= 0 ? '&' : '?').append(part);
        }
        return sb.toString();
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
     * 消息列表出参的统一签名：正文图、引用来源图、产物 URL 就地换成带签名的地址。
     * <p>库里存的是原始 URL（签名是一次性凭据，落库等于写进过期数据），所以每次响应都要现场签一遍。
     * 这段循环此前只长在会话历史接口的实现里，游客分享历史走的是另一条返回路径、没签名 →
     * 症状是「首次回答能看到图，一刷新全成『图片链接无效或已过期』」。</p>
     * <p>入参必须是响应副本（{@code SessionService} 每次查询新建 Map），原地改不会污染缓存。</p>
     */
    public void signMessageMedia(List<Map<String, Object>> messages) {
        if (!isEnabled() || messages == null || messages.isEmpty()) {
            return;
        }
        for (Map<String, Object> msg : messages) {
            if (msg == null) continue;
            Object imgs = msg.get("images");
            if (imgs instanceof List<?> list && !list.isEmpty()) {
                msg.put("images", list.stream().map(String::valueOf).map(this::signUrl).toList());
            }
            Object srcs = msg.get("sources");
            if (srcs instanceof List<?> srcList && !srcList.isEmpty() && srcList.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) srcList;
                msg.put("sources", signSourceImages(typed));
            }
            Object arts = msg.get("artifacts");
            if (arts instanceof List<?> artList && !artList.isEmpty() && artList.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typedArts = (List<Map<String, Object>>) artList;
                for (Map<String, Object> a : typedArts) {
                    Object u = a.get("url");
                    if (u != null) a.put("url", signUrl(String.valueOf(u)));
                }
            }
        }
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
        // 签名密钥派生自登录 JWT 签名密钥（AI_JWT_SECRET，不额外引入配置项）
        String secret = authService.secret();
        return secret == null || secret.isBlank() ? "wenqu-image-signer" : secret + ":image";
    }
}
