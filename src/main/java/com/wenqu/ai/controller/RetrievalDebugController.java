package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.service.DocumentMetaCache;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.KeywordExtractor;
import com.wenqu.ai.service.RerankService;
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

    /** 与 ChatService 保持一致：最终上下文条数（重排区间由 RerankService 单一持有，此处不再复刻） */
    private static final int MAX_CONTEXT_HITS = 8;

    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final KeywordExtractor keywordExtractor;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseService kbService;
    private final com.wenqu.ai.service.ConfigService configService;
    private final com.wenqu.ai.service.UserConfigService userConfigService;

    @Operation(summary = "检索链路调试",
            description = "分步展示检索全链路：关键词命中、向量命中（含相似度分）、合并结果、重排结果、最终上下文（Top 8）、被排除的候选。用于排查召回质量问题。可选 kbIds 限定库范围（对照实验/单库排查）")
    @PostMapping("/retrieval")
    public ResultJson debug(
            @Parameter(description = "{\"question\": \"检索问题\", \"kbIds\": [\"可选，限定库范围\"]}")
            @RequestBody Map<String, Object> body) {
        // 个人体验参数（相关建议条数等）按测试者身份装载：不装载的话面板判定与
        // "我自己聊天时实际会走什么"不符（重排已归知识库/智能体检索设置，按其覆盖判定）
        configService.putUserOverrides(userConfigService.overrides(com.wenqu.ai.util.RequestUser.uid()));
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

        // 3. 合并（ChatService 实际使用的检索结果；kbIds 限定库范围——对照实验/单库排查）
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

        // 5. 最终上下文：走与真实问答**同一道相关性门**。
        //    此前这里只截前 8 条，面板报 8 而生产实际进 1~2 条——排查召回的人会去找一个不存在的堵点
        //    （2026-10-13 实测：为此连试每库配额、门限、重排窗口三组 A/B 全部空转，真因是分布本身
        //    只有 1~2 块过 0.6 门）。与本文件上面第 4 步那条"面板要与生产同口径"是同一条标准。
        double minRerank = configService.getDouble("retrieval.minContextScore", ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE);
        double minFusion = configService.getDouble("retrieval.minFusionScore", ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE);
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
            double rankScore = hasRerank ? h.rerankScore() : h.score();
            double gate = hasRerank ? minRerank : minFusion;
            if (gate > 0 && rankScore < gate) {
                droppedByGate++;
                continue;
            }
            if (gated.size() >= MAX_CONTEXT_HITS) break;
            gated.add(h);
        }
        Map<String, Object> gateInfo = new LinkedHashMap<>();
        gateInfo.put("minRerank", minRerank);
        gateInfo.put("minFusion", minFusion);
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