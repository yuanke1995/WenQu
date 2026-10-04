package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 *   <li>rerank：真实 POST {baseUrl}/rerank 一次（与 {@link RerankService} 同款载荷；Key 非空时附带 Bearer，
 *       云端 rerank 网关必需——GET /models 不带 Key 会被判 401）；</li>
 *   <li>audio：GET {baseUrl}/v1/models 验证网关可达与 Key 有效（ASR/TTS 端点协议各家不一，不做最小调用）；</li>
 *   <li>keyword：GET {baseUrl}/health 探活 + GET {baseUrl}/indexes（带 master key）验证密钥是否被接受。</li>
 *   <li>sandbox：GET {baseUrl}/health 探活 + 带令牌 GET {baseUrl}/api/sandboxes 验证令牌被接受。</li>
 *   <li>webSearch：按 {@code webSearch.provider} 分派各家真实协议打一次最小搜索
 *       （generic=SearXNG 走 GET /search?format=json、tavily/bocha 走 POST 搜索端点），
 *       而非只探根路径——SearXNG 未在 settings.yml 开启 json 格式时 /search 会返 403，
 *       只探根路径会把「装好了但不可用」误判成可用。</li>
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

    // ---- 联网搜索探测 ----
    /** 探测用固定 innocuous 词 + limit=1，把真实调用成本压到最低（仍是一次真实搜索，故能验出格式/鉴权问题） */
    private static final String PROBE_QUERY = "wenqu connectivity probe";
    /** 与 TavilySearchProvider.DEFAULT_URL 一致；表单未填地址时回落（与运行时同款默认） */
    private static final String DEFAULT_TAVILY_URL = "https://api.tavily.com/search";
    /** 与 BochaSearchProvider.DEFAULT_URL 一致 */
    private static final String DEFAULT_BOCHA_URL = "https://api.bochaai.com/v1/web-search";

    /**
     * 执行探测。
     *
     * @param group   chat / vision / embedding / rerank / audio / keyword / ocrmineru / ocrpp / sandbox / webSearch
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
                case "rerank" -> rerankProbe(baseUrl, apiKey, model, start);
                case "audio" -> audioProbe(baseUrl, apiKey, start);
                case "keyword" -> keywordProbe(baseUrl, apiKey, start);
                case "ocrmineru" -> ocrMineruProbe(baseUrl, start);
                case "ocrpp" -> ocrPpProbe(baseUrl, start);
                case "sandbox" -> sandboxProbe(baseUrl, apiKey, start);
                case "websearch" -> webSearchProbe(baseUrl, apiKey, start);
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
     * <p>仅按表单显式值探测（chat.* 与 vision.* 全局网关键已退役，供应商档案是唯一网关来源）。
     */
    private Map<String, Object> chatProbe(String group, String baseUrl, String apiKey, String model,
                                          String path, long start) {
        String base = nvl(baseUrl);
        String key = nvl(apiKey);
        String mdl = nvl(model);
        // vision 无独立路径配置：固定走 /v1/chat/completions（与 VisionService 一致）
        String pathCfg = "chat".equals(group) ? nvl(path) : "";
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

    /** 向量模型探测：复用保存流程的 probe（真实 embedding 一次），额外返回向量维度。
     *  仅按显式传入的表单值探测（embedding.* 全局网关键已退役，供应商档案是唯一网关来源）。 */
    private Map<String, Object> embeddingProbe(String baseUrl, String apiKey, String model,
                                               String path, long start) {
        String base = nvl(baseUrl);
        String key = nvl(apiKey);
        String mdl = model == null ? "" : model.trim();
        String p = nvl(path);
        if (base.isBlank()) return fail(start, "网关地址为空");
        if (mdl.isBlank()) return fail(start, "模型名为空");
        int dim = DynamicEmbeddingModel.probe(base, key, mdl, p);
        if (dim <= 0) return fail(start, "返回维度非法(" + dim + ")，疑似网关不兼容 OpenAI embeddings 协议");
        return ok(start, "可用，向量维度 " + dim);
    }

    /**
     * 重排模型探测：真实 POST {base}/rerank 一次（与 {@link RerankService} 运行时同款载荷），
     * 同时验证地址、路径、Key、模型名。本地 reranker 无 Key 时不带 Authorization。
     */
    private Map<String, Object> rerankProbe(String baseUrl, String apiKey, String model, long start) {
        String base = value(baseUrl, "rerank.baseUrl");
        if (base.isBlank()) return fail(start, "服务地址为空");
        // 仅按显式传入的模型名探测（rerank.model 归知识库/智能体检索设置绑定：值随库/智能体配置变化，探测不读全局）
        String mdl = model == null || model.isBlank() ? "" : model.trim();
        if (mdl.isBlank()) return fail(start, "模型名为空");
        // 与 RerankService 一致：版本段尾缀（…/v1、…/v4）自动移入重排路径（本地地址保持 /v1/rerank）
        String[] np = DynamicOpenAiChatModel.normalize(base, "", "/v1/rerank", "/rerank");
        String url = np[0] + np[1];
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", mdl);
        body.put("query", "连通性探测");
        body.put("documents", List.of("这是一条用于连通性探测的测试文档。"));
        return post(url, apiKey, body, start, "重排地址 " + url);
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

    /**
     * MinerU http-server 探测：GET {baseUrl}/openapi.json 并校验 paths 含 /file_parse。
     * 仅 /openapi.json 返回 200 还不够——要区分「MinerU 就绪」与「同端口跑了别的 OpenAPI 服务」。
     */
    private Map<String, Object> ocrMineruProbe(String baseUrl, long start) {
        String base = value(baseUrl, "parse.ocrMineruUri");
        if (base.isBlank()) return fail(start, "服务地址为空");
        String root = stripTrailingSlash(base);
        if (!isHttpUrl(root)) return fail(start, "地址不合法（仅支持 http/https）：" + root);
        String url = root + "/openapi.json";
        try {
            RestClient client = RestClient.builder().requestFactory(factory()).build();
            String body = client.get().uri(url).retrieve().toEntity(String.class).getBody();
            if (body != null && body.contains("\"/file_parse\"")) {
                return ok(start, "可用（/file_parse 解析端点就绪）");
            }
            return fail(start, "服务在线但未提供 /file_parse 端点（确认该地址是 MinerU http-server）："
                    + cut(safe(body)));
        } catch (Exception e) {
            return fail(start, cut(url + " 请求失败：" + rootMessage(e)));
        }
    }

    /** PP-StructureV3 serving（PaddleX）探测：GET {baseUrl}/health 返回 2xx 即就绪 */
    private Map<String, Object> ocrPpProbe(String baseUrl, long start) {
        String base = value(baseUrl, "parse.ocrPpUri");
        if (base.isBlank()) return fail(start, "服务地址为空");
        String root = stripTrailingSlash(base);
        return get(root + "/health", null, start, "PP-StructureV3 " + root);
    }

    /**
     * 沙盒 provisioner 探测：先 GET /health 探活，再带令牌 GET /api/sandboxes 校验令牌。
     * <p>两步是必要的：provisioner 的 /health <b>不带鉴权</b>（{@code @app.get("/health")} 无 auth 依赖），
     * 令牌错时它照样 200——只探活会把「地址对、令牌错」误判成可用。/api/sandboxes 才有
     * {@code require_provisioner_auth}，401/403 才是令牌问题。
     * <p>只读端点：list 不创建/不删除任何沙盒，与设置页其他探测一样无副作用。
     */
    private Map<String, Object> sandboxProbe(String baseUrl, String apiKey, long start) {
        String base = value(baseUrl, "sandbox.provisionerUrl");
        if (base.isBlank()) return fail(start, "服务地址为空");
        String root = stripTrailingSlash(base);
        if (!isHttpUrl(root)) return fail(start, "地址不合法（仅支持 http/https）：" + root);

        String healthBody;
        try {
            RestClient client = RestClient.builder().requestFactory(factory()).build();
            healthBody = client.get().uri(root + "/health").retrieve().toEntity(String.class).getBody();
        } catch (RestClientResponseException e) {
            return fail(start, cut("/health 返回 HTTP " + e.getStatusCode().value() + "："
                    + safe(e.getResponseBodyAsString())));
        } catch (Exception e) {
            return fail(start, cut("provisioner " + root + " 请求失败：" + rootMessage(e)));
        }
        // 200 但不是 provisioner 的 health（同端口跑了别的服务）：校验关键字段再往下走
        String flavor = provisionerFlavor(healthBody);
        if (flavor == null) {
            return fail(start, "服务在线但不是沙盒 provisioner（/health 响应缺少 status 字段）：" + cut(safe(healthBody)));
        }

        String token = secret(apiKey, "sandbox.token");
        if (token.isBlank()) {
            return fail(start, "服务在线" + flavor + "，但未配置访问令牌（沙盒要求 ≥32 字符，与 provisioner 侧一致）");
        }
        Map<String, Object> authed = get(root + "/api/sandboxes", token, start, "provisioner 令牌校验");
        if (!Boolean.TRUE.equals(authed.get("available"))) {
            return fail(start, "服务在线" + flavor + "，但令牌校验失败（确认与 provisioner 侧一致）：" + authed.get("detail"));
        }
        return ok(start, "可用（服务在线" + flavor + "，令牌有效）");
    }

    /**
     * 从 /health 响应体提取后端形态（docker/local）与在管沙盒数，供探测结果回显。
     *
     * @return 形如「（后端 docker，在管沙盒 2 个）」的片段；不是 provisioner 的响应返回 null
     */
    private static String provisionerFlavor(String body) {
        if (body == null || !body.contains("\"status\"")) return null;
        String backend = "";
        String tracked = "";
        try {
            JSONObject h = JSONObject.parseObject(body);
            if (h == null || h.getString("status") == null) return null;
            backend = h.getString("backend");
            tracked = h.getString("tracked_sandboxes");
        } catch (Exception e) {
            return null;
        }
        StringBuilder sb = new StringBuilder("（");
        if (backend != null && !backend.isBlank()) sb.append("后端 ").append(backend);
        if (tracked != null && !tracked.isBlank()) {
            if (sb.length() > 1) sb.append("，");
            sb.append("在管沙盒 ").append(tracked).append(" 个");
        }
        return sb.length() > 1 ? sb.append("）").toString() : "";
    }

    /**
     * 联网搜索探测：按 {@code webSearch.provider} 打一次**真实的最小搜索**，而不只探根路径。
     *
     * <p>为什么必须真打一次搜索：SearXNG（generic）只有在实例 settings.yml 的 formats 里开启
     * {@code json} 才能返回 JSON，否则 {@code /search?format=json} 直接 403（见
     * {@code GenericSearchProvider} 的类注释）。若只探根路径/健康端点，会把「实例活着但
     * JSON 格式没开＝实际不可用」误判成可用——而这正是自建部署最常见的踩坑。
     *
     * <p>provider 从已保存配置读（表单里没有这个字段，切换服务商时先测后存仍以当前选择的服务商为准）。
     * 探测词用固定的 innocuous 词（"wenqu probe"），limit=1，把真实成本压到最低；
     * provider 抛出的 401/403/余额不足等原样透出，便于与服务商对账。
     */
    private Map<String, Object> webSearchProbe(String baseUrl, String apiKey, long start) {
        String provider = nvl(configService.get("webSearch.provider"));
        if (provider.isBlank()) provider = "tavily";   // 与 schema 的 def 一致
        String base = value(baseUrl, "webSearch.baseUrl");
        String key = secret(apiKey, "webSearch.apiKey");

        return switch (provider.toLowerCase()) {
            case "generic" -> genericSearchProbe(base, key, start);
            // Tavily / Bocha 均为 POST 搜索端点 + Bearer 鉴权，但响应结构不同（results vs data.webPages.value），故分开写
            case "tavily" -> postSearchProbe(base.isBlank() ? DEFAULT_TAVILY_URL : base, key,
                    Map.of("query", PROBE_QUERY, "max_results", 1, "search_depth", "basic"), start, "Tavily", false);
            case "bocha" -> postSearchProbe(base.isBlank() ? DEFAULT_BOCHA_URL : base, key,
                    Map.of("query", PROBE_QUERY, "count", 1, "summary", true, "freshness", "noLimit"), start, "Bocha", true);
            default -> fail(start, "未知的联网搜索服务商：" + provider
                    + "（支持 tavily / bocha / generic）");
        };
    }

    /**
     * SearXNG（generic）探测：{@code GET {base}/search?q=...&format=json&limit=1}。
     *
     * <p>成功判据是「解析出 results 数组」，而不是 HTTP 200——SearXNG 在 formats 未开 json 时
     * 会以 200 返回 HTML 错误页（另一侧还有 403 的情形），只看状态码仍会误判。
     */
    private Map<String, Object> genericSearchProbe(String base, String key, long start) {
        if (base.isBlank()) {
            return fail(start, "服务地址为空（自建 SearXNG 无公共默认地址，webSearch.baseUrl 必填）");
        }
        String root = stripTrailingSlash(base);
        String url = root + "/search?q=" + URLEncoder.encode(PROBE_QUERY, StandardCharsets.UTF_8)
                + "&format=json&limit=1";
        if (!isHttpUrl(root)) return fail(start, "地址不合法（仅支持 http/https）：" + root);

        String body;
        try {
            RestClient client = RestClient.builder().requestFactory(factory()).build();
            RestClient.RequestHeadersSpec<?> spec = client.get().uri(url);
            // SearXNG 通常无鉴权；非空时仍按 Bearer 带上，兼容前面挂了网关的场景（与运行时一致）
            if (!key.isBlank()) spec = spec.header("Authorization", "Bearer " + key);
            body = spec.retrieve().toEntity(String.class).getBody();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 403 是 SearXNG 的标志性错误：json 格式没在 settings.yml 里开
            String hint = status == 403
                    ? "（SearXNG 需在实例 settings.yml 的 formats 里加入 json，否则 /search?format=json 返 403）"
                    : status == 401 ? "（地址前面挂了网关且要求鉴权，请填服务 Key）" : "";
            return fail(start, cut("HTTP " + status + hint + "：" + safe(e.getResponseBodyAsString())));
        } catch (Exception e) {
            return fail(start, cut("搜索服务 " + root + " 请求失败：" + rootMessage(e)));
        }

        com.alibaba.fastjson2.JSONObject obj;
        try {
            obj = JSONObject.parseObject(body);
        } catch (Exception e) {
            // 非 JSON：多半是 formats 未开 json 而返回了 HTML 错误页
            return fail(start, "响应不是 JSON（SearXNG 需在 settings.yml 的 formats 里加入 json）：" + cut(safe(body)));
        }
        if (obj == null || obj.getJSONArray("results") == null) {
            return fail(start, "响应 JSON 缺少 results 字段，确认这是 SearXNG 实例：" + cut(safe(body)));
        }
        int n = obj.getJSONArray("results").size();
        return ok(start, "可用（HTTP 200，SearXNG 返回 " + n + " 条结果；formats 已开 json）");
    }

    /**
     * Tavily / Bocha 探测：POST 一次最小搜索载荷。
     * <p>成功判据按各家真实响应结构取结果条数（与运行时 provider 的解析口径一致）：
     * Tavily 是 {@code results}，Bocha 嵌套在 {@code data.webPages.value}——两者不同，
     * 统一按 results 判会把「Bocha 完全正常」误报成失败。
     */
    private Map<String, Object> postSearchProbe(String url, String key, Object body,
                                                long start, String label, boolean bochaShape) {
        if (!isHttpUrl(url)) return fail(start, "地址不合法（仅支持 http/https）：" + url);
        if (key.isBlank()) {
            return fail(start, "服务 Key 为空（" + label + " 按 Bearer 鉴权，缺 Key 必然 401）");
        }
        String resp;
        try {
            RestClient client = RestClient.builder().requestFactory(factory()).build();
            resp = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + key)
                    .body(body).retrieve().toEntity(String.class).getBody();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String hint = status == 401 || status == 403 ? "（服务 Key 无效或无权限）"
                    : status == 402 ? "（账户余额不足）"
                    : status == 429 ? "（触发限流，稍后重试）" : "";
            return fail(start, cut("HTTP " + status + hint + "：" + safe(e.getResponseBodyAsString())));
        } catch (Exception e) {
            return fail(start, cut(label + " " + url + " 请求失败：" + rootMessage(e)));
        }
        try {
            com.alibaba.fastjson2.JSONObject obj = JSONObject.parseObject(resp);
            JSONArray arr = bochaShape ? bochaResults(obj) : (obj == null ? null : obj.getJSONArray("results"));
            if (arr != null) {
                return ok(start, "可用（HTTP 200，" + label + " 返回 " + arr.size() + " 条结果）");
            }
            return fail(start, "响应缺少结果数组（Bocha 应在 data.webPages.value），确认地址指向 " + label
                    + " 搜索端点：" + cut(safe(resp)));
        } catch (Exception e) {
            return fail(start, "响应不是 JSON（确认地址指向 " + label + " 搜索端点）：" + cut(safe(resp)));
        }
    }

    /** Bocha 的结果数组在 data.webPages.value（三层嵌套，与 BochaSearchProvider 解析口径一致） */
    private static JSONArray bochaResults(JSONObject obj) {
        if (obj == null || obj.getJSONObject("data") == null) return null;
        if (obj.getJSONObject("data").getJSONObject("webPages") == null) return null;
        return obj.getJSONObject("data").getJSONObject("webPages").getJSONArray("value");
    }

    /**
     * 语音模型探测（ASR/TTS）：各家语音端点协议不一（multipart 上传 / 二进制音频响应），
     * 无统一的最小探测载荷——退而验证网关可达性与 Key 是否被接受（GET {base}/v1/models），
     * 并明确标注未实际调用语音端点；网关不实现 /models 时如实按失败返回。
     */
    private Map<String, Object> audioProbe(String baseUrl, String apiKey, long start) {
        String base = nvl(baseUrl);
        if (base.isBlank()) return fail(start, "网关地址为空");
        // 与运行时同款路径归一：版本段尾缀（…/v1、…/v4）自动移入模型列表路径
        String[] np = DynamicOpenAiChatModel.normalize(base, "", "/v1/models", "/models");
        String url = np[0] + np[1];
        Map<String, Object> r = get(url, apiKey, start, "语音网关 " + url);
        if (Boolean.TRUE.equals(r.get("available"))) {
            r.put("detail", "网关可达（未实际调用语音端点——ASR/TTS 协议各家不一，模型本身请通过业务功能验证）");
        }
        return r;
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
