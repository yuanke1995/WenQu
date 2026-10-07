package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.util.RequestUser;
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
 * - 融合：向量分 × 向量权重 + 关键词分 × 关键词权重（加权和），双命中叠加各自分量
 * 权重来自 DB 配置 retrieval.*（设置页保存即生效；默认 0.6/0.4）
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

    private final KbVectorStoreRegistry kbVectorStores;
    private final KnowledgeMapper knowledgeMapper;
    /** 附加索引命中解析：向量命中的 id 不是知识块 id 时，经 QA 问法表 / 子块表解析回来源块 */
    private final com.wenqu.ai.mapper.KnowledgeQaMapper knowledgeQaMapper;
    private final com.wenqu.ai.mapper.KnowledgeChildMapper knowledgeChildMapper;
    private final AiDocumentMapper documentMapper;
    private final KeywordExtractor keywordExtractor;
    private final ConfigService configService;
    private final KeywordIndexService keywordIndexService;
    private final StringRedisTemplate redisTemplate;
    private final ResourceVisibilityService resourceVisibilityService;
    /** 知识库共享范围：检索过滤要按"文档 + 所属库"两级判定（与管理接口同口径） */
    private final KnowledgeBaseService knowledgeBaseService;
    /** P1 GraphRAG 图扩展（收口处调用；GraphRagService 不反向依赖本服务，无环） */
    private final GraphRagService graphRagService;
    /** ACL 标签编译：把可见库集合 + 当前身份编译成向量索引的 filter 表达式（检索时过滤） */
    private final DocumentAclTags documentAclTags;

    /** 全量重嵌入进行中（持有分布式锁的实例正在 DROP/重建向量索引）→ 各实例向量路跳过降级关键词 */
    private boolean reembedInProgress() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(DocumentService.REEMBED_LOCK_KEY));
        } catch (Exception e) {
            return false; // Redis 异常按未锁定处理（向量检索自身仍有 try-catch 兜底）
        }
    }

    /**
     * 混合检索结果（titlePath 章节路径，检索侧拼装上下文用）。
     * rerankScore：重排模型相关度分（0~1，relevance_score），仅重排实际执行后由 RerankService 回填；
     * 未重排时为 null——score 始终是融合分（向量+关键词加权），两者口径不同，引用来源分别透出。
     */
    public record Hit(String knowledgeId, String docId, String title, String content,
                      List<String> images, double score, Integer chunkIndex, String titlePath,
                      Double rerankScore) {
        /** 回填重排分（其余字段原样拷贝；record 等值含大文本字段，重排排序直接用该分值比较） */
        public Hit withRerankScore(double rerankScore) {
            return new Hit(knowledgeId, docId, title, content, images, score, chunkIndex, titlePath, rerankScore);
        }
    }

    /**
     * 检索诊断（fail-loud：单路失败/降级标记，随检索结果透传给调用方上报 degradations）。
     * 只写不入日志，避免热路径日志噪音；由 RagService 统一转成回答级警示。
     */
    public static final class RetrievalDiag {
        private boolean vectorFailed;
        /** 多库检索中某库失败（其余库仍正常返回）——与 vectorFailed 互斥：后者是单库向量路整体失败 */
        private boolean vectorPartialFailed;
        private boolean keywordFailed;   // Meili 不可用（探测失败/冷却/401）→ 本次降级 MySQL LIKE
        private boolean keywordBusy;     // 关键词降级检索繁忙/超时 → 本次跳过关键词路
        private boolean keywordFallback; // Meili 无命中回退 MySQL（仅调试面板展示，不扰用户）
        private boolean multiTimeout;    // 多路检索超时/失败 → 降级首路/仅用已完成结果
        private String lastError;
        private int graphExpanded;       // P1：图扩展并入的块数（GraphRAG 开启的库才有值）
        private String aclPushdownError; // ACL 过滤条件下推失败（已退回事后过滤兜底，非越权但召回受损）

        void vectorFailed(String err) { this.vectorFailed = true; this.lastError = err; }
        void vectorPartialFailed(String err) { this.vectorPartialFailed = true; if (this.lastError == null) this.lastError = err; }
        void keywordFailed() { this.keywordFailed = true; }
        void keywordBusy() { this.keywordBusy = true; }
        void keywordFallback() { this.keywordFallback = true; }
        void multiTimeout() { this.multiTimeout = true; }
        /** 库门下推失败（记 error 而非布尔：既要让调试面板看到原因，也避免多字段互相覆盖）。字段名沿用 acl* 前缀，属既有 API 契约不更名 */
        void aclPushdownFailed(String err) { this.aclPushdownError = err; }

        /** 清空本次诊断（改写回退/二次检索前调用：最终用于回答的那次检索的状态为准） */
        void reset() {
            this.vectorFailed = false;
            this.vectorPartialFailed = false;
            this.keywordFailed = false;
            this.keywordBusy = false;
            this.keywordFallback = false;
            this.multiTimeout = false;
            this.lastError = null;
            this.aclPushdownError = null;
        }

        public boolean isVectorFailed() { return vectorFailed; }
        public boolean isVectorPartialFailed() { return vectorPartialFailed; }
        public boolean isKeywordFailed() { return keywordFailed; }
        public boolean isKeywordBusy() { return keywordBusy; }
        public boolean isKeywordFallback() { return keywordFallback; }
        public boolean isMultiTimeout() { return multiTimeout; }
        public String lastError() { return lastError; }
        public int getGraphExpanded() { return graphExpanded; }
        void addGraphExpanded(int n) { this.graphExpanded += n; }
        /** 库门下推失败原因（null=本次下推正常）。字段名沿用 acl* 前缀，属既有 API 契约不更名 */
        public String aclPushdownError() { return aclPushdownError; }
    }

    /**
     * 混合检索：返回按融合分降序的结果（已过滤弃用文档）。旧签名委托，外部调用方不受影响。
     */
    public List<Hit> search(String query) {
        return search(query, null);
    }

    /** 带诊断的混合检索（fail-loud：单路失败/降级写入 diag，由调用方转回答级警示）；kbIds 限定检索的知识库（空=全部） */
    public List<Hit> search(String query, RetrievalDiag diag) {
        return search(query, diag, null);
    }

    /**
     * 混合检索；{@code kbIds} 限定检索的知识库（空=全部）。
     * <p><b>不启用权限补采</b>（{@code adaptiveTopK=false}）——等价于改动前的行为，供量化评估
     * （{@code RetrievalEvaluationService}）与检索调试面板使用：它们比较的是检索策略本身，
     * 补采会改变 topK 语义、让跨轮基线不可比（评测基线是调 vectorWeight/阈值后重标定的锚点）。
     */
    public List<Hit> search(String query, RetrievalDiag diag, java.util.Collection<String> kbIds) {
        return search(query, diag, kbIds, false);
    }

    /**
     * @param kbIds 限定检索的知识库（空=全部）
     * @param adaptiveTopK 是否启用「权限有效召回」补采（见 {@link #vectorSearch}）。
     *        <b>{@code false} 时严格按配置 topK 取一次</b>——量化评估与检索调试必须走此路。
     *        生产问答走 {@code true}，让权限过滤参与配额计算。
     */
    public List<Hit> search(String query, RetrievalDiag diag, java.util.Collection<String> kbIds,
            boolean adaptiveTopK) {
        // 权重动态读取（DB 配置，保存即生效；缺失时兜底 yml 默认值 0.6/0.4/0.1）
        double vectorWeight = configService.getDouble("retrieval.vectorWeight");
        double keywordWeight = configService.getDouble("retrieval.keywordWeight");

        // 可见范围过滤（资源共享范围）：当前用户不可见的文档在向量/关键词两路统一剔除
        // 1. 向量召回（放大召回率；按目标知识库的向量模型分组逐库检索后合并）
        //    adaptiveTopK=true 时按「权限有效召回目标」补采（生产问答）；false 时严格按配置 topK 一次（评估/调试）
        //    补采还受 retrieval.aclTopKSupplant 总闸约束：应急阀，管理员可在设置页关掉（见下方 KNN 成本说明）
        boolean supplement = adaptiveTopK && configService.getBoolean("retrieval.aclTopKSupplant", true);
        List<Document> vectorDocs = supplement
                ? vectorSearch(query, diag, kbIds, Math.max(1, configService.getInt("retrieval.vectorTopK", 15)))
                : vectorSearch(query, diag, kbIds, 0);

        // 2. 关键词召回（并行，超时兜底）
        List<Knowledge> kwDocs = keywordSearch(query, diag);

        // 3. 批量加载向量命中的知识块元数据（一次 selectBatchIds 替代逐条 selectById）+ 不可召回文档集合
        Map<String, Knowledge> kidMap = loadKnowledgeBatch(vectorDocs);
        Set<String> blockedDocIds = loadNonRetrievableDocIds(kidMap);

        // 4. 可见范围过滤（**晚绑定**：召回后才判，只查「本轮命中的那些文档」）
        //    旧实现是召回前全表扫 c_ai_document 算出不可见集合——文档量涨上去就是每问一次全表，
        //    且 ACL 真相被复制进向量 metadata 造成两处口径（详见 DocumentAclTags 类注释）。
        //    现在只对命中块涉及的 docId 批量查一次（WHERE id IN (...)，主键索引、亚毫秒级），
        //    判定全部收敛到 ResourceVisibilityService 一处，改共享范围下次检索即生效、无需重写索引。
        Set<String> visibleDocIds = loadVisibleDocIdsOfHits(vectorDocs, kwDocs, kidMap);

        // 4. 合并去重 + 加权（向量权重 × 归一化向量分 + 关键词权重 × 词频加权分；双命中叠加）
        Map<String, Hit> merged = new LinkedHashMap<>();

        // 向量命中：score = 向量权重 × 归一化向量分；非生效文档（弃用/解析中/解析失败）跳过，与关键词路 status=0 语义一致
        // 附加索引命中（QA 问法/父子子块）：向量 id 不是知识块 id，kidMap 已解析回来源块——同一块可被块向量与
        // 多个附加向量同时命中，按 knowledgeId 合并保留最高分（与"双命中叠加"的关键词路互不干扰）
        double vt = vecThreshold();
        for (Document doc : vectorDocs) {
            Knowledge k = kidMap.get(String.valueOf(doc.getId()));
            boolean augHit = k != null && !String.valueOf(doc.getId()).equals(String.valueOf(k.getId()));
            String kid = augHit ? String.valueOf(k.getId()) : String.valueOf(doc.getId());
            String docId = k != null && k.getDocId() != null ? String.valueOf(k.getDocId()) : metadataDocId(doc);
            if (docId != null && blockedDocIds.contains(docId)) {
                log.debug("[RAG] 跳过非生效文档命中: docId={} kid={}", docId, kid);
                continue;
            }
            // 可见范围：白名单判定（docId 为空=手动知识块，不在文档 ACL 管辖内，保留）
            if (docId != null && !docId.isBlank() && !visibleDocIds.contains(docId)) {
                log.debug("[RAG] 跳过不可见文档命中: docId={} kid={}", docId, kid);
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
            Hit hit = augHit ? buildAugHit(doc, k, kid, score) : buildHit(doc, k, kid, score);
            merged.merge(kid, hit, (a, b) -> a.score() >= b.score() ? a : b);
        }
        // 关键词命中：score = 关键词权重 × 词频加权分；与向量命中叠加（相加）
        for (Knowledge k : kwDocs) {
            // 可见范围：白名单判定（与向量路同口径；docId 为空=手动块，不在文档 ACL 管辖内）
            String kDocId = k.getDocId() == null ? "" : String.valueOf(k.getDocId());
            if (!kDocId.isBlank() && !visibleDocIds.contains(kDocId)) {
                log.debug("[RAG] 跳过不可见文档命中（关键词路）: docId={} kid={}", kDocId, k.getId());
                continue;
            }
            double hitRate = k.getKwScore(); // 词频加权归一化分（0~1，替代原词元占比）
            double score = keywordWeight * hitRate;
            merged.merge(k.getId(), buildHit(k, score), (oldHit, newHit) ->
                    new Hit(oldHit.knowledgeId(),
                            oldHit.docId() == null || oldHit.docId().isBlank() ? newHit.docId() : oldHit.docId(),
                            oldHit.title(), oldHit.content(), oldHit.images(),
                            oldHit.score() + newHit.score(), // A1：双命中叠加
                            oldHit.chunkIndex() == null ? newHit.chunkIndex() : oldHit.chunkIndex(),
                            oldHit.titlePath() == null ? newHit.titlePath() : oldHit.titlePath(),
                            null));
        }

        List<Hit> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        // P1 GraphRAG 图扩展：按库级开关一跳补块（真实块衰减分并入，diag.graphExpanded 计数）
        try {
            result = graphRagService.expand(result, kbIds, diag);
        } catch (Exception e) {
            log.warn("[RAG] 图扩展失败（不影响主检索结果）: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 多路并行检索线程池（daemon，供深度思考多路检索用）。线程数随 chat.pipelineThreads 联动
     * （searchMulti 入口对齐）：深度思考每轮最多并行 maxSubQueries 路，固定 4 线程在百级并发下
     * 让多路检索退化成串行、整段撞 8s 总超时后只保留首路——多路召回名存实亡
     */
    private ThreadPoolExecutor multiSearchPool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "multi-search");
        t.setDaemon(true);
        return t;
    });

    /** 与 chat.pipelineThreads 对齐（每次多路检索前校一次，int 比较开销可忽略）：单路检索是
     *  亚秒~秒级任务，给流水线同等并发即可让 N 路 × M 轮的任务波次摊平 */
    private void syncMultiSearchSize() {
        int n = Math.max(4, configService.getInt("chat.pipelineThreads", 32));
        if (n == multiSearchPool.getCorePoolSize()) return;
        multiSearchPool.setMaximumPoolSize(Math.max(multiSearchPool.getMaximumPoolSize(), n));
        multiSearchPool.setCorePoolSize(n);
        multiSearchPool.setMaximumPoolSize(n);
    }

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
        return searchMulti(queries, diag, null);
    }

    public List<Hit> searchMulti(List<String> queries, RetrievalDiag diag, java.util.Collection<String> kbIds) {
        return searchMulti(queries, diag, kbIds, false);
    }

    /**
     * 多路检索；{@code adaptiveTopK} 见 {@link #search(String, RetrievalDiag, java.util.Collection, boolean)}。
     * <p><b>多路的补采按路数收敛</b>：≤2 路开（每路补 1 次、最坏 topK 翻倍，总 KNN 成本 ≤4 倍，
     * 且多路本就为拆子问题取多样性，收益最大）；≥3 路不开——N 路同步放大 KNN 而
     * {@code multiSearchPool} 线程有限（随 chat.pipelineThreads 联动，终归有上界），成本乘路数后超时风险陡增，此时归并后的多路共识与
     * 多样性收益已能覆盖名额损失。
     */
    public List<Hit> searchMulti(List<String> queries, RetrievalDiag diag,
            java.util.Collection<String> kbIds, boolean adaptiveTopK) {
        if (queries == null || queries.isEmpty()) return List.of();
        List<String> qs = queries.stream().map(String::trim).filter(q -> !q.isBlank()).distinct().toList();
        if (qs.size() <= 1) {
            return qs.isEmpty() ? List.of() : search(qs.get(0), diag, kbIds, adaptiveTopK);
        }
        syncMultiSearchSize();
        try {
            // 本轮参数覆盖（全局 < 知识库 < 智能体的合并结果）：ThreadLocal 不随任务提交跨线程继承，
            // 必须在提交前取快照、在子线程内重放；否则多路并行检索静默退化为全局配置（库级/智能体级策略全丢）
            Map<String, String> runOverrides = configService.currentOverrides();
            // 多路是否开补采：按「路数 × 补采倍数」的总 KNN 成本设闸。
            // 补采只补 1 次但 topK 最高放大到 MAX_TOPK_CAP(=60)，即单路最坏 2 倍 KNN；
            // N 路并行时总成本是 N×2 倍，而 multiSearchPool 只有 4 线程——4 路开补采等于把
            // 向量路 KNN 打满 8 倍，超时风险陡增。故按路数收敛：≤2 路开（成本可控且收益最大，
            // 拆子问题本就为多样性），≥3 路不开（此时归并后的多路共识与多样性收益已能覆盖名额损失）。
            boolean multiSupplement = adaptiveTopK && qs.size() <= 2;
            List<CompletableFuture<List<Hit>>> futures = qs.stream()
                    .map(q -> CompletableFuture.supplyAsync(
                            () -> runWithOverrides(runOverrides, () -> search(q, diag, kbIds, multiSupplement)),
                            multiSearchPool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(configService.getInt("retrieval.searchTimeoutMs", 8000), TimeUnit.MILLISECONDS);
            // 合并：同一 knowledgeId 保留 score 最高者（跨 query 分数同体系可直接 max），
            // 并统计每块被几个子查询命中——多路共识是强相关信号：只被一路命中的块可能是某次
            // 改写的偶然召回，而多路都命中说明该块与问题的多个侧面都相关。
            double consensusBonus = configService.getDouble("retrieval.multiConsensusBonus", 0.05);
            Map<String, Hit> merged = new LinkedHashMap<>();
            Map<String, Integer> hitByQueries = new HashMap<>();
            for (CompletableFuture<List<Hit>> f : futures) {
                List<Hit> hits = f.isDone() ? f.getNow(List.of()) : List.of();
                for (Hit h : hits) {
                    hitByQueries.merge(h.knowledgeId(), 1, Integer::sum);
                    merged.merge(h.knowledgeId(), h, (a, b) -> a.score() >= b.score() ? a : b);
                }
            }
            List<Hit> result = new ArrayList<>(merged.values());
            if (consensusBonus > 0) {
                // 加成 = (命中路数-1) × bonus，封顶 2×bonus（3 路以上不再增长，防极端放大挤掉高分块）
                for (int i = 0; i < result.size(); i++) {
                    Hit h = result.get(i);
                    int count = hitByQueries.getOrDefault(h.knowledgeId(), 1);
                    if (count >= 2) {
                        double bonus = Math.min(count - 1, 2) * consensusBonus;
                        result.set(i, new Hit(h.knowledgeId(), h.docId(), h.title(), h.content(),
                                h.images(), h.score() + bonus, h.chunkIndex(), h.titlePath(), h.rerankScore()));
                        log.debug("[RAG] 多路共识加分: kid={} 命中路数={} bonus={}", h.knowledgeId(), count, bonus);
                    }
                }
            }
            result.sort((a, b) -> Double.compare(b.score(), a.score()));
            return result;
        } catch (Exception e) {
            // L1 fail-loud：多路检索超时/失败，降级首路（不再静默）
            if (diag != null) diag.multiTimeout();
            log.warn("[FAIL-LOUD] 多路检索超时/失败，降级首路: {}", e.getMessage());
            return search(qs.get(0), diag, kbIds);
        }
    }

    /**
     * 在子线程内重放本轮参数覆盖后执行检索。
     * <p>覆盖值取自调用线程的快照（{@link ConfigService#currentOverrides()}），子线程用完必须清：
     * multiSearchPool 是复用池，残留会让下一轮请求继承上一轮的库/智能体检索策略。
     */
    private List<Hit> runWithOverrides(Map<String, String> overrides, java.util.function.Supplier<List<Hit>> task) {
        if (overrides == null || overrides.isEmpty()) return task.get();
        configService.putOverrides(overrides);
        try {
            return task.get();
        } finally {
            configService.clearOverride();
        }
    }

    /**
     * 向量召回（独立方法，供检索调试复用）
     */
    public List<Document> vectorSearch(String query) {
        return vectorSearch(query, null, null);
    }

    /**
     * 带诊断的向量召回（fail-loud：单路失败/降级写入 diag）；kbIds 限定知识库（空=全部库分组检索）。
     * <p><b>严格按配置 topK 取一次，不补采</b>——调试面板要展示的就是「当前配置下的真实召回」，
     * 补采会掩盖 topK 被权限吃掉的事实。需要生产问答的补采行为请走 {@link #search(String, RetrievalDiag, java.util.Collection, boolean)}。
     */
    public List<Document> vectorSearch(String query, RetrievalDiag diag, java.util.Collection<String> kbIds) {
        return vectorSearch(query, diag, kbIds, 0);
    }

    /**
     * 向量召回；{@code minWantedHits} = 通过可见性判定的目标条数（&lt;=0 表示不补采，按配置 topK 取一次）。
     *
     * <p><b>为什么需要补采（治本点）</b>：文档级 ACL 是<b>晚绑定</b>——向量路先按 topK 取候选，
     * 再由 {@link #loadVisibleDocIdsOfHits} 剔除无权文档。于是「topK 配额」被同库内的无权文档占掉，
     * 用户实际拿到的有效命中少于 topK（私有文档占比越高损失越大），而系统以为召回正常。
     * 本方法把固定配额改成<b>「要够 N 条有效结果才停」</b>：先按配置 topK 取一次，
     * 若因权限被剔除导致有效条数不足，按实测保留率放大 topK <b>补采一次</b>，过滤仍由调用方做。
     * 权限过滤从「削减结果」变成「参与配额计算」。
     *
     * <p><b>为什么不做「文档级 ACL 下推索引」</b>（2026-10-06 复核否决，见 cd57b47）：
     * 下推要求权限变更时重写向量标签，而标签陈旧即越权；且要覆盖 6 处写入点 + QA/子块附加索引，
     * 极易做漏。补采是纯读侧优化，不碰任何写入路径，风险面小得多。
     *
     * <p><b>边界（安全与成本）</b>：
     * <ul>
     *   <li>最多补采 <b>1 次</b>，不递归——避免可见集合极小时反复放大把 KNN 打满；</li>
     *   <li>放大后 topK 有硬上限 {@value #MAX_TOPK_CAP}；</li>
     *   <li>只在<b>确实有东西被剔除且仍不足</b>时补：全部可见（保留率=1）不放大；
     *       <b>全被剔除（保留率=0）也不放大</b>——那是「该用户对命中库无权」，属权限配置/可见集合问题，
     *       放大 topK 既无意义（还是那些文档）又像在掩盖问题；</li>
     *   <li>计数失败不补采（保持初召回）：补采是<em>优化</em>不是安全手段，权限判定始终由晚绑定兜底。</li>
     * </ul>
     */
    private List<Document> vectorSearch(String query, RetrievalDiag diag,
            java.util.Collection<String> kbIds, int minWantedHits) {
        // 全量重嵌入期间（任一实例执行 DROP/重建索引中）：向量索引不存在或半成品，
        // 直接跳过向量路（安静降级关键词路），避免对半成品索引检索产生错误/空召回与噪音告警
        if (reembedInProgress()) {
            log.debug("[RAG] 全量重嵌入进行中，向量路本次跳过（关键词路继续）");
            return List.of();
        }
        List<VectorStore> stores = resolveVectorStores(kbIds);
        if (stores.isEmpty()) return List.of();
        // 可见范围过滤条件（检索时过滤）：可见库集合 + 当前用户身份，编译成 RediSearch filter 下推。
        // 必须在 resolveVectorStores 之后、similaritySearch 之前求值——下推靠它把无权文档挡在 KNN 之外。
        String aclFilter = buildAclFilter(kbIds, diag);
        try {
            int baseTopK = Math.max(1, configService.getInt("retrieval.vectorTopK", 15));
            List<Document> first = similaritySearchAcross(stores, query, aclFilter, baseTopK, diag);
            if (minWantedHits <= 0 || first.isEmpty()) return first;

            int visibleCount = countVisibleHits(first);
            if (visibleCount >= minWantedHits || visibleCount == 0) return first;

            // 保留率 = 有效/召回。visibleCount==0 已在上行 return，故除数必 >0
            double keepRate = (double) visibleCount / first.size();
            int target = Math.min((int) Math.ceil(baseTopK / keepRate), MAX_TOPK_CAP);
            if (target <= baseTopK) {
                log.debug("[RAG] 权限补采：放大后 topK={} 未超过 {} 上限，保持初召回（keepRate={}）",
                        target, MAX_TOPK_CAP, String.format("%.2f", keepRate));
                return first;
            }
            log.info("[RAG] 权限补采：有效命中 {}/{}（keepRate={}），topK {} → {} 补采一次",
                    visibleCount, first.size(), String.format("%.2f", keepRate), baseTopK, target);
            List<Document> second = similaritySearchAcross(stores, query, aclFilter, target, diag);
            return second.isEmpty() ? first : second;
        } catch (Exception e) {
            // M4 fail-loud：向量路失败不再静默空
            if (diag != null) diag.vectorFailed(e.getMessage());
            log.warn("[FAIL-LOUD] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 补采放大后的 topK 硬上限：防止「几乎全被剔除」时把 KNN 打成重负载 */
    private static final int MAX_TOPK_CAP = 60;

    /**
     * 统计本轮向量命中里<b>通过可见性判定</b>的条数（只计数，不做任何权限决策）。
     * 可见性唯一出口 = {@code ResourceVisibilityService}，此处只借用其判定做配额估算；
     * 最终过滤仍在 {@code search()} 内进行——补采只决定「多取多少」，绝不参与权限放行。
     */
    private int countVisibleHits(List<Document> vectorDocs) {
        try {
            Map<String, Knowledge> kidMap = loadKnowledgeBatch(vectorDocs);
            Set<String> visibleDocIds = loadVisibleDocIdsOfHits(vectorDocs, List.of(), kidMap);
            int n = 0;
            for (Document d : vectorDocs) {
                Knowledge k = kidMap.get(String.valueOf(d.getId()));
                String docId = k != null && k.getDocId() != null ? String.valueOf(k.getDocId()) : metadataDocId(d);
                // docId 为空 = 手动知识块，不在文档 ACL 管辖内，计为可见
                if (docId == null || docId.isBlank() || visibleDocIds.contains(docId)) n++;
            }
            return n;
        } catch (Exception e) {
            // 计数失败不进补采（保持初召回）——补采是优化不是安全手段
            log.debug("[RAG] 权限补采跳过：可见性计数失败 {}", e.getMessage());
            return 0;
        }
    }

    /** 按 topK 逐库检索并合并（多库时向量空间互不相通，必须逐库查后归并） */
    private List<Document> similaritySearchAcross(List<VectorStore> stores, String query,
            String aclFilter, int topK, RetrievalDiag diag) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                // topK 直接取配置（默认 15，下限 1）：评估扫参需要小于 15 的值，max(15,...) 钳制会让扫参等价
                .topK(Math.max(1, topK))
                // 阈值以 DB 键 retrieval.vecThreshold 为准（0~1 白名单校验，评估"应用此组"可写）；
                // 不设 yml 上限钳制——0.5+ 区间对扫参/精调是有效区间，钳制会让配置静默失效
                .similarityThreshold(vecThreshold());
        // 库门下推：检索时过滤的核心。放在 similarityThreshold 之后设置，两者互不影响。
        // 下推失败（索引缺字段/表达式非法）时退回无过滤检索 + 事后过滤，绝不因下推失败中断检索。
        if (aclFilter != null && !aclFilter.isBlank()) {
            try {
                builder = builder.filterExpression(aclFilter);
            } catch (Exception e) {
                if (diag != null) diag.aclPushdownFailed(e.getMessage());
                log.warn("[FAIL-LOUD] 库门过滤条件下推失败，本次退回无过滤向量检索（改由事后过滤兜底）: {}",
                        e.getMessage());
            }
        }
        SearchRequest req = builder.build();
        // 单库（绝大多数场景：无自定义向量模型库，或范围命中单一库）直接查，保持原行为
        if (stores.size() == 1) return stores.get(0).similaritySearch(req);
        // 多库：每库绑定的向量模型不同（向量空间互不相通），逐库检索后合并——同块保留最高分
        Map<String, Document> merged = new LinkedHashMap<>();
        for (VectorStore st : stores) {
            try {
                for (Document d : st.similaritySearch(req)) {
                    merged.merge(String.valueOf(d.getId()), d, (a, b) ->
                            parseScore(a.getScore()) >= parseScore(b.getScore()) ? a : b);
                }
            } catch (Exception e) {
                // 多库检索中单库失败：其余库继续返回，但必须把失败透传到 diag——否则用户侧降级提示被吞、
                // 静默丢掉该库向量召回（单库整体失败走外层 vectorFailed，二者互斥）
                if (diag != null) diag.vectorPartialFailed(e.getMessage());
                log.warn("[FAIL-LOUD] 向量检索失败（多库检索中某库失败，其余库继续）: {}", e.getMessage());
            }
        }
        List<Document> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Double.compare(parseScore(b.getScore()), parseScore(a.getScore())));
        return out;
    }

    /**
     * 目标知识库集合 → 去重后的向量库实例（每库按各自绑定的向量模型路由独立索引）。
     *
     * <p><b>为什么必须在建实例之前按可见性收窄（2026-10-06 修）</b>：
     * 原来 kbIds 为空时直接 {@code allStores()} 展开<b>全部</b>已绑模型的库，不看当前用户能不能读。
     * 后果不是「多搜了点无关内容」这么轻——向量空间互不相通，逐库检索意味着<b>每个库都要用自己绑定的
     * 向量模型把 query 嵌一遍</b>，于是：
     * <ol>
     *   <li><b>烧别人的额度</b>：A 用户提问会去调 B 用户知识库绑定的供应商，B 的账户被扣费、
     *       B 收到「额度不足」通知，而 A 全程不知情（实测：yuanke 名下库的嵌入模型额度耗尽后，
     *       admin 提问反复报「阿里百炼平台额度不足」，对话模型用的却是 admin 自己的 glm-5.3）；</li>
     *   <li><b>报错归属错位</b>：库里那套「通知归属人不是管理员」的设计是对的（供应商谁建归谁），
     *       但检索期越权代打把这条设计的前提破坏了——owner 从没 configuring 过这条链路，却在替别人背额度；</li>
     *   <li><b>拖慢每一轮问答</b>：无谓的嵌入往返 + 无谓的索引 KNN。</li>
     * </ol>
     *
     * <p><b>与库门过滤的关系</b>：{@link #buildAclFilter} 编译的 kbId 表达式只对<b>已发出去的那次检索</b>生效，
     * 它挡得住「yuanke 的块被返回给 admin」，却<b>挡不住这次检索本身发生</b>（嵌入请求在过滤前就发出去了）。
     * 可见性必须在<b>发起检索之前</b>收窄，过滤只是第二道防线、不能顶替这一步。
     *
     * <p><b>安全方向</b>：可见库查询失败（{@code loadVisibleKbIds()} 返回 null）时<b>不放行</b>——
     * 退化成「不搜任何库」，宁可本轮无召回也不越权代打他人额度（与库门表达式同一取向：
     * 空集=什么都搜得到是最危险的写法）。
     */
    private List<VectorStore> resolveVectorStores(java.util.Collection<String> kbIds) {
        Set<String> visibleKbIds = loadVisibleKbIds();
        if (visibleKbIds == null) {
            // 可见库判不出来 ⇒ 不猜、不放行：本轮不发起任何向量检索（fail-closed）
            log.error("[FAIL-LOUD] 可见库集合查询失败，本轮跳过向量检索（不代打无权库的向量模型）");
            return List.of();
        }
        // 请求范围（智能体/工具绑定）更窄时取交集：范围外的库既无权也不该被检索
        Set<String> targetKbIds = new LinkedHashSet<>();
        for (com.wenqu.ai.model.KnowledgeBase kb : kbVectorStores.customKbs()) {
            if (!visibleKbIds.contains(kb.getId())) continue;
            if (kbIds != null && !kbIds.isEmpty() && !kbIds.contains(kb.getId())) continue;
            targetKbIds.add(kb.getId());
        }
        java.util.LinkedHashMap<VectorStore, Boolean> out = new java.util.LinkedHashMap<>();
        for (String kbId : targetKbIds) {
            try {
                out.put(kbVectorStores.storeForKb(kbId), Boolean.TRUE);
            } catch (Exception e) {
                log.warn("[FAIL-LOUD] 知识库 {} 向量库构建失败（跳过该库）: {}", kbId, e.getMessage());
            }
        }
        return new ArrayList<>(out.keySet());
    }

    /**
     * 关键词检索：按 keyword.engine 分派——
     * meilisearch（可用时）走外部索引（中文分词 + 相关度打分）；否则/不可用时降级 MySQL 词元 LIKE。
     * 两条实现返回同一契约：List&lt;Knowledge&gt; 且已填充 kwScore/titleHit/hitTerms/totalTerms。
     */
    public List<Knowledge> keywordSearch(String query) {
        return keywordSearch(query, null);
    }

    /** 带诊断的关键词检索（fail-loud：Meili 不可用/降级 MySQL/繁忙跳过写入 diag） */
    public List<Knowledge> keywordSearch(String query, RetrievalDiag diag) {
        List<String> terms = keywordExtractor.extract(query);
        if (terms.isEmpty()) return List.of();
        // LIMIT 必须在调用线程求值：supplyAsync 内跑在 commonPool 线程，ThreadLocal 参数覆盖（评估扫参）传不进去
        int limit = keywordLimit();
        if (keywordIndexService.isAvailable()) {
            List<Knowledge> hits = keywordSearchMeili(query, terms, limit);
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
    private List<Knowledge> keywordSearchMeili(String query, List<String> terms, int limit) {
        // 过量取回（×2）为状态过滤留余量，避免被弃用/解析中文档的块挤掉有效命中
        // 传 jieba 分词词元而非原始问句：Meili 默认 matchingStrategy=last（首词必须命中，再从末尾逐词删减），
        // 且中文在 Meili 里按字符切 token——直接传原句时，首字（如"怎/如/什"）不在语料中就会直接 0 命中。
        // 词元以空格分隔可形成正确 token 边界；词元为空时回退原句。
        String meiliQuery = (terms == null || terms.isEmpty()) ? query : String.join(" ", terms);
        List<KeywordIndexService.ScoredId> scored = keywordIndexService.search(meiliQuery, Math.max(limit * 2, limit));
        if (scored.isEmpty()) return List.of();
        Map<String, Double> scoreById = new LinkedHashMap<>();
        for (KeywordIndexService.ScoredId s : scored) scoreById.put(s.id(), s.score());
        List<Knowledge> loaded;
        try {
            loaded = knowledgeMapper.selectBatchIds(scoreById.keySet());
        } catch (Exception e) {
            log.warn("[Keyword] 批量载回知识块失败，降级 MySQL: {}", e.getMessage());
            return List.of();
        }
        if (loaded.isEmpty()) return List.of();
        Map<String, Knowledge> byId = loaded.stream()
                .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
        Set<String> blockedDocIds = loadNonRetrievableDocIds(byId);

        List<Knowledge> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : scoreById.entrySet()) {
            if (result.size() >= limit) break;
            Knowledge k = byId.get(e.getKey());
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

    /** 回填词元命中统计（hitTerms/totalTerms 供检索调试展示） */
    private void fillTermStats(Knowledge k, List<String> terms) {
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
    private List<Knowledge> keywordSearchMysql(List<String> terms, int limit) {
        return keywordSearchMysql(terms, limit, null);
    }

    /** 带诊断的 MySQL 关键词召回（fail-loud：繁忙/超时跳过整路写入 diag） */
    private List<Knowledge> keywordSearchMysql(List<String> terms, int limit, RetrievalDiag diag) {
        Future<List<Knowledge>> future;
        try {
            future = keywordFallbackPool.submit(() -> {
                // WHERE doc_id IN (生效文档) AND ((content LIKE ? OR title LIKE ?) OR ...)
                QueryWrapper<Knowledge> wrapper = new QueryWrapper<Knowledge>()
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
                List<Knowledge> hits = knowledgeMapper.selectList(wrapper);
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
    private List<Knowledge> scoreKeywordHits(List<Knowledge> hits, List<String> terms) {
        // 词元在命中集内的文档频率（IDF 用）
        Map<String, Integer> df = new HashMap<>();
        for (Knowledge k : hits) {
            for (String term : terms) {
                if (countOccurrences(k.getContent(), term) > 0 || countOccurrences(k.getTitle(), term) > 0) {
                    df.merge(term, 1, Integer::sum);
                }
            }
        }
        double maxScore = 0;
        for (Knowledge k : hits) {
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
        for (Knowledge k : hits) {
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

    private double parseScore(Double score) {
        return score == null ? 0 : score;
    }

    /**
     * 附加索引命中构建（QA 问法 / 父子子块）：附加向量只负责把命中路由到来源块——
     * title/content/图片/章节路径全部取块本身（问法/切片文本不进上下文不进引用），
     * 与直接命中该块的可引用性完全一致
     */
    private Hit buildAugHit(Document doc, Knowledge k, String kid, double score) {
        String docId = metadataDocId(doc);
        if (docId == null || docId.isEmpty()) {
            docId = k.getDocId() == null ? "" : String.valueOf(k.getDocId());
        }
        List<String> images = (k.getImages() == null || k.getImages().isBlank())
                ? List.of() : com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
        return new Hit(kid, docId, k.getTitle(), k.getContent(), images, score, k.getChunkIndex(), k.getTitlePath(), null);
    }

    private Hit buildHit(Document doc, Knowledge k, String kid, double score) {        Map<String, Object> md = doc.getMetadata();
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
        return new Hit(kid, docId, title, doc.getText(), images, score, chunkIndex, titlePath, null);
    }

    /**
     * 批量加载向量命中的知识块（一次 selectBatchIds，替代逐条 selectById；失败返回空 Map 走原降级）。
     * QA 增强：未命中的 id 查问答对表解析回来源块（vector id = qa.id；元数据可能被 RedisVectorStore
     * 丢弃，M6——以表为准不依赖 metadata）
     */
    private Map<String, Knowledge> loadKnowledgeBatch(List<Document> vectorDocs) {
        if (vectorDocs == null || vectorDocs.isEmpty()) return Map.of();
        List<String> ids = vectorDocs.stream()
                .map(d -> String.valueOf(d.getId()))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        try {
            Map<String, Knowledge> out = knowledgeMapper.selectBatchIds(ids).stream()
                    .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
            // 附加索引命中解析：块表查不到的 id → QA 问法表（qa.id）/ 子块表（child.id）→ 来源块行
            List<String> missing = ids.stream().filter(id -> !out.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                java.util.Set<String> kids = new java.util.LinkedHashSet<>();
                try {
                    for (com.wenqu.ai.model.KnowledgeQa qa : knowledgeQaMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.wenqu.ai.model.KnowledgeQa>()
                                    .in("id", missing))) {
                        if (qa.getKnowledgeId() != null) kids.add(qa.getKnowledgeId());
                    }
                } catch (Exception e) {
                    log.warn("QA 命中解析失败: {}", e.getMessage());
                }
                try {
                    for (com.wenqu.ai.model.KnowledgeChild c : knowledgeChildMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.wenqu.ai.model.KnowledgeChild>()
                                    .in("id", missing))) {
                        if (c.getKnowledgeId() != null) kids.add(c.getKnowledgeId());
                    }
                } catch (Exception e) {
                    log.warn("子块命中解析失败: {}", e.getMessage());
                }
                if (!kids.isEmpty()) {
                    Map<String, Knowledge> chunkById = knowledgeMapper.selectBatchIds(kids).stream()
                            .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
                    for (com.wenqu.ai.model.KnowledgeQa qa : knowledgeQaMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.wenqu.ai.model.KnowledgeQa>()
                                    .in("id", missing))) {
                        Knowledge chunk = chunkById.get(qa.getKnowledgeId());
                        if (chunk != null) out.put(String.valueOf(qa.getId()), chunk);
                    }
                    for (com.wenqu.ai.model.KnowledgeChild c : knowledgeChildMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.wenqu.ai.model.KnowledgeChild>()
                                    .in("id", missing))) {
                        Knowledge chunk = chunkById.get(c.getKnowledgeId());
                        if (chunk != null) out.put(String.valueOf(c.getId()), chunk);
                    }
                }
            }
            return out;
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
    private Set<String> loadNonRetrievableDocIds(Map<String, Knowledge> kidMap) {
        Set<String> docIds = kidMap.values().stream()
                .map(Knowledge::getDocId)
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

    /**
     * 编译<b>库门</b>过滤表达式并下推到向量索引（检索时过滤）。
     *
     * <p><b>下推范围仅限库级</b>（2026-10-06 晚绑定改造后）：文档级 ACL 依赖
     * 「未配置共享=跟随库」这种 DB 状态规则，索引层无法表达，改由
     * {@link #loadVisibleDocIdsOfHits} 在检索后按命中块实时判定（晚绑定）。
     * 库级满足下推三前提——集合小（几十个库）、每次实时算（无陈旧问题）、能整库挡掉召回。
     *
     * <p><b>「检索时过滤」与「检索后过滤」的差别</b>：
     * <ul>
     *   <li><b>检索后过滤</b>：先无差别召回 topK，再在 Java 里逐条判可见性并剔除
     *       ——<b>不可见文档照样占掉 topK 名额</b>。topK=15 若 12 条是别人的私有文档，
     *       本用户只剩 3 条可用，召回率被 ACL 悄悄吃掉；</li>
     *   <li><b>检索时过滤</b>（现，库级）：把「可见库 IN(...)」作为 RediSearch filter 下推，
     *       无权库的文档<b>在 KNN 之前</b>就被排除，topK 名额不被整库浪费。</li>
     * </ul>
     *
     * <p><b>残留代价（已知，接受）</b>：库级已下推，但<b>文档级不享有此优化</b>——
     * 同一个库内的私有文档仍会占掉 topK 名额，这是换「权限真相单一来源 + 改权限即时生效」的代价。
     *
     * <p><b>库门与请求范围的交集</b>：kbIds（智能体/工具绑定的库范围）比可见库集合更窄时，
     * 取交集才是本次真正要搜的库——只按可见库过滤会忽略范围收窄（虽不越权，但白搜了无权无关的库）。
     *
     * <p><b>安全方向</b>：可见库为空（当前用户一个库都读不了）→ 构造永假表达式（查不到任何东西），
     * <b>而不是省略过滤</b>。省略等于对全库放开，是最危险的写法。
     *
     * @return filter 表达式；无法确定可见库时返回 null（退回无过滤检索 + 事后过滤兜底）
     */
    private String buildAclFilter(java.util.Collection<String> kbIds, RetrievalDiag diag) {
        try {
            Set<String> visibleKbIds = loadVisibleKbIds();
            if (visibleKbIds == null) {
                // 库可见性查询失败：不猜、不放行，退回不下的检索（文档级晚绑定过滤仍会拦住越权）
                if (diag != null) diag.aclPushdownFailed("可见库集合查询失败");
                return null;
            }
            if (kbIds != null && !kbIds.isEmpty()) {
                visibleKbIds.retainAll(kbIds);
            }
            return documentAclTags.buildKbGateExpression(visibleKbIds);
        } catch (Exception e) {
            if (diag != null) diag.aclPushdownFailed(e.getMessage());
            log.warn("[FAIL-LOUD] 编译库门过滤条件失败，本次退回无下推检索（文档级过滤仍生效）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 当前用户可读的知识库 id 集合（<b>库门下推的唯一来源，每次实时计算</b>）。
     *
     * <p><b>为什么库级能下推而文档级不能</b>：库集合是几十量级、每轮实时算（<b>无陈旧问题</b>），
     * 且能整库挡掉召回；文档级是「未配置共享=跟随库」这种依赖 DB 状态的规则，
     * 索引层无法表达，只能在应用层逐条判（晚绑定，见 {@link DocumentAclTags} 类注释）。
     *
     * <p><b>查库表而非文档表</b>：库数量是几十量级，文档表会随使用持续增长。
     *
     * @return 可见库 id 集合；查询失败返回 null（调用方据此退回无下推，<b>不返回空集</b>——
     *         空集会被编译成「什么都搜不到」，把一次 DB 抖动变成全量召回失败）
     */
    private Set<String> loadVisibleKbIds() {
        try {
            ResourceVisibilityService.Principal p = new ResourceVisibilityService.Principal(
                    RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
            Set<String> visible = new LinkedHashSet<>();
            for (com.wenqu.ai.model.KnowledgeBase kb : knowledgeBaseService.list()) {
                if (resourceVisibilityService.canRead(p, kb.getShareConfig(), kb.getCreatedBy(),
                        ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
                    visible.add(kb.getId());
                }
            }
            return visible;
        } catch (Exception e) {
            log.error("[FAIL-LOUD] 查询可见知识库失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * <b>检索期可见范围判定（晚绑定，唯一入口）</b>：返回<b>本轮命中块涉及</b>的文档里，当前用户可见的 docId 集合。
     *
     * <p><b>为什么是「按命中块查」而不是「全表算不可见集」</b>（2026-10-05 晚绑定改造）：
     * 旧实现每问一次都 {@code selectList} 全表 {@code c_ai_document}（无 WHERE）并逐行解析
     * {@code share_config}，文档量涨上去就是硬伤；更本质的问题是它与「写入侧把 ACL 烘焙进向量
     * metadata」并存，导致<b>同一份 share_config 被两处各解析一次</b>，口径易漂移
     * （「OR 门里没有标签=永不可见」那次 bug 即由此而来）。
     * <p>现在只查本轮命中的那些文档（向量路 metadata + 关键词路 docId 合并去重），
     * 走主键索引、亚毫秒级；<b>改共享范围下次检索即生效，不需要重写任何索引</b>。
     *
     * <p><b>两级判定，与管理接口同源</b>（全部走 {@link ResourceVisibilityService}，判定逻辑只此一处）：
     * 文档可见 = 所属库可读 <b>且</b> 文档自身可读（未配置共享时跟随库）。
     *
     * <p><b>异常时的取向</b>：查询失败返回<b>空集</b>（=什么都不可见）而非放行全部。
     * 这是与旧实现相反的取舍，理由是<b>权限判定失败应当 fail-closed</b>：
     * 放行全部等于把一次 DB 抖动变成越权，而空集最坏只是「检索不到、报个错」。
     * 用户重试或运维介入即可恢复，不会泄露内容。
     */
    private Set<String> loadVisibleDocIdsOfHits(List<Document> vectorDocs, List<Knowledge> kwDocs,
                                               Map<String, Knowledge> kidMap) {
        // 汇总本轮涉及的 docId：向量路优先取块表（权威），取不到再退metadata（向量库可能丢 metadata）
        Set<String> hitDocIds = new LinkedHashSet<>();
        for (Document d : vectorDocs == null ? List.<Document>of() : vectorDocs) {
            Knowledge k = kidMap == null ? null : kidMap.get(String.valueOf(d.getId()));
            String docId = k != null && k.getDocId() != null ? String.valueOf(k.getDocId()) : metadataDocId(d);
            if (docId != null && !docId.isBlank()) hitDocIds.add(docId);
        }
        for (Knowledge k : kwDocs == null ? List.<Knowledge>of() : kwDocs) {
            if (k.getDocId() != null && !String.valueOf(k.getDocId()).isBlank()) {
                hitDocIds.add(String.valueOf(k.getDocId()));
            }
        }
        if (hitDocIds.isEmpty()) return Set.of();

        try {
            List<AiDocument> docs = documentMapper.selectBatchIds(hitDocIds);
            ResourceVisibilityService.Principal p = new ResourceVisibilityService.Principal(
                    RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());

            // 库级：可见库集合（几十行，逐个 JSON 判；库级已下推进索引，这里是文档级的库门复核）
            Set<String> visibleKbIds = loadVisibleKbIds();
            if (visibleKbIds == null) {
                log.error("[FAIL-LOUD] 可见库集合查询失败，本轮按「全部不可见」处理（fail-closed）");
                return Set.of();
            }

            Set<String> visible = new LinkedHashSet<>();
            for (AiDocument d : docs) {
                if (d == null) continue;
                String kbId = d.getKbId();
                // 未归属任何库的文档（kbId 空）按默认库判定（kb_id 空=默认库语义，与管理接口一致）
                boolean kbVisible = kbId == null || kbId.isBlank() || visibleKbIds.contains(kbId);
                boolean docVisible = resourceVisibilityService.canReadDocFollowKb(
                        p, d.getShareConfig(), d.getCreatedBy());
                if (kbVisible && docVisible) visible.add(String.valueOf(d.getId()));
            }
            return visible;
        } catch (Exception e) {
            // fail-closed：判定失败时不可见，而不是放行（见方法注释）
            log.error("[FAIL-LOUD] 查询命中文档可见范围失败，本轮按「全部不可见」处理: {}", e.getMessage());
            return Set.of();
        }
    }

    private String metadataDocId(Document doc) {
        Object v = doc.getMetadata().get("docId");
        return v == null ? null : String.valueOf(v);
    }

    private Hit buildHit(Knowledge k, double score) {
        List<String> images = new ArrayList<>();
        if (k.getImages() != null && !k.getImages().isBlank()) {
            try {
                images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
            } catch (Exception e) {
                images = List.of();
            }
        }
        return new Hit(String.valueOf(k.getId()), String.valueOf(k.getDocId()),
                k.getTitle(), k.getContent(), images, score, k.getChunkIndex(), k.getTitlePath(), null);
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
