package com.wisesoft.ai.service;

import com.wisesoft.ai.util.RequestUser;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.model.Chunk;
import com.wisesoft.ai.parser.DocumentParser;
import com.wisesoft.ai.parser.DocxParser;
import com.wisesoft.ai.thread.ThreadPoolManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文档管理服务
 * 上传（docx/pdf/xlsx）→ 异步解析分块 → 向量化存 Redis → 元数据存 MySQL
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
    private final KnowledgeMapper knowledgeMapper;
    private final com.wisesoft.ai.mapper.AiDocumentVersionMapper versionMapper;
    private final VectorStore vectorStore;
    private final AppProperties properties;
    private final DocumentMetaCache documentMetaCache;
    private final com.wisesoft.ai.mapper.QaLogMapper qaLogMapper;
    private final List<DocumentParser> parsers;
    private final ConfigService configService;
    private final KeywordIndexService keywordIndexService;
    /** 知识块引用关系（交叉引用识别 + 1-hop 扩散）：与块/文档同生命周期重建 */
    private final ResourceVisibilityService resourceVisibilityService;
    /** 向量模型（@Primary 为 DynamicEmbeddingModel）：重嵌入前探测新维度用 */
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;
    /** docx 解析器：图片描述补齐用（解析时失败/超限的图，按 URL 重新描述） */
    private final DocxParser docxParser;
    /** Redis：全量重嵌入分布式互斥锁（多副本共享库时防两个实例互删对方正在重建的索引） */
    private final StringRedisTemplate redisTemplate;
    /** 解析进度节流守卫：docId -> 已上报 progress（值未变化不写库） */
    private final Map<String, Integer> progressGuard = new ConcurrentHashMap<>();
    /** 图片描述补齐进行中标志（docId -> true；防并发重复触发） */
    private final Map<String, Boolean> descBackfillRunning = new ConcurrentHashMap<>();
    /** 删除标志：delete() 立即置位，解析线程检查点秒查（不等 DB） */
    private final Map<String, Boolean> deletedFlags = new ConcurrentHashMap<>();
    /** 解析线程引用：delete() 时 interrupt 实现立即中断（图片 join 等待立即响应） */
    private final Map<String, Thread> parseThreads = new ConcurrentHashMap<>();
    /** 同名上传串行锁：避免并发上传同一文件名时双方都判定"无可复用"而产生重复文档（单实例内有效） */
    private final Map<String, Object> uploadLocks = new ConcurrentHashMap<>();

    /** 向量库索引名（DROP/重建索引用，与 spring.ai.vectorstore.redis.index-name 一致） */
    @Value("${spring.ai.vectorstore.redis.index-name:ai-doc-index}")
    private String vectorIndexName;

    /**
     * 向量库 schema 自动初始化开关（与 RedisVectorStore 自动配置同源）。
     * 重嵌入护栏：为 false 时 afterPropertiesSet() 不会重建索引，DROP 之后将无索引可写可查，
     * 向量路彻底不可用且无法自愈——必须先于 DROP 拒绝任务。
     */
    @Value("${spring.ai.vectorstore.redis.initialize-schema:true}")
    private boolean vectorInitializeSchema;

    /** 全量重嵌入任务状态（设置页查询/展示；字段 volatile 供异步线程写、接口线程读） */
    private final ReembedStatus reembedStatus = new ReembedStatus();
    private final AtomicBoolean reembedRunning = new AtomicBoolean(false);

    /** 全量重嵌入分布式锁 key：持有期间所有实例的向量检索路跳过（降级关键词路），避免命中半成品索引 */
    public static final String REEMBED_LOCK_KEY = "ai-doc:reembed:lock";
    /** 锁 TTL：任务每批刷新续期；实例崩溃后最多 TTL 秒自愈（不再永久降级） */
    private static final long REEMBED_LOCK_TTL_SECONDS = 120;

    /** 重嵌入状态快照（设置页/接口用） */
    public static class ReembedStatus {
        public volatile String status = "idle";   // idle / running / done / failed
        public volatile int total;
        public volatile int done;
        public volatile int failed;
        public volatile String error;
        public volatile long startTime;
        public volatile long endTime;
        /** 切换前索引维度（取自 embedding.dimensions 记录，0=首次/未知） */
        public volatile int oldDim;
        /** 本次重建所用新模型维度（探测得到，索引 schema 按此重建） */
        public volatile int newDim;
        /** 对账：任务结束时索引内实际文档数（与 done 对比可发现丢块） */
        public volatile int indexed;
    }

    /** 解析线程池（并发 parse.concurrency 可调：避免多文档同时解析打爆 embedding/Ollama；保存即生效） */
    private ThreadPoolExecutor parseExecutor;

    /** 提交解析任务前同步并发数（parse.concurrency，DB 配置保存即生效） */
    private void syncParseConcurrency() {
        int c = configService.getInt("parse.concurrency", 2);
        if (c > 0 && c != parseExecutor.getCorePoolSize()) {
            parseExecutor.setCorePoolSize(c);
            parseExecutor.setMaximumPoolSize(c);
            log.info("[Parse] 解析并发调整为 {}", c);
        }
    }

    @PostConstruct
    void init() {
        // 命名线程工厂：线程 dump 可辨识解析任务归属。非守护线程——停机时 shutdown() 不打断在跑解析，
        // 让其自然收尾（真被强杀残留的 status=2 由启动对账 recoverStuckParsing 复位）
        parseExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(50),
                r -> new Thread(r, "doc-parse"));
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
            for (AiDocument d : stuck) {
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, d.getId())
                        .eq(AiDocument::getStatus, 2)
                        .set(AiDocument::getStatus, 3)
                        .set(AiDocument::getFailReason, "服务重启中断解析，请重新解析或重新上传")
                        .set(AiDocument::getParseDesc, "解析中断(服务重启)"));
                log.warn("[Recover] 复位残留解析中文档: {} ({})", d.getFileName(), d.getId());
            }
            log.info("[Recover] 启动对账完成，复位 {} 个残留'解析中'文档", stuck.size());
        } catch (Exception e) {
            log.warn("[Recover] 启动对账失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        parseExecutor.shutdown();
    }

    /**
     * 上传文档：校验格式 → 同名替换 → 源文件落盘 → 建记录(解析中) → 异步解析
     */
    public AiDocument upload(MultipartFile file, String description) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BizException("文件名为空");
        }
        String ext = extOf(fileName);
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(ext))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持的文件格式: ." + ext + "（支持 docx/pdf/xlsx）"));
        if (file.isEmpty()) {
            throw new BizException("请选择文件");
        }
        validateMagicBytes(file, ext);

        // 同名串行：并发上传同一文件名时，避免双方都判定"无可复用"而各建一条文档（本实例内互斥；
        // 跨实例仍靠 tryLockParsing 的 CAS 兜底，最坏情况产生一条重复记录，可手动删除）
        Object lock = uploadLocks.computeIfAbsent(fileName, k -> new Object());
        try {
            synchronized (lock) {
                return doUpload(file, fileName, ext, description, parser);
            }
        } finally {
            uploadLocks.remove(fileName, lock);
        }
    }

    /** 上传主体（已按文件名串行）：优先复用同名文档走 diff，否则新建 */
    private AiDocument doUpload(MultipartFile file, String fileName, String ext, String description,
                                DocumentParser parser) throws Exception {
        // 同名文档优先复用其 docId 走 diff 重解析（upsert 语义：文档身份/knowledgeId 稳定，未变块增量复用、只重嵌变更处）；
        // 无可复用（无同名，或同名均解析中已清理）时走全新上传
        AiDocument reusable = reusableTarget(fileName);
        if (reusable != null) {
            return replaceExisting(reusable, file, description, parser);
        }

        // 全新上传：源文件落盘（异步解析需要；重解析复用）
        AiDocument doc = new AiDocument();
        doc.setFileName(fileName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus(2); // 解析中
        doc.setDescription(description);
        doc.setCreatedBy(RequestUser.uid());
        documentMapper.insert(doc);
        documentMetaCache.invalidate(doc.getId());
        updateProgress(doc.getId(), 0, "已提交,等待解析");

        Path source;
        try {
            source = saveSourceFile(file, doc.getId(), fileName);
        } catch (Exception e) {
            // 补偿：源文件落盘失败时清理刚插入的记录，避免残留"解析中"脏数据
            log.warn("源文件落盘失败，清理记录: {} error={}", doc.getId(), e.getMessage());
            documentMapper.deleteById(doc.getId());
            throw e;
        }
        final DocumentParser fp = parser;
        syncParseConcurrency();
        try {
            parseExecutor.submit(() -> processUpload(doc.getId(), fileName, source, fp));
        } catch (RejectedExecutionException e) {
            // L9 fail-loud：队列满（≥50 待解析任务）：拒绝新任务并告知当前队列数，清理本次记录避免脏数据
            int queued = parseExecutor == null ? 0 : parseExecutor.getQueue().size();
            documentMapper.deleteById(doc.getId());
            throw new BizException("解析队列繁忙（当前排队 " + queued + " 个任务），请稍后再试");
        }
        return doc;
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
            vectorStore.add(List.of(new Document(k.getId(),
                    buildEmbedText(k.getTitle(), k.getTitlePath(), content, null), metadata)));
            k.setVectorId(k.getId());
            knowledgeMapper.updateById(k);
            keywordIndexService.indexChunks(List.of(k)); // 关键词索引同步（best-effort）
            return true;
        } catch (Exception e) {
            log.warn("知识块向量化失败 id={}: {}", k.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 全量重嵌入（向量模型热切换后自动触发，也可设置页手动触发）：
     * 向量无法跨模型迁移（不同模型向量空间数学不兼容，即使维度相同语义也不同），
     * 唯一正确做法是清空向量索引后用新模型全量重算。
     * <p>
     * 编排顺序（前两步是护栏，任何一步失败都在动索引之前抛出，旧索引与旧向量保持完整）：
     * <ol>
     *   <li>护栏：initialize-schema 必须为 true，否则 DROP 后无法重建索引（向量路永久不可用）</li>
     *   <li>护栏：探测新模型维度（不可达/维度非法即放弃，服务不降级），与 embedding.dimensions
     *       记录的旧维度比对记日志</li>
     *   <li>DROP 向量索引（连数据）→ 按新维度重建 schema</li>
     *   <li>游标分批重新 embedding 全部知识块</li>
     *   <li>记录新维度到 embedding.dimensions（下次切换的旧维度基线）+ 索引文档数对账</li>
     * </ol>
     * 期间向量检索返回空结果，自动降级关键词路（HybridRetrieval 已有降级），系统不中断；
     * 新文档解析/知识块编辑在重嵌期间写入的向量即为新模型产物，任务覆盖不到的增量部分由
     * 分批游标自然补齐（重嵌开始后新增的块 id 大于游标会被后续批次读到；先写后读的块会被同 id 覆盖）。
     * 已知残留风险：DROP 与并发解析的向量写入撞车时那批向量会落进已删除索引，
     * 由第 6 步对账（indexed vs done）暴露，解析空闲时再触发一次即可补齐。
     */
    public boolean reembedAllAsync() {
        if (!reembedRunning.compareAndSet(false, true)) {
            log.warn("[Reembed] 全量重嵌入任务已在运行，忽略重复触发");
            return false;
        }
        Thread t = new Thread(() -> {
            boolean lockHeld = false;
            try {
                // 多副本互斥：仅一个实例执行 DROP+重建（并发执行会互删对方正在写的索引）；
                // Redis 不可用时退化为仅本地 CAS（原单实例语义，避免锁本身阻断重嵌入）
                lockHeld = acquireReembedLock();
                if (!lockHeld) {
                    reembedStatus.status = "failed";
                    reembedStatus.error = "另一实例正在执行全量重嵌入，本次已跳过（避免索引互删），完成后可重试";
                    log.warn("[Reembed] 另一实例正在执行全量重嵌入（分布式锁被占），本次跳过");
                    return;
                }
                reembedAll();
                reembedStatus.endTime = System.currentTimeMillis();
                reembedStatus.status = "done";
                log.info("[Reembed] 全量重嵌入完成: {}/{} 块, 失败 {} 块, 维度 {}→{}, 索引内 {} 块, 耗时 {}ms",
                        reembedStatus.done, reembedStatus.total, reembedStatus.failed,
                        reembedStatus.oldDim, reembedStatus.newDim, reembedStatus.indexed,
                        reembedStatus.endTime - reembedStatus.startTime);
            } catch (Exception e) {
                reembedStatus.status = "failed";
                reembedStatus.error = e.getMessage();
                log.error("[Reembed] 全量重嵌入失败（已完成 {} 块）: {}", reembedStatus.done, e.getMessage(), e);
            } finally {
                if (lockHeld) {
                    releaseReembedLock();
                }
                reembedStatus.endTime = System.currentTimeMillis();
                reembedRunning.set(false);
            }
        }, "reembed-all");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** 抢占全量重嵌入分布式锁（setIfAbsent + TTL）；false=其它实例正在执行 */
    private boolean acquireReembedLock() {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                    REEMBED_LOCK_KEY, "1", java.time.Duration.ofSeconds(REEMBED_LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[Reembed] 分布式锁不可用（Redis 异常），按单实例语义继续: {}", e.getMessage());
            return true;
        }
    }

    /** 批处理循环中续期锁（防长任务执行中被 TTL 误释放，崩溃后自然过期自愈） */
    private void refreshReembedLock() {
        try {
            redisTemplate.expire(REEMBED_LOCK_KEY, java.time.Duration.ofSeconds(REEMBED_LOCK_TTL_SECONDS));
        } catch (Exception ignored) {
            // 续期失败不阻断任务本体
        }
    }

    /** 释放分布式锁（仅持有者调用） */
    private void releaseReembedLock() {
        try {
            redisTemplate.delete(REEMBED_LOCK_KEY);
        } catch (Exception ignored) {
        }
    }

    public ReembedStatus getReembedStatus() {
        return reembedStatus;
    }

    private void reembedAll() {
        if (!(vectorStore instanceof RedisVectorStore rvs)) {
            throw new IllegalStateException("向量库非 RedisVectorStore，不支持全量重嵌入（当前: "
                    + vectorStore.getClass().getName() + "）");
        }
        // 护栏1（前置，先于任何破坏性操作）：schema 自动初始化关闭时不能 DROP——
        // afterPropertiesSet() 不会重建索引，DROP 后向量路将永久不可用且无法自愈
        if (!vectorInitializeSchema) {
            throw new IllegalStateException("spring.ai.vectorstore.redis.initialize-schema=false，"
                    + "DROP 索引后无法自动重建（向量检索将永久不可用），已拒绝执行重嵌入；"
                    + "请置为 true 后重启，或由运维手动重建索引");
        }
        reembedStatus.status = "running";
        reembedStatus.total = 0;
        reembedStatus.done = 0;
        reembedStatus.failed = 0;
        reembedStatus.error = null;
        reembedStatus.indexed = 0;
        reembedStatus.startTime = System.currentTimeMillis();
        reembedStatus.endTime = 0;
        // 护栏2（前置）：真实探测新模型维度。此时 DynamicEmbeddingModel 已指向新配置，
        // 探测失败说明新模型不可达——在 DROP 之前抛出，旧索引与旧向量保持完整（服务不降级）
        reembedStatus.oldDim = Math.max(0, configService.getInt("embedding.dimensions", 0));
        int newDim;
        try {
            newDim = embeddingModel.dimensions();
        } catch (Exception e) {
            throw new IllegalStateException("新向量模型维度探测失败（" + e.getMessage()
                    + "），已放弃重嵌入并保留旧索引；请先修正向量模型配置", e);
        }
        if (newDim <= 0) {
            throw new IllegalStateException("新向量模型返回维度非法(" + newDim + ")，已放弃重嵌入并保留旧索引");
        }
        reembedStatus.newDim = newDim;
        log.info("[Reembed] 维度护栏通过: 旧索引维度={} → 新模型维度={}{}", reembedStatus.oldDim, newDim,
                reembedStatus.oldDim > 0 && reembedStatus.oldDim != newDim ? "（维度变化，索引 schema 必须重建）" : "");
        // 1. DROP 索引连数据：旧模型向量全部作废（维度不同时 RediSearch schema 也必须重建）
        try {
            rvs.getJedis().ftDropIndexDD(vectorIndexName);
            log.info("[Reembed] 向量索引 {} 已删除（含旧向量数据）", vectorIndexName);
        } catch (Exception e) {
            log.info("[Reembed] 向量索引 {} 不存在或删除失败（空库场景可忽略）: {}", vectorIndexName, e.getMessage());
        }
        // 2. 重建索引 schema：embeddingModel（DynamicEmbeddingModel）此时已是新配置，dimensions() 为新维度
        rvs.afterPropertiesSet();
        // 3. 游标分批全量重嵌（id 升序、LIMIT 翻页，逻辑删除由 MyBatis-Plus 自动过滤；与解析链路同批大小与重试）
        int batchSize = Math.max(1, configService.getInt("parse.embedBatchSize", 10));
        int embedRetry = Math.max(0, configService.getInt("parse.embedRetryCount", 1));
        String lastId = "";
        while (true) {
            List<Knowledge> batch = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                    .gt(Knowledge::getId, lastId)
                    .orderByAsc(Knowledge::getId)
                    .last("LIMIT " + batchSize));
            if (batch.isEmpty()) {
                break;
            }
            lastId = batch.get(batch.size() - 1).getId();
            reembedStatus.total = Math.max(reembedStatus.total, reembedStatus.done + batch.size());
            List<Document> docs = new ArrayList<>(batch.size());
            for (Knowledge k : batch) {
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
                // overlap 前缀是解析期上下文，重嵌时不可恢复，传 null（仅影响分块边界处的语义衔接，影响极小）
                docs.add(new Document(k.getId(),
                        buildEmbedText(k.getTitle(), k.getTitlePath(), k.getContent(), null), metadata));
            }
            try {
                vectorAddWithRetry("reembed", docs, embedRetry);
                reembedStatus.done += docs.size();
            } catch (Exception e) {
                // 单批失败不终止整任务（重嵌是重建性操作，失败块记数，完成后可再次触发补齐）
                reembedStatus.failed += docs.size();
                log.warn("[FAIL-LOUD] [Reembed] 批次重嵌失败（{} 块）: {}", docs.size(), e.getMessage());
            }
            // 续期分布式锁（任务可能持续数分钟~数小时，防止 TTL 期间被误释放导致其它实例切入互删索引）
            refreshReembedLock();
        }
        // 4. 记录本次索引维度：作为下次切换的"旧维度"基线，也让设置页能显示当前索引维度。
        // 只在索引确实按 newDim 重建后写入，失败任务不留下说谎的记录
        configService.putInternal("embedding.dimensions", String.valueOf(newDim));
        // 5. 对账：索引实际文档数 vs 本次成功写入数。两者差异说明有丢块
        // （典型来源：DROP 与并发解析写入撞车，那批向量落进了已删除的索引）
        reembedStatus.indexed = readIndexDocCount(rvs);
        if (reembedStatus.indexed > 0 && reembedStatus.indexed < reembedStatus.done) {
            log.warn("[FAIL-LOUD] [Reembed] 索引对账不一致: 成功写入 {} 块，索引内仅 {} 块"
                    + "（可能与并发解析撞车），建议解析空闲时再触发一次重嵌入补齐",
                    reembedStatus.done, reembedStatus.indexed);
        }
    }

    /**
     * 读取索引内文档数（FT.INFO num_docs），仅用于对账展示——失败返回 0（不影响任务判定成败）
     */
    private int readIndexDocCount(RedisVectorStore rvs) {
        try {
            Object n = rvs.getJedis().ftInfo(vectorIndexName).get("num_docs");
            return n == null ? 0 : Integer.parseInt(String.valueOf(n).trim());
        } catch (Exception e) {
            log.debug("[Reembed] 索引文档数读取失败（跳过对账）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 删除感知：内存删除标志优先（delete() 立即置位）；兜底查 DB（物理删除后 selectById 为 null）
     */
    private boolean isDocAlive(String docId) {
        if (deletedFlags.containsKey(docId)) return false;
        return documentMapper.selectById(docId) != null;
    }

    /**
     * 解析中途停止时的补偿清理：删已写向量 + MySQL 元数据 + 图片目录（文档已被删除，不恢复状态）
     */
    private void cleanupPartial(String docId, List<Document> aiDocs) {
        log.info("[{}] 解析过程中文档已被删除，停止解析并清理本次产物", docId);
        try {
            List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
            if (!vectorIds.isEmpty()) vectorStore.delete(vectorIds);
        } catch (Exception e) {
            log.warn("[{}] 补偿删除向量失败: {}", docId, e.getMessage());
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
        keywordIndexService.deleteByDoc(docId); // 关键词索引同步（best-effort）
        cleanupImages(docId);
    }

    /**
     * 解析进度上报（节流：progress 未变化且非终态时不写库；只更新进度两字段，避免整行 update）
     */
    /**
     * 向量化单批写入（M10 fail-loud）：失败自动重试 retryCount 次，仍失败抛异常 → 文档整体失败/回退，
     * 绝不静默丢块（否则文档置成功但部分块仅关键词可召回）。
     */
    private void vectorAddWithRetry(String docId, List<Document> batch, int retryCount) {
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                vectorStore.add(batch);
                return;
            } catch (Exception e) {
                lastErr = e;
                if (attempt < retryCount) {
                    log.warn("[FAIL-LOUD] [{}] 向量化批次失败（第 {} 次重试）: {}", docId, attempt + 1, e.getMessage());
                }
            }
        }
        throw new RuntimeException("向量化批次失败: " + (lastErr == null ? "unknown" : lastErr.getMessage()), lastErr);
    }

    private void updateProgress(String docId, int progress, String desc) {
        boolean terminal = "解析完成".equals(desc) || "解析失败".equals(desc);
        Integer last = progressGuard.get(docId);
        if (last != null && last.equals(progress) && !terminal) return;
        progressGuard.put(docId, progress);
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getParseProgress, progress)
                .set(AiDocument::getParseDesc, desc));
    }

    /**
     * 异步解析核心：解析 → MySQL 元数据 + 向量 → 状态置 0；失败置 3 + 原因 + 补偿清理
     */
    private void processUpload(String docId, String fileName, Path source, DocumentParser parser) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) return;
        List<Document> aiDocs = new ArrayList<>();
        // 重解析场景（diff 前已有旧知识块）：解析失败时保留旧块回退生效，不整表清空（先建后删容错）
        boolean hadExistingContent = false;
        try {
            // 新解析任务：清残留删除标志 + 记录线程（供 delete() 中断）
            deletedFlags.remove(docId);
            parseThreads.put(docId, Thread.currentThread());
            updateProgress(docId, 5, "开始解析");
            // 流式解析：直接传源文件 Path（已持久落盘），避免大文件全量读入堆内存
            updateProgress(docId, 10, "解析文档内容(图片较多时较慢)");
            List<Chunk> chunks = parser.parse(source, fileName, docId,
                    (percent, desc) -> updateProgress(docId, percent, desc));
            // 分块重叠：不再改写块正文（content 保持净内容），重叠尾巴作为 embedding 文本前缀在循环内计算——
            // 不入库、不进指纹，因此邻块变动不会连锁改变本块指纹（chunk.overlap 可调，0=关闭）
            int overlap = configService.getInt("chunk.overlap", properties.getChunk().getOverlap());
            // 截断保护：超大文档只保留前 maxChunks 块（防止 embedding 调用数万次/解析失控）
            int maxChunks = configService.getInt("chunk.maxChunks");
            int truncatedChunks = 0;
            if (maxChunks > 0 && chunks.size() > maxChunks) {
                // fail-loud：截断不再静默——计数入终态 desc（"截断保留前N/共M块"）
                truncatedChunks = chunks.size() - maxChunks;
                log.warn("[{}] 文档过大，解析出 {} 块，按配置截断保留前 {} 块（丢弃 {} 块）", docId, chunks.size(), maxChunks, truncatedChunks);
                chunks = new ArrayList<>(chunks.subList(0, maxChunks));
            }
            if (chunks.isEmpty()) {
                throw new BizException("文档未解析出任何内容");
            }
            // 删除感知：解析过程中文档被删除则立即停止并清理本次产物（避免孤儿数据/白耗资源）
            if (!isDocAlive(docId)) { cleanupPartial(docId, aiDocs); return; }
            updateProgress(docId, 30, "分块完成,准备入库");

            // ===== 增量更新：对比式重建 =====
            // 旧块按 content_hash 索引；内容未变的块保留 knowledgeId+向量（跳过重新 embedding）
            // 存量旧块无 hash（老版本数据）时视为全部变更 → 首次重解析等价全量重建，语义正确
            List<Knowledge> oldList = knowledgeMapper.selectList(
                    new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
            hadExistingContent = !oldList.isEmpty();
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
                String hash = contentHash(chunk.title(), chunk.titlePath(), chunk.content(), chunk.images());
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
                    // 内容未变：保留 knowledgeId + 向量；仅更新 chunkIndex（位置奖励用）
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
                aiDocs.add(new Document(knowledge.getId(),
                        buildEmbedText(chunk.title(), chunk.titlePath(), chunk.content(), overlapPrefix), metadata));
                newBlocks.add(knowledge);
                added++;
                // 入库进度：30 → 50（每 10 块上报一次）
                if ((i + 1) % 10 == 0 || i == total - 1) {
                    updateProgress(docId, 30 + Math.min(20, (i + 1) * 20 / total), "入库 " + added + "（保留 " + reused + "）");
                }
                // 删除感知：入库循环内每 10 块检查，删除立即停止（不等循环结束）
                if ((i + 1) % 10 == 0 && !isDocAlive(docId)) {
                    cleanupPartial(docId, aiDocs);
                    return;
                }
            }
            // 删除感知：入库后、向量化前再查一次
            if (!isDocAlive(docId)) { cleanupPartial(docId, aiDocs); return; }

            // 写入向量库（embedding 接口单次请求有条数上限，需分批；M10：每批失败自动重试 embedRetry 次）
            if (!aiDocs.isEmpty()) {
                int batchSize = Math.max(1, configService.getInt("parse.embedBatchSize", 10));
                int embedRetry = Math.max(0, configService.getInt("parse.embedRetryCount", 1));
                int totalBatch = (aiDocs.size() + batchSize - 1) / batchSize;
                int batchNo = 0;
                for (int i = 0; i < aiDocs.size(); i += batchSize) {
                    // 删除感知：向量化每批前检查，删除立即停止
                    if (!isDocAlive(docId)) {
                        cleanupPartial(docId, aiDocs);
                        return;
                    }
                    int end = Math.min(i + batchSize, aiDocs.size());
                    vectorAddWithRetry(docId, aiDocs.subList(i, end), embedRetry);
                    batchNo++;
                    // 向量化进度：50 → 95（每批精确上报）
                    updateProgress(docId, 50 + Math.min(45, batchNo * 45 / totalBatch), "向量化 " + end + "/" + aiDocs.size());
                    log.info("[{}] 向量化 {}-{} / {}", docId, i + 1, end, aiDocs.size());
                }
            }

            // 删除感知：向量化后、回写状态前最后确认
            if (!isDocAlive(docId)) { cleanupPartial(docId, aiDocs); return; }

            // 关键词索引同步：向量化成功后写入本次新增/变更块（best-effort，失败可用 /search-index/reindex 修复）
            keywordIndexService.indexChunks(newBlocks);

            // 清理未被新块匹配的旧块（内容变更的旧版本 / 被删除的段落与图片）。
            // 时机必须在向量化成功之后：若向量化失败，旧块仍保留 → hadExistingContent 回退 status=0 时内容完整可用；
            // 若提前删除，失败后旧版已毁、新版未建成，文档知识块全空（回退失效）。
            if (!staleOld.isEmpty()) {
                List<String> delIds = staleOld.stream().map(Knowledge::getVectorId)
                        .filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
                if (!delIds.isEmpty()) {
                    try {
                        vectorStore.delete(delIds);
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
                log.info("[{}] 增量清理旧块 {} 个（内容变更/删除）", docId, staleOld.size());
            }

            // 孤儿图片清扫：删除未被任何剩余知识块引用的图片文件（被删图/变更图的旧文件；未变图保留供复用块引用）
            boolean swept = sweepOrphanImages(docId);

            // fail-loud：截断/跳过统计聚合进终态 desc（chunk.maxChunks 截断 + DocxParser 图片统计），前端状态列可见
            String doneDesc = "解析完成(保留 " + reused + " 新增 " + added + " 清理 " + staleOld.size() + ")";
            String statsDesc = parseStatsDesc(docId, truncatedChunks, parser);
            if (!statsDesc.isEmpty()) doneDesc += "；" + statsDesc;
            if (!swept) doneDesc += "；孤儿清扫失败";
            updateProgress(docId, 100, doneDesc);
            // 引用关系重建（交叉引用识别）：块已全部入库（含 reused+newBlocks），此时重建引用最完整；
            // 失败仅告警不阻断（检索侧降级为不扩散）
            if (configService.getBoolean("retrieval.refDetectEnabled")) {
            }

            doc.setChunkCount(chunks.size());
            doc.setStatus(0);
            doc.setFailReason(null);
            documentMapper.updateById(doc);
            // 版本管理：成功解析后版本号 +1 并保存快照
            try {
                int newVersion = (doc.getVersion() == null ? 0 : doc.getVersion()) + 1;
                doc.setVersion(newVersion);
                documentMapper.updateById(doc);
                saveSnapshot(docId, newVersion);
            } catch (Exception e) {
                log.warn("[{}] 保存版本快照失败: {}", docId, e.getMessage());
            }
            log.info("[{}] 解析成功: {} chunks", docId, chunks.size());
            // 图片描述补齐：解析中视觉调用失败/超限的图，后台补描述并回写知识块（图片语义全部进入 RAG）
            backfillImageDescriptions(docId);
        } catch (Exception e) {
            // 删除场景：线程被 delete() 中断（interrupt）或检查点发现删除 → 只清理产物，不置失败状态
            if (deletedFlags.containsKey(docId)) {
                log.info("[{}] 解析已被删除中断: {}", docId, e.getMessage());
                cleanupPartial(docId, aiDocs);
                return;
            }
            log.error("[{}] 解析失败: {}", docId, e.getMessage());
            // 补偿清理：只清理本次新增的 aiDocs（删向量 + 物理删行），保留 diff 复用/已存在的旧块
            try {
                List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
                if (!vectorIds.isEmpty()) vectorStore.delete(vectorIds);
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
            if (hadExistingContent) {
                // 重解析失败：未变旧块仍在（变更块已被 diff 清理），回退到生效状态继续可用
                doc.setStatus(0);
                doc.setFailReason("重解析失败，已保留上一版内容: " + truncate(e.getMessage()));
            } else {
                // 全新解析失败：无旧内容可回退，清理图片目录并置失败
                cleanupImages(docId);
                doc.setStatus(3);
                doc.setFailReason(truncate(e.getMessage()));
            }
            documentMapper.updateById(doc);
            // 失败：进度保留最后值，desc 区分回退/失败
            updateProgress(docId, progressGuard.getOrDefault(docId, 0), hadExistingContent ? "解析失败(已回退旧内容)" : "解析失败");
        } finally {
            // 清理解析线程引用与删除标志（delete() 的 DB 物理删除仍可兜底 isDocAlive）
            parseThreads.remove(docId);
            deletedFlags.remove(docId);
        }
    }

    /**
     * 删除文档（向量/MySQL/图片分别清理）
     */
    public void delete(String docId) {
        // 立即标记删除 + 中断解析线程（图片 join 等待立即响应，不再等阶段检查点）
        // 多实例语义：deletedFlags/parseThreads 为进程内存态——本实例解析的任务可立即中断；
        // 其他实例上运行的解析任务由 isDocAlive 的 DB 兜底感知（删除后 selectById 为 null），
        // 在阶段检查点（入库每 10 块/向量化每批）秒级停止并清理产物，保证跨实例不产生孤儿数据。
        deletedFlags.put(docId, true);
        Thread parseThread = parseThreads.get(docId);
        if (parseThread != null && parseThread.isAlive()) {
            parseThread.interrupt();
            log.info("[{}] 删除时中断解析线程", docId);
        }
        List<Knowledge> chunks = knowledgeMapper.selectList(
                new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
        // 删除顺序：先删向量，成功后再删 MySQL 行。
        // 反序会产生"MySQL 已删、向量残留"的不可见孤儿（无 knowledgeId 可追溯，只能整库重建）；
        // 本序若向量删除失败则保留 MySQL 行并抛错，用户可重试删除，不产生不可追溯残留。
        if (!chunks.isEmpty()) {
            List<String> vectorIds = chunks.stream().map(Knowledge::getVectorId)
                    .filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
            if (!vectorIds.isEmpty()) {
                try {
                    vectorStore.delete(vectorIds);
                } catch (Exception e) {
                    deletedFlags.remove(docId); // 删除未完成，撤销删除标志避免误停后续解析
                    log.error("[{}] 删除向量失败，已中止删除（MySQL 记录保留，可重试）: {}", docId, e.getMessage());
                    throw new BizException("删除向量失败，文档未删除，请稍后重试");
                }
            }
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
        documentMapper.deleteById(docId);
        keywordIndexService.deleteByDoc(docId); // 关键词索引同步（best-effort）
        // 清理版本快照
        try {
            versionMapper.delete(new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                    .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId));
        } catch (Exception e) {
            log.warn("清理版本快照失败: {}", e.getMessage());
        }
        documentMetaCache.invalidate(docId);
        // 引用关系清理（纯派生数据，随文档删除）
        cleanupImages(docId);
        cleanupSourceFile(docId);
    }

    /**
     * 文档列表
     */
    public List<AiDocument> list() {
        LambdaQueryWrapper<AiDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(AiDocument::getCreateTime);
        return documentMapper.selectList(wrapper);
    }

    /**
     * 启停用文档（改 MySQL status + 缓存 + 关键词索引同步）。
     * 向量保留，检索侧按 status 过滤即时生效；索引同步使 Meilisearch 与有效块集合保持一致：
     * 弃用 → 从索引删除该文档全部块（不漂移、不占位）；恢复 → 现存块重新灌入。
     * 解析中（status=2）禁止启停用：解析完成会把状态覆写回 0 并重灌索引，启停用意图会被静默丢弃
     * （与 updateKnowledge/rollback 的解析中拦截一致）。
     */
    public void updateStatus(String docId, int status) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (status != 0 && status != 1) throw new BizException("非法状态");
        if (doc.getStatus() != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可启停用");
        doc.setStatus(status);
        documentMapper.updateById(doc);
        documentMetaCache.invalidate(docId);
        // 关键词索引同步（best-effort，失败仅告警；检索侧 loadNonRetrievableDocIds 已兜底过滤弃用）
        try {
            if (status == 1) {
                keywordIndexService.deleteByDoc(docId);
                log.info("[{}] 文档已弃用，关键词索引已移除", docId);
            } else {
                List<Knowledge> blocks = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
                if (!blocks.isEmpty()) {
                    keywordIndexService.indexChunks(blocks);
                    log.info("[{}] 文档已恢复，关键词索引已灌入 {} 块", docId, blocks.size());
                }
            }
        } catch (Exception e) {
            log.warn("[{}] 关键词索引同步失败（可稍后 /reindex 修复）: {}", docId, e.getMessage());
        }
    }

    /**
     * 设置文档共享范围（写入侧强校验 manage⊆read；空串=恢复全局可见）。
     * 仅改 share_config 列与缓存，不影响解析状态/向量。
     */
    public void updateShareConfig(String docId, String shareConfigJson, String operator) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        resourceVisibilityService.validateShareConfig(shareConfigJson);
        String normalized = (shareConfigJson == null || shareConfigJson.isBlank()) ? null : shareConfigJson;
        // 必须用 set(..., null) 显式置空：MyBatis-Plus 默认更新策略为 NOT_NULL，updateById 会跳过
        // null 字段 → 「改回全员共享」会静默不生效（保存成功但范围没放开）
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getShareConfig, normalized));
        documentMetaCache.invalidate(docId);
        log.info("[AUDIT] 设置文档共享范围 operator={} docId={} scope={}", operator, docId,
                normalized == null ? "global" : "custom");
    }

    /**
     * 批量删除文档（任一失败不中断，继续处理其余）
     */
    public void batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            try {
                delete(id);
            } catch (Exception e) {
                log.warn("批量删除失败: id={} error={}", id, e.getMessage());
            }
        }
        documentMetaCache.invalidateAll();
    }

    /**
     * 批量启停用（ids 非空；任一失败不中断）
     */
    public void batchUpdateStatus(List<String> ids, int status) {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            try {
                updateStatus(id, status);
            } catch (Exception e) {
                log.warn("批量启停用失败: id={} error={}", id, e.getMessage());
            }
        }
        documentMetaCache.invalidateAll();
    }

    /**
     * 编辑知识块：先写新向量（同 knowledgeId 覆盖）→ 成功后更新 MySQL → 清理历史遗留的异 id 旧向量。
     * 顺序保证一致性：向量化失败时 MySQL 与旧向量都不动，抛错让调用方重试，不会出现"内容已改但无向量"。
     */
    public void updateKnowledge(String id, String title, String content) {
        Knowledge k = knowledgeMapper.selectById(id);
        if (k == null) throw new BizException("知识块不存在");
        if (title == null || title.isBlank()) throw new BizException("标题不能为空");
        if (title.length() > 200) throw new BizException("标题过长（最多200字）");
        if (content == null || content.isBlank()) throw new BizException("内容不能为空");
        if (k.getDocId() != null) {
            AiDocument doc = documentMapper.selectById(k.getDocId());
            if (doc != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可编辑知识块");
        }

        String oldVectorId = k.getVectorId();
        String newTitle = title.trim();

        // 1. 先写新向量：id 复用 knowledgeId，向量库按 id upsert（同 id 覆盖旧向量）
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (k.getDocId() != null) metadata.put("docId", k.getDocId());
            metadata.put("title", newTitle);
            metadata.put("knowledgeId", k.getId());
            if (k.getTitlePath() != null && !k.getTitlePath().isBlank()) {
                metadata.put("titlePath", k.getTitlePath());
            }
            if (k.getImages() != null && !k.getImages().isBlank()) {
                metadata.put("images", k.getImages());
            }
            vectorStore.add(List.of(new Document(k.getId(),
                    buildEmbedText(newTitle, k.getTitlePath(), content, null), metadata)));
        } catch (Exception e) {
            log.warn("知识块重新向量化失败 id={}: {}", id, e.getMessage());
            throw new BizException("知识块向量化失败，内容未修改，请稍后重试");
        }

        // 2. 向量写入成功后再更新 MySQL（此时两侧内容一致）
        String oldTitle = k.getTitle();
        String oldContent = k.getContent();
        k.setTitle(newTitle);
        k.setContent(content);
        k.setContentHash(contentHash(k.getTitle(), k.getTitlePath(), k.getContent(), parseImages(k.getImages())));
        k.setVectorId(k.getId());
        knowledgeMapper.updateById(k);
        keywordIndexService.indexChunks(List.of(k)); // 关键词索引同步：按 id upsert（best-effort）
        // 3. 清理历史遗留的异 id 旧向量（正常链路 vectorId==knowledgeId，已被 upsert 覆盖，无需删除）
        if (oldVectorId != null && !oldVectorId.isBlank() && !oldVectorId.equals(k.getId())) {
            try {
                vectorStore.delete(List.of(oldVectorId));
            } catch (Exception e) {
                log.warn("清理旧向量失败 id={} oldVectorId={}: {}", id, oldVectorId, e.getMessage());
            }
        }
        // 4. 引用关系：内容可能变化 → 重建该块出边（入边目标 id 不变，无需重建）
        if (configService.getBoolean("retrieval.refDetectEnabled")) {
        }
    }

    /**
     * 删除知识块：删向量 + 逻辑删行 + 扣减文档 chunk_count
     */
    public void deleteKnowledge(String id) {
        Knowledge k = knowledgeMapper.selectById(id);
        if (k == null) throw new BizException("知识块不存在");
        if (k.getDocId() != null) {
            AiDocument doc = documentMapper.selectById(k.getDocId());
            if (doc != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可删除知识块");
        }

        if (k.getVectorId() != null && !k.getVectorId().isBlank()) {
            try {
                vectorStore.delete(List.of(k.getVectorId()));
            } catch (Exception e) {
                log.warn("删除知识块向量失败 id={}: {}", id, e.getMessage());
            }
        }
        knowledgeMapper.deleteById(id);
        keywordIndexService.deleteChunks(List.of(id)); // 关键词索引同步（best-effort）

        // 扣减文档 chunk_count（尽力而为）
        if (k.getDocId() != null) {
            try {
                AiDocument doc = documentMapper.selectById(k.getDocId());
                if (doc != null && doc.getChunkCount() != null && doc.getChunkCount() > 0) {
                    doc.setChunkCount(doc.getChunkCount() - 1);
                    documentMapper.updateById(doc);
                }
            } catch (Exception e) {
                log.warn("更新文档 chunk_count 失败: {}", e.getMessage());
            }
        }
        // 引用关系清理：块被删，其出边（引用他人）与入边（被他人引用）一并移除
    }

    // ==================== 版本管理 ====================

    /**
     * 保存文档当前知识块状态为版本快照（按 chunk_index 有序，快照含原 knowledgeId 便于回滚后可溯源）
     */
    private void saveSnapshot(String docId, int version) {
        List<Knowledge> chunks = knowledgeMapper.selectList(
                new LambdaQueryWrapper<Knowledge>()
                        .eq(Knowledge::getDocId, docId)
                        .orderByAsc(Knowledge::getChunkIndex));
        List<Map<String, Object>> snapshot = chunks.stream().map(k -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", k.getId());
            m.put("title", k.getTitle());
            m.put("content", k.getContent());
            m.put("titlePath", k.getTitlePath());
            m.put("images", k.getImages() == null ? null : com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class));
            return m;
        }).toList();

        // 同 (docId, version) 唯一：先删再插（重解析同版本覆盖）
        versionMapper.delete(new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                .eq(com.wisesoft.ai.model.AiDocumentVersion::getVersion, version));
        com.wisesoft.ai.model.AiDocumentVersion v = new com.wisesoft.ai.model.AiDocumentVersion();
        v.setDocId(docId);
        v.setVersion(version);
        v.setChunkCount(chunks.size());
        v.setSnapshotJson(JSON.toJSONString(snapshot));
        versionMapper.insert(v);
        log.info("[{}] 保存版本快照 v{}: {} chunks", docId, version, chunks.size());
    }

    /**
     * 文档版本列表（倒序）
     */
    public List<Map<String, Object>> listVersions(String docId) {
        return versionMapper.selectList(
                        new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                                .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                                .orderByDesc(com.wisesoft.ai.model.AiDocumentVersion::getVersion))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", v.getVersion());
                    m.put("chunkCount", v.getChunkCount());
                    m.put("createTime", v.getCreateTime());
                    return m;
                }).toList();
    }

    /**
     * 回滚到指定版本：先全量向量化快照内容（用最终 id 直接写入：与当前行同 id 的块自动覆盖旧向量、快照新增 id 落新向量），
     * 嵌入全部成功后再做行级切换（仅删除不在快照中的旧向量与旧行，按快照原 id 重建行 + 关键词索引）。
     * <p>
     * 相比"先物理清空再重建"：嵌入（外部模型调用，失败主因）失败时当前内容分毫未动，并按现存量行恢复向量；
     * 切换段全部为本地幂等操作，即使失败重试即可，不再出现"回滚到一半把当前可用内容毁掉"。
     * 已知边界：嵌入阶段同 id 向量已被快照内容覆盖，切换完成前对该文档的并发检索可能短暂命中快照语义
     * （行内容仍是旧版本），窗口仅限本方法执行期间。
     */
    public void rollback(String docId, int version) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (doc.getStatus() != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可回滚");

        com.wisesoft.ai.model.AiDocumentVersion v = versionMapper.selectOne(
                new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                        .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                        .eq(com.wisesoft.ai.model.AiDocumentVersion::getVersion, version));
        if (v == null) throw new BizException("目标版本不存在");

        List<Map<String, Object>> snapshot = JSON.parseObject(v.getSnapshotJson(),
                new com.alibaba.fastjson2.TypeReference<List<Map<String, Object>>>() {});
        if (snapshot == null || snapshot.isEmpty()) {
            throw new BizException("目标版本无知识块数据");
        }

        // 1. 构建快照块（仅内存，不落库不动向量；复用原 id 保持历史引用可溯源）
        List<org.springframework.ai.document.Document> aiDocs = new ArrayList<>();
        List<Knowledge> rebuilt = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> item : snapshot) {
            // 快照字段：id/title/content/titlePath/images（旧快照无 titlePath 按 null 兼容）
            String oldId = item.get("id") == null ? null : String.valueOf(item.get("id"));
            String title = item.get("title") == null ? "" : String.valueOf(item.get("title"));
            String content = item.get("content") == null ? "" : String.valueOf(item.get("content"));
            String titlePath = item.get("titlePath") == null ? null : String.valueOf(item.get("titlePath"));
            Object imagesObj = item.get("images");
            List<String> imgList = imagesObj instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();

            Knowledge k = new Knowledge();
            if (oldId != null && !oldId.isBlank()) k.setId(oldId);
            k.setDocId(docId);
            k.setTitle(title);
            k.setContent(content);
            k.setTitlePath(titlePath);
            k.setImages(imagesObj == null ? null : JSON.toJSONString(imagesObj));
            k.setChunkIndex(idx++);
            k.setContentHash(contentHash(title, titlePath, content, imgList));
            k.setVectorId(k.getId()); // 向量 id 与行 id 恒同（与 embedAndStore 语义一致）

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("docId", docId);
            metadata.put("title", title);
            metadata.put("knowledgeId", k.getId());
            if (titlePath != null && !titlePath.isBlank()) metadata.put("titlePath", titlePath);
            if (k.getImages() != null) metadata.put("images", k.getImages());
            aiDocs.add(new org.springframework.ai.document.Document(k.getId(),
                    buildEmbedText(title, titlePath, content, null), metadata));
            rebuilt.add(k);
        }

        // 2. 先向量化（最终 id 直接写入）。任一批重试一次后仍失败 → 恢复原向量并抛错，当前内容保持可用
        if (!aiDocs.isEmpty()) {
            List<Knowledge> currentRows = knowledgeMapper.selectList(
                    new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
            java.util.Set<String> written = new java.util.HashSet<>();
            int batchSize = 10;
            for (int i = 0; i < aiDocs.size(); i += batchSize) {
                int end = Math.min(i + batchSize, aiDocs.size());
                List<org.springframework.ai.document.Document> batch = aiDocs.subList(i, end);
                batch.forEach(d -> written.add(d.getId()));
                try {
                    vectorStore.add(batch);
                } catch (Exception e) {
                    log.warn("[{}] 回滚向量化失败 {}-{}，重试一次: {}", docId, i + 1, end, e.getMessage());
                    try {
                        vectorStore.add(batch);
                    } catch (Exception e2) {
                        log.error("[{}] 回滚向量化重试仍失败 {}-{}: {}", docId, i + 1, end, e2.getMessage());
                        restoreVectorsAfterRollbackFail(docId, currentRows, written);
                        throw new BizException("回滚向量化失败，已恢复原文档内容与向量，请稍后重试");
                    }
                }
            }
        }

        // 3. 行级切换（全为本地幂等操作）：删不在快照中的旧向量与旧行 → 按快照原 id 重建行 → 关键词索引
        List<Knowledge> currentRows = knowledgeMapper.selectList(
                new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
        java.util.Set<String> keepIds = rebuilt.stream().map(Knowledge::getId).collect(java.util.stream.Collectors.toSet());
        List<String> staleVectorIds = currentRows.stream().map(Knowledge::getVectorId)
                .filter(id -> id != null && !id.isBlank() && !keepIds.contains(id))
                .toList();
        if (!staleVectorIds.isEmpty()) {
            try {
                vectorStore.delete(staleVectorIds);
            } catch (Exception e) {
                log.warn("[{}] 回滚删除多余向量失败: {}", docId, e.getMessage());
            }
        }
        knowledgeMapper.physicalDeleteByDocId(docId);
        keywordIndexService.deleteByDoc(docId); // 关键词索引同步：清空该文档旧块（best-effort）
        for (Knowledge k : rebuilt) {
            knowledgeMapper.insert(k);
        }

        // 4. 更新文档状态 + 清理较新版本行
        doc.setChunkCount(snapshot.size());
        doc.setVersion(version);
        doc.setStatus(0);
        doc.setFailReason(null);
        documentMapper.updateById(doc);
        versionMapper.delete(new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                .gt(com.wisesoft.ai.model.AiDocumentVersion::getVersion, version));
        documentMetaCache.invalidate(docId);
        keywordIndexService.indexChunks(rebuilt); // 关键词索引同步：写入重建块（best-effort）
        // 引用关系重建（回滚后块内容回到快照版本）
        if (configService.getBoolean("retrieval.refDetectEnabled")) {
        }
        log.info("[{}] 回滚到 v{} 完成: {} chunks", docId, version, snapshot.size());
    }

    /**
     * 回滚向量化失败后的恢复：删除本阶段写入的向量（含同 id 覆盖的旧向量与快照新增的孤儿向量），
     * 再按当前 MySQL 行内容重新向量化——embedding 为瞬时故障时可自愈；仍失败时该文档仅关键词可召回
     * （行与状态均保持原样，重试回滚或重新解析即可恢复向量），不再出现"回滚失败连带当前内容丢失"。
     */
    private void restoreVectorsAfterRollbackFail(String docId, List<Knowledge> currentRows, java.util.Set<String> writtenIds) {
        try {
            List<String> rowVectorIds = currentRows.stream().map(Knowledge::getVectorId)
                    .filter(id -> id != null && !id.isBlank()).toList();
            java.util.Set<String> rowIdSet = currentRows.stream().map(Knowledge::getId)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> orphanIds = writtenIds.stream().filter(id -> !rowIdSet.contains(id)).toList();
            List<String> toDelete = new ArrayList<>(rowVectorIds);
            toDelete.addAll(orphanIds);
            if (!toDelete.isEmpty()) {
                vectorStore.delete(toDelete);
            }
        } catch (Exception e) {
            log.warn("[{}] 回滚失败后清理向量异常: {}", docId, e.getMessage());
        }
        // 按当前行内容恢复向量（逐行 best-effort；行内容始终在库中，缺向量可稍后重解析补齐）
        for (Knowledge k : currentRows) {
            embedAndStore(k, k.getContent());
        }
        updateProgress(docId, 0, "回滚向量化失败，原内容与向量已恢复，请稍后重试");
    }

    /**
     * 文档命中次数统计：从问答日志 hit_doc_ids 聚合，返回 {docId: count}
     * hit_doc_ids 为逗号分隔串无法直接 GROUP BY，按近 90 天 + LIMIT 上限做有界扫描（避免全表拉取到内存）
     */
    public Map<String, Long> statsHitCounts() {
        Map<String, Long> counts = new HashMap<>();
        try {
            var logs = qaLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wisesoft.ai.model.QaLog>()
                    .isNotNull(com.wisesoft.ai.model.QaLog::getHitDocIds)
                    .ne(com.wisesoft.ai.model.QaLog::getHitDocIds, "")
                    .ge(com.wisesoft.ai.model.QaLog::getCreatedAt, java.time.LocalDateTime.now().minusDays(90))
                    .select(com.wisesoft.ai.model.QaLog::getHitDocIds)
                    .last("LIMIT 20000"));
            for (var log : logs) {
                if (log.getHitDocIds() == null || log.getHitDocIds().isBlank()) continue;
                for (String id : log.getHitDocIds().split(",")) {
                    if (!id.isBlank()) counts.merge(id.trim(), 1L, Long::sum);
                }
            }
        } catch (Exception e) {
            log.warn("统计文档命中次数失败: {}", e.getMessage());
        }
        return counts;
    }

    /**
     * 重解析（复用源文件重新走解析流程）
     */
    public void reparse(String docId) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        Path source = sourceFile(docId, doc.getFileName());
        if (!Files.exists(source)) throw new BizException("源文件缺失，无法重解析（请重新上传）");
        DocumentParser parser = parsers.stream().filter(p -> p.supports(doc.getFileType()))
                .findFirst().orElse(null);
        if (parser == null) throw new BizException("不支持的文件格式");

        // 并发防护（多实例也原子）：CAS 抢占"解析中"状态，失败说明已有解析在进行
        tryLockParsing(docId);

        // 重解析前先保存当前状态快照（作为版本历史）；旧知识块保留给增量对比（diff），由 processUpload 匹配复用/清理变更块
        try {
            saveSnapshot(docId, doc.getVersion() == null ? 0 : doc.getVersion());
        } catch (Exception e) {
            log.warn("重解析前保存快照失败: {}", e.getMessage());
        }
        // 不整体删除旧向量与知识块：processUpload 的 diff 式重建依赖它们做 content_hash 匹配
        // （未变块保留 id+向量，变更/删除块由 processUpload 清理；失败时旧内容可回退保留）
        // 也不清空图片目录：内容寻址文件名下，未变图片文件保留供复用块引用，变更/删除图的旧文件由 processUpload 成功后孤儿清扫
        // 提交前保存"解析前"状态：下方 setStatus(2) 会改写内存对象，队列满恢复分支必须用此原值，
        // 否则恢复语句把状态重置回 2（沿用 doc.getStatus() 的原实现会让文档永久卡在"解析中"）
        int origStatus = doc.getStatus() == null ? 0 : doc.getStatus();
        // 队列满恢复时一并回滚解析态字段：下方 updateProgress 会把 parse_desc 改写为"重新解析中"，
        // 只回滚 status 会让已入库文档悬浮显示"重新解析中"（终态与描述不一致）
        Integer origProgress = doc.getParseProgress();
        String origParseDesc = doc.getParseDesc();
        String origFailReason = doc.getFailReason();
        doc.setStatus(2);
        doc.setFailReason(null);
        documentMapper.updateById(doc);
        documentMetaCache.invalidate(docId);
        updateProgress(docId, 0, "重新解析中");

        final DocumentParser fp = parser;
        syncParseConcurrency();
        try {
            parseExecutor.submit(() -> processUpload(docId, doc.getFileName(), source, fp));
        } catch (RejectedExecutionException e) {
            // 队列满：恢复文档原状态与解析态字段（避免停留在"解析中"或残留"重新解析中"描述）
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .set(AiDocument::getStatus, origStatus)
                    .set(AiDocument::getParseProgress, origProgress)
                    .set(AiDocument::getParseDesc, origParseDesc)
                    .set(AiDocument::getFailReason, origFailReason));
            throw new BizException("解析队列繁忙（已有 50 个待解析任务），请稍后再试");
        }
    }

    /**
     * 同名复用目标：查同名文档（status 0/2/3，弃用记录保留），优先复用最近一条 status 0（生效）、其次 status 3（失败）的，
     * 其余同名文档（含正在解析 status=2 的旧任务）删除，保持"替换"语义。无可复用返回 null。
     */
    private AiDocument reusableTarget(String fileName) {
        List<AiDocument> existing = documentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getFileName, fileName)
                        .in(AiDocument::getStatus, 0, 2, 3)
                        .orderByDesc(AiDocument::getCreateTime)
                        .last("limit 10"));
        if (existing.isEmpty()) return null;
        // 优先最近一条生效(status=0)，其次最近一条失败(status=3)：复用后走 diff 增量，避免误删仍有内容的生效文档
        AiDocument target = existing.stream()
                .filter(d -> d.getStatus() != null && d.getStatus() == 0)
                .findFirst()
                .orElseGet(() -> existing.stream()
                        .filter(d -> d.getStatus() != null && d.getStatus() == 3)
                        .findFirst().orElse(null));
        String targetId = target == null ? "" : target.getId();
        for (AiDocument d : existing) {
            if (d.getId().equals(targetId)) continue;
            log.info("Replacing existing document: {} ({})", fileName, d.getId());
            delete(d.getId());
        }
        return target;
    }

    /**
     * 同名替换：复用原 docId（覆盖源文件 + 走 diff 重解析）
     * 文档身份与 knowledgeId 保持稳定（历史引用/评估集不失效），未变块增量复用、只重嵌变更处。
     */
    private AiDocument replaceExisting(AiDocument existing, MultipartFile file, String description,
                                       DocumentParser parser) throws Exception {
        String docId = existing.getId();
        int origStatus = existing.getStatus() == null ? 0 : existing.getStatus();
        // 未成功提交时一并回滚解析态字段：fail_reason 下方会置 null、parse_desc 会被改写为
        // "已提交,等待解析"，只回滚 status 会留下终态与描述不一致的脏数据
        Integer origProgress = existing.getParseProgress();
        String origParseDesc = existing.getParseDesc();
        String origFailReason = existing.getFailReason();
        // 并发防护（多实例也原子）：CAS 抢占"解析中"状态，失败说明已有解析在进行
        tryLockParsing(docId);
        boolean submitted = false;
        try {
            // 保留旧内容快照（可回滚）；旧块保留给 processUpload 的 diff 匹配
            try {
                saveSnapshot(docId, existing.getVersion() == null ? 0 : existing.getVersion());
            } catch (Exception e) {
                log.warn("[{}] 替换前保存快照失败: {}", docId, e.getMessage());
            }
            // 覆盖源文件（同 docId 同路径）
            Path source = saveSourceFile(file, docId, existing.getFileName());
            // 更新元数据并置解析中
            existing.setFileSize(file.getSize());
            existing.setDescription(description);
            existing.setStatus(2);
            existing.setFailReason(null);
            documentMapper.updateById(existing);
            documentMetaCache.invalidate(docId);
            updateProgress(docId, 0, "已提交,等待解析");

            final DocumentParser fp = parser;
            syncParseConcurrency();
            parseExecutor.submit(() -> processUpload(docId, existing.getFileName(), source, fp));
            submitted = true;
            return existing;
        } catch (RejectedExecutionException e) {
            throw new BizException("解析队列繁忙（已有 50 个待解析任务），请稍后再试");
        } finally {
            // 未成功提交解析任务时恢复原状态与解析态字段（避免卡在"解析中"或残留错误描述）
            if (!submitted) {
                try {
                    documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                            .eq(AiDocument::getId, docId)
                            .set(AiDocument::getStatus, origStatus)
                            .set(AiDocument::getParseProgress, origProgress)
                            .set(AiDocument::getParseDesc, origParseDesc)
                            .set(AiDocument::getFailReason, origFailReason));
                } catch (Exception ex) {
                    log.warn("[{}] 恢复文档状态失败: {}", docId, ex.getMessage());
                }
            }
        }
    }

    /** 并发防护（多实例也原子）：CAS 抢占"解析中"状态，失败说明已有解析在进行 */
    private void tryLockParsing(String docId) {
        int locked = documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .and(w -> w.ne(AiDocument::getStatus, 2).or().isNull(AiDocument::getStatus))
                .set(AiDocument::getStatus, 2));
        if (locked == 0) {
            throw new BizException("该文档正在解析中，请等待完成后再操作");
        }
    }

    // ==================== 文件管理 ====================

    private Path saveSourceFile(MultipartFile file, String docId, String fileName) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "files", docId);
        Files.createDirectories(dir);
        Path target = dir.resolve(sanitize(fileName));
        file.transferTo(target.toFile());
        return target;
    }

    /** 源文件（下载用）：文件名 + 磁盘路径 */
    public record SourceFile(String fileName, Path path) {
    }

    /**
     * 取源文件供下载（个人文件区：用户上传的原始文件可取回）。
     * 文档不存在或源文件缺失（旧数据未保留）时抛可读业务异常。
     */
    public SourceFile sourceFileForDownload(String docId) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        Path p = sourceFile(docId, doc.getFileName());
        if (!Files.exists(p)) throw new BizException("源文件缺失（旧数据可能未保留源文件，请重新上传该文档）");
        return new SourceFile(doc.getFileName(), p);
    }

    private Path sourceFile(String docId, String fileName) {
        return Paths.get(properties.getImages().getDir(), "files", docId, sanitize(fileName));
    }

    private void cleanupSourceFile(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "files", docId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            // L11 fail-loud：清理失败升级 error（遗留磁盘文件属数据一致性风险）
            log.error("[FAIL-LOUD] 清理源文件目录失败: {}", e.getMessage());
        }
    }

    private void cleanupImages(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.error("[FAIL-LOUD] 清理图片目录失败: {}", e.getMessage());
        }
    }

    /**
     * 孤儿图片清扫：删除 data/images/{docId}/ 下未被任何剩余知识块 images 字段引用的文件
     * （内容寻址文件名下，被删图/变更图的旧文件在这里回收；未变图片保留供复用块引用，保证 URL 稳定）
     *
     * @return true=正常完成（含无孤儿）；false=清扫失败（L11 fail-loud：调用方把失败写进终态 desc）
     */
    private boolean sweepOrphanImages(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        if (!Files.exists(dir)) return true;
        try {
            List<Knowledge> blocks = knowledgeMapper.selectList(
                    new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
            Set<String> referenced = new HashSet<>();
            for (Knowledge b : blocks) {
                if (b.getImages() == null || b.getImages().isBlank()) continue;
                try {
                    JSON.parseArray(b.getImages(), String.class).forEach(u -> {
                        String fn = u.substring(u.lastIndexOf('/') + 1);
                        if (!fn.isBlank()) referenced.add(fn);
                    });
                } catch (Exception ignored) {
                }
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> Files.isRegularFile(p) && !referenced.contains(p.getFileName().toString()))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("[{}] 孤儿图片删除失败: {}", docId, e.getMessage());
                            }
                        });
            }
            return true;
        } catch (IOException e) {
            log.error("[FAIL-LOUD] [{}] 孤儿图片清扫失败: {}", docId, e.getMessage());
            return false;
        }
    }

    private String extOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase();
    }

    /**
     * 魔数校验：文件头字节必须与扩展名对应的真实格式一致（防伪造扩展名绕过类型限制）。
     * docx/xlsx 为 OODF zip 容器（PK\x03\x04），pdf 为 %PDF-。
     * 仅读文件头部 8 字节，不整体加载。
     */
    private void validateMagicBytes(MultipartFile file, String ext) {
        byte[] expected = switch (ext) {
            case "docx", "xlsx", "doc", "xls" -> new byte[]{0x50, 0x4B, 0x03, 0x04}; // PK.. (zip 容器)
            case "pdf" -> new byte[]{0x25, 0x50, 0x44, 0x46};                        // %PDF
            default -> null; // 其余格式无魔数约定，跳过
        };
        if (expected == null) return;
        byte[] head = new byte[expected.length];
        try (java.io.InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length || !java.util.Arrays.equals(head, expected)) {
                throw new BizException("文件内容与扩展名 ." + ext + " 不符，已拒绝上传");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 读不出文件头（异常 IO）：宁可拒绝也不放行伪造文件
            throw new BizException("无法读取文件内容，已拒绝上传");
        }
    }

    /** 文件名清洗（防路径穿越） */
    private String sanitize(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String truncate(String s) {
        if (s == null) return "未知错误";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /**
     * 内容指纹：SHA-256(title + "\n" + titlePath + "\n" + content + "\n" + images)
     * 重解析增量对比（未变块保留向量）、知识块编辑/新增维护用。
     * 含章节路径（章节改名重嵌该章，语义正确）、含 images（图片删除/替换 → 块走新增，避免引用已删图片）。
     * 不含分块重叠尾巴：前块变动不会连锁改变本块指纹。
     */
    public String contentHash(String title, String content) {
        return contentHash(title, null, content, List.of());
    }

    public String contentHash(String title, String titlePath, String content, List<String> images) {
        try {
            String img = images == null || images.isEmpty() ? "" : String.join(",", images);
            String path = titlePath == null ? "" : titlePath;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((title + "\n" + path + "\n" + content + "\n" + img)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 向量化/embedding 文本统一拼装：title + 【上下文】章节路径 + 重叠尾巴 + 净正文。
     * 入库 content 只存净正文；路径与重叠仅作为向量语义的上下文输入，检索时路径另由 RagService 拼装。
     */
    private String buildEmbedText(String title, String titlePath, String content, String overlapPrefix) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title).append("\n");
        if (titlePath != null && !titlePath.isBlank()) sb.append("【上下文】").append(titlePath).append("\n\n");
        if (overlapPrefix != null && !overlapPrefix.isBlank()) sb.append(overlapPrefix);
        sb.append(content);
        return sb.toString();
    }

    /** 知识块占位符：有描述 [图片：xxx] 或无描述 [图片] */
    private static final Pattern IMG_PH = Pattern.compile("\\[图片(?:：[^\\]]*)?]");

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
                List<Knowledge> blocks = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId)
                                .orderByAsc(Knowledge::getChunkIndex));
                if (blocks.isEmpty()) return;
                // 1. 收集全部无描述图 URL（块内裸 [图片] 占位按序对应 images 列表）
                Set<String> missing = new LinkedHashSet<>();
                for (Knowledge k : blocks) {
                    collectMissingImages(k.getContent(), parseImages(k.getImages()), missing);
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
                    String newContent = replaceMissingImages(k.getContent(), parseImages(k.getImages()), descByUrl);
                    if (!newContent.equals(k.getContent())) {
                        try {
                            updateKnowledge(k.getId(), k.getTitle(), newContent);
                            written++;
                        } catch (Exception e) {
                            log.warn("[{}] 知识块补描述回写失败 id={}: {}", docId, k.getId(), e.getMessage());
                        }
                    }
                }
                log.info("[{}] 图片描述补齐完成：补 {}/{} 张，回写 {} 块", docId, descByUrl.size(), missing.size(), written);
                // M8 fail-loud：补完仍缺的图必须留在解析状态可见（不能只记一条日志）
                int remaining = countRemainingMissing(blocks, descByUrl);
                if (remaining > 0) {
                    log.warn("[FAIL-LOUD] [{}] 图片描述补齐后仍有 {} 张无描述（视觉模型不可用？可稍后重试补描述接口）", docId, remaining);
                    try {
                        AiDocument d = documentMapper.selectById(docId);
                        String base = d == null || d.getParseDesc() == null ? "" : d.getParseDesc();
                        updateProgress(docId, 100,
                                (base.isEmpty() ? "" : base + "；") + "补描述后仍有 " + remaining + " 张图无描述");
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] 图片描述补齐任务异常: {}", docId, e.getMessage());
            } finally {
                descBackfillRunning.remove(docId);
            }
        });
        if (!submitted) {
            // L10 fail-loud：任务被共享池丢弃（队列满）——必须落状态，不留无感知缺口
            descBackfillRunning.remove(docId);
            log.error("[FAIL-LOUD] [{}] 图片描述补齐任务被丢弃（后台队列满），请稍后重试", docId);
            try {
                AiDocument d = documentMapper.selectById(docId);
                String base = d == null || d.getParseDesc() == null ? "" : d.getParseDesc();
                updateProgress(docId, 100, (base.isEmpty() ? "" : base + "；") + "补描述未执行（后台队列满，可稍后重试）");
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

    /** 补描述后仍无描述（本次未补上）的图片数（M8 fail-loud 统计用） */
    private int countRemainingMissing(List<Knowledge> blocks, Map<String, String> descByUrl) {
        Set<String> missing = new LinkedHashSet<>();
        for (Knowledge k : blocks) {
            collectMissingImages(k.getContent(), parseImages(k.getImages()), missing);
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

    /** 知识块 images 字段（JSON 数组串）→ List<String>（空/解析失败返回空列表） */
    private List<String> parseImages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
