package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文档管理服务
 * 上传（解析器声明格式：docx/pdf/xlsx/ppt/pptx/txt/md/csv 等）→ 异步解析分块 → 向量化存 Redis → 元数据存 MySQL
 * 状态流转：2 解析中 → 0 生效 / 3 解析失败（fail_reason）
 * 一致性：解析/向量失败主动补偿清理（删向量+MySQL+图片），避免孤儿数据
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {


    private final AiDocumentMapper documentMapper;
    private final com.wenqu.ai.mapper.KnowledgeBaseMapper kbMapper;
    private final AppProperties properties;
    private final DocumentMetaCache documentMetaCache;

    /** 回收站与彻底删除（见 DocumentRecycleService） */
    private final DocumentRecycleService recycleService;
    /** 版本快照与回滚（见 DocumentVersionService） */
    private final DocumentVersionService versionService;

    /** 向量写入/重建/迁移/附加索引清理（见 DocumentVectorOps） */
    private final DocumentVectorOps vectorOps;

    /** 源文件/图片文件存取与清理（见 DocumentFileStore） */
    private final DocumentFileStore fileStore;

    /** 只读统计聚合（见 DocumentStatsService） */
    private final DocumentStatsService statsService;

    /** 知识块/标签写操作（见 DocumentChunkService） */
    private final DocumentChunkService chunkService;

    /** 入库链路：上传/导入/替换/网页刷新（见 DocumentIngestService） */
    private final DocumentIngestService ingestService;

    /** 解析执行链路（见 DocumentParsePipeline） */
    private final DocumentParsePipeline parsePipeline;


    /**
     * 取源文件供下载（个人文件区：用户上传的原始文件可取回）。
     * 文档不存在或源文件缺失（旧数据未保留）时抛可读业务异常。
     */
    public DocumentFileStore.SourceFile sourceFileForDownload(String docId) {
        return fileStore.sourceFileForDownload(docId);
    }

    // ==================== 向量相关公开入口（门面委托，实现见 DocumentVectorOps） ====================

    /** 单知识块向量化并入库（供手动新增/编辑知识块复用；embedding 失败降级返回 false，不阻断入库） */
    public boolean embedAndStore(Knowledge k, String content) {
        return vectorOps.embedAndStore(k, content);
    }

    /** 按库重嵌入（异步）：知识库切换/清空绑定向量模型后调用 */
    public void reembedKbAsync(String kbId, List<String> docIds, String oldRef, String newRef) {
        vectorOps.reembedKbAsync(kbId, docIds, oldRef, newRef);
    }

    /** 同模型重建本库向量（异步，运维用） */
    public void rebuildKbVectorsAsync(String kbId) {
        vectorOps.rebuildKbVectorsAsync(kbId);
    }

    /** 单文档跨库向量迁移（异步）：两库向量模型不同时调用 */
    public void migrateDocAsync(String docId, String fromKbId, String toKbId) {
        vectorOps.migrateDocAsync(docId, fromKbId, toKbId);
    }

    /** 内容指纹（重解析增量对比、知识块编辑维护用） */
    public String contentHash(String title, String content) {
        return DocumentTextUtils.contentHash(title, content);
    }

    public String contentHash(String title, String titlePath, String content, List<String> images) {
        return DocumentTextUtils.contentHash(title, titlePath, content, images);
    }

    // ==================== 回收站公开入口（门面委托，实现见 DocumentRecycleService） ====================

    /** 删除文档（生效中→回收站；解析中→直接物理清除） */
    public void delete(String docId) {
        recycleService.delete(docId);
    }

    /** 回收站列表（kbId 空=全部） */
    public List<AiDocument> recycleList(String kbId) {
        return recycleService.recycleList(kbId);
    }

    /** 回收站取单条（供控制器做管理权校验） */
    public AiDocument getRecycled(String docId) {
        return recycleService.getRecycled(docId);
    }

    /** 从回收站恢复 */
    public void restore(String docId) {
        recycleService.restore(docId);
    }

    /** 彻底删除（物理清除） */
    public void purge(String docId) {
        recycleService.purge(docId);
    }

    /** 级联彻底删除某个知识库的全部文档 */
    public void purgeByKb(String kbId) {
        recycleService.purgeByKb(kbId);
    }

    // ==================== 版本公开入口（门面委托，实现见 DocumentVersionService） ====================

    /** 文档版本列表（倒序） */
    public List<Map<String, Object>> listVersions(String docId) {
        return versionService.listVersions(docId);
    }

    /** 回滚到指定版本 */
    public void rollback(String docId, int version) {
        versionService.rollback(docId, version);
    }


    public void setTags(String docId, String tags) {
        chunkService.setTags(docId, tags);
    }

    private final ConfigService configService;
    /** 文档解析任务队列：上传/重解析只登记任务，执行由队列扫描器抢占（队列满不丢任务、失败自动退避重试） */
    private final ParseQueueService parseQueue;
    /** 解析过程状态：删除标志/解析线程引用/进度节流（见 DocumentParseState） */
    private final DocumentParseState parseState;
    /** 默认库识别与 kb 归属归一（见 DocumentKbResolver） */
    private final DocumentKbResolver kbResolver;


    /** 全量重嵌入分布式锁 key：持有期间所有实例的向量检索路跳过（降级关键词路），避免命中半成品索引 */
    public static final String REEMBED_LOCK_KEY = "wenqu:reembed:lock";

    /**
     * 文档解析走「持久化任务队列 + 扫描器抢占」（见 {@link ParseQueueService}）：上传只登记任务，
     * worker 池与扫描器都在 ParseQueueService 里，本类不自建解析线程池。
     * 并发上限 parse.concurrency，下游 OCR / 视觉 / embedding 另有闸门限流（改并发前先看那几处）。
     */

    @PostConstruct
    void init() {
        // 跨平台保护：Windows 绝对路径（如 D:/xxx、C:\xxx）在非 Windows 系统上会被 Paths.get() 当作
        // 相对路径，拼到 Tomcat 工作目录下导致上传/落盘失败。检测到即回退默认 ./data 并告警。
        String dir = properties.getImages().getDir();
        if (!File.separator.equals("\\") && dir != null && dir.matches("^[A-Za-z]:[\\\\/].*")) {
            log.warn("images.dir 配置为 Windows 路径 {}，当前系统非 Windows，已回退为默认 ./data；"
                    + "请在启动时通过环境变量 AI_IMAGES_DIR 指定正确的绝对路径", dir);
            properties.getImages().setDir("data");
        }
        // 多副本提示：数据目录（源文件/图片/评估集）必须指向共享存储，各副本才能访问同一批产物
        log.info("数据目录: {}（多副本部署请确认所有实例指向同一共享存储）", properties.getImages().getDir());
        recoverStuckParsing();
    }

    /**
     * 启动对账：把上次进程异常退出（崩溃/kill）残留的"解析中"(status=2) 文档复位为解析失败。
     * 这些文档的解析线程已随进程消失，不复位则永久卡在解析中，且 tryLockParsing 会拒绝重解析/替换。
     * 复位为 3（失败）而非 0：其知识块可能只写了一半，需用户显式重解析或重新上传修复；
     * 向量路与关键词路均按 status=0 过滤，status=3 期间残留半成品不会进入检索上下文。
     * <p>
     * 队列化之后多一层判断：崩溃前刚入队或刚被抢占的任务，行还在 c_ai_parse_task 里（租约会由扫描器回收后重跑），
     * 这种不能判失败——判了就丢掉一次本可以自动恢复的解析。只有**没有任何任务行**的残留 status=2 才是真丢失，
     * 才复位为失败等人工处理。
     * <p>
     * 注意：多副本部署时本方法可能复位其他实例正在解析的文档（其检查点会感知并停止）。
     * 若采用多副本，应把 parse.recoverStuckOnStartup 置 false 并改由运维单点执行。
     */
    private void recoverStuckParsing() {
        if (!configService.getBoolean("parse.recoverStuckOnStartup")) {
            log.info("[Recover] 启动对账已关闭（parse.recoverStuckOnStartup=false），跳过 status=2 复位");
            return;
        }
        try {
            List<AiDocument> stuck = documentMapper.selectList(
                    new LambdaQueryWrapper<AiDocument>().eq(AiDocument::getStatus, 2));
            if (stuck.isEmpty()) return;
            int recovered = 0, handed = 0;
            for (AiDocument d : stuck) {
                if (parseQueue.hasActiveTask(d.getId())) {
                    handed++;
                    log.warn("[Recover] 文档 {} ({}) 仍有未结束的解析任务，交给队列重跑（不判失败）",
                            d.getFileName(), d.getId());
                    continue;
                }
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, d.getId())
                        .eq(AiDocument::getStatus, 2)
                        .set(AiDocument::getStatus, 3)
                        .set(AiDocument::getFailReason, "服务重启中断解析，请重新解析或重新上传")
                        .set(AiDocument::getParseDesc, "解析中断(服务重启)"));
                recovered++;
                log.warn("[Recover] 复位残留解析中文档: {} ({})", d.getFileName(), d.getId());
            }
            log.info("[Recover] 启动对账完成：复位 {} 个残留'解析中'文档，{} 个已交队列重跑", recovered, handed);
        } catch (Exception e) {
            log.warn("[Recover] 启动对账失败: {}", e.getMessage());
        }
    }


    public AiDocument upload(MultipartFile file, String description, String kbId, String visionRef) throws Exception {
        return ingestService.upload(file, description, kbId, visionRef);
    }


    public AiDocument importFromUrl(String url, String description, String kbId) throws Exception {
        return ingestService.importFromUrl(url, description, kbId);
    }


    public AiDocument ingestText(String title, String content, String kbId, String mode, String docId) throws Exception {
        return ingestService.ingestText(title, content, kbId, mode, docId);
    }

    /** 单次扫描最多触发的刷新数（防一次积压打满抓取/解析链路） */
    private static final int WEB_REFRESH_SCAN_LIMIT = 20;

    public int refreshDueWebSources() {
        return ingestService.refreshDueWebSources();
    }


    public void setRefreshConfig(String docId, int autoRefresh, String cron) {
        ingestService.setRefreshConfig(docId, autoRefresh, cron);
    }


    // ==================== 附加索引（QA 问法 + 父子子块）统一清理/恢复 ====================


    /** 文档行读取（跨库向量迁移判断用；不存在返回 null） */
    public AiDocument getDoc(String docId) {
        return docId == null || docId.isBlank() ? null : documentMapper.selectById(docId);
    }


    private void updateProgress(String docId, int progress, String desc) {
        updateProgress(docId, progress, desc, false);
    }

    /**
     * terminal=true 的终态描述（解析完成/失败/补描述失败）不许被「进度没变」节流吞掉：
     * 失败回退与补描述 fail-loud 都发生在进度已到过同一值之后（回退取 guard 现值、补描述恒 100），
     * 吞掉就是状态页永远停在「向量化 12/30」这类半程文案上。终态判定也不能靠 desc 字符串反推——
     * 实际文案从不等于「解析完成/解析失败」裸串，老判据在主路径上从来没命中过。
     */
    private void updateProgress(String docId, int progress, String desc, boolean terminal) {
        parseState.update(docId, progress, desc, terminal);
    }


    /**
     * 知识库解析参数白名单（本库级覆盖项；平台默认值取设置页当前值或 ConfigDefaults 常量）。
     * <p>public：知识库新建表单的"默认值模板"端点需要按同一白名单取生效默认值（见 KnowledgeBaseController）。
     */
    public static final Set<String> PARSE_PARAM_KEYS = Set.of(
            "chunk.maxSize", "chunk.overlap", "chunk.maxChunks", "chunk.maxImages",
            "chunk.structural", "chunk.structuralRatio", "chunk.headingDepth",
            "parse.qaEnabled", "parse.qaPerChunk", "parse.childEnabled", "parse.childSize",
            "parse.ocrEngine", "parse.ocrMinText", "parse.ocrDpi");

    /**
     * 白名单里平台层为固定常量的键 → schema tips 里的文案名：
     * 这些参数只在库级开放，schema 字段清单里没有定义，问号文案按本映射直接取 tips 原文
     * （见 KnowledgeBaseController.paramTips），避免两处解释漂移。
     */
    public static final Map<String, String> PARSE_PARAM_TIPS = Map.of(
            "chunk.maxChunks", "maxChunks",
            "chunk.maxImages", "maxImages",
            "parse.childSize", "childSize",
            "parse.ocrMinText", "ocrMinText",
            "parse.ocrDpi", "ocrDpi");


    /**
     * 文档列表
     */
    public List<AiDocument> list() {
        return list(null);
    }

    /**
     * 按知识库过滤的文档列表（KB 卡片点进后的文档管理页用）。
     * 默认库语义与检索侧一致：kb_id = kbId，且 kbId 为默认库时并上 kb_id IS NULL 的历史文档。
     * kbId 空 = 不过滤（全部文档）。
     */
    public List<AiDocument> list(String kbId) {
        LambdaQueryWrapper<AiDocument> wrapper = new LambdaQueryWrapper<>();
        if (kbId != null && !kbId.isBlank()) {
            wrapper.and(w -> {
                w.eq(AiDocument::getKbId, kbId);
                if (kbId.equals(defaultKbIdOf())) {
                    w.or().isNull(AiDocument::getKbId);
                }
            });
        }
        wrapper.orderByDesc(AiDocument::getCreateTime);
        return documentMapper.selectList(wrapper);
    }

    /** 默认知识库 ID（is_default=1 且未删除；查不到返回 null） */
    /** 默认知识库 ID（is_default=1 且未删除；查不到返回 null） */
    private String defaultKbIdOf() {
        return kbResolver.defaultKbId();
    }

    /** kb_id 为空的历史行归默认库（与上传/检索链路的口径一致）；默认库也不存在时才留空 */
    /** kb_id 为空的历史行归默认库（与上传/检索链路的口径一致）；默认库也不存在时才留空 */
    private String resolveOwnKb(Object rawKb, String defaultKbId) {
        return kbResolver.resolveOwnKb(rawKb, defaultKbId);
    }


    public void updateStatus(String docId, int status) {
        chunkService.updateStatus(docId, status);
    }


    public void batchDelete(List<String> ids) {
        chunkService.batchDelete(ids);
    }


    public void batchUpdateStatus(List<String> ids, int status) {
        chunkService.batchUpdateStatus(ids, status);
    }


    public void updateKnowledge(String id, String title, String content) {
        chunkService.updateKnowledge(id, title, content);
    }


    public void deleteKnowledge(String id) {
        chunkService.deleteKnowledge(id);
    }

    // ==================== 版本管理 ====================


    // ==================== 只读统计（实现见 DocumentStatsService） ====================

    public Map<String, Object> healthReport() {
        return statsService.healthReport();
    }

    public Map<String, Long> statsHitCounts() {
        return statsService.statsHitCounts();
    }

    public Map<String, DocumentStatsService.HitStat> hitStatsByDoc() {
        return statsService.hitStatsByDoc();
    }

    public Map<String, String> docKbMap() {
        return statsService.docKbMap();
    }

    public Map<String, Long> recycleCountsByKb() {
        return statsService.recycleCountsByKb();
    }


    /**
     * 重解析（复用源文件重新走解析流程）
     */
    public void reparse(String docId) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        // 背压先判：CAS 会先把文档置"解析中"，判晚了回滚也会留下脏状态
        if (parseQueue.isBackpressured()) {
            throw new BizException("解析队列已满（" + parseQueue.queuedCount() + "/" + parseQueue.capacity()
                    + "），请稍后再试");
        }
        // 并发防护（多实例也原子）：CAS 抢占"解析中"状态，失败说明已有解析在进行
        ingestService.tryLockParsing(docId);

        // 重解析前先保存当前状态快照（作为版本历史）；旧知识块保留给增量对比（diff），由 processUpload 匹配复用/清理变更块
        try {
            versionService.saveSnapshot(docId, doc.getVersion() == null ? 0 : doc.getVersion());
        } catch (Exception e) {
            log.warn("重解析前保存快照失败: {}", e.getMessage());
        }
        // 不整体删除旧向量与知识块：processUpload 的 diff 式重建依赖它们做 content_hash 匹配
        // （未变块保留 id+向量，变更/删除块由 processUpload 清理；失败时旧内容可回退保留）
        // 也不清空图片目录：内容寻址文件名下，未变图片文件保留供复用块引用，变更/删除图的旧文件由 processUpload 成功后孤儿清扫
        doc.setStatus(2);
        doc.setFailReason(null);
        // 只写该写的列：updateById 全列回写 + NOT_NULL 策略会让 failReason=null 被跳过，
        // 上一次的失败原因残留在文档上（重解析成功后仍挂着旧错误）
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getStatus, 2)
                .set(AiDocument::getFailReason, null));
        documentMetaCache.invalidate(docId);
        updateProgress(docId, 0, "重新解析中（已入队）");

        // 清掉该文档全部既有任务（含作废/孤儿行），随后 enqueue 一条新任务——保证同一文档只有一条有效任务
        parseQueue.purgeByDoc(docId);
        // 登记驱动：手动重解析优先级高于自动排队（priority=1）
        parseQueue.enqueue(docId, doc.getKbId(), 1);
    }


    public void runParseTask(String taskId, String docId) throws Exception {
        parsePipeline.runParseTask(taskId, docId);
    }

    /** 解析任务终态置失败（队列层收口时调用） */
    public void markParseFailed(String docId, String msg) {
        parsePipeline.markParseFailed(docId, msg);
    }

    /** 提交图片描述补齐任务（解析期视觉失败/超限的图后台补描述） */
    public void backfillImageDescriptions(String docId) {
        parsePipeline.backfillImageDescriptions(docId);
    }

    /** 解析参数内置默认值（面板展示用） */
    public Map<String, String> parseParamDefaults() {
        return parsePipeline.parseParamDefaults();
    }


    /** 解析队列统计（全平台口径） */
    public Map<String, Object> queueStats() {
        return parseQueue.queueStats();
    }

    /**
     * 单库解析队列统计（文档管理页页头「排队 N / 执行 M」）：只看该库的任务。
     * 全局口径下别的库在跑也会显示在本库页头，用户读成「我上传的卡住了」。
     * 默认库口径与 {@link #list(String)} 一致：并上 kb_id 为空的历史文档任务。
     */
    public Map<String, Object> queueStats(String kbId) {
        if (kbId == null || kbId.isBlank()) return parseQueue.queueStats();
        com.wenqu.ai.model.KnowledgeBase kb = kbMapper.selectById(kbId);
        boolean includeNullKb = kb != null && Integer.valueOf(1).equals(kb.getIsDefault());
        return parseQueue.queueStats(kbId, includeNullKb);
    }

    /**
     * 终态失败（作废）解析任务清单：文档管理页「作废」胶囊下钻用。
     * 默认库口径与 {@link #list(String)} 一致：并上 kb_id 为空的历史任务。
     */
    public List<Map<String, Object>> deadTasks(String kbId) {
        if (kbId == null || kbId.isBlank()) return parseQueue.deadTasks(null, false);
        com.wenqu.ai.model.KnowledgeBase kb = kbMapper.selectById(kbId);
        boolean includeNullKb = kb != null && Integer.valueOf(1).equals(kb.getIsDefault());
        return parseQueue.deadTasks(kbId, includeNullKb);
    }

    /** 删除一条作废/孤儿解析任务行（清理界面上「作废」计数里已无意义的记录） */
    public int deleteParseTask(String taskId) {
        return parseQueue.deleteTask(taskId);
    }

    /**
     * 按解析任务 ID 取所属文档（删除任务前校验管理权用）：任务行可能对应已删文档，
     * 此时返回 null，调用方据此放行「孤儿任务清理」。
     */
    public AiDocument getDocByTask(String taskId) {
        try {
            String docId = parseQueue.getTaskDocId(taskId);
            if (docId == null) return null;
            return documentMapper.selectById(docId);
        } catch (Exception e) {
            log.warn("[DOC] 按任务查文档失败 task={}: {}", taskId, e.getMessage());
            return null;
        }
    }


    // ==================== 文件管理 ====================


    /** 知识块占位符：有描述 [图片：xxx] 或无描述 [图片] */
    private static final Pattern IMG_PH = Pattern.compile("\\[图片(?:：[^\\]]*)?]");


}
