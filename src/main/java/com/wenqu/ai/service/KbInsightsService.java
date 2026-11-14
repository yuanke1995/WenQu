package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.agentx.WenquAgentxTools;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KbFeedbackMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.QaLogMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.KbFeedback;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.QaLog;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 知识库洞察：概览聚合、30 天趋势、未命中问题、绑定智能体、试检索、引用反馈与归档。
 * <p>
 * 所有方法都以「当前登录用户可读」为边界：官方内置库仅管理员级可见（与知识库管理页同口径），
 * 其余按资源可见性判定；检索类方法复用生产检索链路（含文档级 ACL 过滤），不另开越权通道。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbInsightsService {

    private final KnowledgeBaseService kbService;
    private final DocumentService documentService;
    private final DocumentMetaCache documentMetaCache;
    private final GraphRagService graphRagService;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final KbRetrievalParamService kbRetrievalParams;
    private final ConfigService configService;
    private final UserConfigService userConfigService;
    private final ResourceVisibilityService visibility;
    private final RoleService roleService;
    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final AiDocumentMapper documentMapper;
    private final QaLogMapper qaLogMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final KbFeedbackMapper feedbackMapper;

    // ==================== 概览 ====================

    /**
     * 知识库列表 + 体检数据：文档状态分布、知识块、90 天命中、最近使用、绑定智能体数、图谱规模。
     * 一次 healthReport + 一次问答日志扫描，避免前端逐卡请求。
     */
    public Map<String, Object> overview() {
        List<Map<String, Object>> kbs = kbService.listWithCounts(true).stream()
                .filter(this::readableRow)
                .collect(Collectors.toList());

        Map<String, Map<String, Object>> healthByKb = healthByKb();
        Map<String, Long> recycleByKb = documentService.recycleCountsByKb();
        Map<String, String> docKb = documentService.docKbMap();
        Map<String, DocumentService.HitStat> hits = documentService.hitStatsByDoc();
        Map<String, Long> hitByKb = new HashMap<>();
        Map<String, LocalDateTime> lastByKb = new HashMap<>();
        hits.forEach((docId, st) -> {
            String kbId = docKb.get(docId);
            if (kbId == null || kbId.isBlank()) return;
            hitByKb.merge(kbId, st.count(), Long::sum);
            if (st.lastAt() != null) lastByKb.merge(kbId, st.lastAt(), (a, b) -> a.isAfter(b) ? a : b);
        });
        Map<String, Integer> agentRefs = agentRefsByKb(kbs);

        long totalDocs = 0, totalChunks = 0, totalHits = 0, needAttention = 0;
        for (Map<String, Object> m : kbs) {
            String id = String.valueOf(m.get("id"));
            Map<String, Object> h = healthByKb.getOrDefault(id, Map.of());
            long active = lng(h.get("docActive"));
            long parsing = lng(h.get("docParsing"));
            long failed = lng(h.get("docFailed"));
            long deprecated = lng(h.get("docDeprecated"));
            long chunkTotal = lng(h.get("chunkTotal"));
            long recycled = recycleByKb.getOrDefault(id, 0L);
            long hit90d = hitByKb.getOrDefault(id, 0L);
            boolean archived = Integer.valueOf(1).equals(m.get("archived"));
            boolean noEmbedding = m.get("embeddingRef") == null || String.valueOf(m.get("embeddingRef")).isBlank();

            m.put("docTotal", lng(h.get("docTotal")));
            m.put("docActive", active);
            m.put("docParsing", parsing);
            m.put("docFailed", failed);
            m.put("docDeprecated", deprecated);
            m.put("chunkTotal", chunkTotal);
            m.put("recycleCount", recycled);
            m.put("hit90d", hit90d);
            m.put("lastUsedAt", lastByKb.get(id));
            m.put("agentRefCount", agentRefs.getOrDefault(id, 0));
            m.put("noEmbedding", noEmbedding);
            m.put("needAttention", !archived && (failed > 0 || parsing > 0 || noEmbedding));
            Map<String, Object> graph = new LinkedHashMap<>();
            if (Integer.valueOf(1).equals(m.get("graphEnabled"))) {
                try {
                    Map<String, Object> st = graphRagService.status(id);
                    graph.put("entities", lng(st.get("entities")));
                    graph.put("triples", lng(st.get("triples")));
                    graph.put("building", Boolean.TRUE.equals(st.get("building")));
                } catch (Exception e) {
                    graph.put("entities", 0L);
                    graph.put("triples", 0L);
                    graph.put("building", false);
                }
            } else {
                graph.put("entities", 0L);
                graph.put("triples", 0L);
                graph.put("building", false);
            }
            m.put("graph", graph);

            totalDocs += lng(h.get("docTotal"));
            totalChunks += chunkTotal;
            totalHits += hit90d;
            if (Boolean.TRUE.equals(m.get("needAttention"))) needAttention++;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("kbCount", kbs.size());
        stats.put("docCount", totalDocs);
        stats.put("chunkCount", totalChunks);
        stats.put("hit90d", totalHits);
        stats.put("attentionCount", needAttention);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", stats);
        out.put("knowledgeBases", kbs);
        return out;
    }

    // ==================== 库洞察 ====================

    /** 单库洞察：健康分布、近 N 天趋势、Top 命中文档、未命中问题、最近活动 */
    public Map<String, Object> insights(String kbId, int days) {
        KnowledgeBase kb = requireReadable(kbId);
        int d = Math.max(1, Math.min(days, 90));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(d);

        Map<String, Object> health = healthByKb().getOrDefault(kbId, Map.of());
        List<AiDocument> docs = docsOfKb(kb);
        Set<String> docIds = docs.stream().map(AiDocument::getId).filter(x -> x != null)
                .collect(Collectors.toSet());
        Map<String, String> docNames = docs.stream().filter(x -> x.getId() != null)
                .collect(Collectors.toMap(AiDocument::getId, x -> nvl(x.getFileName()), (a, b) -> a));

        List<QaLog> logs = qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .eq(QaLog::getRetrievalAttempted, 1)
                .ge(QaLog::getCreatedAt, cutoff)
                .select(QaLog::getQuestion, QaLog::getHitDocIds, QaLog::getKbIds, QaLog::getCreatedAt)
                .last("LIMIT 20000"));

        Map<String, long[]> trend = new LinkedHashMap<>();
        for (int i = d - 1; i >= 0; i--) {
            trend.put(LocalDate.now().minusDays(i).toString(), new long[2]);
        }
        Map<String, long[]> docHits = new HashMap<>();
        Map<String, Map<String, Object>> unmatched = new LinkedHashMap<>();
        for (QaLog log : logs) {
            if (log.getCreatedAt() == null) continue;
            boolean any = false;
            if (log.getHitDocIds() != null && !log.getHitDocIds().isBlank()) {
                for (String raw : log.getHitDocIds().split(",")) {
                    String docId = raw.trim();
                    if (!docId.isEmpty() && docIds.contains(docId)) {
                        any = true;
                        docHits.computeIfAbsent(docId, k -> new long[1])[0]++;
                    }
                }
            }
            // 库归属：本轮检索范围明确包含本库，才算本库的一次问答（命中/未命中都算）。
            // 存量日志没有库范围，只能靠命中反推——零命中无法归属任何库，不能算成本库的未命中，
            // 否则新建库会凭空继承其他库的历史未命中问题。
            if (!kbScopeContains(log.getKbIds(), kbId) && !any) continue;
            long[] arr = trend.get(log.getCreatedAt().toLocalDate().toString());
            if (arr != null) {
                if (any) arr[0]++; else arr[1]++;
            }
            if (!any) {
                String q = log.getQuestion() == null ? "" : log.getQuestion().trim();
                if (q.isEmpty()) continue;
                Map<String, Object> u = unmatched.computeIfAbsent(q, k -> {
                    Map<String, Object> init = new LinkedHashMap<>();
                    init.put("question", k);
                    init.put("count", 0L);
                    init.put("lastAt", log.getCreatedAt());
                    init.put("reason", log.getHitDocIds() == null || log.getHitDocIds().isBlank() ? "no_result" : "low");
                    return init;
                });
                u.put("count", lng(u.get("count")) + 1);
                LocalDateTime last = (LocalDateTime) u.get("lastAt");
                if (log.getCreatedAt().isAfter(last)) u.put("lastAt", log.getCreatedAt());
            }
        }

        // 用户「引用没用」反馈并入未命中（对话侧反馈可能没有 question，用占位文案）
        List<KbFeedback> bad = feedbackMapper.selectList(new LambdaQueryWrapper<KbFeedback>()
                .eq(KbFeedback::getKbId, kbId)
                .eq(KbFeedback::getUseful, 0)
                .ge(KbFeedback::getCreateTime, cutoff)
                .last("LIMIT 500"));
        for (KbFeedback f : bad) {
            String q = f.getQuestion() == null || f.getQuestion().isBlank() ? "（对话引用未记录问题）" : f.getQuestion().trim();
            Map<String, Object> u = unmatched.computeIfAbsent(q, k -> {
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("question", k);
                init.put("count", 0L);
                init.put("lastAt", f.getCreateTime());
                init.put("reason", f.getKnowledgeId() == null || f.getKnowledgeId().isBlank() ? "no_result" : "feedback");
                return init;
            });
            u.put("count", lng(u.get("count")) + 1);
            LocalDateTime last = (LocalDateTime) u.get("lastAt");
            if (f.getCreateTime() != null && (last == null || f.getCreateTime().isAfter(last))) {
                u.put("lastAt", f.getCreateTime());
            }
        }

        long hitTotal = trend.values().stream().mapToLong(a -> a[0]).sum();
        long missTotal = trend.values().stream().mapToLong(a -> a[1]).sum();
        long total = hitTotal + missTotal;
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("questions", total);
        coverage.put("withHit", hitTotal);
        coverage.put("rate", total == 0 ? 0 : Math.round(hitTotal * 1000.0 / total) / 10.0);

        List<Map<String, Object>> trendRows = new ArrayList<>();
        trend.forEach((day, arr) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day);
            row.put("hit", arr[0]);
            row.put("miss", arr[1]);
            trendRows.add(row);
        });

        List<Map<String, Object>> topDocs = new ArrayList<>();
        docHits.forEach((docId, c) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", docId);
            row.put("docName", docNames.getOrDefault(docId, docId));
            row.put("count", c[0]);
            topDocs.add(row);
        });
        topDocs.sort((a, b) -> Long.compare(lng(b.get("count")), lng(a.get("count"))));
        List<Map<String, Object>> topDocsLimited = topDocs.size() > 5
                ? new ArrayList<>(topDocs.subList(0, 5)) : topDocs;

        List<Map<String, Object>> unmatchedRows = new ArrayList<>(unmatched.values());
        unmatchedRows.sort(Comparator
                .comparingLong((Map<String, Object> x) -> lng(x.get("count"))).reversed()
                .thenComparing(x -> (LocalDateTime) x.get("lastAt"), Comparator.nullsLast(Comparator.reverseOrder())));
        if (unmatchedRows.size() > 20) unmatchedRows = new ArrayList<>(unmatchedRows.subList(0, 20));

        List<AiDocument> recentDocs = new ArrayList<>(docs);
        recentDocs.sort(Comparator.comparing(AiDocument::getUpdateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(6, recentDocs.size()); i++) {
            AiDocument doc = recentDocs.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "doc");
            row.put("docId", doc.getId());
            row.put("name", doc.getFileName());
            row.put("status", doc.getStatus());
            row.put("time", doc.getUpdateTime());
            recent.add(row);
        }
        try {
            Map<String, Object> gs = graphRagService.status(kbId);
            if (Boolean.TRUE.equals(gs.get("building"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "graph");
                row.put("name", "知识图谱构建中 " + lng(gs.get("done")) + "/" + lng(gs.get("total")));
                row.put("status", 2);
                row.put("time", LocalDateTime.now());
                recent.add(0, row);
            }
        } catch (Exception ignored) {
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kb", brief(kb));
        out.put("days", d);
        out.put("health", health);
        out.put("coverage", coverage);
        out.put("trend", trendRows);
        out.put("topDocs", topDocsLimited);
        out.put("unmatched", unmatchedRows);
        out.put("recent", recent);
        return out;
    }

    /** 未命中问题（洞察的轻量子集，供抽屉 Tab / 一键加入评测） */
    public List<Map<String, Object>> unmatched(String kbId, int days) {
        Object v = insights(kbId, days).get("unmatched");
        if (v instanceof List<?> list) {
            return list.stream().filter(x -> x instanceof Map<?, ?>)
                    .map(x -> {
                        Map<?, ?> src = (Map<?, ?>) x;
                        Map<String, Object> row = new LinkedHashMap<>();
                        src.forEach((k, val) -> row.put(String.valueOf(k), val));
                        return row;
                    }).toList();
        }
        return List.of();
    }

    // ==================== 绑定智能体 ====================

    /** 读当前用户可见、且会用这个库的智能体（删除影响面/卡片引用数） */
    public List<Map<String, Object>> agents(String kbId) {
        KnowledgeBase kb = requireReadable(kbId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agent a : agentService.list()) {
            String kind = bindingKind(a, kb);
            if (kind == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("name", a.getName());
            row.put("icon", a.getIcon());
            row.put("description", a.getDescription());
            row.put("isBuiltin", a.getIsBuiltin());
            row.put("bindingKind", kind);
            out.add(row);
        }
        return out;
    }

    // ==================== 试检索 ====================

    /**
     * 试检索：只走检索/重排链路，不调用聊天模型，不产生回答费用。
     * 返回 topK 命中与分数、命中判定、耗时、召回/重排规模、降级告警。
     */
    public Map<String, Object> probe(String kbId, Map<String, Object> body) {
        requireReadable(kbId);
        String query = body.get("query") == null ? "" : String.valueOf(body.get("query")).trim();
        if (query.isEmpty()) throw new BizException("请输入检索问题");
        if (query.length() > 2000) throw new BizException("问题过长（上限 2000 字）");
        int topK = clampInt(body.get("topK"), 8, 1, 20);
        boolean wantRerank = body.get("rerank") == null
                || !("false".equalsIgnoreCase(String.valueOf(body.get("rerank")))
                || Integer.valueOf(0).equals(body.get("rerank")));

        configService.putUserOverrides(userConfigService.overrides(RequestUser.uid()));
        try {
            long t0 = System.currentTimeMillis();
            HybridRetrievalService.RetrievalDiag diag = new HybridRetrievalService.RetrievalDiag();
            List<HybridRetrievalService.Hit> merged =
                    hybridRetrievalService.search(query, diag, List.of(kbId), false);
            long recallMs = System.currentTimeMillis() - t0;

            // 两路分量（仅试检索展示用）：fusion 内部不保留分量，这里按同一 query 再取一次。
            // 向量分必须按「知识块 id」汇总（QA/父子附加索引的 vector id 不是块 id，直接对 id 会全部落空）。
            Map<String, Double> vectorScores = new HashMap<>();
            try {
                vectorScores.putAll(hybridRetrievalService.vectorScoreByKnowledge(query, List.of(kbId)));
            } catch (Exception e) {
                log.debug("[KB-PROBE] 取向量分量失败（不影响主结果）: {}", e.getMessage());
            }
            Map<String, Double> keywordScores = new HashMap<>();
            try {
                for (Knowledge k : hybridRetrievalService.keywordSearch(query)) {
                    if (k.getId() != null) keywordScores.put(String.valueOf(k.getId()), k.getKwScore());
                }
            } catch (Exception e) {
                log.debug("[KB-PROBE] 取关键词分量失败（不影响主结果）: {}", e.getMessage());
            }

            List<HybridRetrievalService.Hit> reranked = merged;
            boolean rerankApplied = false;
            String rerankSkip = null;
            if (wantRerank) {
                if (merged.size() < 2) {
                    rerankSkip = "命中过少（<2），无需重排";
                } else {
                    AtomicReference<String> skipRef = new AtomicReference<>();
                    try {
                        reranked = kbRetrievalParams.withRerankModel(List.of(kbId), Set.of(kbId), () -> {
                            List<HybridRetrievalService.Hit> r = rerankService.rank(merged, query);
                            if (r.stream().anyMatch(h -> h.rerankScore() != null)) return r;
                            // 未生效时取精确原因（须在覆盖作用域内取：此时路由=本库绑定的模型）
                            String un = rerankService.debugUnavailableReason();
                            skipRef.set(un != null ? un : "重排未生效（候选不足触发下限或服务不可用）");
                            return r;
                        });
                        rerankApplied = reranked.stream().anyMatch(h -> h.rerankScore() != null);
                        rerankSkip = skipRef.get();
                    } catch (Exception e) {
                        rerankSkip = "重排失败：" + e.getMessage();
                    }
                }
            }

            double minRerank = configService.getDouble("retrieval.minContextScore",
                    ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE);
            double minFusion = configService.getDouble("retrieval.minFusionScore",
                    ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE);
            List<Map<String, Object>> hits = new ArrayList<>();
            int dropped = 0;
            for (HybridRetrievalService.Hit h : reranked) {
                boolean hasRerank = h.rerankScore() != null;
                if (rerankApplied && !hasRerank) {
                    dropped++;
                    continue;
                }
                double rankScore = hasRerank ? h.rerankScore() : h.score();
                double gate = hasRerank ? minRerank : minFusion;
                if (gate > 0 && rankScore < gate) {
                    dropped++;
                    continue;
                }
                if (hits.size() >= topK) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("knowledgeId", h.knowledgeId());
                row.put("docId", h.docId());
                row.put("docName", documentMetaCache.getFileName(h.docId()));
                row.put("title", h.title());
                row.put("titlePath", h.titlePath());
                row.put("chunkIndex", h.chunkIndex());
                row.put("score", round3(h.score()));
                row.put("rerankScore", h.rerankScore() == null ? null : round3(h.rerankScore()));
                row.put("vectorScore", vectorScores.get(h.knowledgeId()) == null
                        ? null : round3(vectorScores.get(h.knowledgeId())));
                row.put("keywordScore", keywordScores.get(h.knowledgeId()) == null
                        ? null : round3(keywordScores.get(h.knowledgeId())));
                row.put("snippet", snippet(h.content(), 240));
                hits.add(row);
            }

            KbRetrievalParamService.Params p = kbRetrievalParams.forScope(List.of(kbId))
                    .getOrDefault(kbId, kbRetrievalParams.defaults());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("vecThreshold", p.vecThreshold());
            params.put("vectorWeight", p.vectorWeight());
            params.put("keywordWeight", p.keywordWeight());
            params.put("rerankModel", p.rerankModel());
            params.put("rerankMinHits", p.rerankMinHits());
            params.put("rerankMaxHits", p.rerankMaxHits());
            KnowledgeBase kb = kbService.get(kbId);
            params.put("configured", kb != null && kb.getQueryParams() != null && !kb.getQueryParams().isBlank());

            List<String> warnings = new ArrayList<>();
            if (diag.isVectorPartialFailed()) warnings.add("向量检索部分失败，结果可能不完整");
            if (diag.isKeywordFailed()) warnings.add("关键词检索失败，仅返回向量结果");
            if (diag.isKeywordFallback()) warnings.add("关键词引擎降级为数据库匹配");
            if (diag.isKeywordBusy()) warnings.add("关键词检索繁忙，本轮跳过关键词路");
            if (diag.aclPushdownError() != null) warnings.add("库权限下推失败，已退回事后过滤");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("verdict", hits.isEmpty() ? "none" : "hit");
            out.put("hits", hits);
            out.put("recallCount", merged.size());
            out.put("rerankedCount", reranked.size());
            out.put("droppedByGate", dropped);
            out.put("topK", topK);
            out.put("latencyMs", System.currentTimeMillis() - t0);
            out.put("recallMs", recallMs);
            out.put("rerankApplied", rerankApplied);
            out.put("rerankSkipReason", rerankSkip);
            out.put("thresholds", Map.of("minRerank", minRerank, "minFusion", minFusion));
            out.put("params", params);
            out.put("warnings", warnings);
            return out;
        } finally {
            configService.clearUserOverrides();
        }
    }

    // ==================== 归档 ====================

    /** 归档/恢复：归档时从全部智能体的知识库绑定中摘除该库（保留文档与向量，可恢复） */
    public Map<String, Object> archive(String kbId, boolean archived) {
        KnowledgeBase kb = kbService.get(kbId);
        if (kb == null || Integer.valueOf(1).equals(kb.getDeleted())) throw new BizException("知识库不存在");
        if (Integer.valueOf(1).equals(kb.getIsDefault())) throw new BizException("默认知识库不可归档");
        if (Integer.valueOf(1).equals(kb.getBuiltin())) throw new BizException("官方内置知识库不可归档");
        kbService.setArchived(kbId, archived);
        int detached = archived ? detachFromAgents(kbId) : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", kbId);
        out.put("archived", archived ? 1 : 0);
        out.put("detachedAgents", detached);
        return out;
    }

    // ==================== 引用反馈 ====================

    /** 记录一条引用反馈；kbId 由知识块反查，避免前端伪造归属 */
    public Map<String, Object> feedback(String kbId, Map<String, Object> body) {
        requireReadable(kbId);
        String knowledgeId = body.get("knowledgeId") == null ? "" : String.valueOf(body.get("knowledgeId"));
        String docId = body.get("docId") == null ? "" : String.valueOf(body.get("docId"));
        boolean useful = !("false".equalsIgnoreCase(String.valueOf(body.get("useful")))
                || Integer.valueOf(0).equals(body.get("useful")));
        if (!knowledgeId.isBlank()) {
            Knowledge k = knowledgeMapper.selectById(knowledgeId);
            if (k == null) throw new BizException("知识块不存在");
            if (docId.isBlank() && k.getDocId() != null) docId = String.valueOf(k.getDocId());
        }
        String question = body.get("question") == null ? null : String.valueOf(body.get("question")).trim();
        if (question != null && question.length() > 500) question = question.substring(0, 500);
        KbFeedback f = new KbFeedback();
        f.setKbId(kbId);
        f.setKnowledgeId(knowledgeId.isBlank() ? null : knowledgeId);
        f.setDocId(docId.isBlank() ? null : docId);
        f.setQuestion(question == null || question.isEmpty() ? null : question);
        f.setUseful(useful ? 1 : 0);
        f.setUid(RequestUser.uid());
        f.setCreateTime(LocalDateTime.now());
        feedbackMapper.insert(f);
        return Map.of("id", f.getId(), "useful", useful ? 1 : 0);
    }

    /**
     * 对话引用反馈：前端只带 knowledgeId/docId，kbId 由文档归属反查。
     * 这样 SourceList 不必为了反馈而额外知道库 ID，也避免前端伪造归属。
     */
    public Map<String, Object> feedbackByChunk(Map<String, Object> body) {
        String knowledgeId = body.get("knowledgeId") == null ? "" : String.valueOf(body.get("knowledgeId"));
        String docId = body.get("docId") == null ? "" : String.valueOf(body.get("docId"));
        if (knowledgeId.isBlank() && docId.isBlank()) throw new BizException("缺少引用信息");
        if (docId.isBlank() && !knowledgeId.isBlank()) {
            Knowledge k = knowledgeMapper.selectById(knowledgeId);
            if (k == null) throw new BizException("知识块不存在");
            if (k.getDocId() != null) docId = String.valueOf(k.getDocId());
        }
        String kbId = null;
        if (!docId.isBlank()) {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc != null && doc.getKbId() != null && !doc.getKbId().isBlank()) kbId = doc.getKbId();
        }
        if (kbId == null || kbId.isBlank()) kbId = kbService.defaultId(RequestUser.uid());
        if (kbId == null || kbId.isBlank()) throw new BizException("无法定位引用所属知识库");
        return feedback(kbId, body);
    }

    /**
     * 记录一次「试检索无命中」：只存问题，不绑定知识块。
     * 与引用差评同表（useful=0），进未命中列表供评测沉淀。
     */
    public Map<String, Object> recordMiss(String kbId, Map<String, Object> body) {
        requireReadable(kbId);
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question")).trim();
        if (question.isEmpty()) throw new BizException("缺少问题");
        if (question.length() > 500) question = question.substring(0, 500);
        KbFeedback f = new KbFeedback();
        f.setKbId(kbId);
        f.setQuestion(question);
        f.setUseful(0);
        f.setUid(RequestUser.uid());
        f.setCreateTime(LocalDateTime.now());
        feedbackMapper.insert(f);
        return Map.of("id", f.getId());
    }

    // ==================== 内部工具 ====================

    private ResourceVisibilityService.Principal principal() {
        return new ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    private boolean admin() {
        return roleService.isAdminAny(RequestUser.roles());
    }

    private boolean readableRow(Map<String, Object> row) {
        return visibility.canReadKbManagement(principal(), (Integer) row.get("builtin"),
                (String) row.get("createdBy"), admin());
    }

    private KnowledgeBase requireReadable(String kbId) {
        KnowledgeBase kb = kbService.get(kbId);
        if (kb == null || Integer.valueOf(1).equals(kb.getDeleted())) throw new BizException("知识库不存在");
        if (!visibility.canReadKbManagement(principal(), kb.getBuiltin(), kb.getCreatedBy(), admin())) {
            throw new BizException("知识库不存在");
        }
        return kb;
    }

    private Map<String, Map<String, Object>> healthByKb() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        Object kbs = documentService.healthReport().get("kbs");
        if (kbs instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String id = m.get("kbId") == null ? null : String.valueOf(m.get("kbId"));
                if (id == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                out.put(id, row);
            }
        }
        return out;
    }

    private List<AiDocument> docsOfKb(KnowledgeBase kb) {
        LambdaQueryWrapper<AiDocument> w = new LambdaQueryWrapper<AiDocument>().eq(AiDocument::getDeleted, 0);
        if (Integer.valueOf(1).equals(kb.getIsDefault())) {
            w.and(q -> q.eq(AiDocument::getKbId, kb.getId())
                    .or().isNull(AiDocument::getKbId)
                    .or().eq(AiDocument::getKbId, ""));
        } else {
            w.eq(AiDocument::getKbId, kb.getId());
        }
        return documentMapper.selectList(w.orderByDesc(AiDocument::getUpdateTime));
    }

    private Map<String, Integer> agentRefsByKb(List<Map<String, Object>> kbs) {
        Map<String, Integer> out = new HashMap<>();
        Set<String> builtinScoped = kbs.stream()
                .filter(m -> Integer.valueOf(1).equals(m.get("builtin"))
                        || Integer.valueOf(1).equals(m.get("isDefault")))
                .map(m -> String.valueOf(m.get("id")))
                .collect(Collectors.toSet());
        try {
            for (Agent a : agentMapper.selectList(new LambdaQueryWrapper<Agent>())) {
                if (WenquAgentxTools.blocksKnowledge(a)) continue;
                if (Integer.valueOf(1).equals(a.getIsBuiltin())) {
                    for (String kbId : builtinScoped) out.merge(kbId, 1, Integer::sum);
                    continue;
                }
                String csv = a.getKnowledgeBaseIds();
                if (csv == null || csv.isBlank()) continue;
                for (String id : csv.split(",")) {
                    String kbId = id.trim();
                    if (!kbId.isEmpty()) out.merge(kbId, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            log.warn("统计知识库绑定智能体失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 该智能体会不会用这个库：{@code "bound"}=显式绑定，{@code "default"}=内置「问渠」的动态范围，
     * {@code null}=不用。与运行时检索范围（{@code RetrievalScopeService}）同口径——
     * 内置「问渠」恒检索「使用者的默认库＋官方手册库」，按使用者动态解析、不落在
     * {@code knowledge_base_ids} 上，只认 CSV 会把它从每个库的影响面里漏掉。
     * 默认库每人一个，所以任何人的默认库都在这个范围内。
     * <p>静态纯判据（同 {@code WenquAgentxTools.blocksKnowledge}）：让这条口径可被单测钉住。
     */
    static String bindingKind(Agent a, KnowledgeBase kb) {
        if (WenquAgentxTools.blocksKnowledge(a)) return null;
        if (Integer.valueOf(1).equals(a.getIsBuiltin())) {
            return Integer.valueOf(1).equals(kb.getBuiltin())
                    || Integer.valueOf(1).equals(kb.getIsDefault()) ? "default" : null;
        }
        return binds(a, kb.getId()) ? "bound" : null;
    }

    private static boolean binds(Agent a, String kbId) {
        String csv = a.getKnowledgeBaseIds();
        if (csv == null || csv.isBlank()) return false;
        for (String id : csv.split(",")) {
            if (kbId.equals(id.trim())) return true;
        }
        return false;
    }

    private int detachFromAgents(String kbId) {
        int changed = 0;
        try {
            for (Agent a : agentMapper.selectList(new LambdaQueryWrapper<Agent>())) {
                String csv = a.getKnowledgeBaseIds();
                if (csv == null || csv.isBlank()) continue;
                List<String> kept = new ArrayList<>();
                boolean hit = false;
                for (String id : csv.split(",")) {
                    String t = id.trim();
                    if (t.isEmpty()) continue;
                    if (t.equals(kbId)) hit = true; else kept.add(t);
                }
                if (!hit) continue;
                a.setKnowledgeBaseIds(String.join(",", new LinkedHashSet<>(kept)));
                agentMapper.updateById(a);
                changed++;
            }
        } catch (Exception e) {
            log.warn("归档时摘除智能体绑定失败: kbId={} {}", kbId, e.getMessage());
        }
        return changed;
    }

    private Map<String, Object> brief(KnowledgeBase kb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kb.getId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("icon", kb.getIcon());
        m.put("isDefault", kb.getIsDefault());
        m.put("builtin", kb.getBuiltin());
        m.put("archived", kb.getArchived() == null ? 0 : kb.getArchived());
        m.put("embeddingRef", kb.getEmbeddingRef());
        m.put("graphEnabled", kb.getGraphEnabled());
        m.put("updateTime", kb.getUpdateTime());
        return m;
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int clampInt(Object v, int def, int min, int max) {
        int n = def;
        if (v instanceof Number num) n = num.intValue();
        else if (v != null) {
            try { n = Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) { }
        }
        return Math.max(min, Math.min(n, max));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 问答日志里的库范围（逗号分隔）是否包含指定库；空范围=存量/未限定，返回 false。 */
    private static boolean kbScopeContains(String kbIds, String kbId) {
        if (kbIds == null || kbIds.isBlank() || kbId == null || kbId.isBlank()) return false;
        for (String id : kbIds.split(",")) {
            if (kbId.equals(id.trim())) return true;
        }
        return false;
    }

    private static String snippet(String content, int max) {
        if (content == null) return "";
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
