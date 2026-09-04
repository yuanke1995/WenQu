package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 智能表单 Agent 的「手」：封装对 dynamic-form /open/agent/* 开放接口的 HTTP 调用。
 *
 * <p>dynamic-form 侧接口（P0-a 已实现）：
 * <ul>
 *   <li>GET  /open/agent/table/list        —— 可用表单清单</li>
 *   <li>GET  /open/agent/table/structure?tableId= —— 表单结构（components + columns）</li>
 *   <li>POST /open/agent/draft/validate    —— 校验草稿 JSON 字段合法性</li>
 * </ul>
 * 统一携带 X-Agent-Token 请求头。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class FormAgentToolService {

    private final AiAppProperties.FormAgent props;
    private final HttpClient httpClient;

    public FormAgentToolService(AiAppProperties properties) {
        this.props = properties.getFormAgent();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMillis()))
                .build();
    }

    /** dynamic-form Agent API 是否可用（地址已配置） */
    public boolean isConfigured() {
        return props.isEnabled()
                && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                && props.getToken() != null && !props.getToken().isBlank();
    }

    /** 列出可用表单 */
    public JSONObject listTables() {
        return doGet("/open/agent/table/list");
    }

    /** 获取指定表单结构 */
    public JSONObject getTableStructure(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            throw new BizException("缺少 tableId");
        }
        return doGet("/open/agent/table/structure?tableId=" + encode(tableId));
    }

    /** 校验草稿 JSON 字段合法性 */
    public JSONObject validateDraft(String tableId, Map<String, Object> data) {
        if (tableId == null || tableId.isBlank() || data == null) {
            throw new BizException("参数缺失：需要 tableId 与 data");
        }
        JSONObject body = new JSONObject();
        body.put("tableId", tableId);
        body.put("data", data);
        return doPost("/open/agent/draft/validate", body);
    }

    // ===== 内部实现 =====

    private JSONObject doGet(String path) {
        ensureConfigured();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(join(path)))
                    .timeout(Duration.ofMillis(props.getTimeoutMillis()))
                    .header("X-Agent-Token", props.getToken())
                    .GET()
                    .build();
            return send(req);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[FormAgent] GET {} 调用失败: {}", path, e.getMessage());
            throw new BizException("表单服务调用失败：" + e.getMessage());
        }
    }

    private JSONObject doPost(String path, JSONObject body) {
        ensureConfigured();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(join(path)))
                    .timeout(Duration.ofMillis(props.getTimeoutMillis()))
                    .header("X-Agent-Token", props.getToken())
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();
            return send(req);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[FormAgent] POST {} 调用失败: {}", path, e.getMessage());
            throw new BizException("表单服务调用失败：" + e.getMessage());
        }
    }

    private JSONObject send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new BizException("表单服务返回异常状态：" + resp.statusCode());
        }
        JSONObject json = JSON.parseObject(resp.body());
        // dynamic-form ResultJson 结构：success / code / msg / data
        if (json == null || !Boolean.TRUE.equals(json.getBoolean("success"))) {
            String msg = json == null ? "空响应" : json.getString("msg");
            throw new BizException("表单服务拒绝：" + (msg == null ? "未知错误" : msg));
        }
        return json.getJSONObject("data") == null ? json : json.getJSONObject("data");
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new BizException("智能表单 Agent 未配置（ai-app.form-agent.base-url/token 缺失或未启用）");
        }
    }

    private String join(String path) {
        String base = props.getBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private String encode(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
