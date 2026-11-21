package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.service.DocumentMetaCache;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.KbRetrievalParamService;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.KeywordExtractor;
import com.wenqu.ai.service.QuerySplitter;
import com.wenqu.ai.service.RerankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import com.wenqu.ai.security.Permission;

/**
 * 检索调试：分步展示"关键词命中 → 向量命中 → 合并 → 重排 → 最终上下文 → 被排除"，
 * 用于排查"为什么答非所问"
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/debug")
@RequiredArgsConstructor
@Tag(name = "检索调试", description = "检索链路分步调试，排查召回问题")
public class RetrievalDebugController {

    /** 与 ChatService 保持一致：最终上下文条数（重排区间由 RerankService 单一持有，此处不再复刻） */
    private static final int MAX_CONTEXT_HITS = 8;

    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final KeywordExtractor keywordExtractor;
    private final QuerySplitter querySplitter;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseService kbService;
    private final com.wenqu.ai.service.ConfigService configService;
    private final com.wenqu.ai.service.UserConfigService userConfigService;
    /** 库级检索参数：面板要显示“每库生效值”，重排也要按库收敛模型（与生产同口径） */
    private final com.wenqu.ai.service.KbRetrievalParamService kbRetrievalParams;
    /** 按智能体复现：把它的资料范围与文档条件按生产同一条链路解析出来 */
    private final com.wenqu.ai.service.AgentService agentService;
    private final com.wenqu.ai.agent.context.RetrievalScopeService retrievalScopeService;
    /** 追问改写：可选 sessionId + modelRef 时真实跑一次模型改写，便于复现「追问为什么没搜到」 */
    private final com.wenqu.ai.service.FollowUpQueryRewriter queryRewriter;
    private final com.wenqu.ai.service.SessionService sessionService;

    @Operation(summary = "检索链路调试",
            description = "分步展示检索全链路：关键词命中、向量命中（含相似度分）、合并结果、重排结果、最终上下文（Top 8）、被排除的候选。用于排查召回质量问题。可选 kbIds 限定库范围（对照实验/单库排查）；可选 agentId 按该智能体的资料范围与文档条件检索；可选 filter 直接指定过滤条件（优先于 agentId）；可选 sessionId + modelRef 真实跑一次追问改写，并按「改写词 + 原词」两路合并")
    @Permission("retrieval-debug:run")
    @PostMapping("/retrieval")
    public ResultJson debug(
            @Parameter(description = "{\"question\": \"检索问题\", \"kbIds\": [\"可选，限定库范围\"]}")
            @RequestBody Map<String, Object> body) {
        // 个人覆盖按测试者身份装载：不装载的话面板判定与
        // "我自己聊天时实际会走什么"不符（重排模型归知识库、开关归智能体，按其覆盖判定）
        configService.putUserOverrides(userConfigService.overrides(com.wenqu.ai.util.RequestUser.uid()));
        try {
            return debugInternal(body);
        } finally {
            configService.clearUserOverrides();
        }
    }

    /**
     * 调试用元数据过滤：body.filter = {kbIds, docIds, tags, fileTypes, updatedWithinDays, createdBy}。
     * 键缺省=不限；显式空数组=无结果（与生产 RetrievalFilter 同一语义）。
     */
    private static com.wenqu.ai.service.RetrievalFilter parseFilter(Map<String, Object> body) {
        if (!(body.get("filter") instanceof Map<?, ?> raw)) {
            return com.wenqu.ai.service.RetrievalFilter.NONE;
        }
        Integer days = intValue(raw.get("updatedWithinDays"));
        Set<String> docIds = strings(raw.get("docIds"));
        if (docIds != null && docIds.size() > 500) {
            throw new BizException("filter.docIds 调试上限 500 条");
        }
        return new com.wenqu.ai.service.RetrievalFilter(
                strings(raw.get("kbIds")),
                docIds,
                strings(raw.get("tags")),
                strings(raw.get("fileTypes")),
                days == null || days <= 0 ? null : java.time.LocalDateTime.now().minusDays(days),
                null,
                text(raw.get("createdBy")));
    }

    /** 缺省 → null（不限）；出现但为空数组 → 空集合（无结果） */
    private static Set<String> strings(Object v) {
        if (v == null) return null;
        if (!(v instanceof List<?> list)) return null;
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Integer intValue(Object v) {
        if (v == null) return null;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private ResultJson debugInternal(Map<String, Object> body) {
        String query = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        if (query.isBlank()) {
            throw new BizException("请输入问题");
        }
        // 可选库范围（对照实验：同一问题在文本抽取库 vs OCR 库分别检索对比）
        java.util.Collection<String> kbIds = null;
        if (body.get("kbIds") instanceof List<?> l && !l.isEmpty()) {
            kbIds = l.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        HybridRetrievalService.RetrievalDiag diag = new HybridRetrievalService.RetrievalDiag();
        diag.subQueries(querySplitter.analyze(query).queries());
        // 可选元数据过滤（与生产同一份 RetrievalFilter）：用于复现「标签/类型/时间下为什么搜不到」
        com.wenqu.ai.service.RetrievalFilter filter = parseFilter(body);
        // 可选按智能体复现：资料范围与文档条件走生产同一条解析链路；显式 filter 优先于智能体配置
        String agentId = text(body.get("agentId"));
        if (agentId != null) {
            com.wenqu.ai.model.Agent agent = agentService.findReadable(agentId);
            if (agent == null) throw new BizException("智能体不存在或不可见");
            com.wenqu.ai.service.KnowledgeRetrievalTool.KbScope scope =
                    retrievalScopeService.resolve(agent, com.wenqu.ai.util.RequestUser.uid(), false, null);
            if (!body.containsKey("filter") && scope.filter() != null) filter = scope.filter();
            if (kbIds == null && scope.kbIds() != null) kbIds = scope.kbIds();
            result.put("agentId", agentId);
        }
        // 检索词元（分词结果，便于验证分词/子词元召回效果）
        result.put("keywordTerms", keywordExtractor.extract(query));

        // 可选追问改写：给了 sessionId 就按最近 3 轮历史真实跑一次；modelRef 缺省时只返回 no-model 跳过原因
        String sessionId = text(body.get("sessionId"));
        com.wenqu.ai.service.FollowUpQueryRewriter.Result rewrite = null;
        if (sessionId != null) {
            java.util.List<java.util.Map<String, Object>> history = sessionService.getRecentHistory(sessionId, 3);
            rewrite = queryRewriter.rewrite(text(body.get("modelRef")), query, query, history,
                    com.wenqu.ai.util.RequestUser.uid(), sessionId);
            java.util.Map<String, Object> rw = new LinkedHashMap<>();
            rw.put("applied", rewrite.applied());
            rw.put("original", query);
            rw.put("query", rewrite.query());
            rw.put("skipReason", rewrite.skipReason());
            rw.put("elapsedMs", rewrite.elapsedMs());
            result.put("rewrite", rw);
        }

        // 1. 关键词命中（含命中率/标题命中）
        List<Knowledge> kwDocs = hybridRetrievalService.keywordSearch(query, diag, 0, filter);
        result.put("keywordHits", kwDocs.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledgeId", k.getId());
            m.put("title", k.getTitle());
            m.put("docName", documentMetaCache.getFileName(k.getDocId()));
            m.put("hitRate", k.getTotalTerms() > 0
                    ? Math.round(k.getHitTerms() * 100.0 / k.getTotalTerms()) / 100.0 : 0);
            m.put("titleHit", k.isTitleHit());
            m.put("snippet", snippet(k.getContent()));
            return m;
        }).toList());

        // 2. 向量命中（相似度分；kbIds 限定库范围与 merged 段一致）
        List<Document> vectorDocs = hybridRetrievalService.vectorSearch(query, diag, kbIds, filter);
        result.put("vectorHits", vectorDocs.stream().map(doc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String kid = String.valueOf(doc.getId());
            Object titleObj = doc.getMetadata().get("title");
            String title = titleObj == null ? "" : String.valueOf(titleObj);
            // RedisVectorStore 查询还原 metadata 可能缺失 title/docId，按 knowledgeId 查 MySQL 兜底（与 buildHit 一致）
            String docId = doc.getMetadata().get("docId") == null ? "" : String.valueOf(doc.getMetadata().get("docId"));
            if (title.isEmpty() || docId.isEmpty()) {
                try {
                    Knowledge k = knowledgeMapper.selectById(kid);
                    if (k != null) {
                        if (title.isEmpty()) title = k.getTitle() == null ? "" : k.getTitle();
                        if (docId.isEmpty() && k.getDocId() != null) docId = String.valueOf(k.getDocId());
                    }
                } catch (Exception ignored) {
                }
            }
            m.put("knowledgeId", kid);
            m.put("title", title);
            m.put("docName", documentMetaCache.getFileName(docId));
            m.put("score", doc.getScore() == null ? 0 : Math.round(doc.getScore() * 100.0) / 100.0);
            m.put("snippet", snippet(doc.getText()));
            return m;
        }).toList());

        // 3. 合并（ChatService 实际使用的检索结果；kbIds 限定库范围——对照实验/单库排查）
        //    改写成功时按生产同口径走「改写词 + 原词」两路并行，便于直接对照差异。
        List<HybridRetrievalService.Hit> merged = rewrite != null && rewrite.applied()
                ? hybridRetrievalService.searchMulti(List.of(rewrite.query(), query), diag, kbIds, false, filter)
                : hybridRetrievalService.search(query, diag, kbIds, false, filter);
        if (kbIds != null) {
            // 库界现在已并进 filter 同时约束两条召回路；这里保留一次文档级复核，
            // 用于对照实验时把「库范围内文档」与命中结果对齐（手动块不在文档表内，会被这里剔掉）
            Set<String> scopeDocs = new HashSet<>(kbService.docIdsOf(new java.util.ArrayList<>(kbIds)));
            merged = merged.stream()
                    .filter(h -> h.docId() != null && scopeDocs.contains(h.docId()))
                    .toList();
        }
        result.put("merged", merged.stream().map(this::hitMap).toList());
        // 过滤链路诊断：被元数据条件剔除多少、文档范围是否精确下推、关键词走了哪条引擎
        Map<String, Object> diagOut = new LinkedHashMap<>();
        diagOut.put("metaFiltered", diag.getMetaFiltered());
        diagOut.put("filterPushdown", diag.filterPushdown());
        diagOut.put("keywordEngine", diag.keywordEngine());
        diagOut.put("graphExpanded", diag.getGraphExpanded());
        diagOut.put("aclPushdownError", diag.aclPushdownError());
        diagOut.put("subQueries", diag.subQueries());
        result.put("retrievalDiag", diagOut);

        // 每库生效参数：只列**本轮真正命中的库**（命中块已过可见性判定），不把范围外的库名/模型带出去。
        // 面板按库展示阈值/权重/重排模型，避免“改了库参数但面板仍显示全局值”的误导。
        Map<String, Object> kbParams = new LinkedHashMap<>();
        Set<String> hitKbIds = merged.stream().map(HybridRetrievalService.Hit::kbId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!hitKbIds.isEmpty()) {
            kbRetrievalParams.forScope(kbIds).forEach((id, p) -> {
                if (!hitKbIds.contains(id)) return;
                Map<String, Object> m = new LinkedHashMap<>();
                com.wenqu.ai.model.KnowledgeBase kb = kbService.get(id);
                m.put("name", kb == null ? id : kb.getName());
                m.put("vecThreshold", p.vecThreshold());
                m.put("vectorWeight", p.vectorWeight());
                m.put("keywordWeight", p.keywordWeight());
                m.put("rerankModel", p.rerankModel() == null ? "" : p.rerankModel());
                m.put("rerankMinHits", p.rerankMinHits());
                m.put("rerankMaxHits", p.rerankMaxHits());
                m.put("vectorTopK", p.vectorTopK());
                m.put("keywordLimit", p.keywordLimit());
                m.put("minContextScore", p.minContextScore());
                m.put("minFusionScore", p.minFusionScore());
                m.put("lowConfidencePolicy", p.lowConfidencePolicy());
                m.put("top1Floor", p.top1Floor());
                m.put("configured", kb != null && kb.getQueryParams() != null && !kb.getQueryParams().isBlank());
                kbParams.put(id, m);
            });
        }
        result.put("kbParams", kbParams);

        // 4. 重排（与生产链路同一入口，判定单一来源）：RerankService.rank 内含完整口径——
        //    「候选 <rerank.minHits 跳过 / ≥minHits 重排、超 rerank.maxHits 截断 top maxHits 重排」。
        List<HybridRetrievalService.Hit> reranked = merged;
        // 重排可用性/实际调用都按“本轮命中库的重排模型”判定：库绑了模型就不该因平台层没配而报不可用。
        // 按命中库而不是范围取模型：范围可能是“全部库”，按范围会选中别人的默认库（hitKbIds 已在上面算出）。
        String reason = kbRetrievalParams.withRerankModel(kbIds, hitKbIds, rerankService::debugUnavailableReason);
        if (reason != null) {
            // 未启用/服务不可用/冷却中——真实反映"没重排"及原因
            result.put("rerankApplied", false);
            result.put("rerankSkipReason", reason);
        } else if (merged.size() < 2) {
            result.put("rerankApplied", false);
            result.put("rerankSkipReason", "命中过少（<2）无需重排");
        } else {
            final List<HybridRetrievalService.Hit> candidates = merged;
            reranked = kbRetrievalParams.withRerankModel(kbIds, hitKbIds,
                    () -> rerankService.rank(candidates, query));
            boolean applied = reranked.stream().anyMatch(h -> h.rerankScore() != null);
            result.put("rerankApplied", applied);
            if (!applied) {
                result.put("rerankSkipReason", "命中过少（< rerank.minHits）或服务不可用，未重排");
            } else if (reranked.size() > merged.size()) {
                result.put("rerankNote", "候选 " + merged.size() + " 条，与生产同口径重排头部");
            }
        }
        result.put("reranked", reranked.stream().map(this::hitMap).toList());

        // 5. 最终上下文：走与真实问答**同一道相关性门**。分域门已归库级检索参数，
        //    按命中块所属库取各自的门值（显式覆盖>库级 JSON>内置默认），各库门值见上面 kbParams。
        Map<String, KbRetrievalParamService.Params> gatePs = kbRetrievalParams.forScope(kbIds);
        KbRetrievalParamService.Params defP = kbRetrievalParams.defaults();
        List<HybridRetrievalService.Hit> gated = new ArrayList<>();
        int droppedByGate = 0;
        // 重排本轮真执行过时，未进重排窗口的候选不得借融合分混进上下文——与生产同一条规则，
        // 少了它面板就会报 8 而实际进 2（相关性分域不同，融合分与重排分不可互相顶替）
        boolean rerankExecuted = Boolean.TRUE.equals(result.get("rerankApplied"));
        for (HybridRetrievalService.Hit h : reranked) {
            boolean hasRerank = h.rerankScore() != null;
            if (rerankExecuted && !hasRerank) {
                droppedByGate++;
                continue;
            }
            KbRetrievalParamService.Params p = gatePs.getOrDefault(h.kbId(), defP);
            double rankScore = hasRerank ? h.rerankScore() : h.score();
            double gate = hasRerank ? p.minContextScore() : p.minFusionScore();
            if (gate > 0 && rankScore < gate) {
                droppedByGate++;
                continue;
            }
            if (gated.size() >= MAX_CONTEXT_HITS) break;
            gated.add(h);
        }
        Map<String, Object> gateInfo = new LinkedHashMap<>();
        gateInfo.put("droppedByGate", droppedByGate);
        // 与生产仍有两处已知差别，写出来让看的人自己校正预期，而不是又当成 bug 去查：
        gateInfo.put("note", "面板对全部候选重排，生产只重排融合分 top rerank.maxHits——窗口大小不同会让面板数略多于实际；"
                + "单文档/单库配额与信息增益去冗这里不复刻（只减不增）。相关性门与未重排不得入场两条已同口径。");
        result.put("contextGate", gateInfo);
        result.put("finalContext", gated.stream().map(this::hitMap).toList());
        List<HybridRetrievalService.Hit> rest = new ArrayList<>(reranked);
        rest.removeAll(gated);
        result.put("excluded", rest.stream().map(this::hitMap).toList());
        return ResultJson.ok(result);
    }

    private Map<String, Object> hitMap(HybridRetrievalService.Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("knowledgeId", h.knowledgeId());
        m.put("title", h.title());
        m.put("docId", h.docId());
        m.put("chunkIndex", h.chunkIndex());
        m.put("docName", documentMetaCache.getFileName(h.docId()));
        m.put("score", Math.round(h.score() * 100.0) / 100.0);
        // 重排分（未重排的候选为 null）：融合分与重排分是两个分域，必须分别展示，
        // 否则按面板看到的融合分去调 minContextScore（只对重排分生效）必然调错
        if (h.rerankScore() != null) m.put("rerankScore", Math.round(h.rerankScore() * 1000.0) / 1000.0);
        m.put("snippet", snippet(h.content()));
        return m;
    }

    private String snippet(String content) {
        if (content == null) return "";
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}