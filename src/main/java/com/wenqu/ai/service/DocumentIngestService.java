package com.wenqu.ai.service;

import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.parser.DocumentParser;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档入库链路：上传、网页导入、文本写入、同名替换、网页源定时刷新。
 * 只负责「收下并登记解析任务」，解析执行见 {@link DocumentParsePipeline}。
 * 同名上传在本实例内串行，跨实例由 CAS 抢占兜底。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final KeywordIndexService keywordIndexService;
    private final DocumentRecycleService recycleService;
    private final DocumentVersionService versionService;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeBaseService kbService;
    private final DocumentMetaCache documentMetaCache;
    private final DocumentFileStore fileStore;
    private final WebPageFetcher webPageFetcher;
    private final ParseQueueService parseQueue;
    private final ModelRegistryService modelRegistryService;
    private final List<DocumentParser> parsers;
    private final GraphRagService graphRagService;
    private final DocumentVectorOps vectorOps;
    private final NotificationService notificationService;
    private final DocumentParseState parseState;
    private final ConfigService configService;
    private final VisionService visionService;

    /** 单次扫描最多触发的刷新数（防一次积压打满抓取/解析链路） */
    private static final int WEB_REFRESH_SCAN_LIMIT = 20;

    /** 同名上传串行锁：避免并发上传同一文件名时双方都判定"无可复用"而产生重复文档（单实例内有效） */
    private final Map<String, Object> uploadLocks = new ConcurrentHashMap<>();

    /** 正在自动刷新的网页文档 id（防同文档上轮未结束又被触发；单实例内有效） */
    private final Set<String> refreshingDocs = ConcurrentHashMap.newKeySet();

    /** 解析进度上报（委托解析状态，节流语义见 DocumentParseState） */
    private void updateProgress(String docId, int progress, String desc) {
        parseState.update(docId, progress, desc);
    }

    /** 上传支持格式清单（聚合全部解析器声明；解析器增删后报错文案自动跟上，不再写死过期） */
    private String supportedExtsCsv() {
        return parsers.stream()
                .flatMap(p -> p.supportedExts().stream())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("/"));
    }

    /**
     * 上传文档：校验格式 → 同名替换 → 源文件落盘 → 建记录(解析中) → 异步解析。
     * visionRef（可选）：文档级视觉模型覆盖——fail-loud 校验引用可解析（引用无效立即拒绝，
     * 不留到解析期静默跳过图片描述）；空 = 跟随知识库双槽位配置。
     */
    public AiDocument upload(MultipartFile file, String description, String kbId, String visionRef) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BizException("文件名为空");
        }
        String docVisionRef = visionRef == null ? "" : visionRef.trim();
        if (docVisionRef.length() > 255) {
            throw new BizException("视觉模型引用过长（最多 255 字）");
        }
        if (!docVisionRef.isBlank() && modelRegistryService.resolveReference(docVisionRef) == null) {
            throw new BizException("视觉模型引用无效或已被删除，请重新选择");
        }
        String ext = extOf(fileName);
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(ext))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持的文件格式: ." + ext + "（支持 " + supportedExtsCsv() + "）"));
        if (file.isEmpty()) {
            throw new BizException("请选择文件");
        }
        validateMagicBytes(file, ext);

        // 同名串行：并发上传同一文件名时，避免双方都判定"无可复用"而各建一条文档（本实例内互斥；
        // 跨实例仍靠 tryLockParsing 的 CAS 兜底，最坏情况产生一条重复记录，可手动删除）
        // 目标知识库校验（kb_id 必填：未指定归入默认库）：不存在/已删除拒绝，避免上传进黑洞
        String targetKbId = kbId == null || kbId.isBlank() ? kbService.defaultId(RequestUser.uid()) : kbId.trim();
        {
            com.wenqu.ai.model.KnowledgeBase kb = kbMapper.selectById(targetKbId);
            if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
                throw new BizException("目标知识库不存在或已删除");
            }
        }
        Object lock = uploadLocks.computeIfAbsent(fileName, k -> new Object());
        try {
            synchronized (lock) {
                return doUpload(file, fileName, ext, description, parser, targetKbId, docVisionRef.isBlank() ? null : docVisionRef);
            }
        } finally {
            uploadLocks.remove(fileName, lock);
        }
    }

    /** 上传主体（已按文件名串行）：优先复用同名文档走 diff，否则新建 */
    private AiDocument doUpload(MultipartFile file, String fileName, String ext, String description,
                                DocumentParser parser, String targetKbId, String visionRef) throws Exception {
        // 背压：队列排满时**在落盘建记录之前**就拒绝，而不是先收下、再在线程池里丢掉解析任务。
        if (parseQueue.isBackpressured()) {
            throw new BizException("解析队列已满（" + parseQueue.queuedCount() + "/" + parseQueue.capacity()
                    + "），请稍后再上传");
        }
        // 同名文档优先复用其 docId 走 diff 重解析（upsert 语义：文档身份/knowledgeId 稳定，未变块增量复用、只重嵌变更处）；
        // 无可复用（无同名，或同名均解析中已清理）时走全新上传
        AiDocument reusable = reusableTarget(fileName);
        if (reusable != null) {
            AiDocument doc = replaceExisting(reusable, file, description, parser, visionRef);
            // 同名复用是 upsert 语义：文档身份不变；目标库与现归属不同时移动归属（向量模型不同会异步迁移向量）
            if (targetKbId != null && !targetKbId.equals(doc.getKbId())) {
                String fromKbId = doc.getKbId();
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, doc.getId())
                        .set(AiDocument::getKbId, targetKbId)
                        .set(AiDocument::getUpdateTime, java.time.LocalDateTime.now()));
                doc.setKbId(targetKbId);
                // 图谱随迁：同名复用换库与 moveDoc 同一口径，三元组/实体归属跟着文档走（旧库连带清理）
                graphRagService.migrateDocGraph(doc.getId(), fromKbId);
                vectorOps.migrateDocAsync(doc.getId(), fromKbId, targetKbId);
            }
            return doc;
        }

        // 全新上传：源文件落盘（异步解析需要；重解析复用）
        AiDocument doc = new AiDocument();
        doc.setFileName(fileName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus(2); // 解析中
        doc.setDescription(description);
        doc.setKbId(targetKbId);
        doc.setVisionRef(visionRef);
        doc.setCreatedBy(RequestUser.uid());
        documentMapper.insert(doc);
        documentMetaCache.invalidate(doc.getId());
        // 源文件落盘（解析期读取；重解析复用同一份）
        try {
            fileStore.saveSourceFile(file, doc.getId(), fileName);
        } catch (Exception e) {
            // 补偿：源文件落盘失败时清理刚插入的记录，避免残留"解析中"脏数据
            log.warn("源文件落盘失败，清理记录: {} error={}", doc.getId(), e.getMessage());
            documentMapper.deleteById(doc.getId());
            throw e;
        }
        // 登记驱动：只往持久化队列塞一行任务就返回，解析由扫描器抢占执行。
        parseQueue.enqueue(doc.getId(), doc.getKbId(), 0);
        return doc;
    }

    /**
     * 网页 URL 导入：抓取 HTML 快照 → 以 file_type=url 走与上传完全相同的入库链路
     * （同名替换 → 源文件落盘 → 异步 WebParser 解析 → 分块/向量化/关键词索引）。
     * 源文件即 HTML 快照：重解析读快照离线提取，不重新抓网；源 URL 记录在 source_url 列。
     * 安全：仅 http/https；手动逐跳跟进重定向，每一跳都做内网地址校验（SSRF 防护）。
     */
    public AiDocument importFromUrl(String url, String description, String kbId) throws Exception {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) throw new BizException("URL 不能为空");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new BizException("URL 格式不合法");
        }
        WebPageFetcher.FetchResult page = webPageFetcher.fetchPage(uri);

        // 文档名 = 页面 title（空则取 host+path）；同名导入走 doUpload 的 upsert 替换语义
        String fileName = webPageFetcher.pageTitle(page.bytes());
        if (fileName.isBlank()) {
            String host = page.uri().getHost() == null ? "" : page.uri().getHost();
            String path = page.uri().getPath() == null ? "" : page.uri().getPath();
            fileName = (host + path).replaceAll("^/+", "");
        }
        fileName = fileName.replaceAll("\\p{Cntrl}", " ").trim();
        if (fileName.isBlank()) fileName = page.uri().toString();
        if (fileName.length() > 200) fileName = fileName.substring(0, 200);

        DocumentParser parser = parsers.stream().filter(p -> p.supports("url")).findFirst()
                .orElseThrow(() -> new BizException("网页解析器未就绪"));
        // 目标库校验与 upload() 同口径（doUpload 内不再校验）
        String targetKbId = kbId == null || kbId.isBlank() ? kbService.defaultId(RequestUser.uid()) : kbId.trim();
        {
            KnowledgeBase kb = kbMapper.selectById(targetKbId);
            if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
                throw new BizException("目标知识库不存在或已删除");
            }
        }
        Object lock = uploadLocks.computeIfAbsent(fileName, k -> new Object());
        try {
            synchronized (lock) {
                AiDocument doc = doUpload(new WebPageFetcher.InMemoryPage(page.bytes(), fileName), fileName, "url",
                        description, parser, targetKbId, null);
                // 源 URL 回写（doUpload 不管这个字段；新导入与同名替换两条路都要落）
                String finalUrl = page.uri().toString();
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, doc.getId())
                        .set(AiDocument::getSourceUrl, finalUrl));
                doc.setSourceUrl(finalUrl);
                return doc;
            }
        } finally {
            uploadLocks.remove(fileName, lock);
        }
    }

    /**
     * 文本直接入库（工作流 kb_write 节点专用入口）：内容以 UTF-8 构造 .md 内存文件，
     * 走与上传完全相同的入库链路（同名 upsert / 同 docId 替换 → 源文件落盘 → 异步解析/分块/向量化/关键词索引）。
     * 解析异步进行：返回时文档处于「解析中」，docId 已可被下游节点引用（trace 注明该语义）。
     * <p>
     * 权限：只有知识库创建者可写（比手动上传更严——自动化入口无人盯着，归属校验 fail-loud）。
     * 身份取 {@code RequestUser}：工作流节点线程经 {@code withReplay} 已重放为运行发起人。
     *
     * @param mode create=新建（同名 upsert）；update=按 docId 覆盖内容重解析（文档身份/归属不变，标题不参与）
     */
    public AiDocument ingestText(String title, String content, String kbId, String mode, String docId) throws Exception {
        boolean update = "update".equalsIgnoreCase(mode == null ? "" : mode.trim());
        if (content == null || content.isBlank()) {
            throw new BizException("文档内容为空：kb_write 节点需要非空正文");
        }
        String targetKbId = kbId == null || kbId.isBlank() ? kbService.defaultId(RequestUser.uid()) : kbId.trim();
        KnowledgeBase kb = kbMapper.selectById(targetKbId);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
            throw new BizException("目标知识库不存在或已删除");
        }
        if (kb.getCreatedBy() != null && !kb.getCreatedBy().equals(RequestUser.uid())) {
            throw new BizException("只有知识库创建者可以写入：「" + kb.getName() + "」");
        }

        // update 模式：按 docId 定位既有文档，覆盖内容重解析（replaceExisting 的 CAS/快照/重嵌全套语义）
        if (update) {
            if (docId == null || docId.isBlank()) {
                throw new BizException("更新模式需要文档ID（可引用上游节点输出，如 {{节点id.docId}}）");
            }
            AiDocument existing = documentMapper.selectById(docId.trim());
            if (existing == null) {
                throw new BizException("要更新的文档不存在：" + docId.trim());
            }
            if (!targetKbId.equals(existing.getKbId())) {
                throw new BizException("文档「" + existing.getFileName() + "」不在目标知识库里（更新不改变归属，请选对库）");
            }
            if ("url".equalsIgnoreCase(existing.getFileType())) {
                throw new BizException("不支持覆盖网页型文档（其内容由定时抓取维护）");
            }
            DocumentParser parser = parsers.stream().filter(p -> p.supports("md")).findFirst()
                    .orElseThrow(() -> new BizException("文本解析器未就绪"));
            Object lock = uploadLocks.computeIfAbsent(existing.getFileName(), k -> new Object());
            try {
                synchronized (lock) {
                    return replaceExisting(existing, new WebPageFetcher.InMemoryPage(
                            content.getBytes(java.nio.charset.StandardCharsets.UTF_8), existing.getFileName()),
                            "由工作流更新", parser, null);
                }
            } finally {
                uploadLocks.remove(existing.getFileName(), lock);
            }
        }

        // create 模式：标题 → 文件名（清洗口径与 importFromUrl 相同），同名走 doUpload 的 upsert 语义
        String name = title == null ? "" : title.replaceAll("\\p{Cntrl}", " ").trim();
        if (name.isBlank()) name = "未命名文档";
        if (name.length() > 200) name = name.substring(0, 200);
        String fileName = name + ".md";
        DocumentParser parser = parsers.stream().filter(p -> p.supports("md")).findFirst()
                .orElseThrow(() -> new BizException("文本解析器未就绪"));
        Object lock = uploadLocks.computeIfAbsent(fileName, k -> new Object());
        try {
            synchronized (lock) {
                return doUpload(new WebPageFetcher.InMemoryPage(content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        fileName), fileName, "md", "由工作流写入", parser, targetKbId, null);
            }
        } finally {
            uploadLocks.remove(fileName, lock);
        }
    }

    /**
     * 网页源定时刷新：扫描到期（next_refresh_at<=now）且开启自动刷新的 url 文档，
     * 逐个重新抓网 + 同名替换重建（复用 {@link #importFromUrl} 全套入库链路：落盘/异步解析/向量化/索引），
     * 成功/失败均推进 last_refresh_at 与 next_refresh_at。由 {@link ScheduleCenter} 节拍调用；
     * 单实例下先推进 next_refresh_at 再执行，天然防重复触发；失败 fail-loud 不中断其他文档。
     *
     * @return 本轮实际处理的文档数（含刷新失败的——逐文档失败已单独告警，不使任务行变红；
     *         0=空跑——无到期文档，调用方据此免落执行日志）
     */
    public int refreshDueWebSources() {
        LocalDateTime now = LocalDateTime.now();
        List<AiDocument> due = documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getFileType, "url")
                .eq(AiDocument::getAutoRefresh, 1)
                .isNotNull(AiDocument::getNextRefreshAt)
                .le(AiDocument::getNextRefreshAt, now)
                .eq(AiDocument::getDeleted, 0)
                .last("limit " + WEB_REFRESH_SCAN_LIMIT));
        int processed = 0;
        for (AiDocument doc : due) {
            if (!refreshingDocs.add(doc.getId())) {
                continue; // 同文档上轮未结束跳过
            }
            processed++;
            try {
                log.info("[WEB-REFRESH] 开始刷新 doc={} url={}", doc.getId(), doc.getSourceUrl());
                importFromUrl(doc.getSourceUrl(), doc.getDescription(), doc.getKbId());
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, doc.getId())
                        .set(AiDocument::getLastRefreshAt, now)
                        .set(AiDocument::getNextRefreshAt, nextRefreshAt(doc.getRefreshCron())));
                log.info("[WEB-REFRESH] 刷新完成 doc={} url={}", doc.getId(), doc.getSourceUrl());
            } catch (Exception e) {
                log.warn("[FAIL-LOUD] 网页源刷新失败 doc={} url={}: {}", doc.getId(), doc.getSourceUrl(), e.getMessage());
                notificationService.create(doc.getCreatedBy(), Notification.TYPE_WEB_REFRESH_FAILED,
                        "网页源「" + doc.getFileName() + "」自动刷新失败",
                        "源地址 " + doc.getSourceUrl() + "：下次刷新时间已顺延；可在文档管理中手动重试。",
                        "kb", doc.getKbId(), "web:" + doc.getId(), null);
                // 失败也推进 next_refresh_at（按 cron 或兜底 1 天），避免立刻重试打爆源站
                documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .eq(AiDocument::getId, doc.getId())
                        .set(AiDocument::getNextRefreshAt, nextRefreshAt(doc.getRefreshCron())));
            } finally {
                refreshingDocs.remove(doc.getId());
            }
        }
        return processed;
    }

    /** 配置网页源自动刷新：开启时校验 cron（5段）并重算 next_refresh_at；关闭时清空 next_refresh_at。仅 file_type=url 有效 */
    public void setRefreshConfig(String docId, int autoRefresh, String cron) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (!"url".equals(doc.getFileType())) {
            throw new BizException("仅网页导入（file_type=url）的文档支持自动刷新");
        }
        if (autoRefresh == 1) {
            String c = cron == null ? "" : cron.trim();
            if (c.isEmpty()) throw new BizException("开启自动刷新需提供 refreshCron（5段，如 0 3 * * *）");
            ScheduledJobService.validateCron(c, "Asia/Shanghai");
            LocalDateTime next = ScheduledJobService.nextRunAt(c, "Asia/Shanghai", LocalDateTime.now());
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .set(AiDocument::getAutoRefresh, 1)
                    .set(AiDocument::getRefreshCron, c)
                    .set(AiDocument::getNextRefreshAt, next));
        } else {
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .set(AiDocument::getAutoRefresh, 0)
                    .set(AiDocument::getRefreshCron, null)
                    .set(AiDocument::getNextRefreshAt, null));
        }
    }

    /** 下次刷新时刻：cron 按 Asia/Shanghai 解释；空/非法 cron 兜底 1 天（不让刷新卡死） */
    private LocalDateTime nextRefreshAt(String cron) {
        if (cron == null || cron.isBlank()) return LocalDateTime.now().plusDays(1);
        try {
            return ScheduledJobService.nextRunAt(cron, "Asia/Shanghai", LocalDateTime.now());
        } catch (Exception e) {
            return LocalDateTime.now().plusDays(1);
        }
    }

    /**
     * 解析中途停止时的补偿清理：删已写向量 + MySQL 元数据 + 图片目录（文档已被删除，不恢复状态）
     */
    public void cleanupPartial(String docId, List<Document> aiDocs) {
        log.info("[{}] 解析过程中文档已被删除，停止解析并清理本次产物", docId);
        try {
            List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
            if (!vectorIds.isEmpty()) vectorOps.storeOf(docId).delete(vectorIds);
        } catch (Exception e) {
            log.warn("[{}] 补偿删除向量失败: {}", docId, e.getMessage());
        }
        // QA/子块同步清理：本次解析期生成的附加索引随删除一并清理
        vectorOps.deleteExtrasByDoc(docId);
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
        keywordIndexService.deleteByDoc(docId); // 关键词索引同步（best-effort）
        fileStore.cleanupImages(docId);
    }

    private String extOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 魔数校验：文件头字节必须与扩展名对应的真实格式一致（防伪造扩展名绕过类型限制）。
     * docx/xlsx 为 OODF zip 容器（PK\x03\x04），pdf 为 %PDF-。
     * 仅读文件头部 8 字节，不整体加载。
     */
    private void validateMagicBytes(MultipartFile file, String ext) {
        byte[] expected = switch (ext) {
            case "docx", "xlsx", "doc", "xls", "pptx" -> new byte[]{0x50, 0x4B, 0x03, 0x04}; // PK.. (zip 容器)
            case "ppt" -> new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}; // OLE2
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
            recycleService.delete(d.getId());
        }
        return target;
    }

    /**
     * 同名替换：复用原 docId（覆盖源文件 + 走 diff 重解析）
     * 文档身份与 knowledgeId 保持稳定（历史引用/评估集不失效），未变块增量复用、只重嵌变更处。
     */
    private AiDocument replaceExisting(AiDocument existing, MultipartFile file, String description,
                                       DocumentParser parser, String visionRef) throws Exception {
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
                versionService.saveSnapshot(docId, existing.getVersion() == null ? 0 : existing.getVersion());
            } catch (Exception e) {
                log.warn("[{}] 替换前保存快照失败: {}", docId, e.getMessage());
            }
            // 覆盖源文件（同 docId 同路径）；解析期由 runParseTask 按 docId 重新取路径读取
            fileStore.saveSourceFile(file, docId, existing.getFileName());
            // 更新元数据并置解析中（visionRef 同步为本次上传的选择：null=清覆盖回跟随知识库）
            existing.setFileSize(file.getSize());
            existing.setDescription(description);
            existing.setVisionRef(visionRef);
            existing.setStatus(2);
            existing.setFailReason(null);
            // 只写该写的列：全列 updateById + NOT_NULL 策略会让 failReason=null 被跳过，旧失败原因残留
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId)
                    .set(AiDocument::getFileSize, existing.getFileSize())
                    .set(AiDocument::getDescription, existing.getDescription())
                    .set(AiDocument::getVisionRef, existing.getVisionRef())
                    .set(AiDocument::getStatus, 2)
                    .set(AiDocument::getFailReason, null));
            documentMetaCache.invalidate(docId);
            updateProgress(docId, 0, "已提交,等待解析");

            // 登记驱动：入队一行任务即返回（解析由扫描器抢占执行，不再受线程池容量影响）
            parseQueue.purgeByDoc(docId);
            parseQueue.enqueue(docId, existing.getKbId(), 0);
            submitted = true;
            return existing;
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
    public void tryLockParsing(String docId) {
        int locked = documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .and(w -> w.ne(AiDocument::getStatus, 2).or().isNull(AiDocument::getStatus))
                .set(AiDocument::getStatus, 2));
        if (locked == 0) {
            throw new BizException("该文档正在解析中，请等待完成后再操作");
        }
    }

}
