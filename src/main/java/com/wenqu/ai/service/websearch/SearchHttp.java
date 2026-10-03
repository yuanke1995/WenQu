package com.wenqu.ai.service.websearch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 联网搜索的 HTTP 收发（JDK 内置 HttpClient，不引入新依赖）。
 * <p>
 * 用 JDK 自带客户端而不是 Spring 的 RestClient，理由：工具是在模型调用线程里**同步阻塞**执行，
 * 需要按请求的精确超时（HttpRequest.timeout 逐请求设置），且不应受全局 HTTP 客户端配置影响。
 * 连接超时固定 10s（握手阶段），读取超时由调用方按 webSearch.timeoutMs 给。
 */
final class SearchHttp {

    /** 连接超时：握手卡住不应拖到读取超时才失败 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SearchHttp() {
    }

    /** POST JSON；非 2xx 抛 {@link WebSearchException}（带上状态码语义） */
    static String post(String url, Map<String, String> headers, String jsonBody, int timeoutMs) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
        applyHeaders(b, headers);
        return send(b.build(), url);
    }

    /** GET；非 2xx 抛 {@link WebSearchException} */
    static String get(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .GET();
        applyHeaders(b, headers);
        return send(b.build(), url);
    }

    private static void applyHeaders(HttpRequest.Builder b, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            // Content-Type 已由 post 设置，跳过避免重复头导致的部分网关 400
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            b.header(e.getKey(), e.getValue());
        }
    }

    private static String send(HttpRequest request, String url) throws Exception {
        HttpResponse<String> resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new WebSearchException("搜索服务返回 HTTP " + code + describe(code) + "（" + url + "）");
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new WebSearchException("搜索服务返回空响应（" + url + "）");
        }
        return body;
    }

    /** 状态码语义：让"密钥错了"和"服务端炸了"在提示里就能分清，不用翻日志 */
    private static String describe(int code) {
        return switch (code) {
            case 401 -> "（API Key 无效或缺失）";
            case 403 -> "（无权限或额度不足）";
            case 429 -> "（触发限流）";
            case 404 -> "（接口地址不存在）";
            default -> code >= 500 ? "（服务商内部错误）" : "";
        };
    }
}
