package com.wenqu.ai.util;

import com.wenqu.ai.common.BizException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 出站 webhook 投递的<b>唯一出口</b>（收编自 WorkflowService.postCallback，智能体轮终回调共用同一份）：
 * HMAC-SHA256 签名头、事件名头、10s 超时、URL 校验（scheme + SSRF）。
 * <p>
 * 为什么收编：工作流运行终态回调与智能体轮终回调是同一个「带签名通知外部系统」的动作，
 * 各写一份迟早漂移（签名头名、超时、SSRF 口径三处最容易各改各的）。调用方只带
 * 事件名与业务头，签名与超时这里定死。
 * <p>
 * 失败语义由调用方定：工作流回调是 fire-and-forget（失败仅告警），智能体轮终回调有
 * 持久化重试（c_ai_webhook_delivery）——本类只返回结果，不做重试决策。
 *
 * @author yuanke
 */
@Slf4j
public final class OutboundWebhook {

    /** 出站超时（与迁移前工作流回调的 10s 同值） */
    public static final int TIMEOUT_MS = 10_000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private OutboundWebhook() {
    }

    /** 一次投递的结果：error 非空 = 传输层失败（超时/连接拒绝等）；status 非空 = 对端应答码 */
    public record Receipt(Integer status, String error) {
        public boolean ok() {
            return error == null && status != null && status >= 200 && status < 300;
        }
    }

    /**
     * 出站 POST：{@code X-Wenqu-Event}=事件名、{@code X-Wenqu-Signature}=sha256=HMAC(secret, body)
     * （secret 空/白 = 不签名）、其余业务头由调用方传（如 X-Wenqu-Run-Id / X-Wenqu-Turn-Id）。
     */
    public static Receipt post(String url, String event, Map<String, String> extraHeaders,
                               String secret, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Wenqu-Event", event)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (extraHeaders != null) {
                for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                    if (h.getKey() != null && h.getValue() != null) b.header(h.getKey(), h.getValue());
                }
            }
            if (secret != null && !secret.isBlank()) {
                b.header("X-Wenqu-Signature", "sha256=" + hmacSha256Hex(secret, body));
            }
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Receipt(resp.statusCode(), null);
        } catch (Exception e) {
            return new Receipt(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** HMAC-SHA256 十六进制签名（签的是 body 原文字节，与签名头同源） */
    public static String hmacSha256Hex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 回调地址校验：http/https + 过 SSRF（内网地址拒绝，与工作流 HTTP 节点同口径）。
     * 保存期调用（fail-loud），投递期不再重复校验——存进来时已是合法地址。
     */
    public static void assertUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new BizException("回调地址不是合法 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException("回调地址必须以 http:// 或 https:// 开头");
        }
        try {
            SsrfGuard.requirePublicHost(uri);
        } catch (BizException e) {
            throw new BizException("回调地址不允许指向内网：" + e.getMessage());
        }
    }
}
