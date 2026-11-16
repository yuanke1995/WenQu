package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.ParseFatalException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.config.ConfigKeys;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Chunk;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.parser.DocumentParser;
import com.wenqu.ai.parser.DocxParser;
import com.wenqu.ai.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析执行链路：解析 → 分块 → diff 式增量重建 → 向量化 → 附加索引（QA/子块）→ 版本快照 → 收口。
 * 由 {@link ParseQueueService} 的 worker 调用 {@link #runParseTask} 进入；上传/导入只登记任务，见 {@link DocumentIngestService}。
 * <p>
 * 一致性约定：向量化成功后才清理旧块与旧向量；失败只回滚本次新增产物，保留上一版内容可回退。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParsePipeline {
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final DocumentMetaCache documentMetaCache;
    private final com.wenqu.ai.mapper.KnowledgeBaseMapper kbMapper;
    private final DocumentParseState parseState;
    private final ConfigService configService;
    private final AppProperties properties;
    private final DocumentVectorOps vectorOps;
    private final KeywordIndexService keywordIndexService;
    private final QaIndexService qaIndexService;
    private final ChildChunkService childChunkService;
    private final DocumentVersionService versionService;
    private final DocumentFileStore fileStore;
    private final GraphRagService graphRagService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final VisionService visionService;
    private final List<DocumentParser> parsers;
    private final DocxParser docxParser;
    private final NotificationService notificationService;
    /** 解析产物清理与上传链路共用（补偿语义见 cleanupPartial） */
    private final DocumentIngestService ingestService;
    /** 知识块写操作：图片描述补齐后回写块正文走同一条编辑链路 */
    private final DocumentChunkService chunkService;

    /** 图片描述补齐进行中标志（docId -> true；防并发重复触发） */
    private final Map<String, Boolean> descBackfillRunning = new ConcurrentHashMap<>();

    /** 图片占位符：docx 解析出的内嵌图标记 */
    private static final Pattern IMG_PH = Pattern.compile("\\[图片(?:：[^\\]]*)?]");

    private void updateProgress(String docId, int progress, String desc) {
        parseState.update(docId, progress, desc);
    }

    private void updateProgress(String docId, int progress, String desc, boolean terminal) {
        parseState.update(docId, progress, desc, terminal);
    }

    /**
     * 异步解析核心：解析 → MySQL 元数据 + 向量 → 状态置 0；失败按错误类型分流 + 补偿清理 + 异常上抛
     * <p>
     * 失败必须上抛给 {@link ParseQueueService}：队列据此判定 retryable/dead 并发终态通知。
     * 在此吞掉异常会让队列把任务记成 succeeded 并发「解析完成」通知，而文档实际是失败态。
     */
    private void processUpload(String docId, String fileName, Path source, DocumentParser parser) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) return;
        List<Document> aiDocs = new ArrayList<>();
        // 重解析场景（diff 前已有旧知识块）：解析失败时保留旧块回退生效，不整表清空（先建后删容错）
        boolean hadExistingContent = false;
        try {
            // 新解析任务：清残留删除标志 + 记录线程（供 delete() 中断）
            parseState.clearDeleted(docId);
            parseState.bindThread(docId, Thread.currentThread());
            // 解析参数按知识库解析：全局设置（chunk.*）为底 ← 本库 parse_params 覆盖，线程局部生效——
            // 解析器/processUpload 里的 configService 读取自动取到本库值；视觉模型同理（图片描述按库 visionRef）。
            // 仅对本次解析生效，finally 清除；解析参数改动只影响之后的解析（历史文档需重解析）。
            final com.wenqu.ai.model.KnowledgeBase parseKb =
                    doc.getKbId() == null || doc.getKbId().isBlank() ? null : kbMapper.selectById(doc.getKbId());
            applyParseParams(parseKb, doc);
            updateProgress(docId, 5, "开始解析");
            // 流式解析：直接传源文件 Path（已持久落盘），避免大文件全量读入堆内存
            updateProgress(docId, 10, "解析文档内容(图片较多时较慢)");
            List<Chunk> chunks = parser.parse(source, fileName, docId,
                    (percent, desc) -> updateProgress(docId, percent, desc));
            // 分块重叠：不再改写块正文（content 保持净内容），重叠尾巴作为 embedding 文本前缀在循环内计算——
            // 不入库、不进指纹，因此邻块变动不会连锁改变本块指纹（chunk.overlap 可调，0=关闭）
            int overlap = configService.getInt("chunk.overlap", properties.getChunk().getOverlap());
            // 截断保护：超大文档只保留前 maxChunks 块（防止 embedding 调用数万次/解析失控）
            int maxChunks = configService.getInt(ConfigKeys.CHUNK_MAX_CHUNKS, ConfigDefaults.CHUNK_MAX_CHUNKS);
            int truncatedChunks = 0;
            if (maxChunks > 0 && chunks.size() > maxChunks) {
                // fail-loud：截断不再静默——计数入终态 desc（"截断保留前N/共M块"）
                truncatedChunks = chunks.size() - maxChunks;
                log.warn("[{}] 文档过大，解析出 {} 块，按配置截断保留前 {} 块（丢弃 {} 块）", docId, chunks.size(), maxChunks, truncatedChunks);
                chunks = new ArrayList<>(chunks.subList(0, maxChunks));
            }
            if (chunks.isEmpty()) {
                // 终态错误：重试多少次都还是空，交给队列直接置 dead，别白耗一轮退避
                throw new ParseFatalException("文档未解析出任何内容");
            }
            // 删除感知：解析过程中文档被删除则立即停止并清理本次产物（避免孤儿数据/白耗资源）
            if (!isDocAlive(docId)) { ingestService.cleanupPartial(docId, aiDocs); return; }
            updateProgress(docId, 30, "分块完成,准备入库");

            // ===== 增量更新：对比式重建 =====
            // 旧块按 content_hash 索引；内容未变的块保留 knowledgeId+向量（跳过重新 embedding）
            // 存量旧块无 hash（老版本数据）时视为全部变更 → 首次重解析等价全量重建，语义正确
            List<Knowledge> oldList = knowledgeMapper.selectList(
                    new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
            hadExistingContent = !oldList.isEmpty();
            // 库门字段：循环外查一次文档行拿 kbId，本方法所有新块共用（避免每块查库）
            AiDocument docRow = documentMapper.selectById(docId);
            String kbIdOfDoc = docRow == null ? null : docRow.getKbId();
            // 旧块按 content_hash 索引（同内容多块 → List，逐块一一对应出队，避免重复内容块 id 抖动）
            Map<String, List<Knowledge>> oldByHash = new HashMap<>();
            for (Knowledge ok : oldList) {
                if (ok.getContentHash() != null && !ok.getContentHash().isBlank()) {
                    oldByHash.computeIfAbsent(ok.getContentHash(), k -> new ArrayList<>()).add(ok);
                }
            }
            List<Knowledge> staleOld = new ArrayList<>(oldList);  // 未被新块匹配的旧块（内容变更的旧版/被删段落）→ 清理
            List<Knowledge> newBlocks = new ArrayList<>();        // 本次新增/变更块（向量化成功后同步关键词索引）
            int reused = 0, added = 0;
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                Chunk chunk = chunks.get(i);
                // 指纹 = title + 章节路径 + 净正文 + 图片（不含 overlap）：前块变动永不连锁；章节改名重嵌该章（语义正确）
                String hash = DocumentTextUtils.contentHash(chunk.title(), chunk.titlePath(), chunk.content(), chunk.images());
                // 重叠尾巴：上一块净内容尾部 N 字，仅拼入 embedding 文本（向量语义衔接），不入库不进指纹
                String overlapPrefix = "";
                if (overlap > 0 && i > 0) {
                    String prevContent = chunks.get(i - 1).content();
                    if (!prevContent.isEmpty()) {
                        String tail = prevContent.length() <= overlap
                                ? prevContent : prevContent.substring(prevContent.length() - overlap);
                        overlapPrefix = tail.replaceAll("\\[图片[^\\]]*\\]", " ").trim();
                    }
                }
                Knowledge match = null;
                List<Knowledge> bucket = oldByHash.get(hash);
                if (bucket != null && !bucket.isEmpty()) {
                    match = bucket.remove(bucket.size() - 1);
                    if (bucket.isEmpty()) oldByHash.remove(hash);
                }
                if (match != null) {
                    // 内容未变：保留 knowledgeId + 向量；仅更新 chunkIndex
                    staleOld.remove(match);
                    if (match.getChunkIndex() == null || match.getChunkIndex() != i) {
                        match.setChunkIndex(i);
                        knowledgeMapper.updateById(match);
                    }
                    reused++;
                    continue;
                }
                // 新块/变更块：入库 + 待向量化
                Knowledge knowledge = new Knowledge();
                knowledge.setDocId(docId);
                knowledge.setTitle(chunk.title());
                knowledge.setContent(chunk.content());
                knowledge.setTitlePath(chunk.titlePath());
                knowledge.setImages(chunk.images().isEmpty() ? null : JSON.toJSONString(chunk.images()));
                knowledge.setChunkIndex(i);
                knowledge.setContentHash(hash);
                knowledgeMapper.insert(knowledge);
                knowledge.setVectorId(knowledge.getId());
                knowledgeMapper.updateById(knowledge);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", docId);
                metadata.put("title", chunk.title());
                metadata.put("knowledgeId", knowledge.getId());
                if (chunk.titlePath() != null && !chunk.titlePath().isBlank()) {
                    metadata.put("titlePath", chunk.titlePath());
                }
                if (!chunk.images().isEmpty()) {
                    metadata.put("images", JSON.toJSONString(chunk.images()));
                }
                if (kbIdOfDoc != null && !kbIdOfDoc.isBlank()) {
                    metadata.put(DocumentAclTags.FIELD_KB_ID, kbIdOfDoc);
                }
                aiDocs.add(new Document(knowledge.getId(),
                        DocumentTextUtils.buildEmbedText(chunk.title(), chunk.titlePath(), chunk.content(), overlapPrefix), metadata));
                newBlocks.add(knowledge);
                added++;
                // 入库进度：30 → 50（每 10 块上报一次）
                if ((i + 1) % 10 == 0 || i == total - 1) {
                    updateProgress(docId, 30 + Math.min(20, (i + 1) * 20 / total), "入库 " + added + "（保留 " + reused + "）");
                }
                // 删除感知：入库循环内每 10 块检查，删除立即停止（不等循环结束）
                if ((i + 1) % 10 == 0 && !isDocAlive(docId)) {
                    ingestService.cleanupPartial(docId, aiDocs);
                    return;
                }
            }
            // 删除感知：入库后、向量化前再查一次
            if (!isDocAlive(docId)) { ingestService.cleanupPartial(docId, aiDocs); return; }

            // 写入向量库（embedding 接口单次请求有条数上限，需分批；每批失败自动重试 embedRetry 次）
            if (!aiDocs.isEmpty()) {
                int batchSize = Math.max(1, configService.getInt("parse.embedBatchSize", ConfigDefaults.PARSE_EMBED_BATCH_SIZE));
                int embedRetry = Math.max(0, configService.getInt("parse.embedRetryCount", ConfigDefaults.PARSE_EMBED_RETRY_COUNT));
                int totalBatch = (aiDocs.size() + batchSize - 1) / batchSize;
                int batchNo = 0;
                for (int i = 0; i < aiDocs.size(); i += batchSize) {
                    // 删除感知：向量化每批前检查，删除立即停止
                    if (!isDocAlive(docId)) {
                        ingestService.cleanupPartial(docId, aiDocs);
                        return;
                    }
                    int end = Math.min(i + batchSize, aiDocs.size());
                    vectorOps.vectorAddWithRetry(docId, aiDocs.subList(i, end), embedRetry,
                            (i + 1) + "-" + end + "/" + aiDocs.size());
                    batchNo++;
                    // 向量化进度：50 → 95（每批精确上报）
                    updateProgress(docId, 50 + Math.min(45, batchNo * 45 / totalBatch), "向量化 " + end + "/" + aiDocs.size());
                    log.info("[{}] 向量化 {}-{} / {}", docId, i + 1, end, aiDocs.size());
                }
            }

            // 删除感知：向量化后、回写状态前最后确认
            if (!isDocAlive(docId)) { ingestService.cleanupPartial(docId, aiDocs); return; }

            // 关键词索引同步：向量化成功后写入本次新增/变更块（best-effort，失败可用 /search-index/reindex 修复）
            keywordIndexService.indexChunks(newBlocks);

            // QA 增强索引（parse.qaEnabled，库级可经 parse_params 覆盖）：对新增/变更块生成问答对、按问法向量化，
            // 命中后返回来源块。best-effort：失败不回退解析结果（块本身已全量可召回），失败计入终态 desc。
            // 未变块不重生成——其问答对与向量原样保留（diff 复用语义）。
            int qaCount = 0;
            boolean qaFailed = false;
            if (!newBlocks.isEmpty() && configService.getBoolean("parse.qaEnabled", true)) {
                updateProgress(docId, 95, "生成问答对");
                try {
                    qaCount = qaIndexService.generateForBlocks(docId, newBlocks, vectorOps.storeOf(docId),
                            (pct, desc) -> updateProgress(docId, 95 + Math.min(4, pct / 25), desc),
                            vectorOps.kbOwnerOfDoc(docId));
                } catch (Exception e) {
                    qaFailed = true;
                    log.warn("[FAIL-LOUD] [{}] QA 生成失败（块已全量可召回，不影响解析结果）: {}", docId, e.getMessage());
                }
            }

            // 父子分块（parse.childEnabled）：超长块切子块做检索索引，命中后返回父块完整正文。
            // 纯确定性切分（无 LLM），成本仅子块 embedding；best-effort 同 QA。
            int childCount = 0;
            boolean childFailed = false;
            if (!newBlocks.isEmpty() && configService.getBoolean("parse.childEnabled", true)) {
                updateProgress(docId, 95, "生成子块索引");
                try {
                    childCount = childChunkService.generateForBlocks(docId, newBlocks, vectorOps.storeOf(docId),
                            (pct, desc) -> updateProgress(docId, 95 + Math.min(4, pct / 25), desc));
                } catch (Exception e) {
                    childFailed = true;
                    log.warn("[FAIL-LOUD] [{}] 子块索引失败（块已全量可召回，不影响解析结果）: {}", docId, e.getMessage());
                }
            }

            // 清理未被新块匹配的旧块（内容变更的旧版本 / 被删除的段落与图片）。
            // 时机必须在向量化成功之后：若向量化失败，旧块仍保留 → hadExistingContent 回退 status=0 时内容完整可用；
            // 若提前删除，失败后旧版已毁、新版未建成，文档知识块全空（回退失效）。
            if (!staleOld.isEmpty()) {
                List<String> delIds = staleOld.stream().map(Knowledge::getVectorId)
                        .filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
                if (!delIds.isEmpty()) {
                    try {
                        vectorOps.storeOf(docId).delete(delIds);
                    } catch (Exception e) {
                        log.warn("[{}] 增量清理旧向量失败: {}", docId, e.getMessage());
                    }
                }
                for (Knowledge d : staleOld) {
                    try {
                        knowledgeMapper.physicalDeleteById(d.getId());
                    } catch (Exception e) {
                        log.warn("[{}] 增量清理旧块失败: {}", docId, e.getMessage());
                    }
                }
                // 关键词索引同步：删除变更/被删块（best-effort）
                keywordIndexService.deleteChunks(staleOld.stream().map(Knowledge::getId)
                        .filter(Objects::nonNull).toList());
                // 附加索引（QA 问法 + 父子子块）随块清理：漏删会残留僵尸问法/子块命中
                vectorOps.deleteExtrasByKnowledge(staleOld.stream().map(Knowledge::getId)
                        .filter(Objects::nonNull).toList(), docId);
                log.info("[{}] 增量清理旧块 {} 个（内容变更/删除）", docId, staleOld.size());
            }

            // 孤儿图片清扫：删除未被任何剩余知识块引用的图片文件（被删图/变更图的旧文件；未变图保留供复用块引用）
            boolean swept = fileStore.sweepOrphanImages(docId);

            // fail-loud：截断/跳过统计聚合进终态 desc（chunk.maxChunks 截断 + DocxParser 图片统计），前端状态列可见
            String doneDesc = "解析完成(保留 " + reused + " 新增 " + added + " 清理 " + staleOld.size() + ")";
            String statsDesc = parseStatsDesc(docId, truncatedChunks, parser);
            if (!statsDesc.isEmpty()) doneDesc += "；" + statsDesc;
            if (qaCount > 0) doneDesc += "；QA " + qaCount;
            if (qaFailed) doneDesc += "；QA生成失败";
            if (childCount > 0) doneDesc += "；子块 " + childCount;
            if (childFailed) doneDesc += "；子块索引失败";
            if (!swept) doneDesc += "；孤儿清扫失败";
            updateProgress(docId, 100, doneDesc, true);
            // 终态回写只写该写的列。**不能用 updateById(doc)**：doc 是解析开始前读的那一行，
            // 全列回写会把刚由 updateProgress 落库的 parse_progress/parse_desc 覆盖回陈旧值
            // （终态描述退回「重新解析中（已入队）」）；且 updateById 走 MyBatis-Plus 的
            // NOT_NULL 策略，failReason=null 会被跳过 → 上一次的失败原因残留在已入库文档上。
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .set(AiDocument::getChunkCount, chunks.size())
                    .set(AiDocument::getStatus, 0)
                    .set(AiDocument::getFailReason, null));
            doc.setChunkCount(chunks.size());
            doc.setStatus(0);
            doc.setFailReason(null);
            // 版本管理：成功解析后版本号 +1 并保存快照
            try {
                int newVersion = (doc.getVersion() == null ? 0 : doc.getVersion()) + 1;
                doc.setVersion(newVersion);
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, docId)
                        .set(AiDocument::getVersion, newVersion));
                versionService.saveSnapshot(docId, newVersion);
            } catch (Exception e) {
                log.warn("[{}] 保存版本快照失败: {}", docId, e.getMessage());
            }
            log.info("[{}] 解析成功: {} chunks", docId, chunks.size());
            // 图片描述补齐：解析中视觉调用失败/超限的图，后台补描述并回写知识块（图片语义全部进入 RAG）
            backfillImageDescriptions(docId);
            // GraphRAG：库级开关开启时异步抽实体关系三元组（抽取模型与判权按库主，
            // 见 GraphRagService.extractDoc；这里传的 uid 仅作库主缺失时的兜底）
            graphRagService.onDocParsed(docId, null, "user");
            // 解析完成事件（工作流事件触发源 kb.docIndexed）。旁路发布——
            // 与「解析完成」通知同款语义：监听侧自兜异常，这里任何抖动都不许影响解析收口
            try {
                eventPublisher.publishEvent(new DocIndexedEvent(docId, doc.getKbId(),
                        doc.getFileName(), doc.getCreatedBy()));
            } catch (Exception ex) {
                log.warn("[{}] 解析完成事件发布失败（已忽略）: {}", docId, ex.getMessage());
            }
        } catch (Exception e) {
            // 删除场景：线程被 delete() 中断（interrupt）或检查点发现删除 → 只清理产物，不置失败状态
            if (parseState.isDeleted(docId)) {
                log.info("[{}] 解析已被删除中断: {}", docId, e.getMessage());
                ingestService.cleanupPartial(docId, aiDocs);
                return;
            }
            log.error("[{}] 解析失败: {}", docId, e.getMessage());
            // 补偿清理：只清理本次新增的 aiDocs（删向量 + 物理删行），保留 diff 复用/已存在的旧块
            try {
                List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
                if (!vectorIds.isEmpty()) vectorOps.storeOf(docId).delete(vectorIds);
            } catch (Exception ex) {
                log.warn("[{}] 补偿删除向量失败: {}", docId, ex.getMessage());
            }
            for (Document d : aiDocs) {
                try {
                    knowledgeMapper.physicalDeleteById(d.getId());
                } catch (Exception ex) {
                    log.warn("[{}] 补偿删除知识块失败: {}", docId, ex.getMessage());
                }
            }
            // 关键词索引同步：删除本次新增块（best-effort）
            keywordIndexService.deleteChunks(aiDocs.stream().map(Document::getId).toList());
            // 状态分流：**终态错误**（内容/格式类，重试也不会成功）→ 置失败；**偶发错误**（超时/IO/上游 5xx）
            // → 保持「解析中」，等队列退避重试。两者都必须把异常上抛给队列层——否则队列收不到失败信号，
            // 会把任务记成 succeeded 并发「解析完成」通知（文档却是失败态），且偶发错误永远得不到重试。
            String failDesc;
            Integer failStatus;
            String failReason;
            if (hadExistingContent) {
                // 重解析失败：未变旧块仍在（变更块已被 diff 清理），回退到生效状态继续可用
                failStatus = 0;
                failReason = "重解析失败，已保留上一版内容: " + DocumentTextUtils.truncate(e.getMessage());
                failDesc = "解析失败(已回退旧内容)";
            } else if (isFatalParseError(e)) {
                // 全新解析失败：无旧内容可回退，清理图片目录并置失败
                fileStore.cleanupImages(docId);
                failStatus = 3;
                failReason = DocumentTextUtils.truncate(e.getMessage());
                failDesc = "解析失败";
            } else {
                // 偶发失败：文档保持「解析中」，由队列退避后重试；重试耗尽时队列会把它翻成 dead
                failStatus = 2;
                failReason = null;
                failDesc = "解析失败(将自动重试)";
            }
            // 只写该写的列：全列 updateById(doc) 会把陈旧的 parse_progress/parse_desc 写回，
            // 且 failReason=null 被 NOT_NULL 策略跳过 → 上一次的失败原因残留。
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .set(AiDocument::getStatus, failStatus)
                    .set(AiDocument::getFailReason, failReason));
            doc.setStatus(failStatus);
            doc.setFailReason(failReason);
            // 失败：进度保留最后值，desc 与状态口径一致（解析中就说重试，失败才说失败）
            updateProgress(docId, parseState.lastProgressOrZero(docId), failDesc, true);
            // 上抛给队列层：任务状态（retryable/dead）与终态通知由它统一收口。
            // ParseFatalException 原样上抛（队列按它判终态不重试）；其余包成 RuntimeException 走退避重试。
            if (e instanceof ParseFatalException) throw (ParseFatalException) e;
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        } finally {
            // 清理解析线程引用与删除标志（delete() 的 DB 物理删除仍可兜底 isDocAlive）
            parseState.unbindThread(docId);
            parseState.clearDeleted(docId);
            configService.clearOverride();
            visionService.clearParseScope();
        }
    }

    /**
     * 应用知识库级解析参数：parse_params JSON 的白名单键（chunk.*）以线程局部覆盖生效，
     * 视觉模型交给 VisionService 的解析期双槽位（图片描述按库路由；扫描件 OCR 可单独指定）；
     * 未配置的键继承全局。
     * 文档级覆盖：上传时为本文档指定的视觉模型（doc.visionRef）优先——对该文档**所有**图片理解
     * （内嵌图描述与扫描页 OCR）生效；留空 = 双槽位各按库配置。
     */
    private void applyParseParams(com.wenqu.ai.model.KnowledgeBase kb, AiDocument doc) {
        if (kb == null) return;
        String raw = kb.getParseParams();
        if (raw != null && !raw.isBlank()) {
            try {
                com.alibaba.fastjson2.JSONObject p = com.alibaba.fastjson2.JSON.parseObject(raw);
                if (p != null) {
                    Map<String, String> overrides = new LinkedHashMap<>();
                    for (String key : DocumentService.PARSE_PARAM_KEYS) {
                        Object v = p.get(key);
                        if (v != null && !String.valueOf(v).isBlank()) {
                            overrides.put(key, String.valueOf(v).trim());
                        }
                    }
                    if (!overrides.isEmpty()) {
                        configService.putOverrides(overrides);
                        log.info("[{}] 应用知识库「{}」解析参数覆盖: {}", kb.getId(), kb.getName(), overrides.keySet());
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] 知识库「{}」解析参数解析失败（忽略，使用全局设置）: {}", kb.getId(), kb.getName(), e.getMessage());
            }
        }
        String visionRef = parseVisionRef(kb);
        String ocrRef = parseOcrRef(kb);
        String docRef = doc == null || doc.getVisionRef() == null ? "" : doc.getVisionRef().trim();
        if (!docRef.isBlank()) {
            // 文档级覆盖（上传时指定）：本文档所有图片理解都用它，优先于库级双槽位
            visionService.startParseScope(docRef, docRef);
        } else {
            visionService.startParseScope(visionRef, ocrRef);
        }
    }

    /**
     * 解析参数内置默认（知识库弹窗占位符 + 新建库预填的唯一展示源）。
     * <p>全局默认值层已退役（2026-11-14，系统设置不再有「文档解析默认模板」）：
     * 默认值=yml 层 AppProperties（解析读点的 fallback 同源）+ ConfigDefaults 常量，留空=跟随这里的值。
     */
    public Map<String, String> parseParamDefaults() {
        AppProperties.Chunk c = properties.getChunk();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("chunk.maxSize", String.valueOf(c.getMaxSize()));
        m.put("chunk.overlap", String.valueOf(c.getOverlap()));
        m.put("chunk.maxChunks", String.valueOf(ConfigDefaults.CHUNK_MAX_CHUNKS));
        m.put("chunk.maxImages", String.valueOf(ConfigDefaults.CHUNK_MAX_IMAGES));
        m.put("chunk.structural", String.valueOf(c.isStructural()));
        m.put("chunk.structuralRatio", String.valueOf(c.getStructuralRatio()));
        m.put("chunk.headingDepth", String.valueOf(c.getHeadingDepth()));
        m.put("parse.qaEnabled", "true");
        m.put("parse.qaPerChunk", "2");
        m.put("parse.childEnabled", "true");
        m.put("parse.childSize", String.valueOf(ConfigDefaults.PARSE_CHILD_SIZE));
        m.put("parse.ocrEngine", "none");
        m.put("parse.ocrMinText", String.valueOf(ConfigDefaults.PARSE_OCR_MIN_TEXT));
        m.put("parse.ocrDpi", String.valueOf(ConfigDefaults.PARSE_OCR_DPI));
        return m;
    }

    /** 知识库 parse_params 里的 visionRef（空=解析时跳过图片描述） */
    private String parseVisionRef(com.wenqu.ai.model.KnowledgeBase kb) {
        return parseParamRef(kb, "visionRef");
    }

    /** 知识库 parse_params 里的 ocrRef（扫描件 OCR 模型；空=回落 visionRef，跟随语义） */
    private String parseOcrRef(com.wenqu.ai.model.KnowledgeBase kb) {
        return parseParamRef(kb, "ocrRef");
    }

    /** parse_params JSON 取引用键（空/解析失败返回 ""） */
    private String parseParamRef(com.wenqu.ai.model.KnowledgeBase kb, String key) {
        String raw = kb.getParseParams();
        if (raw == null || raw.isBlank()) return "";
        try {
            com.alibaba.fastjson2.JSONObject p = com.alibaba.fastjson2.JSON.parseObject(raw);
            Object v = p == null ? null : p.get(key);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 重解析/上传登记后的任务入口：由 {@link ParseQueueService} 在 worker 线程里调用。
     * 源文件缺失、格式不支持这类问题在这里一次性校验清楚（校验点在解析执行侧，登记侧无权拦）。
     */
    public void runParseTask(String taskId, String docId) throws Exception {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new ParseFatalException("文档不存在，无法解析");
        }
        Path source = fileStore.sourceFile(docId, doc.getFileName());
        if (!Files.exists(source)) {
            throw preflightFailure(doc, "源文件缺失，无法解析（请重新上传：" + doc.getFileName() + "）");
        }
        DocumentParser parser = parsers.stream().filter(p -> p.supports(doc.getFileType()))
                .findFirst().orElse(null);
        if (parser == null) {
            throw preflightFailure(doc, "不支持的文件格式: ." + doc.getFileType());
        }
        processUpload(docId, doc.getFileName(), source, parser);
    }

    /**
     * 预检失败的收口：这类问题重跑不会自愈，判终态（不占退避重试）并把文档从「解析中」落回可读状态。
     * <p>
     * 回写必须在这里做而不是等 {@code processUpload} 的 catch：预检在那个 try 之前，失败时够不着它的
     * 回退逻辑，文档行会一直停在 status=2 —— 前端读成「永远已入队」，且重解析被 {@code tryLockParsing}
     * 的 CAS 永久拒绝（只剩删除能点）。状态口径与 processUpload 的失败分支一致：有旧块回退生效、无旧块置失败。
     */
    private ParseFatalException preflightFailure(AiDocument doc, String msg) {
        boolean hasOldChunks = knowledgeMapper.selectCount(
                new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, doc.getId())) > 0;
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, doc.getId())
                .eq(AiDocument::getStatus, 2)   // 只收自己刚置的「解析中」，不覆盖用户在这期间的停用/弃用
                .set(AiDocument::getStatus, hasOldChunks ? 0 : 3)
                .set(AiDocument::getFailReason, DocumentTextUtils.truncate(hasOldChunks ? "重解析失败，已保留上一版内容: " + msg : msg))
                .set(AiDocument::getParseProgress, 0)
                .set(AiDocument::getParseDesc, hasOldChunks ? "解析失败(已回退旧内容)" : "解析失败"));
        parseState.clearProgress(doc.getId());
        return new ParseFatalException(msg);
    }

    /**
     * 解析终态失败的文档收口：由解析队列在任务判 dead 时调用（致命错误，或可重试错误退避耗尽）。
     * <p>
     * 为什么需要它：偶发失败时 {@code processUpload} 会把文档留在「解析中」等队列重试——
     * 若任务最终被判死（不再重试），文档必须同步落到终态，否则用户永远看到「解析中」，
     * 且 {@code tryLockParsing} 的 CAS 会永久拒绝再次重解析（只剩删除能点）。
     * 状态口径与 {@link #preflightFailure} 一致：有旧块回退生效、无旧块置失败。
     */
    public void markParseFailed(String docId, String msg) {
        try {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc == null) return;
            boolean hasOldChunks = knowledgeMapper.selectCount(
                    new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId)) > 0;
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .eq(AiDocument::getStatus, 2)   // 只收自己置的「解析中」，不覆盖用户在这期间的停用/弃用
                    .set(AiDocument::getStatus, hasOldChunks ? 0 : 3)
                    .set(AiDocument::getFailReason, DocumentTextUtils.truncate(hasOldChunks ? "重解析失败，已保留上一版内容: " + msg : msg))
                    .set(AiDocument::getParseProgress, 0)
                    .set(AiDocument::getParseDesc, hasOldChunks ? "解析失败(已回退旧内容)" : "解析失败"));
            documentMetaCache.invalidate(docId);
            parseState.clearProgress(docId);
        } catch (Exception e) {
            log.warn("[{}] 解析终态失败收口失败: {}", docId, e.getMessage());
        }
    }

    /**
     * 是否终态错误（重试也不会成功）：与队列 {@code ParseQueueService.isFatal} 同一判据。
     * <p>
     * 判据口径必须与队列一致——两边都判终态才置文档失败态；只有一边判终态，
     * 就会出现「文档已置失败、任务却还在退避重试」的错位（重试成功后又得回写状态）。
     */
    private boolean isFatalParseError(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ParseFatalException) return true;
        }
        return false;
    }

    /**
     * 解析统计 → 终态 desc 片段（fail-loud：chunk 截断 + docx 图片截断/类型跳过/落盘失败；全 0 返回空串）。
     * 顺带清理 DocxParser 的 docId 级统计（防泄漏）。
     */
    private String parseStatsDesc(String docId, int truncatedChunks, DocumentParser parser) {
        List<String> parts = new ArrayList<>();
        if (truncatedChunks > 0) parts.add("块截断" + truncatedChunks + "块");
        if (parser instanceof DocxParser dp) {
            try {
                Map<String, Integer> ps = dp.statsOf(docId);
                if (ps.getOrDefault("truncated", 0) > 0) parts.add("图片截断" + ps.get("truncated") + "张");
                if (ps.getOrDefault("typeSkipped", 0) > 0) parts.add("跳过不支持的图片" + ps.get("typeSkipped") + "张");
                if (ps.getOrDefault("persistFailed", 0) > 0) parts.add("图片落盘失败" + ps.get("persistFailed") + "张");
            } finally {
                dp.clearStats(docId);
            }
        }
        return String.join("，", parts);
    }

    /**
     * 补齐文档中"无描述"图片（解析时视觉调用失败/超限留下的裸 [图片] 占位）：
     * 后台逐图调视觉模型补描述，成功则回写知识块（[图片]→[图片：描述]）并重新向量化 + 关键词索引同步，
     * 保证图片语义最终全部进入 RAG。仍失败的图保留裸占位（下次触发/重解析再补），全程不阻断主流程。
     */
    public void backfillImageDescriptions(String docId) {
        if (docId == null || docId.isBlank()) return;
        if (descBackfillRunning.putIfAbsent(docId, Boolean.TRUE) != null) return; // 防重入
        boolean submitted = ThreadPoolManager.execute(() -> {
            try {
                // 补描述跑在共享池线程（非解析线程）：图片描述属"描述"槽位——文档级覆盖优先，其次库级 visionRef
                AiDocument d = documentMapper.selectById(docId);
                com.wenqu.ai.model.KnowledgeBase kb =
                        d == null || d.getKbId() == null || d.getKbId().isBlank()
                                ? null : kbMapper.selectById(d.getKbId());
                String vRef = d != null && d.getVisionRef() != null && !d.getVisionRef().isBlank()
                        ? d.getVisionRef().trim()
                        : (kb == null ? "" : parseVisionRef(kb));
                if (!vRef.isBlank()) visionService.startParseScope(vRef);
                List<Knowledge> blocks = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId)
                                .orderByAsc(Knowledge::getChunkIndex));
                if (blocks.isEmpty()) return;
                // 1. 收集全部无描述图 URL（块内裸 [图片] 占位按序对应 images 列表）
                Set<String> missing = new LinkedHashSet<>();
                for (Knowledge k : blocks) {
                    collectMissingImages(k.getContent(), DocumentTextUtils.parseImages(k.getImages()), missing);
                }
                if (missing.isEmpty()) return;
                // 2. 逐图补描述（串行：视觉模型是本机共享资源，不与解析/问答抢占并发）
                Map<String, String> descByUrl = new HashMap<>();
                for (String url : missing) {
                    String desc = docxParser.describeImageUrl(url);
                    if (desc != null && !desc.isBlank()) descByUrl.put(url, desc);
                }
                if (descByUrl.isEmpty()) return;
                // 3. 回写命中块（裸占位替换；复用 updateKnowledge 重新向量化 + 索引同步）
                int written = 0;
                for (Knowledge k : blocks) {
                    String newContent = replaceMissingImages(k.getContent(), DocumentTextUtils.parseImages(k.getImages()), descByUrl);
                    if (!newContent.equals(k.getContent())) {
                        try {
                            chunkService.updateKnowledge(k.getId(), k.getTitle(), newContent);
                            written++;
                        } catch (Exception e) {
                            log.warn("[{}] 知识块补描述回写失败 id={}: {}", docId, k.getId(), e.getMessage());
                        }
                    }
                }
                log.info("[{}] 图片描述补齐完成：补 {}/{} 张，回写 {} 块", docId, descByUrl.size(), missing.size(), written);
                // fail-loud：补完仍缺的图必须留在解析状态可见（不能只记一条日志）
                int remaining = countRemainingMissing(blocks, descByUrl);
                if (remaining > 0) {
                    log.warn("[FAIL-LOUD] [{}] 图片描述补齐后仍有 {} 张无描述（视觉模型不可用？可稍后重试补描述接口）", docId, remaining);
                    try {
                        AiDocument d2 = documentMapper.selectById(docId);
                        String base = d2 == null || d2.getParseDesc() == null ? "" : d2.getParseDesc();
                        updateProgress(docId, 100,
                                (base.isEmpty() ? "" : base + "；") + "补描述后仍有 " + remaining + " 张图无描述", true);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] 图片描述补齐任务异常: {}", docId, e.getMessage());
            } finally {
                descBackfillRunning.remove(docId);
                visionService.clearParseScope();
            }
        });
        if (!submitted) {
            // fail-loud：任务被共享池丢弃（队列满）——必须落状态，不留无感知缺口
            descBackfillRunning.remove(docId);
            log.error("[FAIL-LOUD] [{}] 图片描述补齐任务被丢弃（后台队列满），请稍后重试", docId);
            try {
                AiDocument d = documentMapper.selectById(docId);
                String base = d == null || d.getParseDesc() == null ? "" : d.getParseDesc();
                updateProgress(docId, 100, (base.isEmpty() ? "" : base + "；") + "补描述未执行（后台队列满，可稍后重试）", true);
            } catch (Exception ignored) {
            }
        }
    }

    /** 收集块内"无描述图"URL：content 中裸 [图片]（后无冒号）按序对应 images 列表 */
    private void collectMissingImages(String content, List<String> images, Set<String> missing) {
        if (content == null || content.isBlank() || images == null || images.isEmpty()) return;
        Matcher m = IMG_PH.matcher(content);
        int idx = 0;
        while (m.find()) {
            String url = images.get(Math.min(idx, images.size() - 1));
            idx++;
            if ("[图片]".equals(m.group()) && url != null && !url.isBlank()) missing.add(url);
        }
    }

    /** 补描述后仍无描述（本次未补上）的图片数（fail-loud 统计用） */
    private int countRemainingMissing(List<Knowledge> blocks, Map<String, String> descByUrl) {
        Set<String> missing = new LinkedHashSet<>();
        for (Knowledge k : blocks) {
            collectMissingImages(k.getContent(), DocumentTextUtils.parseImages(k.getImages()), missing);
        }
        int remain = 0;
        for (String url : missing) {
            String desc = descByUrl.get(url);
            if (desc == null || desc.isBlank()) remain++;
        }
        return remain;
    }

    /** 替换块内已补到描述的裸占位（[图片]→[图片：描述]）；无命中返回原 content */
    private String replaceMissingImages(String content, List<String> images, Map<String, String> descByUrl) {
        if (content == null || content.isBlank() || images == null || images.isEmpty() || descByUrl.isEmpty()) {
            return content;
        }
        Matcher m = IMG_PH.matcher(content);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        boolean changed = false;
        while (m.find()) {
            String ph = m.group();
            String url = images.get(Math.min(idx, images.size() - 1));
            idx++;
            if ("[图片]".equals(ph)) {
                String desc = descByUrl.get(url);
                if (desc != null && !desc.isBlank()) {
                    // 描述含 ASCII 方括号会截断 [图片：…] 标记（IMG_PH 等正则约定标记内不含 ]），统一换全角
                    String safe = desc.replace("[", "［").replace("]", "］");
                    m.appendReplacement(sb, "[图片：" + Matcher.quoteReplacement(safe) + "]");
                    changed = true;
                    continue;
                }
            }
            m.appendReplacement(sb, ph);
        }
        m.appendTail(sb);
        return changed ? sb.toString() : content;
    }

    /**
     * 删除感知：内存删除标志优先（delete() 立即置位）；兜底查 DB（物理删除后 selectById 为 null）
     */
    private boolean isDocAlive(String docId) {
        if (parseState.isDeleted(docId)) return false;
        return documentMapper.selectById(docId) != null;
    }

}
