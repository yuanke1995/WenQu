package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 混合检索：向量召回 + MySQL 关键词召回 并行执行 → 按 knowledgeId 合并去重 → 加权排序
 * <p>
 * 两路召回均只返回生效文档（status=0）的知识块：关键词路 SQL 过滤，向量路按命中块的 docId 批量剔除。
 * <p>
 * - 向量：topK 可配（retrieval.vectorTopK，默认 15）+ 阈值放宽（0.3），分数归一化到 0~1（(score-0.3)/(1-0.3)）
 * - 关键词：词元 LIKE 召回 + 词频加权（tf×idf，标题词频×2，归一化 0~1）
 * - 融合：双命中**叠加**（向量分 + 关键词分 + 标题奖励），单路命中取各自权重分
 * - 位置：文档首块（chunkIndex=0）小幅加分，中部块相对降权
 * 权重来自 DB 配置 retrieval.*（设置页保存即生效；默认 0.6/0.4/0.1）
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    /** 默认参数：检索行为参数收口到 c_ai_config（retrieval.*，设置页可调、保存即生效） */
    private long keywordTimeoutMs() { return configService.getInt("retrieval.keywordTimeoutMs", 800); }
    private int keywordLimit() { return configService.getInt("retrieval.keywordLimit", 20); }
    private double vecThreshold() { return configService.getDouble("retrieval.vecThreshold", 0.3); }

    private final VectorStore vectorStore;
    private final AiKnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final KeywordExtractor keywordExtractor;
    private final ConfigService configService;
    private final KeywordIndexService keywordIndexService;
    private final StringRedisTemplate redisTemplate;

    /** 全量重嵌入进行中（持有分布式锁的实例正在 DROP/重建向量索引）→ 各实例向量路跳过降级关键词 */
    private boolean reembedInProgress() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(DocumentService.REEMBED_LOCK_KEY));
        } catch (Exception e) {
            return false; // Redis 异常按未锁定处理（向量检索自身仍有 try-catch 兜底）
        }
    }

    /**
     * 混合检索结果（chunkIndex 用于位置奖励；titlePath 章节路径，检索侧拼装上下文用）
     */
    public record Hit(String knowledgeId, String docId, String title, String content,
                      List<String> images, double score, Integer chunkIndex, String titlePath) {
    }

    /**
     * 检索诊断（fail-loud：单路失败/降级标记，随检索结果透传给调用方上报 degradations）。
     * 只写不入日志，避免热路径日志噪音；由 RagService 统一转成回答级警示。
     */
    public static final class RetrievalDiag {
        private boolean vectorFailed;
        private boolean keywordFailed;   // Meili 不可用（探测失败/冷却/401）→ 本次降级 MySQL LIKE
        private boolean keywordBusy;     // 关键词降级检索繁忙/超时 → 本次跳过关键词路
        private boolean keywordFallback; // Meili 无命中回退 MySQL（仅调试面板展示，不扰用户）
        private boolean multiTimeout;    // 多路检索超时/失败 → 降级首路/仅用已完成结果
        private String lastError;

        void vectorFailed(String err) { this.vectorFailed = true; this.lastError = err; }
        void keywordFailed() { this.keywordFailed = true; }
        void keywordBusy() { this.keywordBusy = true; }
        void keywordFallback() { this.keywordFallback = true; }
        void multiTimeout() { this.multiTimeout = true; }

        /** 清空本次诊断（改写回退/二次检索前调用：最终用于回答的那次检索的状态为准） */
        void reset() {
            this.vectorFailed = false;
            this.keywordFailed = false;
            this.keywordBusy = false;
            this.keywordFallback = false;
            this.multiTimeout = false;
            this.lastError = null;
        }

        public boolean isVectorFailed() { return vectorFailed; }
        public boolean isKeywordFailed() { return keywordFailed; }
        public boolean isKeywordBusy() { return keywordBusy; }
        public boolean isKeywordFallback() { return keywordFallback; }
        public boolean isMultiTimeout() { return multiTimeout; }
        public String lastError() { return lastError; }
    }

    /**
     * 混合检索：返回按融合分降序的结果（已过滤弃用文档）。旧签名委托，外部调用方不受影响。
     */
    public List<Hit> search(String query) {
        return search(query, null);
    }

    /** 带诊断的混合检索（fail-loud：单路失败/降级写入 diag，由调用方转回答级警示） */
    public List<Hit> search(String query, RetrievalDiag diag) {
        // 权重动态读取（DB 配置，保存即生效；缺失时兜底 yml 默认值 0.6/0.4/0.1）
        double vectorWeight = configService.getDouble("retrieval.vectorWeight");
        double keywordWeight = configService.getDouble("retrieval.keywordWeight");
        double titleBonus = configService.getDouble("retrieval.titleBonus");

        // 1. 向量召回（放大召回率）
        List<Document> vectorDocs = vectorSearch(query, diag);

        // 2. 关键词召回（并行，超时兜底）
        List<AiKnowledge> kwDocs = keywordSearch(query, diag);

        // 3. 批量加载向量命中的知识块元数据（一次 selectBatchIds 替代逐条 selectById）+ 不可召回文档集合
        Map<String, AiKnowledge> kidMap = loadKnowledgeBatch(vectorDocs);
        Set<String> blockedDocIds = loadNonRetrievableDocIds(kidMap);

        // 4. 合并去重 + 加权（默认 sum：A1 双命中叠加；可选 rrf 倒数排名融合，见下）
        String fusionMode = configService.get("retrieval.fusionMode");
        if ("rrf".equalsIgnoreCase(fusionMode)) {
            return mergeByRrf(vectorDocs, kwDocs, kidMap, blockedDocIds);
        }

        Map<String, Hit> merged = new LinkedHashMap<>();

        // 向量命中：score = 向量权重 × 归一化向量分；非生效文档（弃用/解析中/解析失败）跳过，与关键词路 status=0 语义一致
        double vt = vecThreshold();
        for (Document doc : vectorDocs) {
            String kid = String.valueOf(doc.getId());
            AiKnowledge k = kidMap.get(kid);
            String docId = k != null && k.getDocId() != null ? String.valueOf(k.getDocId()) : metadataDocId(doc);
            if (docId != null && blockedDocIds.contains(docId)) {
                log.debug("[RAG] 跳过非生效文档命中: docId={} kid={}", docId, kid);
                continue;
            }
            if (k != null && k.getStatus() != null && k.getStatus() == 1) {
                log.debug("[RAG] 跳过已停用知识块: kid={}", kid);
                continue;
            }
            double vecScore = parseScore(doc.getScore());
            double vecNorm = Math.max(0, (vecScore - vt) / (1.0 - vt));
            // L8（设计保留，不扰用户）：单路为空时分数为绝对加权和——向量单飞=vectorWeight×vecNorm，
            // 关键词单飞=keywordWeight×hitRate，两者同体系（0~0.6 / 0~0.4）可直接比较排序，无虚高问题；
            // 仅当关键词路 hitRate 本身归一化失真时才可能偏高（MySQL LIKE 路已按命中集归一化，属已知边界）
            double score = vectorWeight * vecNorm;
            merged.put(kid, buildHit(doc, k, kid, score));
        }
        // 关键词命中：score = 关键词权重 × 词频加权分 + 标题奖励；与向量命中叠加（相加）
        for (AiKnowledge k : kwDocs) {
            double hitRate = k.getKwScore(); // 词频加权归一化分（0~1，替代原词元占比）
            double score = keywordWeight * hitRate
                    + (k.isTitleHit() ? titleBonus : 0);
            merged.merge(k.getId(), buildHit(k, score), (oldHit, newHit) ->
                    new Hit(oldHit.knowledgeId(),
                            oldHit.docId() == null || oldHit.docId().isBlank() ? newHit.docId() : oldHit.docId(),
                            oldHit.title(), oldHit.content(), oldHit.images(),
                            oldHit.score() + newHit.score(), // A1：双命中叠加
                            oldHit.chunkIndex() == null ? newHit.chunkIndex() : oldHit.chunkIndex(),
                            oldHit.titlePath() == null ? newHit.titlePath() : oldHit.titlePath()));
        }

        // A5：位置奖励（排序前统一加，保证分数与顺序一致）
        List<Hit> result = new ArrayList<>(merged.values());
        for (int i = 0; i < result.size(); i++) {
            Hit h = result.get(i);
            double bonus = positionBonus(h.chunkIndex());
            if (bonus != 0) {
                result.set(i, new Hit(h.knowledgeId(), h.docId(), h.title(), h.content(), h.images(),
                        h.score() + bonus, h.chunkIndex(), h.titlePath()));
            }
        }
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    /**
     * RRF 倒数排名融合（实验模式，retrieval.fusionMode=rrf）：
     * 双路各自按"排序名次"贡献 1/(K+rank+1)（K=60，双命中叠加），规避关键词路命中集内归一化
     * （顶命恒≈1）与向量路绝对归一化之间的标度错配导致的排序漂移。标题/位置奖励是分值加分语义，
     * 不参与名次。单路为空时退化为另一路的纯名次排序。是否优于 sum 需用检索评估页参数组对比验证。
     */
    private List<Hit> mergeByRrf(List<Document> vectorDocs, List<AiKnowledge> kwDocs,
                                 Map<String, AiKnowledge> kidMap, Set<String> blockedDocIds) {
        Map<String, Hit> base = new LinkedHashMap<>();
        Map<String, Double> rrf = new HashMap<>();

        // 向量路（升秩前先按分降序，防御存储返回乱序）
        List<Document> vecRanked = vectorDocs.stream()
                .sorted(Comparator.comparingDouble((Document d) -> parseScore(d.getScore())).reversed())
                .toList();
        int rank = 0;
        for (Document doc : vecRanked) {
            String kid = String.valueOf(doc.getId());
            AiKnowledge k = kidMap.get(kid);
            String docId = k != null && k.getDocId() != null ? String.valueOf(k.getDocId()) : metadataDocId(doc);
            if (docId != null && blockedDocIds.contains(docId)) {
                log.debug("[RAG] RRF 跳过非生效文档命中: docId={} kid={}", docId, kid);
                continue;
            }
            if (k != null && k.getStatus() != null && k.getStatus() == 1) {
                log.debug("[RAG] RRF 跳过已停用知识块: kid={}", kid);
                continue;
            }
            base.put(kid, buildHit(doc, k, kid, 0));
            rrf.merge(kid, 1.0 / (RRF_K + rank + 1), Double::sum);
            rank++;
        }

        // 关键词路（词频加权分降序取秩；与 sum 路同样依赖关键词检索自身的 status 过滤）
        List<AiKnowledge> kwRanked = kwDocs.stream()
                .sorted(Comparator.comparingDouble(AiKnowledge::getKwScore).reversed())
                .toList();
        rank = 0;
        for (AiKnowledge k : kwRanked) {
            String kid = k.getId();
            Hit kwHit = buildHit(k, 0);
            if (base.containsKey(kid)) {
                Hit old = base.get(kid);
                base.put(kid, mergeHits(old, kwHit));
            } else {
                base.put(kid, kwHit);
            }
            rrf.merge(kid, 1.0 / (RRF_K + rank + 1), Double::sum);
            rank++;
        }

        List<Hit> result = new ArrayList<>(base.size());
        base.forEach((kid, h) -> result.add(new Hit(h.knowledgeId(), h.docId(), h.title(), h.content(), h.images(),
                rrf.getOrDefault(kid, 0.0), h.chunkIndex(), h.titlePath())));
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    /** RRF 常数（标准 K=60）：名次贡献 1/(K+rank+1) */
    private static final int RRF_K = 60;

    /** 双路命中字段合并（与 sum 路 A1 合并器同语义：优先保留向量路实体，空字段回落关键词路） */
    private Hit mergeHits(Hit oldHit, Hit newHit) {
        return new Hit(oldHit.knowledgeId(),
                oldHit.docId() == null || oldHit.docId().isBlank() ? newHit.docId() : oldHit.docId(),
                oldHit.title(), oldHit.content(), oldHit.images(),
                oldHit.score(), // 分数由 RRF 名次分统一回填，此处不叠加
                oldHit.chunkIndex() == null ? newHit.chunkIndex() : oldHit.chunkIndex(),
                oldHit.titlePath() == null ? newHit.titlePath() : oldHit.titlePath());
    }

    /** 多路并行检索线程池（daemon，供深度思考多路检索用） */
    private final ExecutorService multiSearchPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "multi-search");
        t.setDaemon(true);
        return t;
    });

    /**
     * MySQL 关键词降级检索池：唯一用途是给阻塞式 LIKE 查询套上超时（keywordTimeoutMs）。
     * 必须独立于 multiSearchPool：searchMulti 的外层任务跑在 multiSearchPool 上，其内部
     * 会调到本方法，同池嵌套提交会在 4 个线程被外层占满时让内层任务永远等不到线程（starvation）。
     * 也不用共享池：队列满时共享池的拒绝策略会阻塞调用方，把压力反弹成请求延迟。
     * 本池队列满即 AbortPolicy 拒绝 → 调用方降级为空结果（关键词路本身就是可选增强）。
     */
    private final ThreadPoolExecutor keywordFallbackPool = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "keyword-mysql");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    @jakarta.annotation.PreDestroy
    void shutdownMultiSearch() {
        multiSearchPool.shutdownNow();
        keywordFallbackPool.shutdownNow();
    }

    /**
     * 多路并行检索（深度思考用）：多个 query 并行调用 search()，按 knowledgeId 合并去重，保留最高分
     * 单 query 直接委托 search()；并行总超时 8s（超时用已完成结果）
     */
    public List<Hit> searchMulti(List<String> queries) {
        return searchMulti(queries, null);
    }

    /** 带诊断的多路检索（fail-loud：超时/失败降级首路写入 diag） */
    public List<Hit> searchMulti(List<String> queries, RetrievalDiag diag) {
        if (queries == null || queries.isEmpty()) return List.of();
        List<String> qs = queries.stream().map(String::trim).filter(q -> !q.isBlank()).distinct().toList();
        if (qs.size() <= 1) {
            return qs.isEmpty() ? List.of() : search(qs.get(0), diag);
        }
        try {
            List<CompletableFuture<List<Hit>>> futures = qs.stream()
                    .map(q -> CompletableFuture.supplyAsync(() -> search(q, diag), multiSearchPool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(configService.getInt("retrieval.searchTimeoutMs", 8000), TimeUnit.MILLISECONDS);
            // 合并：同一 knowledgeId 保留 score 最高者（跨 query 分数同体系可直接 max）
            Map<String, Hit> merged = new LinkedHashMap<>();
            for (CompletableFuture<List<Hit>> f : futures) {
                List<Hit> hits = f.isDone() ? f.getNow(List.of()) : List.of();
                for (Hit h : hits) {
                    merged.merge(h.knowledgeId(), h, (a, b) -> a.score() >= b.score() ? a : b);
                }
            }
            List<Hit> result = new ArrayList<>(merged.values());
            result.sort((a, b) -> Double.compare(b.score(), a.score()));
            return result;
        } catch (Exception e) {
            // L1 fail-loud：多路检索超时/失败，降级首路（不再静默）
            if (diag != null) diag.multiTimeout();
            log.warn("[FAIL-LOUD] 多路检索超时/失败，降级首路: {}", e.getMessage());
            return search(qs.get(0), diag);
        }
    }

    /**
     * 向量召回（独立方法，供检索调试复用）
     */
    public List<Document> vectorSearch(String query) {
        return vectorSearch(query, null);
    }

    /** 带诊断的向量召回（fail-loud：失败写入 diag） */
    public List<Document> vectorSearch(String query, RetrievalDiag diag) {
        // 全量重嵌入期间（任一实例执行 DROP/重建索引中）：向量索引不存在或半成品，
        // 直接跳过向量路（安静降级关键词路），避免对半成品索引检索产生错误/空召回与噪音告警
        if (reembedInProgress()) {
            log.debug("[RAG] 全量重嵌入进行中，向量路本次跳过（关键词路继续）");
            return List.of();
        }
        try {
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    // topK 直接取配置（默认 15，下限 1）：评估扫参需要小于 15 的值，max(15,...) 钳制会让扫参等价
                    .topK(Math.max(1, configService.getInt("retrieval.vectorTopK", 15)))
                    // 阈值以 DB 键 retrieval.vecThreshold 为准（0~1 白名单校验，评估"应用此组"可写）；
                    // 不设 yml 上限钳制——0.5+ 区间对扫参/精调是有效区间，钳制会让配置静默失效
                    .similarityThreshold(vecThreshold())
                    .build();
            return vectorStore.similaritySearch(req);
        } catch (Exception e) {
            // M4 fail-loud：向量路失败不再静默空
            if (diag != null) diag.vectorFailed(e.getMessage());
            log.warn("[FAIL-LOUD] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索：按 keyword.engine 分派——
     * meilisearch（可用时）走外部索引（中文分词 + 相关度打分）；否则/不可用时降级 MySQL 词元 LIKE。
     * 两条实现返回同一契约：List&lt;AiKnowledge&gt; 且已填充 kwScore/titleHit/hitTerms/totalTerms。
     */
    public List<AiKnowledge> keywordSearch(String query) {
        return keywordSearch(query, null);
    }

    /** 带诊断的关键词检索（fail-loud：Meili 不可用/降级 MySQL/繁忙跳过写入 diag） */
    public List<AiKnowledge> keywordSearch(String query, RetrievalDiag diag) {
        List<String> terms = keywordExtractor.extract(query);
        if (terms.isEmpty()) return List.of();
        // LIMIT 必须在调用线程求值：supplyAsync 内跑在 commonPool 线程，ThreadLocal 参数覆盖（评估扫参）传不进去
        int limit = keywordLimit();
        if (keywordIndexService.isAvailable()) {
            List<AiKnowledge> hits = keywordSearchMeili(query, terms, limit);
            if (!hits.isEmpty()) return hits;
            // 索引空/未重建时不静默返回空，回退 MySQL 保证召回（首次切换引擎未 reindex 的常见场景）
            // L2：仅调试展示，不扰用户
            if (diag != null) diag.keywordFallback();
            log.debug("[Keyword] Meilisearch 无命中，回退 MySQL 关键词召回");
        } else {
            // M13 fail-loud：Meili 不可用（探测失败/401/冷却期）→ 降级 MySQL 要标记
            if (diag != null) diag.keywordFailed();
            log.warn("[FAIL-LOUD] Meilisearch 不可用，关键词降级 MySQL LIKE（{}）", keywordIndexService.debugUnavailableReason());
        }
        return keywordSearchMysql(terms, limit, diag);
    }

    /**
     * Meilisearch 关键词召回：索引取 id + 相关度 → 批量载回实体（@TableLogic 自动过滤逻辑删除）
     * → 剔除非生效文档的块（docId 为空的手动知识块保留，与 MySQL 路 isNull(doc_id) 语义一致）
     * → 保持索引给出的相关度顺序，截到 limit
     */
    private List<AiKnowledge> keywordSearchMeili(String query, List<String> terms, int limit) {
        // 过量取回（×2）为状态过滤留余量，避免被弃用/解析中文档的块挤掉有效命中
        // 传 jieba 分词词元而非原始问句：Meili 默认 matchingStrategy=last（首词必须命中，再从末尾逐词删减），
        // 且中文在 Meili 里按字符切 token——直接传原句时，首字（如"怎/如/什"）不在语料中就会直接 0 命中。
        // 词元以空格分隔可形成正确 token 边界；词元为空时回退原句。
        String meiliQuery = (terms == null || terms.isEmpty()) ? query : String.join(" ", terms);
        List<KeywordIndexService.ScoredId> scored = keywordIndexService.search(meiliQuery, Math.max(limit * 2, limit));
        if (scored.isEmpty()) return List.of();
        Map<String, Double> scoreById = new LinkedHashMap<>();
        for (KeywordIndexService.ScoredId s : scored) scoreById.put(s.id(), s.score());
        List<AiKnowledge> loaded;
        try {
            loaded = knowledgeMapper.selectBatchIds(scoreById.keySet());
        } catch (Exception e) {
            log.warn("[Keyword] 批量载回知识块失败，降级 MySQL: {}", e.getMessage());
            return List.of();
        }
        if (loaded.isEmpty()) return List.of();
        Map<String, AiKnowledge> byId = loaded.stream()
                .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
        Set<String> blockedDocIds = loadNonRetrievableDocIds(byId);

        List<AiKnowledge> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : scoreById.entrySet()) {
            if (result.size() >= limit) break;
            AiKnowledge k = byId.get(e.getKey());
            if (k == null) continue; // 索引有、库已删（漂移）：跳过，reindex 可修正
            if (k.getStatus() != null && k.getStatus() == 1) continue; // 块级停用
            String docId = k.getDocId() == null ? null : String.valueOf(k.getDocId());
            if (docId != null && !docId.isBlank() && blockedDocIds.contains(docId)) continue;
            k.setKwScore(e.getValue());   // Meilisearch _rankingScore 已是 0~1 绝对分，直接进融合
            fillTermStats(k, terms);
            result.add(k);
        }
        return result;
    }

    /** 回填词元命中统计（titleHit 参与融合的标题奖励；hitTerms/totalTerms 供检索调试展示） */
    private void fillTermStats(AiKnowledge k, List<String> terms) {
        int hit = 0;
        for (String term : terms) {
            if (countOccurrences(k.getContent(), term) > 0 || countOccurrences(k.getTitle(), term) > 0) hit++;
        }
        k.setHitTerms(hit);
        k.setTotalTerms(terms.size());
        k.setTitleHit(k.getTitle() != null && terms.stream().anyMatch(k.getTitle()::contains));
    }

    /**
     * MySQL 关键词召回（降级路径）：词元 OR LIKE（content/title），自动排除非生效文档；
     * 命中后按词频加权（tf×idf，标题词频×2）在命中集内归一化到 kwScore（0~1）。
     * 注意：LIKE 无法走索引，知识块量大时依赖 keywordTimeoutMs 超时兜底。
     */
    private List<AiKnowledge> keywordSearchMysql(List<String> terms, int limit) {
        return keywordSearchMysql(terms, limit, null);
    }

    /** 带诊断的 MySQL 关键词召回（fail-loud：繁忙/超时跳过整路写入 diag） */
    private List<AiKnowledge> keywordSearchMysql(List<String> terms, int limit, RetrievalDiag diag) {
        Future<List<AiKnowledge>> future;
        try {
            future = keywordFallbackPool.submit(() -> {
                // WHERE doc_id IN (生效文档) AND ((content LIKE ? OR title LIKE ?) OR ...)
                QueryWrapper<AiKnowledge> wrapper = new QueryWrapper<AiKnowledge>()
                        .and(w -> w.inSql("doc_id", "SELECT id FROM c_ai_document WHERE status=0 AND deleted=0")
                                .or().isNull("doc_id"))
                        // 块级停用过滤（status 默认 0；NULL 兼容存量行）
                        .and(w -> w.eq("status", 0).or().isNull("status"));
                // 词元之间必须是 OR（任一命中即可）——用 or(Consumer) 承载后续词元。
                // 注意不能写成 `w.or(); w.and(t -> ...)`：and(Consumer) 会强制以 AND 连接该嵌套段，
                // 把前一个 or() 覆盖掉，词元被 AND 串联（词元越多命中越少，长问句直接 0 命中）。
                wrapper.and(w -> {
                    for (int i = 0; i < terms.size(); i++) {
                        String term = terms.get(i);
                        if (i == 0) {
                            w.and(t -> t.like("content", term).or().like("title", term));
                        } else {
                            w.or(t -> t.like("content", term).or().like("title", term));
                        }
                    }
                });
                wrapper.last("LIMIT " + limit);
                List<AiKnowledge> hits = knowledgeMapper.selectList(wrapper);
                if (hits.isEmpty()) return hits;
                return scoreKeywordHits(hits, terms);
            });
        } catch (RejectedExecutionException e) {
            // 队列已满（LIKE 查询积压）或已停机：跳过关键词路，向量路结果照常返回（fail-loud 标记）
            if (diag != null) diag.keywordBusy();
            log.warn("[FAIL-LOUD] 关键词降级检索繁忙，本次跳过关键词召回");
            return List.of();
        }
        try {
            return future.get(keywordTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 取消：未开始的任务直接出队，避免超时后仍堆积无人取用的慢 LIKE 查询
            future.cancel(true);
            // M4 fail-loud：关键词路失败/超时不再静默空
            if (diag != null) diag.keywordBusy();
            log.warn("[FAIL-LOUD] 关键词检索失败/超时: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 词频加权打分（A3）：tf×idf，标题词频×2，min(tf,3) 封顶防极端词频；命中集内归一化到 0~1
     */
    private List<AiKnowledge> scoreKeywordHits(List<AiKnowledge> hits, List<String> terms) {
        // 词元在命中集内的文档频率（IDF 用）
        Map<String, Integer> df = new HashMap<>();
        for (AiKnowledge k : hits) {
            for (String term : terms) {
                if (countOccurrences(k.getContent(), term) > 0 || countOccurrences(k.getTitle(), term) > 0) {
                    df.merge(term, 1, Integer::sum);
                }
            }
        }
        double maxScore = 0;
        for (AiKnowledge k : hits) {
            int hitTermCount = 0;
            double score = 0;
            for (String term : terms) {
                int tf = countOccurrences(k.getContent(), term) + 2 * countOccurrences(k.getTitle(), term);
                if (tf == 0) continue;
                hitTermCount++;
                double idf = Math.log(1 + (double) hits.size() / Math.max(1, df.getOrDefault(term, 1)));
                score += Math.min(tf, 3) * idf;
            }
            k.setHitTerms(hitTermCount);
            k.setTotalTerms(terms.size());
            k.setTitleHit(k.getTitle() != null && terms.stream().anyMatch(k.getTitle()::contains));
            k.setKwScore(score);
            maxScore = Math.max(maxScore, score);
        }
        // 归一化 0~1（最大加权分映射为 1）
        for (AiKnowledge k : hits) {
            k.setKwScore(maxScore > 0 ? k.getKwScore() / maxScore : 0);
        }
        return hits;
    }

    /**
     * 统计子串出现次数（不重叠）
     */
    private int countOccurrences(String text, String term) {
        if (text == null || text.isEmpty() || term == null || term.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /**
     * 分块位置奖励（A5）：文档首块小幅加分，靠近开头微加，中部不奖励（相对降权）
     */
    private double positionBonus(Integer chunkIndex) {
        if (chunkIndex == null) return 0;
        if (chunkIndex == 0) return configService.getDouble("retrieval.positionBonus", 0.03);
        if (chunkIndex <= 2) return configService.getDouble("retrieval.sectionBonus", 0.01);
        return 0;
    }

    private double parseScore(Double score) {
        return score == null ? 0 : score;
    }

    private Hit buildHit(Document doc, AiKnowledge k, String kid, double score) {
        Map<String, Object> md = doc.getMetadata();
        String docId = metadataDocId(doc);
        String title = md.get("title") == null ? "" : String.valueOf(md.get("title"));
        List<String> images = imagesFromMd(md);
        Integer chunkIndex = null;
        String titlePath = null;
        // M6 RedisVectorStore 可能丢弃 metadata（docId/title/images 均可能为空），用批量加载的知识块兜底
        if (k != null) {
            if (docId == null || docId.isEmpty()) docId = String.valueOf(k.getDocId());
            if (title.isEmpty()) title = k.getTitle();
            if (chunkIndex == null) chunkIndex = k.getChunkIndex();
            if (titlePath == null || titlePath.isBlank()) titlePath = k.getTitlePath();
            if (images.isEmpty() && k.getImages() != null && !k.getImages().isBlank()) {
                images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
            }
        }
        if (titlePath == null) {
            Object tp = md.get("titlePath");
            titlePath = tp == null ? null : String.valueOf(tp);
        }
        if (docId == null) docId = "";
        return new Hit(kid, docId, title, doc.getText(), images, score, chunkIndex, titlePath);
    }

    /**
     * 批量加载向量命中的知识块（一次 selectBatchIds，替代逐条 selectById；失败返回空 Map 走原降级）
     */
    private Map<String, AiKnowledge> loadKnowledgeBatch(List<Document> vectorDocs) {
        if (vectorDocs == null || vectorDocs.isEmpty()) return Map.of();
        List<String> ids = vectorDocs.stream()
                .map(d -> String.valueOf(d.getId()))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        try {
            return knowledgeMapper.selectBatchIds(ids).stream()
                    .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
        } catch (Exception e) {
            log.warn("批量加载知识块元数据失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 不可召回文档 id 集合（status<>0：弃用 1 / 解析中 2 / 解析失败 3），用于向量路过滤；
     * 关键词路已按 status=0 过滤，此处保证两条召回路径语义一致
     * （尤其：重解析 diff 复用保留旧向量、崩溃残留半成品，其向量不应进入上下文）
     */
    private Set<String> loadNonRetrievableDocIds(Map<String, AiKnowledge> kidMap) {
        Set<String> docIds = kidMap.values().stream()
                .map(AiKnowledge::getDocId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
        if (docIds.isEmpty()) return Set.of();
        try {
            List<AiDocument> blocked = documentMapper.selectList(
                    new QueryWrapper<AiDocument>()
                            .select("id")
                            .in("id", docIds)
                            .ne("status", 0)
                            .eq("deleted", 0));
            return blocked.stream().map(d -> String.valueOf(d.getId())).collect(Collectors.toSet());
        } catch (Exception e) {
            // fail-loud：DB 过滤查询失败不能静默放行（弃用/解析中/失败文档的向量会进上下文）
            log.error("[FAIL-LOUD] 查询非生效文档失败，回退内存全量过滤: {}", e.getMessage());
            try {
                // 降级方案：查全部候选 docId（id,status），内存过滤非生效（status!=0）
                List<AiDocument> all = documentMapper.selectList(
                        new QueryWrapper<AiDocument>().select("id", "status").in("id", docIds));
                return all.stream().filter(d -> d.getStatus() != null && d.getStatus() != 0)
                        .map(d -> String.valueOf(d.getId()))
                        .collect(Collectors.toSet());
            } catch (Exception ex) {
                // 双重失败：放行但 error 级告警（不再静默；diag 透传见 M4）
                log.error("[FAIL-LOUD] 内存过滤也失败，非生效文档可能进入上下文: {}", ex.getMessage());
                return Set.of();
            }
        }
    }

    private String metadataDocId(Document doc) {
        Object v = doc.getMetadata().get("docId");
        return v == null ? null : String.valueOf(v);
    }

    private Hit buildHit(AiKnowledge k, double score) {
        List<String> images = new ArrayList<>();
        if (k.getImages() != null && !k.getImages().isBlank()) {
            try {
                images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
            } catch (Exception e) {
                images = List.of();
            }
        }
        return new Hit(String.valueOf(k.getId()), String.valueOf(k.getDocId()),
                k.getTitle(), k.getContent(), images, score, k.getChunkIndex(), k.getTitlePath());
    }

    private List<String> imagesFromMd(Map<String, Object> md) {
        Object v = md.get("images");
        if (v == null) return List.of();
        try {
            return com.alibaba.fastjson2.JSON.parseArray(String.valueOf(v), String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
