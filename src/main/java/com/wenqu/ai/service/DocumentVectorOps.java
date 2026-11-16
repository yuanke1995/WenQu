package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量写入与生命周期：单块向量化、按库重嵌入/重建、跨库迁移、附加索引（QA/子块）向量同步。
 * <p>
 * 从 {@link DocumentService} 抽出：这一块是「向量库」这一个外部系统的全部读写口，与解析状态机、
 * 文件落盘、回收站生命周期无关，因此独立成类。锁 key 仍复用 {@link DocumentService#DocumentService.REEMBED_LOCK_KEY}，
 * 与检索侧的降级判断保持同一把锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVectorOps {

    /** 锁 TTL：任务每批刷新续期；实例崩溃后最多 TTL 秒自愈（不再永久降级） */
    private static final long REEMBED_LOCK_TTL_SECONDS = 120;

    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final com.wenqu.ai.mapper.KnowledgeBaseMapper kbMapper;
    private final KbVectorStoreRegistry kbVectorStores;
    private final DocumentAclTags documentAclTags;
    private final QaIndexService qaIndexService;
    private final ChildChunkService childChunkService;
    private final KeywordIndexService keywordIndexService;
    private final ConfigService configService;
    private final DynamicEmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;
    private final ParseQueueService parseQueue;

    /** 按来源块清理附加索引：向量 + 行（QA 与子块两套都处理；best-effort，失败告警不阻断主流程） */
    public void deleteExtrasByKnowledge(List<String> knowledgeIds, String docId) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return;
        try {
            List<String> vecIds = new ArrayList<>(qaIndexService.vectorIdsByKnowledge(knowledgeIds));
            vecIds.addAll(childChunkService.vectorIdsByKnowledge(knowledgeIds));
            if (!vecIds.isEmpty()) {
                try {
                    storeOf(docId).delete(vecIds);
                } catch (Exception e) {
                    log.warn("清理附加索引向量失败（{} 个，残留可重解析清理）: {}", vecIds.size(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("读取附加索引向量失败: {}", e.getMessage());
        }
        try {
            qaIndexService.deleteByKnowledgePhysical(knowledgeIds);
        } catch (Exception e) {
            log.warn("删除 QA 行失败: {}", e.getMessage());
        }
        try {
            childChunkService.deleteByKnowledgePhysical(knowledgeIds);
        } catch (Exception e) {
            log.warn("删除子块行失败: {}", e.getMessage());
        }
    }

    /** 按文档清理附加索引（删除文档/回滚/解析中断补偿用） */
    public void deleteExtrasByDoc(String docId) {
        if (docId == null || docId.isBlank()) return;
        try {
            List<String> vecIds = new ArrayList<>(qaIndexService.vectorIdsByDoc(docId));
            vecIds.addAll(childChunkService.vectorIdsByDoc(docId));
            if (!vecIds.isEmpty()) {
                try {
                    storeOf(docId).delete(vecIds);
                } catch (Exception e) {
                    log.warn("清理文档附加索引向量失败（{} 个）: {}", vecIds.size(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("读取文档附加索引向量失败: {}", e.getMessage());
        }
        try {
            qaIndexService.deleteByDocPhysical(docId);
        } catch (Exception e) {
            log.warn("删除文档 QA 行失败: {}", e.getMessage());
        }
        try {
            childChunkService.deleteByDocPhysical(docId);
        } catch (Exception e) {
            log.warn("删除文档子块行失败: {}", e.getMessage());
        }
    }

    /** 附加索引向量按（新）向量库恢复：QA 从问法行、子块从切片行（不调 LLM；失败可重解析补齐） */
    private void reembedExtras(List<String> docIds, VectorStore target) {
        try {
            int qaOk = qaIndexService.reembedForDocs(docIds, target);
            if (qaOk > 0) log.info("[Reembed] QA 向量恢复 {} 条", qaOk);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] QA 向量恢复失败（可重解析补齐）: {}", e.getMessage());
        }
        try {
            int childOk = childChunkService.reembedForDocs(docIds, target);
            if (childOk > 0) log.info("[Reembed] 子块向量恢复 {} 条", childOk);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 子块向量恢复失败（可重解析补齐）: {}", e.getMessage());
        }
    }

    /**
     * 按文档路由向量库：doc.kbId → 所属知识库绑定的向量模型（KbVectorStoreRegistry，每库独立索引）。
     * 文档行查不到（已删/迁移竞态）按默认库路由，保证向量有归属、后续删除清理不落空。
     * 回收站行（软删）对标准查询不可见，purge 时必须回退回收站行取归属库——否则路由到默认库，
     * 向量删除打在错误索引上，回收站「彻底删除」恒失败。
     */
    public VectorStore storeOf(String docId) {
        String kbId = null;
        if (docId != null && !docId.isBlank()) {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc == null) doc = documentMapper.selectRecycledById(docId);
            kbId = doc == null ? null : doc.getKbId();
        }
        return kbVectorStores.storeForKb(kbId);
    }

    /**
     * 文档所属知识库的创建者 uid（问答对生成模型回落库主个人默认聊天模型 User.defaultModel，
     * 按库主身份解析；解析是异步链路，线程里没有请求身份/个人覆盖，必须显式查库主）。查不到返回 ""（按未配置处理）。
     */
    public String kbOwnerOfDoc(String docId) {
        try {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc == null || doc.getKbId() == null || doc.getKbId().isBlank()) return "";
            com.wenqu.ai.model.KnowledgeBase kb = kbMapper.selectById(doc.getKbId());
            return kb == null || kb.getCreatedBy() == null ? "" : kb.getCreatedBy();
        } catch (Exception e) {
            log.warn("[{}] 库主解析失败（问答对生成按未配置处理）: {}", docId, e.getMessage());
            return "";
        }
    }

    /**
     * 单知识块向量化并入库（供手动新增知识块复用；embedding 失败降级返回 false，不阻断入库）
     * 成功后回写 vector_id = knowledgeId（与文档解析链路一致）
     */
    public boolean embedAndStore(Knowledge k, String content) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (k.getDocId() != null) {
                metadata.put("docId", k.getDocId());
            }
            metadata.put("title", k.getTitle() == null ? "" : k.getTitle());
            metadata.put("knowledgeId", k.getId());
            if (k.getTitlePath() != null && !k.getTitlePath().isBlank()) {
                metadata.put("titlePath", k.getTitlePath());
            }
            if (k.getImages() != null) {
                metadata.put("images", k.getImages());
            }
            documentAclTags.putKbId(metadata, k.getDocId());
            storeOf(k.getDocId()).add(List.of(new Document(k.getId(),
                    DocumentTextUtils.buildEmbedText(k.getTitle(), k.getTitlePath(), content, null), metadata)));
            k.setVectorId(k.getId());
            knowledgeMapper.updateById(k);
            keywordIndexService.indexChunks(List.of(k)); // 关键词索引同步（best-effort）
            return true;
        } catch (Exception e) {
            log.warn("知识块向量化失败 id={}: {}", k.getId(), e.getMessage());
            return false;
        }
    }

    /** 抢占全量重嵌入分布式锁（setIfAbsent + TTL）；false=其它实例正在执行 */
    private boolean acquireReembedLock() {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                    DocumentService.REEMBED_LOCK_KEY, "1", java.time.Duration.ofSeconds(REEMBED_LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[Reembed] 分布式锁不可用（Redis 异常），按单实例语义继续: {}", e.getMessage());
            return true;
        }
    }

    /** 批处理循环中续期锁（防长任务执行中被 TTL 误释放，崩溃后自然过期自愈） */
    private void refreshReembedLock() {
        try {
            redisTemplate.expire(DocumentService.REEMBED_LOCK_KEY, java.time.Duration.ofSeconds(REEMBED_LOCK_TTL_SECONDS));
        } catch (Exception ignored) {
            // 续期失败不阻断任务本体
        }
    }

    /** 释放分布式锁（仅持有者调用） */
    private void releaseReembedLock() {
        try {
            redisTemplate.delete(DocumentService.REEMBED_LOCK_KEY);
        } catch (Exception ignored) {
        }
    }

    /**
     * 按库重嵌入（异步）：知识库切换/清空绑定向量模型后调用。
     * 旧向量随旧索引/全局索引清除后，全部块按新模型重新向量化写回，并回写本库维度。
     * 逐库执行（分布式锁与全量重嵌入互斥）；失败仅记日志——可在修复后再次触发。
     *
     * @param docIds 该库全部文档 ID（调用方经 KnowledgeBaseService.docIdsOf 取好；空库=仅建/删索引）
     */
    public void reembedKbAsync(String kbId, List<String> docIds, String oldRef, String newRef) {
        Thread t = new Thread(() -> {
            if (!acquireReembedLock()) {
                log.warn("[FAIL-LOUD] [KB-Reembed] 全量重嵌入正在进行，知识库 {} 的按库重嵌未执行（请稍后重试）", kbId);
                return;
            }
            try {
                reembedKb(kbId, docIds == null ? List.of() : docIds, oldRef, newRef);
            } catch (Exception e) {
                log.error("[FAIL-LOUD] [KB-Reembed] 知识库 {} 按库重嵌失败: {}", kbId, e.getMessage(), e);
            } finally {
                releaseReembedLock();
            }
        }, "reembed-kb-" + kbId);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同模型重建本库向量（异步，运维用）：<b>不需要换向量模型</b>，用当前绑定重跑一遍。
     *
     * <p><b>为什么需要这个入口</b>：{@link #reembedKbAsync} 只在
     * <b>embeddingRef 变更</b>时被 {@code KnowledgeBaseController.update} 触发
     * （条件是 {@code !oldRef.equals(newRef)}），于是有两类运维操作没有入口：
     * <ol>
     *   <li><b>存量向量缺 ACL 标签</b>（FT.ALTER 只改schema 不回填
     *       历史 JSON），需重建才能让全部块参与下推过滤；</li>
     *   <li>索引 schema 被动过（如字段 SEPARATOR 声明错误，RediSearch 不支持改已有字段属性，
     *       只能 DROP 重建）后，需要一个不换模型的重建途径。</li>
     * </ol>
     * 传 oldRef=newRef 即走「DROP 索引 → 按当前模型重新向量化写回」，
     * <b>顺带修好重复/错误声明的 schema</b>（DROP 时 schema 一并丢弃重建）。
     *
     * <p><b>与换绑重嵌共用同一把分布式锁</b>（{@link #DocumentService.REEMBED_LOCK_KEY}）：
     * 并发调用时后来者直接跳过并告警，不会两实例同时 DROP 同一索引。
     */
    public void rebuildKbVectorsAsync(String kbId) {
        Thread t = new Thread(() -> {
            if (!acquireReembedLock()) {
                log.warn("[FAIL-LOUD] [KB-Rebuild] 全量重嵌入正在进行，知识库 {} 的重建未执行（请稍后重试）", kbId);
                return;
            }
            try {
                KnowledgeBase kb = kbMapper.selectById(kbId);
                String ref = kb == null || kb.getEmbeddingRef() == null ? "" : kb.getEmbeddingRef().trim();
                if (ref.isBlank()) {
                    // 绑定必填（validateEmbeddingRef），走到这里说明上游校验失效，fail-loud 不静默回落
                    log.error("[FAIL-LOUD] [KB-Rebuild] 知识库 {} 未绑定向量模型，无法重建", kbId);
                    return;
                }
                List<String> docIds = documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                                .select(AiDocument::getId)
                                .eq(AiDocument::getKbId, kbId)
                                .eq(AiDocument::getDeleted, 0)).stream()
                        .map(AiDocument::getId).toList();
                // oldRef=newRef：模型不变，仅 DROP 索引后按当前模型重新向量化写回
                // （顺带刷 ACL 标签 + 修正被动过的 schema）
                log.info("[KB-Rebuild] 知识库 {} 触发同模型重建：模型={}，文档 {} 个", kbId, ref, docIds.size());
                reembedKb(kbId, docIds, ref, ref);
            } catch (Exception e) {
                log.error("[FAIL-LOUD] [KB-Rebuild] 知识库 {} 重建失败: {}", kbId, e.getMessage(), e);
            } finally {
                releaseReembedLock();
            }
        }, "rebuild-kb-" + kbId);
        t.setDaemon(true);
        t.start();
    }

    private void reembedKb(String kbId, List<String> docIds, String oldRef, String newRef) {
        String old = oldRef == null ? "" : oldRef.trim();
        String neu = newRef == null ? "" : newRef.trim();
        // 1. 新模型维度护栏：探测失败则回退本库 embedding_ref 到旧值（保留旧向量，功能不降级）。
        //    绑定必填（validateEmbeddingRef），newRef 为空属于上游校验失效，fail-loud 不静默落全局库
        if (neu.isEmpty()) {
            throw new IllegalStateException("知识库 " + kbId + " 未绑定新向量模型，无法重嵌入（请在知识库管理中绑定）");
        }
        int newDim;
        try {
            newDim = embeddingModel.forRef(neu).dimensions();
        } catch (Exception e) {
            throw new IllegalStateException("新向量模型维度探测失败（" + e.getMessage() + "），本库保持原绑定", e);
        }
        if (newDim <= 0) throw new IllegalStateException("新向量模型返回维度非法(" + newDim + ")，本库保持原绑定");

        List<Knowledge> rows = docIds.isEmpty() ? List.of() : knowledgeMapper.selectList(
                new LambdaQueryWrapper<Knowledge>().in(Knowledge::getDocId, docIds));

        // 2. 清旧向量：旧绑定=遗留跟随全局（全局索引不再参与路由，残留向量不自动清理）；
        //    旧绑定=自定义引用 → DROP 本库独立索引（连数据）
        if (old.isBlank()) {
            log.info("[KB-Reembed] 知识库 {} 旧绑定跟随全局（已退役），全局索引残留向量如有可在 Redis 手动清理", kbId);
        } else {
            kbVectorStores.dropKbIndex(kbId);
        }
        kbVectorStores.remove(kbId); // 下次访问按新模型重建

        // 3. 按新模型全量重嵌本库
        VectorStore target = kbVectorStores.storeForKb(kbId);
        int batchSize = Math.max(1, configService.getInt("parse.embedBatchSize", ConfigDefaults.PARSE_EMBED_BATCH_SIZE));
        int embedRetry = Math.max(0, configService.getInt("parse.embedRetryCount", ConfigDefaults.PARSE_EMBED_RETRY_COUNT));
        int done = 0;
        int failed = 0;
        // 库门字段：整库重嵌时所有文档同属本库，直接写 kbId，无需逐块查文档行
        //（文档级 ACL 已回晚绑定——检索期按命中块实时查 MySQL 判定，不依赖向量标签）
        {
            for (int i = 0; i < rows.size(); i += batchSize) {
                List<Knowledge> batch = rows.subList(i, Math.min(i + batchSize, rows.size()));
                List<org.springframework.ai.document.Document> docs = new ArrayList<>(batch.size());
                for (Knowledge k : batch) {
                    Map<String, Object> metadata = new HashMap<>();
                    if (k.getDocId() != null) metadata.put("docId", k.getDocId());
                    metadata.put("title", k.getTitle() == null ? "" : k.getTitle());
                    metadata.put("knowledgeId", k.getId());
                    if (k.getTitlePath() != null && !k.getTitlePath().isBlank()) metadata.put("titlePath", k.getTitlePath());
                    if (k.getImages() != null) metadata.put("images", k.getImages());
                    metadata.put(DocumentAclTags.FIELD_KB_ID, kbId);
                    docs.add(new org.springframework.ai.document.Document(k.getId(),
                            DocumentTextUtils.buildEmbedText(k.getTitle(), k.getTitlePath(), k.getContent(), null), metadata));
                }
                try {
                    vectorAddWithRetryInto(target, "kb-" + kbId, docs, embedRetry);
                    done += docs.size();
                } catch (Exception e) {
                    failed += docs.size();
                    log.warn("[FAIL-LOUD] [KB-Reembed] 批次重嵌失败（{} 块）: {}", docs.size(), e.getMessage());
                }
            }
        }
        // 4. 回写本库维度（设置页/排查展示）
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb != null) {
            kb.setEmbeddingDimensions(newDim);
            kb.setUpdateTime(java.time.LocalDateTime.now());
            kbMapper.updateById(kb);
        }
        // 5. QA/子块向量按新模型恢复（从落库行重建，不调 LLM；失败可重解析补齐）
        reembedExtras(docIds, target);
        log.info("[KB-Reembed] 知识库 {} 重嵌完成: 模型 {}，成功 {} 块，失败 {} 块，维度 {}", kbId, neu, done, failed, newDim);
    }

    /**
     * 单文档跨库向量迁移（异步）：moveDoc 前后两库向量模型不同时调用——
     * 从原库索引删除该文档向量，按新库模型重新向量化写入；两库模型相同则无需调用。
     */
    public void migrateDocAsync(String docId, String fromKbId, String toKbId) {
        Thread t = new Thread(() -> {
            try {
                String fromRef = kbRefOf(fromKbId);
                String toRef = kbRefOf(toKbId);
                VectorStore from = kbVectorStores.storeForKb(fromKbId);
                VectorStore to = kbVectorStores.storeForKb(toKbId);
                List<Knowledge> rows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                        .eq(Knowledge::getDocId, docId));
                List<String> vectorIds = rows.stream().map(Knowledge::getVectorId)
                        .filter(java.util.Objects::nonNull).filter(v -> !v.isBlank()).toList();
                // 同 ref 不等于同索引：索引/键前缀按 kbId 隔离（每库独立 RediSearch 索引），
                // 模型相同只是「无需重嵌入」——向量仍要从旧库索引搬运到新库索引，否则新库向量路
                // 召回不到（同名 upsert 跨库导入、同模型库间移动文档都会踩中）。
                // QA/子块向量随迁：从旧库索引删除，新库从落库行恢复（不调 LLM）；同 ref 分支也照搬（索引按库隔离）
                try {
                    List<String> extras = new ArrayList<>(qaIndexService.vectorIdsByDoc(docId));
                    extras.addAll(childChunkService.vectorIdsByDoc(docId));
                    if (!extras.isEmpty()) from.delete(extras);
                    reembedExtras(List.of(docId), to);
                } catch (Exception e) {
                    log.warn("[DOC-MIGRATE] 文档 {} 附加索引向量迁移失败（可重解析补齐）: {}", docId, e.getMessage());
                }
                for (Knowledge k : rows) {
                    Map<String, Object> metadata = new HashMap<>();
                    if (k.getDocId() != null) metadata.put("docId", k.getDocId());
                    metadata.put("title", k.getTitle() == null ? "" : k.getTitle());
                    metadata.put("knowledgeId", k.getId());
                    if (k.getTitlePath() != null && !k.getTitlePath().isBlank()) metadata.put("titlePath", k.getTitlePath());
                    if (k.getImages() != null) metadata.put("images", k.getImages());
                    to.add(List.of(new org.springframework.ai.document.Document(k.getId(),
                            DocumentTextUtils.buildEmbedText(k.getTitle(), k.getTitlePath(), k.getContent(), null), metadata)));
                }
                // 主块向量「先拷后删」，删不分模型同不同：同模型分支若只搬不删，旧库索引会永久残留
                // 这份已搬走的文档，A 库检索照样命中它（可见性按文档当前 kbId 判定，跨库泄漏/白占
                // topK），直到重建索引。放在 to.add 全部成功之后：迁移中途失败时旧库召回还在，不会两头都空。
                if (!vectorIds.isEmpty()) {
                    from.delete(vectorIds);
                }
                log.info("[DOC-MIGRATE] 文档 {} 向量已迁移: {} → {}（{} 块）", docId, fromRef, toRef, rows.size());
            } catch (Exception e) {
                log.error("[FAIL-LOUD] [DOC-MIGRATE] 文档 {} 跨库向量迁移失败（新库仅关键词可召回，可重新解析补齐）: {}",
                        docId, e.getMessage(), e);
            }
        }, "migrate-doc-" + docId);
        t.setDaemon(true);
        t.start();
    }

    /** 库 ID → 其绑定向量模型引用（后台线程调用，无请求态；kbId 空/库不存在/未绑定一律返回空串，由调用方跳过向量迁移） */
    private String kbRefOf(String kbId) {
        if (kbId == null || kbId.isBlank()) return "";
        com.wenqu.ai.model.KnowledgeBase kb = kbMapper.selectById(kbId.trim());
        return kb == null || kb.getEmbeddingRef() == null ? "" : kb.getEmbeddingRef();
    }

    /** 向量化单批写入（指定目标库的重载，供按库重嵌使用） */
    private void vectorAddWithRetryInto(VectorStore store, String tag, List<org.springframework.ai.document.Document> batch,
                                        int retryCount) {
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                // 并发闸：多文档并行解析时 vectorAdd 会把 embedding 服务打爆，闸门放行的批数由 parse.embedConcurrency 控制
                parseQueue.acquireEmbedPermission();
                try {
                    store.add(batch);
                } finally {
                    parseQueue.releaseEmbedPermission();
                }
                return;
            } catch (Exception e) {
                lastErr = e;
                if (attempt < retryCount) {
                    log.warn("[FAIL-LOUD] [{}] 向量化批次失败（第 {} 次重试）size={}: {}",
                            tag, attempt + 1, batch.size(), errText(e), e);
                }
            }
        }
        throw lastErr == null ? new IllegalStateException("向量化失败") : new IllegalStateException("向量化批次失败: " + errText(lastErr), lastErr);
    }

    /** 异常可读描述：message 为空时退回异常类名（HTTP/Redis 客户端常见无 message 的异常） */
    private static String errText(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + m;
    }

    /**
     * 向量化单批写入（fail-loud）：失败自动重试 retryCount 次，仍失败抛异常 → 文档整体失败/回退，
     * 绝不静默丢块（否则文档置成功但部分块仅关键词可召回）。
     */
    public void vectorAddWithRetry(String docId, List<Document> batch, int retryCount, String batchLabel) {
        // 按文档路由向量库（每批解析一次归属；批内同文档，一次 selectById 开销可忽略）
        VectorStore store = storeOf(docId);
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                // 并发闸（parse.embedConcurrency）：向量化是解析链路里最重的下游，必须限并发
                parseQueue.acquireEmbedPermission();
                try {
                    store.add(batch);
                } finally {
                    parseQueue.releaseEmbedPermission();
                }
                return;
            } catch (Exception e) {
                lastErr = e;
                if (attempt < retryCount) {
                    // 打完整堆栈：向量化失败的根因大多是上游异常且常常没有 message（HTTP/Redis），
                    // 只记 getMessage() 会把真实错误吞成一行 "null"，事后无法定位。
                    log.warn("[FAIL-LOUD] [{}] 向量化批次失败（第 {} 次重试）batch={} size={}: {}",
                            docId, attempt + 1, batchLabel, batch.size(), errText(e), e);
                }
            }
        }
        throw new RuntimeException("向量化批次失败: " + errText(lastErr)
                + "（批次 " + batchLabel + "，共 " + batch.size() + " 块）", lastErr);
    }
}
