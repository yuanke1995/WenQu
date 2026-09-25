package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重排服务：调用独立 reranker 服务（OpenAI 兼容 POST /v1/rerank，如 scripts/rerank_server.py 本地服务）。
 *
 * <p>注意：Ollama 官方（含 1.x）没有 /api/rerank 端点、官方库也没有 rerank 模型，
 * 社区模型仅能经 /api/embed 近似且部分环境 embedding 被禁用，因此本项目只支持 OpenAI 兼容协议。
 * 本地部署参考 scripts/win|mac/start_rerank_server.*（sentence-transformers CrossEncoder 服务）。
 *
 * <p>配置经 {@link ModelRegistryService#rerankRoute} 解析（rerank.model 为引用时取对应供应商网关
 * baseUrl/apiKey，遗留值走全局 rerank.* 配置），设置页保存即生效。
 * 未启用/探测失败/调用失败时静默回退为输入顺序（混合检索已按融合分排序）。
 * 探测/失败结果缓存，首次失败记忆禁用（进程内不再重试）；配置变更（网关/模型/超时）自动重建客户端并重置探测。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RerankService {

    private final ConfigService configService;
    private final ModelRegistryService modelRegistryService;
    private final AtomicBoolean supportChecked = new AtomicBoolean(false);
    private volatile boolean rerankSupported = false;
    private volatile RestClient client;          // 按当前 baseUrl/timeout 懒构建
    private volatile String clientKey = "";      // 已构建 client 对应的指纹 baseUrl|path|apiKey（变化时重建）
    private volatile String clientRerankPath = "/v1/rerank"; // 已构建 client 对应的重排路径
    private volatile int clientTimeout = 0;      // 已构建 client 对应的 timeout
    /** 最近一次失败时间戳（失败冷却：短暂故障后自动恢复，避免永久禁用） */
    private volatile long lastFailTs = 0L;
    /** 失败冷却时长：rerank.failCooldownMs 可调（设置页/DB），默认 60s */
    private long failCooldownMs() { return configService.getInt("rerank.failCooldownMs", 60_000); }

    public RerankService(ConfigService configService, ModelRegistryService modelRegistryService) {
        this.configService = configService;
        this.modelRegistryService = modelRegistryService;
        log.info("[Rerank] 配置经供应商注册中心解析（rerank.model 引用→供应商网关；遗留→rerank.*），保存即生效");
    }

    /** 动态读配置（保存即生效） */
    private boolean enabled() { return configService.getBoolean("rerank.enabled"); }

    /** 重排路由：引用→供应商网关；遗留→全局 rerank.*（本地 reranker 默认模型兜底） */
    private ModelRegistryService.ModelRoute route() {
        return modelRegistryService.rerankRoute();
    }

    private String model() {
        String m = route().modelId();
        return m == null || m.isBlank() ? "BAAI/bge-reranker-v2-m3" : m;
    }

    private int timeoutMillis() {
        int t = configService.getInt("rerank.timeoutMillis");
        return t > 0 ? t : 5000;
    }

    /** 配置变化（网关/路径/Key/超时）时重建 RestClient 并重置探测缓存 */
    private RestClient client() {
        ModelRegistryService.ModelRoute r = route();
        // 版本段尾缀（…/v1、…/v4）自动移入重排路径；本地地址保持 /v1/rerank
        String[] np = DynamicOpenAiChatModel.normalize(r.baseUrl(), "", "/v1/rerank", "/rerank");
        String key = np[0] + "|" + np[1] + "|" + (r.apiKey() == null ? "" : r.apiKey());
        int t = timeoutMillis();
        if (client == null || !key.equals(clientKey) || t != clientTimeout) {
            synchronized (this) {
                if (client == null || !key.equals(clientKey) || t != clientTimeout) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(2000);
                    factory.setReadTimeout(t);
                    client = RestClient.builder().baseUrl(np[0]).requestFactory(factory).build();
                    clientKey = key;
                    clientRerankPath = np[1];
                    clientTimeout = t;
                    // 配置变更后重新探测
                    supportChecked.set(false);
                    rerankSupported = false;
                    log.info("[Rerank] 客户端重建: {}，模型 {}，超时 {}ms", np[0], model(), t);
                }
            }
        }
        return client;
    }

    /** 供应商 Key 非空时附带 Bearer（云端 rerank 网关需要；本地 reranker 服务无 Key 时省略） */
    private <T extends RestClient.RequestHeadersSpec<?>> T auth(T spec) {
        String apiKey = route().apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            spec.header("Authorization", "Bearer " + apiKey);
        }
        return spec;
    }

    /**
     * 重排候选（传入按融合分排序的候选，返回重排后的顺序）；未启用/不支持/失败时原样返回
     */
    public List<HybridRetrievalService.Hit> rank(List<HybridRetrievalService.Hit> candidates, String query) {
        return rank(candidates, query, null);
    }

    /**
     * 带个人重排模型覆盖：refOverride 非空时按该引用解析网关+模型（聊天用户的个人默认重排模型），
     * 解析失败回落全局路径；每次独立构建 client，不写全局探测/冷却状态（个人调用失败仅回退排序）。
     */
    public List<HybridRetrievalService.Hit> rank(List<HybridRetrievalService.Hit> candidates, String query,
                                                 String refOverride) {
        if (refOverride != null && !refOverride.isBlank()) {
            ModelRegistryService.ModelRoute r = modelRegistryService.resolveReference(refOverride.trim());
            if (r != null) {
                return rankWithRoute(candidates, query, r, true);
            }
            log.warn("[Rerank] 个人重排模型引用解析失败，回落全局: {}", refOverride);
        }
        return rankGlobal(candidates, query);
    }

    private List<HybridRetrievalService.Hit> rankGlobal(List<HybridRetrievalService.Hit> candidates, String query) {
        if (!enabled() || candidates == null || candidates.size() < 2) return candidates;
        // 防御：query 为空/null 时服务端 400（"query and documents required"）——直接跳过重排回退融合分排序
        if (query == null || query.isBlank()) return candidates;
        if (!checkSupport()) return candidates;
        return rankWithRoute(candidates, query, route(), false);
    }

    private List<HybridRetrievalService.Hit> rankWithRoute(List<HybridRetrievalService.Hit> candidates, String query,
                                                           ModelRegistryService.ModelRoute r, boolean oneShot) {
        {

        try {
            List<String> docs = candidates.stream()
                    .map(h -> h.title() + "\n"
                            + (h.titlePath() != null && !h.titlePath().isBlank()
                                    ? "【上下文】" + h.titlePath() + "\n\n" : "")
                            + h.content())
                    .toList();
            List<Double> scores = oneShot ? rankByOpenAiRerank(query, docs, r) : rankByOpenAiRerank(query, docs);
            if (scores == null || scores.size() != docs.size()) return candidates;

            List<HybridRetrievalService.Hit> ranked = new ArrayList<>(candidates);
            // 预计算 index→score 映射，避免排序比较器里反复 indexOf（O(n² log n) 且 record equals 会逐字段比较大文本）
            Map<HybridRetrievalService.Hit, Double> scoreMap = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                scoreMap.put(candidates.get(i), scores.get(i));
            }
            ranked.sort((a, b) -> Double.compare(scoreMap.get(b), scoreMap.get(a)));
            log.info("[Rerank] 候选 {} 条重排完成", candidates.size());
            return ranked;
        } catch (Exception e) {
            if (oneShot) {
                // 个人模型调用失败仅本次回退，不写全局冷却状态
                log.warn("[Rerank] 个人重排模型调用失败，回退融合分排序: {}", e.getMessage());
            } else {
                // 失败记录：冷却期内不重试（避免每个请求都撞一次），冷却结束后自动恢复探测
                rerankSupported = false;
                lastFailTs = System.currentTimeMillis();
                log.warn("[Rerank] 服务调用失败，回退融合分排序（{}s 后自动重试）: {}",
                        failCooldownMs() / 1000, e.getMessage());
            }
            return candidates;
        }
        }
    }

    /** 一次性客户端 + 按路由发 /v1/rerank（个人重排模型路径；不触碰全局 client/探测状态） */
    private List<Double> rankByOpenAiRerank(String query, List<String> docs,
                                            ModelRegistryService.ModelRoute r) throws Exception {
        if (docs == null || docs.isEmpty()) return null;
        String[] np = DynamicOpenAiChatModel.normalize(r.baseUrl(), "", "/v1/rerank", "/rerank");
        int t = timeoutMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(t);
        RestClient oneShot = RestClient.builder().baseUrl(np[0]).requestFactory(factory).build();
        Map<String, Object> body = new HashMap<>();
        String mdl = r.modelId() == null || r.modelId().isBlank() ? "BAAI/bge-reranker-v2-m3" : r.modelId();
        body.put("model", mdl);
        body.put("query", query == null ? "" : query);
        body.put("documents", docs);
        body.put("top_n", docs.size());
        String resp = oneShot.post()
                .uri(np[1])
                .header("Authorization", "Bearer " + (r.apiKey() == null ? "" : r.apiKey()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseScores(resp, docs.size());
    }

    /**
     * 调试用：返回当前重排不可用的具体原因（null = 可执行）
     * 供检索调试展示"为什么没重排"，避免误以为重排已生效
     */
    public String debugUnavailableReason() {
        if (!enabled()) {
            return "未启用：设置页「检索设置 → 启用重排」未打开（AI_RERANK_ENABLED）";
        }
        if (!checkSupport()) {
            if (System.currentTimeMillis() - lastFailTs < failCooldownMs()) {
                return "重排服务调用失败，冷却中（" + failCooldownMs() / 1000 + "s 后自动重试）";
            }
            return "重排服务探测失败（/v1/models 无响应），请确认本地 reranker 已启动（scripts/win/start_rerank_server.bat）";
        }
        return null;
    }

    /** OpenAI 兼容 /v1/rerank：真交叉编码重排，直接返回 relevance_score */
    private List<Double> rankByOpenAiRerank(String query, List<String> docs) throws Exception {
        if (docs == null || docs.isEmpty()) return null;
        Map<String, Object> body = new HashMap<>();
        body.put("model", model());
        // 防御：query 为空时服务端 400（"query and documents required"），空串可发但服务端仍判空——调用方保证非空
        body.put("query", query == null ? "" : query);
        body.put("documents", docs);
        body.put("top_n", docs.size());

        String resp = auth(client().post()
                .uri(clientRerankPath)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .body(body)
                .retrieve()
                .body(String.class);
        return parseScores(resp, docs.size());
    }

    /** 解析 /v1/rerank 响应：results[].index/relevance_score → 按 index 定位分数（缺失位补 0.0） */
    private List<Double> parseScores(String resp, int size) {
        if (resp == null) return null;
        JSONObject root = JSON.parseObject(resp);
        JSONArray results = root.getJSONArray("results");
        if (results == null || results.isEmpty()) return null;

        Double[] scores = new Double[size];
        for (int i = 0; i < results.size(); i++) {
            var item = results.getJSONObject(i);
            int idx = item.getIntValue("index");
            if (idx >= 0 && idx < scores.length) {
                scores[idx] = item.getDoubleValue("relevance_score");
            }
        }
        List<Double> list = new ArrayList<>(size);
        for (Double s : scores) list.add(s == null ? 0.0 : s);
        return list;
    }

    /**
     * 强制探测服务可用性（绕过缓存，每次真实请求 /v1/models；供设置页"开启前校验"调用）
     * 服务修复后调用本方法可立即恢复，不受此前失败记忆影响
     */
    public boolean checkAvailable() {
        supportChecked.set(false);
        rerankSupported = false;
        boolean ok = checkSupport();
        log.info("[Rerank] 强制探测结果: {}", ok);
        return ok;
    }

    /**
     * 服务可用性探测（仅探测一次并缓存；配置变更后 client() 会自动重置）：
     * GET /v1/models 确认 OpenAI 兼容服务在线
     */
    private boolean checkSupport() {
        // 已探测过：成功直接返回；失败且冷却未过 → 仍不可用；冷却已过 → 重新探测（瞬时故障自动恢复）
        if (supportChecked.get()) {
            if (rerankSupported) return true;
            if (System.currentTimeMillis() - lastFailTs < failCooldownMs()) return false;
            supportChecked.set(false);  // 冷却结束，允许重新探测
        }
        synchronized (this) {
            if (supportChecked.get()) return rerankSupported;
            try {
                String modelsPath = clientRerankPath.replaceFirst("/rerank$", "/models");
                String resp = auth(client().get().uri(modelsPath)).retrieve().body(String.class);
                rerankSupported = resp != null && resp.contains("data");
                if (!rerankSupported) {
                    lastFailTs = System.currentTimeMillis();
                    log.warn("[Rerank] /v1/models 无有效响应，重排不可用（回退融合分排序）");
                }
            } catch (Exception e) {
                rerankSupported = false;
                lastFailTs = System.currentTimeMillis();
                log.warn("[Rerank] 服务探测失败（回退融合分排序）baseUrl={}: {}", clientKey.replaceAll("\\|[^|]*$", "|****"), e.getMessage());
            }
            supportChecked.set(true);
            return rerankSupported;
        }
    }
}
