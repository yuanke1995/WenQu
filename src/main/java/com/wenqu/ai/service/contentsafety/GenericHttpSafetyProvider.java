package com.wenqu.ai.service.contentsafety;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通用 HTTP 内容安全检查服务（服务商中立适配）。
 *
 * <p>定一份最小协议，让<b>任何</b>自建的内容安全检查服务都能接进来，同时给商业服务留一个
 * 转译位（网关侧把阿里云/天御的响应转成这份协议，问渠不必为每家写一个适配器）：
 * <ul>
 *   <li>请求：{@code POST {contentSafety.baseUrl}}，头 {@code Content-Type: application/json}，
 *       配了 {@code contentSafety.apiKey} 时带 {@code Authorization: Bearer <key>}，
 *       体为 {@code {"text":"待检文本","stage":"input|output","userId":"...","sessionId":"..."}}；</li>
 *   <li>响应：{@code {"blocked":true|false,"category":"类目","reason":"可读原因"}}，
 *       字段缺失按「未拦」处理（宁可少拦，也不要因为对方多回了几个字段就判成违规）。</li>
 * </ul>
 *
 * <p>用 JDK 内置 HttpClient：这道闸跑在模型调用线程上，需要<b>逐请求</b>精确超时，
 * 不能被全局 HTTP 客户端的连接池配置拖住（与联网搜索适配器同一个理由）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class GenericHttpSafetyProvider implements ContentSafetyProvider {

    /** 连接超时：握手卡住不该等到读取超时才失败 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public SafetyVerdict check(SafetyRequest req) throws Exception {
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw new IllegalStateException("内容安全服务地址未配置（contentSafety.baseUrl 为空）");
        }
        JSONObject body = new JSONObject();
        body.put("text", req.text());
        body.put("stage", req.stage());
        body.put("userId", req.userId());
        body.put("sessionId", req.sessionId());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(req.baseUrl()))
                .timeout(Duration.ofMillis(Math.max(500, req.timeoutMs())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8));
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + req.apiKey());
        }
        HttpResponse<String> resp = CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("内容安全服务返回 " + resp.statusCode());
        }
        JSONObject json = JSON.parseObject(resp.body() == null ? "{}" : resp.body());
        if (json == null) return SafetyVerdict.pass();
        boolean blocked = json.getBooleanValue("blocked");
        String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length()));
        return new SafetyVerdict(blocked, json.getString("category"), json.getString("reason"), snippet);
    }
}
