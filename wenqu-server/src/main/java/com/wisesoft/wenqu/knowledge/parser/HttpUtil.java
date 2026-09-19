package com.wisesoft.wenqu.knowledge.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 引擎 HTTP 传输助手。
 *
 * <p>必要替换：Python {@code requests} 库 → JDK {@link HttpClient}。
 * requests 的 {@code files=}/{@code data=}/{@code json=} 三个入口在此对齐为
 * {@code get}/{@code postJson}/{@code postMultipart}，表单编码与
 * multipart/form-data（RFC 7578，boundary 随机）语义与 requests 一致。
 */
final class HttpUtil {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpUtil() {}

    static HttpResponse<String> get(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String url, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(builder::header);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> postJson(String url, Map<String, String> headers, Object payload, Duration timeout)
            throws IOException, InterruptedException {
        String body = com.alibaba.fastjson2.JSON.toJSONString(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<byte[]> put(String url, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpResponse<byte[]> getBinary(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * multipart/form-data 上传（对应 requests 的 {@code files={"files": (name, f, mime)}} + {@code data=...}）。
     *
     * @param fileFields 文件字段名 → 文件名（单文件场景，内容为 fileContent）
     * @param fileContent 文件内容
     * @param dataFields 普通表单字段（对应 requests 的 data=；值转字符串）
     */
    static HttpResponse<byte[]> postMultipart(
            String url,
            Map<String, String> fileFields,
            byte[] fileContent,
            Map<String, Object> dataFields,
            Duration timeout)
            throws IOException, InterruptedException {
        String boundary = "----WenQuBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        for (Map.Entry<String, Object> entry : dataFields.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.writeBytes(
                    String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        for (Map.Entry<String, String> entry : fileFields.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            // requests 未指定 mime 时默认 application/octet-stream
            body.writeBytes(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\""
                            + entry.getValue() + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.writeBytes("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.writeBytes(fileContent);
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
