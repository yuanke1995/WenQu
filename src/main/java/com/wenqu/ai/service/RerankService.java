package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.config.ConfigKeys;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重排服务：调用独立 reranker 服务（OpenAI 兼容 POST /v1/rerank；由供应商网关承载，无本地脚本依赖）。
 *
 * <p>注意：Ollama 官方（含 1.x）没有 /api/rerank 端点、官方库也没有 rerank 模型，
 * 社区模型仅能经 /api/embed 近似且部分环境 embedding 被禁用，因此本仓库只支持 OpenAI 兼容协议。
 *
 * <p><b>模型归知识库</b>（2026-11-14 起平台总开关/平台默认/个人默认/rerank.baseUrl 直连全部退役）：
 * 路由按本轮收敛结果解析（见 {@link ModelRegistryService#rerankRoute}——只认库级收敛写入的
 * 线程覆盖 rerank.model），没绑模型的轮次重排直接不可用。唯一剩余开关是智能体级「强制关」
 * （rerank.enabled 显式 {@code false} 经线程覆盖下发，见 {@link #forcedOff}）。
 * <p>路由随轮收敛后多条路由并存，客户端/探测/失败冷却状态都按路由指纹隔离（{@link RouteState}）。
 * 探测失败/调用失败时静默回退为输入顺序（混合检索已按融合分排序），冷却结束自动重试。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RerankService {

    private final ConfigService configService;
    private final ModelRegistryService modelRegistryService;
    /** 按路由指纹隔离的客户端与探测状态（指纹含超时：超时变化即新状态，免重建比对） */
    private final ConcurrentHashMap<String, RouteState> states = new ConcurrentHashMap<>();

    /** 失败冷却时长：rerank.failCooldownMs 可调（DB），默认 60s */
    private long failCooldownMs() { return configService.getInt(ConfigKeys.RERANK_FAIL_COOLDOWN_MS, 60_000); }

    public RerankService(ConfigService configService, ModelRegistryService modelRegistryService) {
        this.configService = configService;
        this.modelRegistryService = modelRegistryService;
    }

    /**
     * 重排「强制关」判据：仅当 rerank.enabled 被显式覆盖为 {@code false} 时关闭
     * （智能体检索设置「强制关」/工作流参数重放/评测扫参）。
     * 键本身已随平台总开关退役、不在 defaults 登记——缺键/null/其它值一律视为跟随
     * （跟随 = 本轮收敛出了重排模型就重排）。
     */
    private boolean forcedOff() {
        String v = configService.get("rerank.enabled");
        return v != null && "false".equalsIgnoreCase(v.trim());
    }

    /** 本轮重排路由：只认库级收敛写入的 rerank.model 线程覆盖；无绑定返回 null（重排不可用） */
    private ModelRegistryService.ModelRoute route() {
        return modelRegistryService.rerankRoute();
    }

    private int timeoutMillis() {
        int t = configService.getInt(ConfigKeys.RERANK_TIMEOUT_MILLIS);
        return t > 0 ? t : 5000;
    }

    /** 单条路由的客户端与探测状态（多库各绑模型时并存，互不串冷却/互不串客户端） */
    private static final class RouteState {
        final RestClient client;
        final String rerankPath;
        final String maskedKey;
        volatile boolean checked = false;   // 是否已探测过（成功后长期复用；失败受冷却约束）
        volatile boolean supported = false;
        volatile long lastFailTs = 0L;

        RouteState(String baseUrl, String rerankPath, String apiKey, int timeout) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(timeout);
            this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
            this.rerankPath = rerankPath;
            this.maskedKey = maskSecret(apiKey);
        }

        void markFail() {
            supported = false;
            checked = true;
            lastFailTs = System.currentTimeMillis();
        }
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return "未配置";
        if (secret.length() <= 4) return "****";
        return "****" + secret.substring(secret.length() - 4);
    }

    /** 取（或建）路由对应的客户端状态：指纹 = 网关地址|重排路径|Key|超时，任一变化即新状态 */
    private RouteState stateFor(ModelRegistryService.ModelRoute r) {
        // 版本段尾缀（…/v1、…/v4）自动移入重排路径；无版本段时回落 /v1/rerank
        String[] np = DynamicOpenAiChatModel.normalize(r.baseUrl(), "", "/v1/rerank", "/rerank");
        String fp = np[0] + "|" + np[1] + "|" + (r.apiKey() == null ? "" : r.apiKey()) + "|" + timeoutMillis();
        return states.computeIfAbsent(fp, k -> {
            RouteState st = new RouteState(np[0], np[1], r.apiKey(), timeoutMillis());
            log.info("[Rerank] 客户端就绪: {}，模型 {}，超时 {}ms", np[0], r.displayName(), timeoutMillis());
            return st;
        });
    }

    /** 供应商 Key 非空时附带 Bearer（云端 rerank 网关需要；无 Key 时不带 Authorization） */
    private <T extends RestClient.RequestHeadersSpec<?>> T auth(T spec, ModelRegistryService.ModelRoute r) {
        if (r.apiKey() != null && !r.apiKey().isBlank()) {
            spec.header("Authorization", "Bearer " + r.apiKey());
        }
        return spec;
    }

    /**
     * 重排候选（传入按融合分排序的候选，返回重排后的顺序）；强制关/未绑定/不支持/失败时原样返回
     */
    public List<HybridRetrievalService.Hit> rank(List<HybridRetrievalService.Hit> candidates, String query) {
        ModelRegistryService.ModelRoute r = route();
        if (forcedOff() || r == null || candidates == null || candidates.size() < 2) return candidates;
        // 防御：query 为空/null 时服务端 400（"query and documents required"）——直接跳过重排回退融合分排序
        if (query == null || query.isBlank()) return candidates;
        if (!checkSupport(stateFor(r))) return candidates;
        return rankWithRoute(candidates, query, r, false);
    }

    /**
     * 小批量强制重排：绕过 minHits/maxHits 窗口，候选多少都整批送 cross-encoder。
     * 消费方：子代理命中并入主链路前的补重排（≤6 块，低于 minHits 窗口，rank() 会整批跳过）——
     * 小批量不补重排就会带着融合分混进主链路，从分域门的低门（minFusionScore）绕过重排门（minContextScore）。
     * 可用性判断/超时/失败回退行为与 rank() 一致（失败回退融合分序并写该路由的冷却）。
     */
    public List<HybridRetrievalService.Hit> rankForced(List<HybridRetrievalService.Hit> candidates, String query) {
        ModelRegistryService.ModelRoute r = route();
        if (forcedOff() || r == null || candidates == null || candidates.isEmpty()) return candidates;
        if (query == null || query.isBlank()) return candidates;
        if (!checkSupport(stateFor(r))) return candidates;
        return rankWithRoute(candidates, query, r, true);
    }

    /**
     * 显式模型引用的强制重排：供跑在检索工具线程之外的消费方复用轮级收敛结果
     * （咨询来源并入、MCP 来源评分——那时 rerank.model 的线程覆盖已随检索调用结束而撤除）。
     * 强制关/引用解析不出路由时原样返回（与未绑定同口径）。
     */
    public List<HybridRetrievalService.Hit> rankForced(List<HybridRetrievalService.Hit> candidates, String query,
                                                       String modelRef) {
        if (forcedOff() || modelRef == null || modelRef.isBlank()) return candidates;
        ModelRegistryService.ModelRoute r = modelRegistryService.resolveReference(modelRef.trim());
        if (r == null) return candidates;
        if (candidates == null || candidates.isEmpty()) return candidates;
        if (query == null || query.isBlank()) return candidates;
        if (!checkSupport(stateFor(r))) return candidates;
        return rankWithRoute(candidates, query, r, true);
    }

    private List<HybridRetrievalService.Hit> rankWithRoute(List<HybridRetrievalService.Hit> candidates, String query,
                                                           ModelRegistryService.ModelRoute r,
                                                           boolean bypassWindow) {
        RouteState st = stateFor(r);
        try {
            // 重排窗口（rerank.minHits/maxHits）：库级参数随模型同库收敛成线程覆盖
            // （见 KbRetrievalParamService.withRerankModel），未覆盖时走内置默认。
            // 窗口存在的根因：主检索 118 块全量发给重排服务，CPU 推理约 10s 撞客户端读超时，
            // 连接断裂后 keep-alive 复用半开连接（"重排服务老是挂"的根因）
            int minHits = Math.max(2, configService.getInt("rerank.minHits", ConfigDefaults.RERANK_MIN_HITS));
            int maxHits = Math.max(minHits, configService.getInt("rerank.maxHits", ConfigDefaults.RERANK_MAX_HITS));
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
            List<Double> scores = rankByOpenAiRerank(st, r, query, docs);
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
            // 失败记录（按路由隔离）：冷却期内不重试（避免每个请求都撞一次），冷却结束后自动恢复探测
            st.markFail();
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
        if (forcedOff()) {
            return "重排已强制关闭：智能体检索设置选了「强制关」";
        }
        // 平台兜底已退役：没绑模型的轮次重排直接不可用，给出明确的绑定指引
        ModelRegistryService.ModelRoute r = route();
        if (r == null) {
            return "未绑定重排模型：重排模型归知识库，选了才重排——"
                    + "请在命中的知识库检索设置里绑定重排模型";
        }
        RouteState st = stateFor(r);
        if (!checkSupport(st)) {
            if (System.currentTimeMillis() - st.lastFailTs < failCooldownMs()) {
                return "重排服务调用失败，冷却中（" + failCooldownMs() / 1000 + "s 后自动重试）";
            }
            return "重排服务探测失败（/v1/models 无响应），请检查重排模型 " + r.displayName()
                    + " 的供应商网关 " + r.baseUrl() + " 连通性与 API Key";
        }
        return null;
    }

    /** OpenAI 兼容 /v1/rerank：真交叉编码重排，直接返回 relevance_score */
    private List<Double> rankByOpenAiRerank(RouteState st, ModelRegistryService.ModelRoute r,
                                            String query, List<String> docs) throws Exception {
        if (docs == null || docs.isEmpty()) return null;
        Map<String, Object> body = new HashMap<>();
        body.put("model", r.modelId());
        // 防御：query 为空时服务端 400（"query and documents required"），空串可发但服务端仍判空——调用方保证非空
        body.put("query", query == null ? "" : query);
        body.put("documents", docs);
        body.put("top_n", docs.size());

        String resp = auth(st.client.post()
                .uri(st.rerankPath)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON), r)
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
     * 服务可用性探测（每条路由独立缓存；失败进入冷却，冷却结束自动重新探测）：
     * GET /v1/models 确认 OpenAI 兼容服务在线
     */
    private boolean checkSupport(RouteState st) {
        // 已探测过：成功直接返回；失败且冷却未过 → 仍不可用；冷却已过 → 重新探测（瞬时故障自动恢复）
        if (st.checked) {
            if (st.supported) return true;
            if (System.currentTimeMillis() - st.lastFailTs < failCooldownMs()) return false;
            st.checked = false;  // 冷却结束，允许重新探测
        }
        synchronized (st) {
            if (st.checked) return st.supported;
            try {
                String modelsPath = st.rerankPath.replaceFirst("/rerank$", "/models");
                String resp = st.client.get().uri(modelsPath).retrieve().body(String.class);
                st.supported = resp != null && resp.contains("data");
                if (!st.supported) {
                    st.lastFailTs = System.currentTimeMillis();
                    log.warn("[Rerank] /v1/models 无有效响应，重排不可用（回退融合分排序）");
                }
            } catch (Exception e) {
                st.supported = false;
                st.lastFailTs = System.currentTimeMillis();
                log.warn("[Rerank] 服务探测失败（回退融合分排序）key={}: {}", st.maskedKey, e.getMessage());
            }
            st.checked = true;
            return st.supported;
        }
    }
}
