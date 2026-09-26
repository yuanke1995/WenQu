package com.wisesoft.ai.sandbox;

import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 沙盒 provisioner HTTP 客户端（对应参考实现 provisioner_client.py 的 {@code ProvisionerClient}）。
 *
 * <p>逐字对齐：构造参数（baseUrl / token / timeoutSeconds / deleteTimeoutSeconds）、
 * health / create / discover / touch / delete 五个端点、{@code _record_from_payload} 的 wire 映射。
 *
 * <p>必要替换：Python httpx → JDK {@link HttpClient}；{@code RuntimeError} →
 * {@link SandboxProvisionerException}。
 * 能力差异：本工程的 provisioner 已随工程搬运至 {@code deploy/sandbox-provisioner/}
 * （{@code app.py} 照搬 + 自建 {@code run.sh}，由部署侧启停），故构造出的客户端
 * 在服务不可达时**仅在调用时才失败**——连接 / 写入 / 连接池仍快速失败，符合参考实现超时语义
 * （create 的 read 不设上限，对齐 {@code httpx.Timeout(timeout_seconds, read=None)}，故传 null）。
 */
public class SandboxProvisionerClient {

    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final Duration deleteTimeout;
    private final String authHeader;

    public SandboxProvisionerClient(String baseUrl, String token, int timeoutSeconds, int deleteTimeoutSeconds) {
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.deleteTimeout = Duration.ofSeconds(deleteTimeoutSeconds);
        this.authHeader = "Bearer " + token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private HttpRequest.Builder requestBuilder(String method, String path, Duration requestTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authHeader);
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }
        // 必须在这里应用 HTTP 方法：JDK HttpClient 的 builder **默认是 GET**。
        // 此前 method 参数被无视 ⇒ touch() 实际发的是 GET（provisioner 没有 GET /touch 路由 ⇒ 恒 404，
        // keepalive 一直静默失效）、delete() 实际发的也是 GET（provisioner 的 GET 路由是 discover ⇒ 返回 200，
        // 客户端以为删成功了，沙盒其实还在——容器一直泄漏）。create() 自己再 .POST(...) 覆盖，不受影响。
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder;
    }

    public boolean health() {
        try {
            HttpResponse<String> response = http.send(
                    requestBuilder("GET", "/health", timeout).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public SandboxRecord create(
            String sandboxId, String threadId, String uid,
            Map<String, String> env, String workdirPath, boolean inheritEnv) {
        JSONObject body = new JSONObject();
        body.put("sandbox_id", sandboxId);
        body.put("thread_id", threadId);
        body.put("workdir_path", workdirPath);
        body.put("uid", uid);
        body.put("env", env == null ? new JSONObject() : new JSONObject(env));
        body.put("inherit_env", inheritEnv);
        HttpResponse<String> response;
        try {
            // read 不设上限，对齐 httpx.Timeout(timeout_seconds, read=None)
            response = http.send(
                    requestBuilder("POST", "/api/sandboxes", null)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SandboxProvisionerException("failed to create sandbox " + sandboxId, e);
        }
        if (response.statusCode() >= 400) {
            throw new SandboxProvisionerException(
                    "failed to create sandbox " + sandboxId + ": " + response.statusCode() + " " + response.body());
        }
        return recordFromPayload(JSONObject.parseObject(response.body()));
    }

    public SandboxRecord discover(String sandboxId) {
        HttpResponse<String> response;
        try {
            response = http.send(
                    requestBuilder("GET", "/api/sandboxes/" + sandboxId, timeout).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SandboxProvisionerException("failed to discover sandbox " + sandboxId, e);
        }
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() >= 400) {
            throw new SandboxProvisionerException(
                    "failed to discover sandbox " + sandboxId + ": " + response.statusCode() + " " + response.body());
        }
        return recordFromPayload(JSONObject.parseObject(response.body()));
    }

    public boolean touch(String sandboxId) {
        HttpResponse<String> response;
        try {
            response = http.send(
                    requestBuilder("POST", "/api/sandboxes/" + sandboxId + "/touch", timeout).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SandboxProvisionerException("failed to touch sandbox " + sandboxId, e);
        }
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() >= 400) {
            throw new SandboxProvisionerException(
                    "failed to touch sandbox " + sandboxId + ": " + response.statusCode() + " " + response.body());
        }
        return true;
    }

    public void delete(String sandboxId, String expectedGeneration) {
        String path = "/api/sandboxes/" + sandboxId;
        if (expectedGeneration != null) {
            path = path + "?expected_generation=" + expectedGeneration;
        }
        HttpResponse<String> response;
        try {
            response = http.send(
                    requestBuilder("DELETE", path, deleteTimeout).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SandboxProvisionerException("failed to delete sandbox " + sandboxId, e);
        }
        if (response.statusCode() == 200 || response.statusCode() == 404) {
            return;
        }
        throw new SandboxProvisionerException(
                "failed to delete sandbox " + sandboxId + ": " + response.statusCode() + " " + response.body());
    }

    private static SandboxRecord recordFromPayload(JSONObject payload) {
        return new SandboxRecord(
                payload.getString("sandbox_id"),
                payload.getString("sandbox_url"),
                payload.containsKey("status") ? payload.getString("status") : null,
                payload.containsKey("generation") ? payload.getString("generation") : null,
                payload.containsKey("workdir_path") ? payload.getString("workdir_path") : null);
    }
}
