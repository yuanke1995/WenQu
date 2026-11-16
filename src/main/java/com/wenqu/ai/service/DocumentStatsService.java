package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.QaLogMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.QaLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档/知识库只读统计聚合：健康度面板、问答命中统计、文档归属映射、回收站计数。
 * 从 {@link DocumentService} 下沉，全部为只读查询，不触发任何对账或修复动作。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentStatsService {
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final QaLogMapper qaLogMapper;
    private final KeywordIndexService keywordIndexService;
    /** 库归属口径（空 kb_id 归默认库）与检索/健康度保持一致 */
    private final DocumentKbResolver kbResolver;

    /**
     * 知识库健康度聚合（§8 面板数据源，只读）：按库汇总文档态分布、块规模与未向量化数，
     * 附全局关键词索引漂移信号（索引数 vs 有效块数）。无库归属的脏数据文档以 kbId=null 行透出
     * （面板标注，不做静默归并）；只做聚合查询，不触发任何对账/修复。
     */
    public Map<String, Object> healthReport() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 文档态分布（kbId 可能为 null：历史空值按默认库归属，与上传链路同口径）
        record DocStat(long total, long active, long deprecated, long parsing, long failed) {}
        String defaultKbId = kbResolver.defaultKbId();
        Map<String, DocStat> byKb = new LinkedHashMap<>();
        for (Map<String, Object> row : documentMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiDocument>()
                .select("kb_id", "COUNT(*) AS total", "SUM(status=0) AS active", "SUM(status=1) AS deprecated",
                        "SUM(status=2) AS parsing", "SUM(status=3) AS failed")
                .eq("deleted", 0)
                .groupBy("kb_id"))) {
            String kbId = kbResolver.resolveOwnKb(row.get("kb_id"), defaultKbId);
            byKb.put(kbId, new DocStat(num(row, "total"), num(row, "active"), num(row, "deprecated"),
                    num(row, "parsing"), num(row, "failed")));
        }

        // 块规模与未向量化：块表无 kb_id，按 doc_id 聚合后经文档归属折算到库。
        // 归属必须绕开 @TableLogic 取全量文档：回收站里的文档（deleted=1，块还在等恢复/清理）也要归回原库，
        // 否则它们的块会掉进空归属桶，在面板上伪装成「未知库」的脏数据
        record DocOwner(String kbId, boolean recycled) {}
        Map<String, DocOwner> docOwner = new HashMap<>();
        for (Map<String, Object> row : documentMapper.selectOwnershipAll()) {
            docOwner.put(String.valueOf(row.get("id")),
                    new DocOwner(kbResolver.resolveOwnKb(row.get("kb_id"), defaultKbId), num(row, "deleted") == 1));
        }
        Map<String, long[]> chunkByKb = new LinkedHashMap<>();
        long[] orphanChunks = new long[2]; // 真无归属：文档行已物理删除 / 手动块的 docId 为空
        for (Map<String, Object> row : knowledgeMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Knowledge>()
                .select("doc_id", "COUNT(*) AS total", "SUM(vector_id IS NULL OR vector_id='') AS no_vector")
                .eq("deleted", 0)
                .groupBy("doc_id"))) {
            DocOwner owner = docOwner.get(String.valueOf(row.get("doc_id")));
            long total = num(row, "total");
            if (owner == null) {
                orphanChunks[0] += total;
                orphanChunks[1] += num(row, "no_vector");
                continue;
            }
            long[] agg = chunkByKb.computeIfAbsent(owner.kbId(), k -> new long[3]);
            if (owner.recycled()) {
                // 回收站文档的块单列：不参与关键词索引，也不该混进「未向量化」的待办数字
                agg[2] += total;
            } else {
                agg[0] += total;
                agg[1] += num(row, "no_vector");
            }
        }

        // 组装行（库名/内置标记补齐；孤儿库=有块无文档行的也透出）
        java.util.Set<String> kbIds = new java.util.LinkedHashSet<>(byKb.keySet());
        kbIds.addAll(chunkByKb.keySet());
        Map<String, KnowledgeBase> kbMap = new HashMap<>();
        for (KnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<>())) {
            kbMap.put(kb.getId(), kb);
        }
        List<Map<String, Object>> kbs = new ArrayList<>();
        for (String kbId : kbIds) {
            KnowledgeBase kb = kbMap.get(kbId);
            DocStat ds = byKb.getOrDefault(kbId, new DocStat(0, 0, 0, 0, 0));
            long[] cs = chunkByKb.getOrDefault(kbId, new long[3]);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kbId", kbId);
            r.put("kbName", kb != null ? kb.getName()
                    : (kbId == null || kbId.isBlank() ? "未归属知识库" : "（未知库 " + kbId + "）"));
            r.put("builtin", kb != null && Integer.valueOf(1).equals(kb.getBuiltin()));
            r.put("docTotal", ds.total());
            r.put("docActive", ds.active());
            r.put("docDeprecated", ds.deprecated());
            r.put("docParsing", ds.parsing());
            r.put("docFailed", ds.failed());
            r.put("chunkTotal", cs[0]);
            r.put("chunkNoVector", cs[1]);
            r.put("chunkRecycled", cs[2]);
            kbs.add(r);
        }
        // 真孤儿块单独一行：名字如实说明「没有文档可归属」，不再借用「未知库」的说法
        if (orphanChunks[0] > 0) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kbId", "__orphan__");
            r.put("kbName", "无文档归属的块");
            r.put("builtin", false);
            r.put("docTotal", 0L);
            r.put("docActive", 0L);
            r.put("docDeprecated", 0L);
            r.put("docParsing", 0L);
            r.put("docFailed", 0L);
            r.put("chunkTotal", orphanChunks[0]);
            r.put("chunkNoVector", orphanChunks[1]);
            r.put("chunkRecycled", 0L);
            kbs.add(r);
        }
        kbs.sort((a, b) -> Long.compare((long) b.get("chunkTotal"), (long) a.get("chunkTotal")));
        out.put("kbs", kbs);

        // 全局关键词索引漂移信号（与 /search-index/stats 同口径：只数有效块；-1=拉取失败不判漂移）
        long effective = keywordIndexService.countEffectiveChunks();
        long indexed = keywordIndexService.indexedCount();
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("engine", keywordIndexService.engine());
        index.put("available", keywordIndexService.isAvailable());
        index.put("indexedCount", indexed);
        index.put("mysqlCount", effective);
        index.put("drift", indexed >= 0 && indexed != effective);
        out.put("index", index);
        return out;
    }

    /** selectMaps 数值取整（SUM/COUNT 在 MySQL 驱动下可能是 Long/BigDecimal/BigInteger） */
    private long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 文档命中次数统计：从问答日志 hit_doc_ids 聚合，返回 {docId: count}
     * hit_doc_ids 为逗号分隔串无法直接 GROUP BY，按近 90 天 + LIMIT 上限做有界扫描（避免全表拉取到内存）
     */
    public Map<String, Long> statsHitCounts() {
        Map<String, Long> counts = new HashMap<>();
        hitStatsByDoc().forEach((id, s) -> counts.put(id, s.count()));
        return counts;
    }

    /** 单个文档的命中统计：次数 + 最近一次命中时间（知识库卡片「最近使用」用） */
    public record HitStat(long count, java.time.LocalDateTime lastAt) {}

    /** 近 90 天命中明细：{docId: {count,lastAt}}，与 {@link #statsHitCounts()} 同源 */
    public Map<String, HitStat> hitStatsByDoc() {
        Map<String, long[]> counts = new HashMap<>();
        Map<String, java.time.LocalDateTime> lastAt = new HashMap<>();
        try {
            var logs = qaLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wenqu.ai.model.QaLog>()
                    .isNotNull(com.wenqu.ai.model.QaLog::getHitDocIds)
                    .ne(com.wenqu.ai.model.QaLog::getHitDocIds, "")
                    .ge(com.wenqu.ai.model.QaLog::getCreatedAt, java.time.LocalDateTime.now().minusDays(90))
                    .select(com.wenqu.ai.model.QaLog::getHitDocIds,
                            com.wenqu.ai.model.QaLog::getCreatedAt)
                    .last("LIMIT 20000"));
            for (var log : logs) {
                if (log.getHitDocIds() == null || log.getHitDocIds().isBlank()) continue;
                for (String id : log.getHitDocIds().split(",")) {
                    String docId = id.trim();
                    if (docId.isEmpty()) continue;
                    counts.computeIfAbsent(docId, k -> new long[1])[0]++;
                    java.time.LocalDateTime t = log.getCreatedAt();
                    if (t != null) lastAt.merge(docId, t, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        } catch (Exception e) {
            log.warn("统计文档命中次数失败: {}", e.getMessage());
        }
        Map<String, HitStat> out = new HashMap<>();
        counts.forEach((id, c) -> out.put(id, new HitStat(c[0], lastAt.get(id))));
        return out;
    }

    /** 全量文档归属映射 {docId: kbId}（含回收站）：空 kbId 按默认库归属，与检索/健康度同口径 */
    public Map<String, String> docKbMap() {
        String defaultKbId = kbResolver.defaultKbId();
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> row : documentMapper.selectOwnershipAll()) {
            out.put(String.valueOf(row.get("id")), kbResolver.resolveOwnKb(row.get("kb_id"), defaultKbId));
        }
        return out;
    }

    /** 各库回收站文档数 {kbId: count}（默认库归属口径与健康度一致） */
    public Map<String, Long> recycleCountsByKb() {
        String defaultKbId = kbResolver.defaultKbId();
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> row : documentMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiDocument>()
                        .select("kb_id", "COUNT(*) AS total")
                        .eq("deleted", 1)
                        .groupBy("kb_id"))) {
            String kbId = kbResolver.resolveOwnKb(row.get("kb_id"), defaultKbId);
            out.merge(kbId, num(row, "total"), Long::sum);
        }
        return out;
    }
}
