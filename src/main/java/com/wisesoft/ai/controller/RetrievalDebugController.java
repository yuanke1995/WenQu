package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.service.DocumentMetaCache;
import com.wisesoft.ai.service.HybridRetrievalService;
import com.wisesoft.ai.service.KnowledgeBaseService;
import com.wisesoft.ai.service.KeywordExtractor;
import com.wisesoft.ai.service.RerankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 检索调试：分步展示"关键词命中 → 向量命中 → 合并 → 重排 → 最终上下文 → 被排除"，
 * 用于排查"为什么答非所问"（P0-3）
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/debug")
@RequiredArgsConstructor
@Tag(name = "检索调试", description = "检索链路分步调试，排查召回问题")
public class RetrievalDebugController {

    /** 与 RagService 保持一致：最终上下文条数（重排区间由 RerankService 单一持有，此处不再复刻） */
    private static final int MAX_CONTEXT_HITS = 8;

    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final KeywordExtractor keywordExtractor;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseService kbService;
    private final com.wisesoft.ai.service.ConfigService configService;
    private final com.wisesoft.ai.service.UserConfigService userConfigService;

    @Operation(summary = "检索链路调试",
            description = "分步展示检索全链路：关键词命中、向量命中（含相似度分）、合并结果、重排结果、最终上下文（Top 8）、被排除的候选。用于排查召回质量问题。可选 kbIds 限定库范围（对照实验/单库排查）")
    @PostMapping("/retrieval")
    public ResultJson debug(
            @Parameter(description = "{\"question\": \"检索问题\", \"kbIds\": [\"可选，限定库范围\"]}")
            @RequestBody Map<String, Object> body) {
        // 个人体验参数（相关建议条数等）按测试者身份装载：不装载的话面板判定与
        // "我自己聊天时实际会走什么"不符（重排已归知识库/智能体检索设置，按其覆盖判定）
        configService.putUserOverrides(userConfigService.overrides(com.wisesoft.ai.util.RequestUser.uid()));
        try {
            return debugInternal(body);
        } finally {
            configService.clearUserOverrides();
        }
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
        // 检索词元（分词结果，便于验证分词/子词元召回效果）
        result.put("keywordTerms", keywordExtractor.extract(query));

        // 1. 关键词命中（含命中率/标题命中）
        List<Knowledge> kwDocs = hybridRetrievalService.keywordSearch(query);
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
        List<Document> vectorDocs = hybridRetrievalService.vectorSearch(query, null, kbIds);
        result.put("vectorHits", vectorDocs.stream().map(doc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String kid = String.valueOf(doc.getId());
            Object titleObj = doc.getMetadata().get("title");
            String title = titleObj == null ? "" : String.valueOf(titleObj);
            // M6 RedisVectorStore 查询还原 metadata 可能缺失 title/docId，按 knowledgeId 查 MySQL 兜底（与 buildHit 一致）
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

        // 3. 合并（RagService 实际使用的检索结果；kbIds 限定库范围——对照实验/单库排查）
        List<HybridRetrievalService.Hit> merged = hybridRetrievalService.search(query, null, kbIds);
        if (kbIds != null) {
            // 关键词路不受 kbIds 约束（产品现状：向量路按库索引过滤，关键词路全库）——
            // 按目标库的文档集合后过滤，与主链路 scopeKbIds（向量路）+ scopeDocIds（合并后）的组合口径一致
            Set<String> scopeDocs = new HashSet<>(kbService.docIdsOf(new java.util.ArrayList<>(kbIds)));
            merged = merged.stream()
                    .filter(h -> h.docId() != null && scopeDocs.contains(h.docId()))
                    .toList();
        }
        result.put("merged", merged.stream().map(this::hitMap).toList());

        // 4. 重排（与生产链路同一入口，判定单一来源）：RerankService.rank 内含完整口径——
        //    「候选 <rerank.minHits 跳过 / ≥minHits 重排、超 rerank.maxHits 截断 top maxHits 重排」。
        //    此前本接口自留一份「仅 6~15 条才重排」的旧判定：候选多于此区间时显示 rerankApplied=false，
        //    而生产在重排（实测候选 65 条仍 top15 重排）——面板与实际不符，按面板调参会得出错误结论。
        List<HybridRetrievalService.Hit> reranked = merged;
        String reason = rerankService.debugUnavailableReason();
        if (reason != null) {
            // 未启用/服务不可用/冷却中——真实反映"没重排"及原因
            result.put("rerankApplied", false);
            result.put("rerankSkipReason", reason);
        } else if (merged.size() < 2) {
            result.put("rerankApplied", false);
            result.put("rerankSkipReason", "命中过少（<2）无需重排");
        } else {
            reranked = rerankService.rank(merged, query);
            boolean applied = reranked.stream().anyMatch(h -> h.rerankScore() != null);
            result.put("rerankApplied", applied);
            if (!applied) {
                result.put("rerankSkipReason", "命中过少（< rerank.minHits）或服务不可用，未重排");
            } else if (reranked.size() > merged.size()) {
                result.put("rerankNote", "候选 " + merged.size() + " 条，与生产同口径重排头部");
            }
        }
        result.put("reranked", reranked.stream().map(this::hitMap).toList());

        // 5. 最终上下文（截断 8）+ 被排除
        List<HybridRetrievalService.Hit> finalTop = reranked.size() > MAX_CONTEXT_HITS
                ? reranked.subList(0, MAX_CONTEXT_HITS) : reranked;
        result.put("finalContext", finalTop.stream().map(this::hitMap).toList());
        if (reranked.size() > MAX_CONTEXT_HITS) {
            result.put("excluded", reranked.subList(MAX_CONTEXT_HITS, reranked.size())
                    .stream().map(this::hitMap).toList());
        } else {
            result.put("excluded", List.of());
        }
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