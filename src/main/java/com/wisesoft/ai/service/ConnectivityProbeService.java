package com.wisesoft.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连通性探测（设置页「测试连接」按钮）。
 *
 * <p>与保存流程（{@link ConfigService#update}）的区别：
 * <ul>
 *   <li>用<b>表单里尚未保存的值</b>探测（"先测后存"）——现有 rerank/keyword 的 check 端点读的是已保存配置，
 *       必须保存后才能测，测出来是旧地址的结果；</li>
 *   <li>不落库、不触发全量重嵌入 / 索引重建等联动，只返回可达性、耗时与失败原因。</li>
 * </ul>
 *
 * <p>探测方式按类型分派：
 * <ul>
 *   <li>chat / vision：向补全地址发一次最小 chat 补全（max_tokens=8）——同时验证 地址、版本段路径、Key、模型名；</li>
 *   <li>embedding：复用 {@link DynamicEmbeddingModel#probe}，额外返回模型向量维度；</li>
 *   <li>rerank：GET {baseUrl}/v1/models（OpenAI 兼容，与 RerankService 探测一致）；</li>
 *   <li>keyword：GET {baseUrl}/health 探活 + GET {baseUrl}/indexes（带 master key）验证密钥是否被接受。</li>
 * </ul>
 *
 * <p>安全：仅供管理员端点调用；出站地址仅允许 http/https，且统一 2s 连接 / 5s 读超时（探测需快速反馈）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectivityProbeService {

    private final ConfigService configService;

    /** 连接超时：探测要快速失败，避免页面长时间转圈 */
    private static final int CONNECT_TIMEOUT_MS = 2000;
    /** 读取超时：模型网关首 token 可能偏慢，留 5s */
    private static final int READ_TIMEOUT_MS = 5000;
    /** 失败详情最大长度（防止网关返回整页 HTML 撑爆响应） */
    private static final int DETAIL_MAX = 300;

    /**
     * 执行探测。
     *
     * @param group   chat / vision / embedding / rerank / keyword
     * @param baseUrl 表单当前值（空则回退已保存配置）
     * @param apiKey  表单当前值（空或 **** 掩码则回退已保存配置，与保存流程约定一致）
     * @param model   表单当前值（空则回退已保存配置）
     * @param path    表单当前值（补全/向量路径；仅 chat / embedding 使用）
     * @return {available, latencyMs, detail}
     */
    public Map<String, Object> probe(String group, String baseUrl, String apiKey, String model, String path) {
        long start = System.currentTimeMillis();
        String g = group == null ? "" : group.trim().toLowerCase();
        try {
            return switch (g) {
                case "chat" -> chatProbe("chat", baseUrl, apiKey, model, path, start);
                case "vision" -> chatProbe("vision", baseUrl, apiKey, model, null, start);
                case "embedding" -> embeddingProbe(baseUrl, apiKey, model, path, start);
                case "rerank" -> rerankProbe(baseUrl, model, start);
                case "keyword" -> keywordProbe(baseUrl, apiKey, start);
                default -> fail(start, "不支持的探测类型：" + group);
            };
        } catch (Exception e) {
            log.info("[Probe] {} 探测异常: {}", g, e.getMessage());
            return fail(start, rootMessage(e));
        }
    }

    /**
     * 对话类探测（chat / vision 共用）：向补全地址发一次最小补全。
     * <p>比 GET /models 更可靠——部分网关不实现 /models；且能顺带验证模型名与 Key 是否被接受。
     */
    private Map<String, Object> chatProbe(String group, String baseUrl, String apiKey, String model,
                                          String path, long start) {
        String base = value(baseUrl, group + ".baseUrl");
        String key = secret(apiKey, group + ".apiKey");
        String mdl = value(model, group + ".model");
        // vision 无独立路径配置：固定走 /v1/chat/completions（与 VisionService 一致）
        String pathCfg = "chat".equals(group) ? value(path, "chat.completionsPath") : "";
        String[] np = DynamicOpenAiChatModel.normalize(base, pathCfg, "/v1/chat/completions", "/chat/completions");
        String url = np[0] + np[1];

        if (np[0].isBlank()) return fail(start, "网关地址为空");
        if (mdl.isBlank()) return fail(start, "模型名为空");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", mdl);
        body.put("messages", List.of(Map.of("role", "user", "content", "连通性探测，回复 ok 即可")));
        body.put("max_tokens", 8);
        body.put("stream", false);
        return post(url, key, body, start, "补全地址 " + url);
    }

    /** 向量模型探测：复用保存流程的 probe（真实 embedding 一次），额外返回向量维度 */
    private Map<String, Object> embeddingProbe(String baseUrl, String apiKey, String model,
                                               String path, long start) {
        String base = value(baseUrl, "embedding.baseUrl");
        String key = secret(apiKey, "embedding.apiKey");
        String mdl = value(model, "embedding.model");
        String p = value(path, "embedding.embeddingsPath");
        if (base.isBlank()) return fail(start, "网关地址为空");
        if (mdl.isBlank()) return fail(start, "模型名为空");
        int dim = DynamicEmbeddingModel.probe(base, key, mdl, p);
        if (dim <= 0) return fail(start, "返回维度非法(" + dim + ")，疑似网关不兼容 OpenAI embeddings 协议");
        return ok(start, "可用，向量维度 " + dim);
    }

    /** 重排服务探测：GET {baseUrl}/v1/models（与 RerankService 一致） */
    private Map<String, Object> rerankProbe(String baseUrl, String model, long start) {
        String base = value(baseUrl, "rerank.baseUrl");
        if (base.isBlank()) return fail(start, "服务地址为空");
        String url = stripTrailingSlash(base) + "/v1/models";
        return get(url, null, start, "重排服务 " + url);
    }

    /**
     * Meilisearch 探测：先 /health 探活，再带 master key 请求 /indexes 验证密钥。
     * 密钥不对时 /health 仍 200，所以必须第二步才能区分"服务没起"与"Key 不对"。
     */
    private Map<String, Object> keywordProbe(String baseUrl, String apiKey, long start) {
        String base = value(baseUrl, "keyword.baseUrl");
        if (base.isBlank()) return fail(start, "服务地址为空");
        String root = stripTrailingSlash(base);
        Map<String, Object> health = get(root + "/health", null, start, "Meilisearch " + root);
        if (!Boolean.TRUE.equals(health.get("available"))) return health;
        String key = secret(apiKey, "keyword.apiKey");
        Map<String, Object> indexed = get(root + "/indexes", key, start, "Meilisearch 密钥校验");
        if (!Boolean.TRUE.equals(indexed.get("available"))) {
            return fail(start, "服务在线，但 master key 校验失败（请确认与服务端 MEILI_MASTER_KEY 一致）："
                    + indexed.get("detail"));
        }
        return ok(start, "可用（服务在线，密钥有效）");
    }

    // ==================== HTTP 基础 ====================

    private Map<String, Object> post(String url, String apiKey, Object body, long start, String what) {
        return request(url, apiKey, what, start, true, body);
    }

    private Map<String, Object> get(String url, String apiKey, long start, String what) {
        return request(url, apiKey, what, start, false, null);
    }

    private Map<String, Object> request(String url, String apiKey, String what, long start,
                                        boolean post, Object body) {
        if (!isHttpUrl(url)) return fail(start, "地址不合法（仅支持 http/https）：" + url);
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        try {
            RestClient client = RestClient.builder().requestFactory(factory()).build();
            ResponseEntity<String> resp;
            if (post) {
                RestClient.RequestBodySpec spec = client.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON);
                if (hasKey) spec = spec.header("Authorization", "Bearer " + apiKey);
                resp = spec.body(body).retrieve().toEntity(String.class);
            } else {
                RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
                if (hasKey) spec = spec.header("Authorization", "Bearer " + apiKey);
                resp = spec.retrieve().toEntity(String.class);
            }
            int status = resp.getStatusCode().value();
            if (status >= 200 && status < 300) {
                return ok(start, "可用（HTTP " + status + "）");
            }
            return fail(start, cut("HTTP " + status + "：" + safe(resp.getBody())));
        } catch (RestClientResponseException e) {
            // 4xx/5xx：网关有响应，说明地址可达，但鉴权/模型/协议有问题——把响应体带出来便于定位
            int status = e.getStatusCode().value();
            String hint = status == 401 || status == 403 ? "（疑似 API Key 无效或无权限）"
                    : status == 404 ? "（地址或路径不存在，检查 baseUrl/版本段与补全路径）" : "";
            return fail(start, cut("HTTP " + status + hint + "：" + safe(e.getResponseBodyAsString())));
        } catch (Exception e) {
            return fail(start, cut(what + " 请求失败：" + rootMessage(e)));
        }
    }

    private SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_TIMEOUT_MS);
        f.setReadTimeout(READ_TIMEOUT_MS);
        return f;
    }

    // ==================== 取值与工具 ====================

    /** 表单值优先，空则回退已保存配置 */
    private String value(String provided, String cfgKey) {
        String v = provided == null ? "" : provided.trim();
        return v.isEmpty() ? nvl(configService.get(cfgKey)) : v;
    }

    /** 密钥：表单值若为空或 **** 掩码（前端未修改时不回传真实值）则回退已保存配置（get 内部透明解密） */
    private String secret(String provided, String cfgKey) {
        String v = provided == null ? "" : provided.trim();
        if (v.isEmpty() || v.startsWith("****")) {
            return nvl(configService.get(cfgKey));
        }
        return v;
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static boolean isHttpUrl(String url) {
        String u = url == null ? "" : url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String safe(String body) {
        if (body == null) return "";
        String b = body.replaceAll("\\s+", " ").trim();
        return b.isEmpty() ? "（空响应）" : b;
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() <= DETAIL_MAX ? s : s.substring(0, DETAIL_MAX) + "…";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static Map<String, Object> ok(long start, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", true);
        m.put("latencyMs", System.currentTimeMillis() - start);
        m.put("detail", detail);
        return m;
    }

    private static Map<String, Object> fail(long start, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        m.put("latencyMs", System.currentTimeMillis() - start);
        m.put("detail", detail);
        return m;
    }
}
