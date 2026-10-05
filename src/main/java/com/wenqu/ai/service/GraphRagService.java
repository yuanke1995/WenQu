package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.GraphEntityMapper;
import com.wenqu.ai.mapper.GraphExtractMapper;
import com.wenqu.ai.mapper.GraphTripleMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.GraphEntity;
import com.wenqu.ai.model.GraphExtract;
import com.wenqu.ai.model.GraphTriple;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.User;
import com.wenqu.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * P1 GraphRAG（库级开关，默认关；MySQL 两表起步不上图数据库）：
 * <ul>
 *   <li><b>抽取</b>：文档块落库后按批（graphrag.batchChunks）合并调 LLM 抽「主体-关系-客体」，
 *       仅收原文陈述；哈希增量（extract 账本 contentHash 一致即跳过）、失败不重试（成本闸）、
 *       每块条数上限（maxTriplesPerChunk）；重建/删除按 doc/chunk 对账清理；</li>
 *   <li><b>检索扩展</b>：主检索命中后做<b>一跳</b>图扩展——命中块内容匹配实体 → 取三元组 →
 *       反查<b>真实知识块</b>以衰减分（触发命中块分 × 0.9）并入结果；不拼合成文本块
 *       （与 minContextScore 低分门兼容），数量封顶 expandTopK；</li>
 *   <li><b>构建</b>：手动按钮回溯存量（分页逐文档串行），进度内存可查；清图是显式动作。</li>
 * </ul>
 * <p>
 * 模型口径（2026-10 库级化）：抽取模型<b>归知识库</b>——知识库编辑里绑定（<code>graph_model_ref</code>，
 * 判权按库主：谁建库烧谁的模型，与向量/视觉模型同口径）；未绑定的回落<b>库主的个人默认聊天模型</b>
 * （<code>User.defaultModel</code>，按库主 uid 显式查询——它是配置引导的必配项②，配好即有）。
 * 两者皆空 = 不抽取并告警（fail-loud，不静默用别的模型烧 token）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class GraphRagService {

    private final GraphEntityMapper entityMapper;
    private final GraphTripleMapper tripleMapper;
    private final GraphExtractMapper extractMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final AiDocumentMapper docMapper;
    private final ModelRegistryService modelRegistryService;
    private final ConfigService configService;
    /** 回落抽取模型读库主的个人默认聊天模型（异步抽取线程没有请求身份，按库主 uid 显式查询） */
    private final UserMapper userMapper;
    private final ChatClient chatClient;

    public GraphRagService(GraphEntityMapper entityMapper, GraphTripleMapper tripleMapper,
                           GraphExtractMapper extractMapper, KnowledgeMapper knowledgeMapper,
                           KnowledgeBaseMapper kbMapper, AiDocumentMapper docMapper,
                           ModelRegistryService modelRegistryService, ConfigService configService,
                           UserMapper userMapper,
                           ChatClient chatClient) {
        this.entityMapper = entityMapper;
        this.tripleMapper = tripleMapper;
        this.extractMapper = extractMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.modelRegistryService = modelRegistryService;
        this.configService = configService;
        this.userMapper = userMapper;
        this.chatClient = chatClient;
    }

    /** 实体/谓词长度上限（超长即弃——LLM 偶发输出整段话当实体，直接挡在入库前） */
    static final int MAX_ENTITY_CHARS = 64;
    static final int MAX_PREDICATE_CHARS = 32;

    /** 构建进度（内存即可：重启丢进度重新点按钮即可，不值得为它建表） */
    private final Map<String, BuildProgress> buildProgress = new ConcurrentHashMap<>();

    /** 构建进度快照（status 端点用） */
    public static final class BuildProgress {
        public volatile boolean running;
        public volatile int total;
        public final AtomicInteger done = new AtomicInteger();
        public final AtomicInteger failed = new AtomicInteger();
        public volatile int extracted;
        public volatile String startedAt;
    }

    // ==================================================================================================
    // 归一（静态可测）
    // ==================================================================================================

    /** 实体归一：去所有空白 + 全角转半角 + 小写。中文实体同一写法的常见变体都收敛到这一个键 */
    public static String normalize(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (Character.isWhitespace(c)) continue;
            if (c >= 'Ａ' && c <= 'Ｚ') c = (char) (c - 'Ａ' + 'A');         // 全角大写 → 半角
            else if (c >= 'ａ' && c <= 'ｚ') c = (char) (c - 'ａ' + 'a');     // 全角小写 → 半角
            else if (c >= '０' && c <= '９') c = (char) (c - '０' + '0');     // 全角数字 → 半角
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ==================================================================================================
    // 抽取管线（解析完成钩子 / 手动构建共用）
    // ==================================================================================================

    /** 解析成功钩子（DocumentService 调用）：开关开启才投异步任务；uid/role 捕获自触发者（个人级模型判权用） */
    public void onDocParsed(String docId, String uid, String role) {
        try {
            AiDocument doc = docMapper.selectById(docId);
            if (doc == null || doc.getKbId() == null || doc.getKbId().isBlank()) return;
            KnowledgeBase kb = kbMapper.selectById(doc.getKbId());
            if (kb == null || !isGraphOn(kb)) return;
            ThreadPoolManager.execute(() -> {
                try {
                    extractDoc(docId, doc.getKbId(), uid, role);
                } catch (Exception e) {
                    log.warn("[GRAPH] 文档 {} 图谱抽取失败（不影响解析）: {}", docId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[GRAPH] 解析钩子异常（不影响解析）: docId={} {}", docId, e.getMessage());
        }
    }

    /** 手动构建（存量回溯）：整库逐文档串行抽取，进度内存可查；重复触发 fail-loud */
    public Map<String, Object> build(String kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) throw new BizException(404, "知识库不存在");
        if (!isGraphOn(kb)) throw new BizException("请先开启该知识库的 GraphRAG 开关");
        BuildProgress progress = buildProgress.get(kbId);
        if (progress != null && progress.running) {
            throw new BizException("该库的图谱构建正在进行中（" + progress.done.get() + "/" + progress.total + "），请等它跑完");
        }
        List<AiDocument> docs = docMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getKbId, kbId).eq(AiDocument::getStatus, 0));
        BuildProgress p = new BuildProgress();
        p.running = true;
        p.total = docs.size();
        p.startedAt = LocalDateTime.now().toString();
        buildProgress.put(kbId, p);
        // 身份兜底：抽取模型判权按库主（extractDoc 内解析）；库主缺失时才回落到触发者身份
        String uid = com.wenqu.ai.util.RequestUser.uid();
        String role = com.wenqu.ai.util.RequestUser.role();
        ThreadPoolManager.execute(() -> {
            try {
                for (AiDocument d : docs) {
                    try {
                        p.extracted += extractDoc(d.getId(), kbId, uid, role);
                    } catch (Exception e) {
                        p.failed.incrementAndGet();
                        log.warn("[GRAPH] 构建：文档 {} 抽取失败: {}", d.getId(), e.getMessage());
                    } finally {
                        p.done.incrementAndGet();
                    }
                }
            } finally {
                p.running = false;
                log.info("[GRAPH] 构建（{}）完成：{}/{} 文档，三元组增量 {}，失败 {}",
                        kbId, p.done.get(), p.total, p.extracted, p.failed.get());
            }
        });
        return Map.of("started", true, "total", docs.size());
    }

    public Map<String, Object> status(String kbId) {
        BuildProgress p = buildProgress.get(kbId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("building", p != null && p.running);
        if (p != null) {
            out.put("total", p.total);
            out.put("done", p.done.get());
            out.put("failed", p.failed.get());
            out.put("extracted", p.extracted);
            out.put("startedAt", p.startedAt);
        }
        Long entities = entityMapper.selectCount(new LambdaQueryWrapper<GraphEntity>().eq(GraphEntity::getKbId, kbId));
        Long triples = tripleMapper.selectCount(new LambdaQueryWrapper<GraphTriple>().eq(GraphTriple::getKbId, kbId));
        out.put("entities", entities);
        out.put("triples", triples);
        return out;
    }

    /** 三元组浏览（带实体名与溯源文档名，分页；图视图一次拉大批——size 上限放宽到 500） */
    public Map<String, Object> triples(String kbId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(10, size), 500);
        var pg = tripleMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, s),
                new LambdaQueryWrapper<GraphTriple>().eq(GraphTriple::getKbId, kbId)
                        .orderByDesc(GraphTriple::getCreateTime));
        // 实体名与文档名批量映射
        Set<String> entityIds = new HashSet<>();
        Set<String> docIds = new HashSet<>();
        for (GraphTriple t : pg.getRecords()) {
            entityIds.add(t.getSubjectId());
            entityIds.add(t.getObjectId());
            if (t.getDocId() != null) docIds.add(t.getDocId());
        }
        Map<String, String> names = new HashMap<>();
        if (!entityIds.isEmpty()) {
            entityMapper.selectBatchIds(entityIds).forEach(e -> names.put(e.getId(), e.getName()));
        }
        Map<String, String> docNames = new HashMap<>();
        if (!docIds.isEmpty()) {
            docMapper.selectBatchIds(docIds).forEach(d -> docNames.put(d.getId(), d.getFileName()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphTriple t : pg.getRecords()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", t.getId());
            r.put("subject", names.getOrDefault(t.getSubjectId(), t.getSubjectId()));
            r.put("predicate", t.getPredicate());
            r.put("object", names.getOrDefault(t.getObjectId(), t.getObjectId()));
            r.put("doc", docNames.getOrDefault(t.getDocId(), t.getDocId()));
            r.put("chunkId", t.getChunkId());
            rows.add(r);
        }
        return Map.of("rows", rows, "total", pg.getTotal(), "page", p, "size", s);
    }

    // ==================== §5 图谱可视化：聚合查询（只读，RBAC 沿 /graph/** 门控） ====================

    /**
     * 图谱视图（§5 核心接口）：按关联度取前 {@code limit} 个实体 + 这些实体之间的关系
     * （同一 (主体,谓词,客体) 的多条三元组合并成一条带计数的关系边）。
     * <p>
     * 与 {@link #triples} 的分工：triples 是逐条核对清单（带来源文档名），view 是<b>画图数据</b>——
     * 度数在库内 GROUP BY 聚合，前端不用再拉全量三元组自行统计（大库下 500 条采样会以偏概全）。
     * 零三元组的孤立实体不进视图（画出来只是孤点，徒增噪音）；实体数超 limit 时 truncated=true，
     * 前端提示「展示关联度最高的前 N 个」，完整定位交给 {@link #search}（超出视图的实体聚焦其邻域）。
     */
    public Map<String, Object> view(String kbId, int limit) {
        requireKb(kbId);
        int n = Math.min(Math.max(20, limit), 500);
        // 度数 = 实体在三元组中出现的次数（主语/客体两侧各记一次）
        Map<String, Integer> degree = new HashMap<>();
        tripleMapper.subjectDegree(kbId).forEach(r -> degree.merge(str(r.get("entityId")), ((Number) r.get("cnt")).intValue(), Integer::sum));
        tripleMapper.objectDegree(kbId).forEach(r -> degree.merge(str(r.get("entityId")), ((Number) r.get("cnt")).intValue(), Integer::sum));
        // 前 N 实体（按度数降序；同名次保持稳定序，避免刷新后布局无谓抖动）
        List<String> topIds = degree.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (!topIds.isEmpty()) {
            Map<String, GraphEntity> ents = entityMapper.selectBatchIds(topIds).stream()
                    .collect(Collectors.toMap(GraphEntity::getId, e -> e));
            for (String id : topIds) {
                GraphEntity e = ents.get(id);
                if (e == null) continue;   // 三元组残留但实体行已被清（清图非事务边界）：跳过
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("name", e.getName());
                m.put("degree", degree.get(id));
                m.put("mentionCount", e.getMentionCount());
                nodes.add(m);
            }
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        if (!nodes.isEmpty()) {
            List<String> nodeIds = nodes.stream().map(m -> String.valueOf(m.get("id"))).toList();
            for (Map<String, Object> r : tripleMapper.aggregateEdges(kbId, nodeIds)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", str(r.get("sourceId")));
                m.put("target", str(r.get("targetId")));
                m.put("predicate", str(r.get("predicate")));
                m.put("count", ((Number) r.get("cnt")).intValue());
                edges.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", Map.of("entities", entityMapper.selectCount(new LambdaQueryWrapper<GraphEntity>().eq(GraphEntity::getKbId, kbId)),
                "triples", tripleMapper.selectCount(new LambdaQueryWrapper<GraphTriple>().eq(GraphTriple::getKbId, kbId))));
        out.put("limit", n);
        out.put("truncated", degree.size() > nodes.size());
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /**
     * 实体详情（§5 点实体看三元组）：实体的全部三元组（主/客两侧），带来源文档名与知识块定位
     * （chunkId/chunkIndex，前端「查看源块」用）。度数即三元组条数（与 view 的度数同口径）。
     */
    public Map<String, Object> entityDetail(String kbId, String entityId) {
        requireKb(kbId);
        GraphEntity e = requireEntity(kbId, entityId);
        List<GraphTriple> ts = tripleMapper.selectList(new LambdaQueryWrapper<GraphTriple>()
                .eq(GraphTriple::getKbId, kbId)
                .and(w -> w.eq(GraphTriple::getSubjectId, entityId).or().eq(GraphTriple::getObjectId, entityId))
                .orderByDesc(GraphTriple::getCreateTime));
        Set<String> otherIds = new HashSet<>();
        Set<String> docIds = new HashSet<>();
        Set<String> chunkIds = new HashSet<>();
        for (GraphTriple t : ts) {
            otherIds.add(t.getSubjectId());
            otherIds.add(t.getObjectId());
            if (t.getDocId() != null) docIds.add(t.getDocId());
            if (t.getChunkId() != null) chunkIds.add(t.getChunkId());
        }
        Map<String, String> names = new HashMap<>();
        if (!otherIds.isEmpty()) {
            entityMapper.selectBatchIds(otherIds).forEach(x -> names.put(x.getId(), x.getName()));
        }
        Map<String, String> docNames = new HashMap<>();
        if (!docIds.isEmpty()) {
            docMapper.selectBatchIds(docIds).forEach(d -> docNames.put(d.getId(), d.getFileName()));
        }
        Map<String, Knowledge> chunks = chunkIds.isEmpty() ? Map.of()
                : knowledgeMapper.selectBatchIds(chunkIds).stream().collect(Collectors.toMap(Knowledge::getId, k -> k));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphTriple t : ts) {
            Knowledge k = t.getChunkId() == null ? null : chunks.get(t.getChunkId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", t.getId());
            r.put("subjectId", t.getSubjectId());
            r.put("subject", names.getOrDefault(t.getSubjectId(), t.getSubjectId()));
            r.put("predicate", t.getPredicate());
            r.put("objectId", t.getObjectId());
            r.put("object", names.getOrDefault(t.getObjectId(), t.getObjectId()));
            r.put("doc", docNames.getOrDefault(t.getDocId(), t.getDocId()));
            r.put("docId", t.getDocId());
            r.put("chunkId", t.getChunkId());
            r.put("chunkIndex", k == null ? null : k.getChunkIndex());
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", Map.of("id", e.getId(), "name", e.getName(),
                "aliases", parseAliases(e), "mentionCount", e.getMentionCount() == null ? 0 : e.getMentionCount()));
        out.put("degree", ts.size());
        out.put("triples", rows);
        return out;
    }

    /**
     * 实体邻域（§5 搜索定位的兜底）：搜索命中的实体不在默认视图前 N 里时，以它为中心取
     * 一跳邻域子图（该实体的全部三元组聚合），前端整体重渲染为聚焦视图——
     * 与 view() 的 nodes/edges 同构，渲染层零分叉。
     */
    public Map<String, Object> neighbor(String kbId, String entityId) {
        Map<String, Object> detail = entityDetail(kbId, entityId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ts = (List<Map<String, Object>>) detail.get("triples");
        String centerId = entityId;
        Map<String, Integer> degree = new LinkedHashMap<>();
        // 聚合键 (subject,predicate,object) → 边；display 名映射已由 detail 做好（键用实体 id，显示时再换名）
        Map<String, Map<String, Object>> edgeByKey = new LinkedHashMap<>();
        for (Map<String, Object> t : ts) {
            String s = String.valueOf(t.get("subjectId"));
            String o = String.valueOf(t.get("objectId"));
            degree.merge(s, 1, Integer::sum);
            degree.merge(o, 1, Integer::sum);
            String key = s + "|" + t.get("predicate") + "|" + o;
            edgeByKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", s);
                m.put("target", o);
                m.put("predicate", t.get("predicate"));
                m.put("count", 0);
                return m;
            }).merge("count", 1, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
        }
        Map<String, String> names = new HashMap<>();
        names.put(centerId, String.valueOf(((Map<?, ?>) detail.get("entity")).get("name")));
        for (Map<String, Object> t : ts) {
            names.put(String.valueOf(t.get("subjectId")), String.valueOf(t.get("subject")));
            names.put(String.valueOf(t.get("objectId")), String.valueOf(t.get("object")));
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> en : degree.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", en.getKey());
            m.put("name", names.getOrDefault(en.getKey(), en.getKey()));
            m.put("degree", en.getValue());
            nodes.add(m);
        }
        // 中心节点排最前（渲染层可据此给中心实体做视觉强调）
        nodes.sort((a, b) -> centerId.equals(a.get("id")) ? -1 : centerId.equals(b.get("id")) ? 1 : 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("center", Map.of("id", centerId, "name", names.getOrDefault(centerId, centerId)));
        out.put("nodes", nodes);
        out.put("edges", new ArrayList<>(edgeByKey.values()));
        return out;
    }

    /**
     * 实体搜索（§5 按实体搜索定位）：按显示名/归一名模糊匹配（别名不参与——归一名已覆盖
     * 大小写/全半角差异，别名只影响抽取去重）。命中前 20 个，按提及次数降序。
     */
    public List<Map<String, Object>> search(String kbId, String q) {
        requireKb(kbId);
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) return List.of();
        List<GraphEntity> hits = entityMapper.selectList(new LambdaQueryWrapper<GraphEntity>()
                .eq(GraphEntity::getKbId, kbId)
                .and(w -> w.like(GraphEntity::getName, query).or().like(GraphEntity::getNameNorm, query))
                .orderByDesc(GraphEntity::getMentionCount)
                .last("LIMIT 20"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GraphEntity e : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("name", e.getName());
            m.put("mentionCount", e.getMentionCount() == null ? 0 : e.getMentionCount());
            out.add(m);
        }
        return out;
    }

    /**
     * 知识块内容（§5 跳转源知识块）：按 chunkId 取块原文 + 所属文档，供实体三元组面板
     * 「查看源块」弹层展示——溯源闭环的最后一跳。跨库 chunkId 一律拒绝（不泄露他库内容）。
     */
    public Map<String, Object> chunk(String kbId, String chunkId) {
        requireKb(kbId);
        if (chunkId == null || chunkId.isBlank()) throw new BizException(404, "知识块不存在");
        Knowledge k = knowledgeMapper.selectById(chunkId);
        if (k == null) throw new BizException(404, "知识块不存在");
        AiDocument doc = k.getDocId() == null ? null : docMapper.selectById(k.getDocId());
        if (doc == null || !kbId.equals(doc.getKbId())) throw new BizException(404, "知识块不存在");
        String content = k.getContent() == null ? "" : k.getContent();
        int max = Math.max(500, configService.getInt("graphrag.chunkViewMaxChars", 4000));
        boolean truncated = content.length() > max;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", k.getId());
        out.put("docId", doc.getId());
        out.put("doc", doc.getFileName());
        out.put("chunkIndex", k.getChunkIndex());
        out.put("title", k.getTitle());
        out.put("titlePath", k.getTitlePath());
        out.put("content", truncated ? content.substring(0, max) : content);
        out.put("truncated", truncated);
        return out;
    }

    private void requireKb(String kbId) {
        if (kbMapper.selectById(kbId) == null) throw new BizException(404, "知识库不存在");
    }

    private GraphEntity requireEntity(String kbId, String entityId) {
        GraphEntity e = entityId == null ? null : entityMapper.selectById(entityId);
        if (e == null || !kbId.equals(e.getKbId())) throw new BizException(404, "实体不存在");
        return e;
    }

    /** 实体别名 JSON → 列表（解析失败/为空返回空列表；展示封顶 8 条，与写入上限一致） */
    private List<String> parseAliases(GraphEntity e) {
        try {
            if (e.getAliases() == null || e.getAliases().isBlank()) return List.of();
            List<String> list = JSON.parseArray(e.getAliases(), String.class);
            return list == null ? List.of() : list.stream().limit(8).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 清空该库图谱（显式动作；开关关闭不删数据） */
    public Map<String, Object> clear(String kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) throw new BizException(404, "知识库不存在");
        BuildProgress p = buildProgress.get(kbId);
        if (p != null && p.running) throw new BizException("构建进行中，请等它跑完或稍后再清");
        int t = tripleMapper.delete(new LambdaQueryWrapper<GraphTriple>().eq(GraphTriple::getKbId, kbId));
        int e = entityMapper.delete(new LambdaQueryWrapper<GraphEntity>().eq(GraphEntity::getKbId, kbId));
        int x = extractMapper.delete(new LambdaQueryWrapper<GraphExtract>()
                .inSql(GraphExtract::getDocId, "SELECT id FROM c_ai_document WHERE kb_id = '" + kbId.replace("'", "''") + "'"));
        log.info("[GRAPH] 清空图谱（{}）：三元组 {} / 实体 {} / 抽取记录 {}", kbId, t, e, x);
        return Map.of("triples", t, "entities", e);
    }

    /**
     * 单文档抽取（对账式）：块在账本且哈希一致 → 跳过；块消失 → 连带清三元组；新/变更块 → 批抽。
     *
     * @return 本次新抽出的三元组条数
     */
    int extractDoc(String docId, String kbId, String uid, String role) {
        // 抽取模型：库级绑定优先（知识库编辑里选择，归库主），空则回落**库主的个人默认聊天模型**
        // User.defaultModel（配置引导必配项②：异步抽取线程没有请求身份，按库主 uid 显式查询）。
        // 判权主体一律按**库主**：抽取跑在异步线程、没有请求身份，谁建库烧谁的模型。
        KnowledgeBase kb = kbMapper.selectById(kbId);
        String kbRef = kb == null || kb.getGraphModelRef() == null ? "" : kb.getGraphModelRef().trim();
        String principalUid = kb != null && kb.getCreatedBy() != null && !kb.getCreatedBy().isBlank()
                ? kb.getCreatedBy() : uid;
        String modelRef = kbRef;
        String modelSource = "库级绑定";
        if (modelRef.isEmpty()) {
            User owner = userMapper.selectById(principalUid);
            modelRef = owner == null || owner.getDefaultModel() == null ? "" : owner.getDefaultModel().trim();
            modelSource = "库主默认聊天模型";
        }
        if (modelRef.isEmpty()) {
            throw new BizException("未配置 GraphRAG 抽取模型：请在知识库编辑里选择抽取模型"
                    + "（或由库主在个人设置里设置默认聊天模型），抽取跳过");
        }
        log.info("[GRAPH] 抽取模型 kb={} model={} 来源={}", kbId, modelRef, modelSource);
        modelRegistryService.assertUsable(modelRef, principalUid, role);
        List<Knowledge> chunks = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getDocId, docId).orderByAsc(Knowledge::getChunkIndex));
        Map<String, GraphExtract> account = new HashMap<>();
        extractMapper.selectList(new LambdaQueryWrapper<GraphExtract>().eq(GraphExtract::getDocId, docId))
                .forEach(r -> account.put(r.getChunkId(), r));
        Set<String> aliveChunkIds = chunks.stream().map(Knowledge::getId).collect(Collectors.toSet());
        // 对账清理：块已消失（被重建删掉）→ 连带清抽取记录与三元组
        for (GraphExtract r : account.values()) {
            if (!aliveChunkIds.contains(r.getChunkId())) {
                extractMapper.deleteById(r.getId());
                tripleMapper.delete(new LambdaQueryWrapper<GraphTriple>().eq(GraphTriple::getChunkId, r.getChunkId()));
            }
        }
        int maxPerChunk = Math.max(1, configService.getInt("graphrag.maxTriplesPerChunk", 10));
        int batchChunks = Math.max(1, configService.getInt("graphrag.batchChunks", 3));
        int extracted = 0;
        List<Knowledge> batch = new ArrayList<>();
        for (Knowledge chunk : chunks) {
            GraphExtract prev = account.get(chunk.getId());
            if (prev != null && chunk.getContentHash() != null && chunk.getContentHash().equals(prev.getContentHash())) {
                continue;   // 哈希一致：已抽过，跳过（增量核心）
            }
            batch.add(chunk);
            if (batch.size() >= batchChunks) {
                extracted += extractBatch(kbId, batch, modelRef, maxPerChunk);
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            extracted += extractBatch(kbId, batch, modelRef, maxPerChunk);
        }
        return extracted;
    }

    /** 一批（若干块）合并抽取：单块失败整批记 failed（不重试），账本照记（哈希相同不再白烧） */
    private int extractBatch(String kbId, List<Knowledge> batch, String modelRef, int maxPerChunk) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("从下面的知识库片段中抽取「主体-关系-客体」三元组。规则：\n")
                .append("1. 只抽取原文明确陈述的事实关系，禁止推断、补全或使用外部知识；\n")
                .append("2. 每个片段最多 ").append(maxPerChunk).append(" 条；没有可抽取的关系就输出空数组；\n")
                .append("3. 主体/客体是简短实体名（公司/人名/地名/产品/条款编号等），关系是一个短语（如 母公司、位于、成立于、适用范围）；\n")
                .append("4. 严格输出 JSON 数组，不要输出任何其它文字：[{\"chunk\":片段序号,\"s\":\"主体\",\"p\":\"关系\",\"o\":\"客体\"}]\n\n");
        for (int i = 0; i < batch.size(); i++) {
            Knowledge c = batch.get(i);
            String content = c.getContent() == null ? "" : c.getContent();
            if (content.length() > 1200) content = content.substring(0, 1200);
            prompt.append("【片段 ").append(i + 1).append("】标题：").append(c.getTitle() == null ? "" : c.getTitle())
                    .append("\n").append(content).append("\n\n");
        }
        int triples = 0;
        boolean ok = true;
        String answer = null;
        try {
            var resp = chatClient.prompt()
                    .user(prompt.toString())
                    .options(OpenAiChatOptions.builder()
                            .model(modelRef)
                            .temperature(0.0)
                            .internalToolExecutionEnabled(false)
                            .build())
                    .call()
                    .chatResponse();
            answer = resp == null || resp.getResult() == null || resp.getResult().getOutput() == null
                    ? "" : resp.getResult().getOutput().getText();
        } catch (Exception e) {
            ok = false;
            log.warn("[GRAPH] 批抽取 LLM 调用失败（kb={}）: {}", kbId, e.getMessage());
        }
        if (ok) {
            List<RawTriple> raw = parseTriples(answer, batch.size(), maxPerChunk);
            for (RawTriple t : raw) {
                if (t.chunkIndex < 1 || t.chunkIndex > batch.size()) continue;
                Knowledge src = batch.get(t.chunkIndex - 1);
                if (saveTriple(kbId, src, t)) triples++;
            }
        }
        // 账本：每块一行（uk_chunk；变更块先删后写）
        for (Knowledge c : batch) {
            GraphExtract prev = extractMapper.selectOne(new LambdaQueryWrapper<GraphExtract>()
                    .eq(GraphExtract::getChunkId, c.getId()).last("LIMIT 1"));
            if (prev != null) extractMapper.deleteById(prev.getId());
            GraphExtract rec = new GraphExtract();
            rec.setId(UUID.randomUUID().toString());
            rec.setDocId(c.getDocId());
            rec.setChunkId(c.getId());
            rec.setContentHash(c.getContentHash() == null ? "" : c.getContentHash());
            rec.setStatus(ok ? "done" : "failed");
            rec.setTripleCount(ok ? triples : 0);
            rec.setCreatedAt(LocalDateTime.now());
            try {
                extractMapper.insert(rec);
            } catch (DuplicateKeyException ignored) {
            }
        }
        return ok ? triples : 0;
    }

    /** 抽取结果 record（静态可测：JSON 解析与每块配额在此收口） */
    public record RawTriple(int chunkIndex, String subject, String predicate, String object) {}

    /**
     * 解析 LLM 输出为三元组：容忍 markdown 代码围栏；每块超出 maxPerChunk 的条目丢弃；
     * 主体/客体/谓词超长或为空的丢弃。解析失败返回空列表（调用方整批记 failed）。
     */
    public static List<RawTriple> parseTriples(String answer, int batchCount, int maxPerChunk) {
        List<RawTriple> out = new ArrayList<>();
        if (answer == null || answer.isBlank()) return out;
        String text = answer.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        try {
            List<Map<String, Object>> arr = JSON.parseArray(text.substring(start, end + 1), (Class<Map<String, Object>>) (Class) Map.class);
            if (arr == null) return out;
            Map<Integer, Integer> perChunk = new HashMap<>();
            for (Map<String, Object> m : arr) {
                if (m == null) continue;
                int chunkIndex = m.get("chunk") instanceof Number n ? n.intValue() : 1;
                String s = str(m.get("s"));
                String p = str(m.get("p"));
                String o = str(m.get("o"));
                if (s.isEmpty() || p.isEmpty() || o.isEmpty()) continue;
                if (s.length() > MAX_ENTITY_CHARS || o.length() > MAX_ENTITY_CHARS || p.length() > MAX_PREDICATE_CHARS) continue;
                int used = perChunk.merge(chunkIndex, 1, Integer::sum);
                if (used > maxPerChunk) continue;   // 每块配额：超出的直接丢（成本闸）
                out.add(new RawTriple(chunkIndex, s, p, o));
            }
        } catch (Exception e) {
            return List.of();   // JSON 解析失败：整批按失败记账
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 实体 upsert + 三元组入库（唯一键幂等），返回是否新增成功 */
    private boolean saveTriple(String kbId, Knowledge chunk, RawTriple t) {
        try {
            GraphEntity subject = upsertEntity(kbId, t.subject());
            GraphEntity object = upsertEntity(kbId, t.object());
            GraphTriple triple = new GraphTriple();
            triple.setId(UUID.randomUUID().toString());
            triple.setKbId(kbId);
            triple.setSubjectId(subject.getId());
            triple.setPredicate(t.predicate());
            triple.setObjectId(object.getId());
            triple.setChunkId(chunk.getId());
            triple.setDocId(chunk.getDocId());
            triple.setCreateTime(LocalDateTime.now());
            tripleMapper.insert(triple);
            return true;
        } catch (DuplicateKeyException e) {
            return false;   // 同库同主谓客已存在（幂等）
        } catch (Exception e) {
            log.warn("[GRAPH] 三元组入库失败: {}", e.getMessage());
            return false;
        }
    }

    /** 实体 upsert：归一命中复用（mention+1，新写法进 aliases），否则新建 */
    private GraphEntity upsertEntity(String kbId, String rawName) {
        String norm = normalize(rawName);
        if (norm.isEmpty()) throw new BizException("实体归一后为空");
        GraphEntity existing = entityMapper.selectOne(new LambdaQueryWrapper<GraphEntity>()
                .eq(GraphEntity::getKbId, kbId).eq(GraphEntity::getNameNorm, norm).last("LIMIT 1"));
        if (existing != null) {
            existing.setMentionCount((existing.getMentionCount() == null ? 0 : existing.getMentionCount()) + 1);
            if (!existing.getName().equals(rawName)) appendAlias(existing, rawName);
            entityMapper.updateById(existing);
            return existing;
        }
        GraphEntity e = new GraphEntity();
        e.setId(UUID.randomUUID().toString());
        e.setKbId(kbId);
        e.setName(rawName.length() > 128 ? rawName.substring(0, 128) : rawName);
        e.setNameNorm(norm.length() > 128 ? norm.substring(0, 128) : norm);
        e.setMentionCount(1);
        e.setCreateTime(LocalDateTime.now());
        try {
            entityMapper.insert(e);
            return e;
        } catch (DuplicateKeyException ex) {
            // 并发下另一线程先建了：回查
            return entityMapper.selectOne(new LambdaQueryWrapper<GraphEntity>()
                    .eq(GraphEntity::getKbId, kbId).eq(GraphEntity::getNameNorm, norm).last("LIMIT 1"));
        }
    }

    private void appendAlias(GraphEntity e, String alias) {
        try {
            List<String> aliases = e.getAliases() == null || e.getAliases().isBlank()
                    ? new ArrayList<>() : new ArrayList<>(JSON.parseArray(e.getAliases(), String.class));
            String trimmed = alias.length() > 64 ? alias.substring(0, 64) : alias;
            if (!aliases.contains(trimmed)) {
                if (aliases.size() >= 8) aliases.remove(0);   // 别名数量上限，防无限膨胀
                aliases.add(trimmed);
                String json = JSON.toJSONString(aliases);
                e.setAliases(json.length() > 1000 ? json.substring(0, 1000) : json);
            }
        } catch (Exception ignored) {
        }
    }

    /** 文档删除钩子：该文档的三元组与抽取记录随删（实体保留——无三元组引用的实体不参与图扩展，无害） */
    public void onDocDeleted(String docId) {
        int t = tripleMapper.delete(new LambdaQueryWrapper<GraphTriple>().eq(GraphTriple::getDocId, docId));
        int x = extractMapper.delete(new LambdaQueryWrapper<GraphExtract>().eq(GraphExtract::getDocId, docId));
        if (t + x > 0) log.info("[GRAPH] 文档 {} 图谱数据已清理：三元组 {} / 抽取记录 {}", docId, t, x);
    }

    // ==================================================================================================
    // 检索图扩展（一跳）
    // ==================================================================================================

    /**
     * 主检索收口处的图扩展：命中块内容匹配实体 → 一跳三元组 → 反查真实知识块并入（衰减分 ×0.9）。
     * 开关按 kbIds 里 graph_enabled=1 的库生效；没开或无命中原样返回。
     */
    public List<HybridRetrievalService.Hit> expand(List<HybridRetrievalService.Hit> hits,
                                                   Collection<String> kbIds,
                                                   HybridRetrievalService.RetrievalDiag diag) {
        if (hits == null || hits.isEmpty()) return hits;
        // 开启了 GraphRAG 的库（与本次检索范围求交）
        List<KnowledgeBase> graphKbs = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getGraphEnabled, 1));
        if (graphKbs.isEmpty()) return hits;
        Set<String> scope = graphKbs.stream().map(KnowledgeBase::getId).collect(Collectors.toSet());
        if (kbIds != null && !kbIds.isEmpty()) {
            scope.retainAll(kbIds);
            if (scope.isEmpty()) return hits;
        }
        int expandTopK = Math.max(0, configService.getInt("graphrag.expandTopK", 5));
        if (expandTopK == 0) return hits;

        Set<String> existingKIds = hits.stream().map(HybridRetrievalService.Hit::knowledgeId)
                .filter(Objects_::nonBlank).collect(Collectors.toSet());
        // 命中块内容匹配实体（取分数最高的前 8 个命中块做触发源）
        Map<String, Double> triggerEntityScore = new HashMap<>();
        for (HybridRetrievalService.Hit hit : hits.stream()
                .sorted(Comparator.comparingDouble(HybridRetrievalService.Hit::score).reversed())
                .limit(8).toList()) {
            String content = hit.content() == null ? "" : hit.content();
            String title = hit.title() == null ? "" : hit.title();
            double score = hit.rerankScore() != null ? hit.rerankScore() : hit.score();
            List<GraphEntity> candidates = entityMapper.selectList(new LambdaQueryWrapper<GraphEntity>()
                    .in(GraphEntity::getKbId, scope)
                    .orderByDesc(GraphEntity::getMentionCount)
                    .last("LIMIT 300"));
            int matched = 0;
            for (GraphEntity e : candidates) {
                if (matched >= 3) break;   // 每个命中块最多触发 3 个实体（防长文本海量匹配）
                if (e.getName() == null || e.getName().length() < 2) continue;
                if ((content.contains(e.getName()) || title.contains(e.getName()))
                        && score > triggerEntityScore.getOrDefault(e.getId(), 0.0)) {
                    triggerEntityScore.put(e.getId(), score);
                    matched++;
                }
            }
        }
        if (triggerEntityScore.isEmpty()) return hits;
        // 一跳三元组
        List<GraphTriple> triples = tripleMapper.selectList(new LambdaQueryWrapper<GraphTriple>()
                .in(GraphTriple::getKbId, scope)
                .and(qw -> qw.in(GraphTriple::getSubjectId, triggerEntityScore.keySet())
                        .or().in(GraphTriple::getObjectId, triggerEntityScore.keySet()))
                .last("LIMIT 100"));
        if (triples.isEmpty()) return hits;
        // 三元组 → 源块（真实块），按「两端实体触发分最高值 × 0.9」衰减，去重、封顶
        List<GraphTriple> picked = triples.stream()
                .sorted((a, b) -> Double.compare(
                        Math.max(triggerEntityScore.getOrDefault(b.getSubjectId(), 0.0), triggerEntityScore.getOrDefault(b.getObjectId(), 0.0)),
                        Math.max(triggerEntityScore.getOrDefault(a.getSubjectId(), 0.0), triggerEntityScore.getOrDefault(a.getObjectId(), 0.0))))
                .limit(expandTopK * 2L)
                .toList();
        Map<String, HybridRetrievalService.Hit> added = new LinkedHashMap<>();
        for (GraphTriple t : picked) {
            if (added.size() >= expandTopK) break;
            if (t.getChunkId() == null || existingKIds.contains(t.getChunkId()) || added.containsKey(t.getChunkId())) continue;
            Knowledge chunk = knowledgeMapper.selectById(t.getChunkId());
            if (chunk == null || (chunk.getStatus() != null && chunk.getStatus() == 1)) continue;
            if (t.getKbId() != null && !scope.contains(t.getKbId())) continue;
            double origin = Math.max(triggerEntityScore.getOrDefault(t.getSubjectId(), 0.0),
                    triggerEntityScore.getOrDefault(t.getObjectId(), 0.0));
            if (origin <= 0) continue;
            double score = origin * 0.9;
            List<String> images = List.of();
            try {
                if (chunk.getImages() != null && !chunk.getImages().isBlank()) images = JSON.parseArray(chunk.getImages(), String.class);
            } catch (Exception ignored) {
            }
            added.put(chunk.getId(), new HybridRetrievalService.Hit(
                    chunk.getId(), chunk.getDocId(), chunk.getTitle(), chunk.getContent(), images,
                    score, chunk.getChunkIndex(), chunk.getTitlePath(), null));
        }
        if (added.isEmpty()) return hits;
        if (diag != null) diag.addGraphExpanded(added.size());
        List<HybridRetrievalService.Hit> out = new ArrayList<>(hits);
        out.addAll(added.values());
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        log.debug("[GRAPH] 图扩展并入 {} 块（命中触发实体 {} 个）", added.size(), triggerEntityScore.size());
        return out;
    }

    /** blank 判断小工具（避免为了两个方法引入 Commons Lang） */
    static final class Objects_ {
        static boolean nonBlank(String s) { return s != null && !s.isBlank(); }
    }

    private boolean isGraphOn(KnowledgeBase kb) {
        return kb.getGraphEnabled() != null && kb.getGraphEnabled() == 1;
    }
}
