package com.wenqu.ai.service;

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
     * baseUrl/apiKey，遗留值走全局 rerank.* 配置），知识库/智能体检索设置保存即生效。
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

    /**
     * 客户端（惰性构建）：首次调用按当前路由构建，网关/重排路径/Key/超时变化时重建。
     * <p>日志按两种情形区分（原先统一打成"客户端重建"，启动首建那条会被误读成"有人改了配置"）：
     * <ul>
     *   <li><b>就绪</b>：进程内首次构建（client == null），打印生效路由与模型；</li>
     *   <li><b>重建</b>：路由或超时确实变了，附具体变更项的旧值 → 新值（Key 按 {@code ****后4位} 掩码）。</li>
     * </ul>
     */
    private RestClient client() {
        ModelRegistryService.ModelRoute r = route();
        // 版本段尾缀（…/v1、…/v4）自动移入重排路径；本地地址保持 /v1/rerank
        String[] np = DynamicOpenAiChatModel.normalize(r.baseUrl(), "", "/v1/rerank", "/rerank");
        int t = timeoutMillis();
        RestClient current = client;
        if (current != null) {
            // 快路径：路由与超时都未变时不进同步块（每次重排都会走 client()）
            if (fingerprint(np, r).equals(clientKey) && t == clientTimeout) {
                return current;
            }
        }
        return rebuild();
    }

    /** 已构建客户端的指纹：网关地址|重排路径|API Key（三者任一变化都需要重建） */
    private String fingerprint(String[] np, ModelRegistryService.ModelRoute r) {
        return np[0] + "|" + np[1] + "|" + (r.apiKey() == null ? "" : r.apiKey());
    }

    /** 重建（或首次构建）客户端：双重检查，并发下只有一个线程真正构建 */
    private RestClient rebuild() {
        synchronized (this) {
            ModelRegistryService.ModelRoute r = route();
            String[] np = DynamicOpenAiChatModel.normalize(r.baseUrl(), "", "/v1/rerank", "/rerank");
            int t = timeoutMillis();
            String fp = fingerprint(np, r);
            boolean first = client == null;
            String oldFp = clientKey;
            int oldTimeout = clientTimeout;
            if (!first && fp.equals(oldFp) && t == oldTimeout) {
                return client; // 等待锁期间已被按同一份路由重建
            }
            // 变更明细：指纹三段 = 网关地址 | 重排路径 | Key，逐段比对便于判断到底哪一项变了
            List<String> changes = List.of();
            if (!first) {
                String[] oldParts = oldFp.split("\\|", -1);
                List<String> items = new ArrayList<>(4);
                if (!np[0].equals(oldParts[0])) items.add("地址 " + oldParts[0] + " → " + np[0]);
                if (!np[1].equals(oldParts[1])) items.add("重排路径 " + oldParts[1] + " → " + np[1]);
                if (!keyPart(fp).equals(keyPart(oldFp))) {
                    items.add("Key " + maskSecret(keyPart(oldFp)) + " → " + maskSecret(keyPart(fp)));
                }
                if (t != oldTimeout) items.add("超时 " + oldTimeout + "ms → " + t + "ms");
                changes = items;
            }
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(t);
            client = RestClient.builder().baseUrl(np[0]).requestFactory(factory).build();
            clientKey = fp;
            clientRerankPath = np[1];
            clientTimeout = t;
            // 配置变更后重新探测
            supportChecked.set(false);
            rerankSupported = false;
            if (first) {
                log.info("[Rerank] 客户端就绪: {}，模型 {}，超时 {}ms", np[0], model(), t);
            } else {
                log.info("[Rerank] 路由变更，客户端已重建: {}，模型 {}，超时 {}ms（变更: {}）",
                        np[0], model(), t, String.join("；", changes));
            }
            return client;
        }
    }

    /** 指纹中的 Key 段（第 3 段；指纹不足三段时返回空串） */
    private static String keyPart(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return "";
        String[] parts = fingerprint.split("\\|", -1);
        return parts.length > 2 ? parts[2] : "";
    }

    /** 密钥展示掩码（与 ConfigService 快照同一口径：****后4位） */
    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return "未配置";
        if (secret.length() <= 4) return "****";
        return "****" + secret.substring(secret.length() - 4);
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
        if (!enabled() || candidates == null || candidates.size() < 2) return candidates;
        // 防御：query 为空/null 时服务端 400（"query and documents required"）——直接跳过重排回退融合分排序
        if (query == null || query.isBlank()) return candidates;
        if (!checkSupport()) return candidates;
        return rankWithRoute(candidates, query, route(), false);
    }

    /**
     * 小批量强制重排：绕过 minHits/maxHits 窗口，候选多少都整批送 cross-encoder。
     * 消费方：子代理命中并入主链路前的补重排（≤6 块，低于 minHits 窗口，rank() 会整批跳过）——
     * 小批量不补重排就会带着融合分混进主链路，从分域门的低门（minFusionScore）绕过重排门（minContextScore）。
     * 可用性判断/超时/失败回退行为与 rank() 一致（失败回退融合分序并写全局冷却）。
     */
    public List<HybridRetrievalService.Hit> rankForced(List<HybridRetrievalService.Hit> candidates, String query) {
        if (!enabled() || candidates == null || candidates.isEmpty()) return candidates;
        if (query == null || query.isBlank()) return candidates;
        if (!checkSupport()) return candidates;
        return rankWithRoute(candidates, query, route(), true);
    }

    private List<HybridRetrievalService.Hit> rankWithRoute(List<HybridRetrievalService.Hit> candidates, String query,
                                                           ModelRegistryService.ModelRoute r,
                                                           boolean bypassWindow) {
        try {
            // 重排区间（rerank.minHits/maxHits，与评估链路同参数）：候选少于 minHits 不值得一次 cross-encoder
            // 推理；多于 maxHits 时**只重排融合分最高的 top maxHits**，其余保持融合分序接在重排结果之后——
            // 此前区间参数在生产链路无消费方，主检索 118 块全量发给本地服务，CPU 推理约 10s 撞客户端读超时，
            // 连接断裂后 keep-alive 复用半开连接（"重排服务老是挂"的根因）
            int minHits = Math.max(2, configService.getInt("rerank.minHits", 6));
            int maxHits = Math.max(minHits, configService.getInt("rerank.maxHits", 15));
            if (!bypassWindow && candidates.size() < minHits) return candidates;
            int cut = Math.min(candidates.size(), maxHits);
            List<HybridRetrievalService.Hit> head = new ArrayList<>(candidates.subList(0, cut));
            List<HybridRetrievalService.Hit> tail = candidates.size() > cut
                    ? new ArrayList<>(candidates.subList(cut, candidates.size())) : List.of();

            List<String> docs = head.stream()
                    .map(h -> h.title() + "\n"
                            + (h.titlePath() != null && !h.titlePath().isBlank()
                                    ? "【上下文】" + h.titlePath() + "\n\n" : "")
                            + h.content())
                    .toList();
            List<Double> scores = rankByOpenAiRerank(query, docs);
            if (scores == null || scores.size() != docs.size()) return candidates;

            // 重排分回填到每条候选（原先只用于排序即丢弃，引用来源无法透出真实相关度）；
            // 直接按分值降序排（primitive 比较，避免 record equals 逐字段比较大文本）
            List<HybridRetrievalService.Hit> ranked = new ArrayList<>(head.size());
            for (int i = 0; i < head.size(); i++) {
                ranked.add(head.get(i).withRerankScore(scores.get(i)));
            }
            ranked.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));
            if (!tail.isEmpty()) {
                ranked.addAll(tail);
                log.info("[Rerank] 候选 {} 条：top {} 进入重排，超出 {} 条保持融合分序",
                        candidates.size(), cut, tail.size());
            } else {
                log.info("[Rerank] 候选 {} 条重排完成", candidates.size());
            }
            return ranked;
        } catch (Exception e) {
            // 失败记录：冷却期内不重试（避免每个请求都撞一次），冷却结束后自动恢复探测
            rerankSupported = false;
            lastFailTs = System.currentTimeMillis();
            log.warn("[Rerank] 服务调用失败，回退融合分排序（{}s 后自动重试）: {}",
                    failCooldownMs() / 1000, e.getMessage());
            return candidates;
        }
    }

    /**
     * 调试用：返回当前重排不可用的具体原因（null = 可执行）
     * 供检索调试展示"为什么没重排"，避免误以为重排已生效
     */
    public String debugUnavailableReason() {
        if (!enabled()) {
            return "未启用：系统设置的平台默认「启用重排」未打开，知识库/智能体检索设置也未开启";
        }
        // 未绑定重排模型（库/智能体覆盖、平台默认都为空）→ 路由回落本地 rerank.baseUrl。
        // 此时探测多半失败，但根因是"没配模型"而不是"服务没起"——旧文案一律指到启动本地 reranker，
        // 会把管理员引去维护一个本可不必存在的服务（云端重排明明可用）。先按未绑定给明确指引。
        if (route().providerId() == null) {
            String base = configService.get("rerank.baseUrl");
            return "未绑定重排模型：知识库/智能体检索设置与平台默认都为空，当前回落本地重排服务 "
                    + (base == null || base.isBlank() ? "（rerank.baseUrl 未配置）" : base)
                    + "；请在系统设置的检索面板登记「平台默认重排模型」，或在知识库检索设置里单独绑定";
        }
        if (!checkSupport()) {
            if (System.currentTimeMillis() - lastFailTs < failCooldownMs()) {
                return "重排服务调用失败，冷却中（" + failCooldownMs() / 1000 + "s 后自动重试）";
            }
            // 提示按实际路由区分：云端供应商引用失败时指向网关连通性/Key——旧文案写死"本地 reranker"，
            // 云端路由失败也被指到没起的本地服务，误导排障方向（本地服务明明已停用）
            ModelRegistryService.ModelRoute r = route();
            if (r.providerId() != null) {
                return "重排服务探测失败（/v1/models 无响应），请检查重排模型 " + r.displayName()
                        + " 的供应商网关 " + r.baseUrl() + " 连通性与 API Key";
            }
            return "重排服务探测失败（/v1/models 无响应），请确认本地 reranker 已启动"
                    + "（scripts/mac/start_rerank_server.sh 或 scripts/win/start_rerank_server.bat）";
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
