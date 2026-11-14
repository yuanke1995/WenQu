package com.wenqu.ai.service.httptool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 工具的执行体（JDK 内置 HttpClient，不引入新依赖）。
 * <p>
 * 与 {@code SearchHttp} 同一惯例的理由：工具在模型调用线程里<b>同步阻塞</b>执行，
 * 需要按请求的精确超时（工具集逐个可配），且不该受全局 HTTP 客户端配置影响。
 * 与搜索不同的是<b>失败语义</b>：宿主 API 返回 4xx/5xx 是「业务数据」（模型要看到状态码与
 * 响应体才能向用户解释或改参数重试），按结果返回而不是抛异常；只有连不上/超时这类传输层
 * 故障才抛——那是「这次调用没发生」，返回假结果反而误导模型。
 * <p>
 * URL 组装的安全属性：{@code baseUrl} 来自智能体配置（固定值），操作路径来自导入时解析的规格——
 * 模型只能填参数，<b>不能改写目标主机</b>（这是本工具与「给模型一把任意 HTTP 客户端」的本质区别）。
 *
 * @author yuanke
 */
public final class HttpToolExecutor {

    /** 解密后的鉴权（执行器不碰密文：HttpToolService 在装配期解好传入） */
    public record AuthSpec(String header, String value) {
    }

    public record Result(int status, String body) {
    }

    /** 传输层失败（连不上/超时/地址非法）：调用方转成模型可读的错误结果 */
    public static class HttpToolException extends RuntimeException {
        public HttpToolException(String message) {
            super(message);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpToolExecutor() {
    }

    /**
     * 执行一次操作调用。
     *
     * @param args 模型给的入参（键=inputSchema 属性名；body 键固定为 "body"）
     */
    public static Result execute(HttpToolModels.HttpToolset set, HttpToolModels.HttpOp op,
                                 Map<String, Object> args, AuthSpec auth) {
        Map<String, Object> a = args == null ? Map.of() : args;
        String url = buildUrl(set.getBaseUrl(), op.getPath(), op.getParams(), a);
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder().uri(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new HttpToolException("接口地址不合法：" + url);
        }
        b.timeout(Duration.ofMillis(Math.min(HttpToolModels.TIMEOUT_MAX_MS,
                Math.max(HttpToolModels.TIMEOUT_MIN_MS, set.getTimeoutMs() == null
                        ? HttpToolModels.TIMEOUT_DEFAULT_MS : set.getTimeoutMs()))));

        Map<String, String> headers = new LinkedHashMap<>();
        if (auth != null && auth.value() != null && !auth.value().isBlank()) {
            headers.put(auth.header() == null || auth.header().isBlank() ? "Authorization" : auth.header(),
                    auth.value());
        }
        for (HttpToolModels.HttpParam p : op.getParams()) {
            if (!"header".equals(p.getIn())) continue;
            Object v = a.get(p.getName());
            if (v != null) headers.put(p.getName(), String.valueOf(v));
        }
        boolean hasBody = op.isHasBody() && a.get("body") != null;
        if (hasBody) headers.put("Content-Type", "application/json");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String name = h.getKey();
            if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")
                    || name.equalsIgnoreCase("connection")) {
                continue;   // 传输层头不允许借道工具配置注入
            }
            b.header(name, h.getValue());
        }
        b.method(op.getMethod(), hasBody
                ? HttpRequest.BodyPublishers.ofString(com.alibaba.fastjson2.JSON.toJSONString(a.get("body")),
                StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> resp;
        try {
            resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpToolException("调用被中断");
        } catch (IOException e) {
            throw new HttpToolException("调用失败：" + rootMessage(e));
        }
        String body = readCapped(resp.body());
        return new Result(resp.statusCode(), body);
    }

    /** URL = baseUrl + 路径参数替换 + query 拼接；路径参数缺失当场报（fail-loud 到模型可读） */
    private static String buildUrl(String baseUrl, String path,
                                   List<HttpToolModels.HttpParam> params, Map<String, Object> args) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String p = path;
        List<String> query = new ArrayList<>();
        for (HttpToolModels.HttpParam param : params) {
            Object v = args.get(param.getName());
            if ("path".equals(param.getIn())) {
                if (v == null || String.valueOf(v).isEmpty()) {
                    throw new HttpToolException("缺少路径参数 " + param.getName());
                }
                p = p.replace("{" + param.getName() + "}", urlEncode(String.valueOf(v)));
            } else if ("query".equals(param.getIn()) && v != null) {
                query.add(urlEncode(param.getName()) + "=" + urlEncode(String.valueOf(v)));
            }
        }
        if (p.contains("{")) {
            throw new HttpToolException("路径模板里还有未提供的参数：" + p);
        }
        StringBuilder url = new StringBuilder(base).append(p);
        if (!query.isEmpty()) url.append(base.contains("?") ? '&' : '?').append(String.join("&", query));
        return url.toString();
    }

    /** 响应有界读取：超限即截断并关闭（不读完大响应，防宿主接口返回巨包拖垮模型线程） */
    private static String readCapped(InputStream in) {
        try (InputStream stream = in) {
            int cap = HttpToolModels.MAX_RESPONSE_BYTES;
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int total = 0, n;
            while ((n = stream.read(buf)) != -1) {
                if (total + n > cap) {
                    out.write(buf, 0, cap - total);
                    total = cap;
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            String s = out.toString(StandardCharsets.UTF_8);
            return total >= cap ? s + "\n…（响应超过 " + (cap / 1024) + "KB 已截断）" : s;
        } catch (IOException e) {
            throw new HttpToolException("读取响应失败：" + rootMessage(e));
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
