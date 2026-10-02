package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.KnowledgeBaseMapper;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.model.KnowledgeBase;
import com.wisesoft.ai.parser.TextParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 官方内置知识库种子同步（问渠使用手册）。
 * <p>
 * 把 classpath:manual/*.md 同步进一个<b>系统拥有的官方内置库</b>（builtin=1），
 * 让「问渠怎么用」本身成为可检索的知识——问答链路、智能体知识范围、帮助中心共用同一份数据。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>触发时机</b>：{@link ApplicationReadyEvent}（晚于全部 ApplicationRunner，
 *       保证 SchemaMigrator 已把 builtin/source_hash 列补齐）；</li>
 *   <li><b>幂等增量</b>：按源文件 SHA-256 指纹（c_ai_document.source_hash）判定——
 *       指纹未变且状态生效则跳过；变更/新增整篇重建（删旧文档走 {@link DocumentService#delete} 全套清理）；
 *       status=3（上次向量化失败）的篇目无条件重建，实现重启自愈；</li>
 *   <li><b>权限语义</b>：库 createdBy=system + share_config 全员只读 ⇒ 现有 ResourceVisibilityService
 *       天然「人人可读、无人可管」，检索可见性零改动；服务层 update/delete 另有 builtin 拦截双保险；</li>
 *   <li><b>向量模型</b>：优先沿用已解析可用的现有绑定；否则取既有知识库绑定的众数，
 *       再退到注册表第一个可用 embedding（{@link ModelRegistryService#firstAvailableEmbeddingRef()}）；
 *       绑定变化触发全篇重建（向量空间与索引一一对应）。**没有任何可用向量模型时明确 ERROR 并跳过本轮**，
 *       不静默吞掉（库/文档行不建半成品）；</li>
 *   <li><b>绕过解析队列</b>：手册随包分发、内容小且纯文本，直接 TextParser 分块 +
 *       {@link DocumentService#embedAndStore}（向量化+关键词索引与手动建块同口径），不占用上传解析队列。</li>
 * </ul>
 * 假设：单实例种子（多实例各自跑同一套幂等同步，最坏并发重建同一篇目，结果一致；源文件指纹保证不重复入库）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ManualSeedService {

    /** 官方内置库名称 */
    public static final String MANUAL_KB_NAME = "问渠使用手册";
    /** 官方内置库图标（emoji；'wenqu' 品牌标是个人默认库专属，避免混淆） */
    private static final String MANUAL_KB_ICON = "📘";
    /** 手册文档固定描述（文档页展示） */
    private static final String MANUAL_DOC_DESC = "问渠官方使用手册（随版本自动同步，不可编辑）";
    /** 全员只读共享配置（v2：read_scope=global；manage_scope 缺失=除创建者外无人可管理——system 不会命中任何 uid） */
    private static final String READ_GLOBAL_SHARE = "{\"version\":2,\"read_scope\":{\"access_level\":\"global\"}}";

    private final KnowledgeBaseMapper kbMapper;
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final DocumentService documentService;
    private final TextParser textParser;
    private final ModelRegistryService modelRegistryService;

    /** 防重入（Ready 事件只会触发一次，防御未来多处触发） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public ManualSeedService(KnowledgeBaseMapper kbMapper, AiDocumentMapper documentMapper,
                             KnowledgeMapper knowledgeMapper, DocumentService documentService,
                             TextParser textParser, ModelRegistryService modelRegistryService) {
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.documentService = documentService;
        this.textParser = textParser;
        this.modelRegistryService = modelRegistryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!running.compareAndSet(false, true)) return;
        try {
            sync();
        } catch (Exception e) {
            // 启动种子失败不阻断应用，但必须 ERROR 显式留痕（不静默）
            log.error("[ManualSeed] 官方内置手册同步失败（不影响应用启动）: {}", e.getMessage(), e);
        }
    }

    /** 同步主体：解析 classpath 手册 → 官方库 get-or-create → 按指纹增量重建篇目 */
    void sync() {
        List<ManualFile> files = loadBundledFiles();
        if (files.isEmpty()) {
            log.warn("[ManualSeed] classpath:manual/ 下没有手册文件，跳过同步");
            return;
        }

        // 1. 官方库 get-or-create
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getBuiltin, 1)
                .eq(KnowledgeBase::getDeleted, 0)
                .last("LIMIT 1"));
        boolean rebuildAll = false;
        if (kb == null) {
            String ref = resolveEmbeddingRef(null);
            if (ref == null) return; // resolveEmbeddingRef 内已 ERROR 留痕
            kb = new KnowledgeBase();
            kb.setName(MANUAL_KB_NAME);
            kb.setDescription("问渠官方使用手册：产品功能、操作指南、配置说明与常见问题。随版本自动同步，全员可读，不可编辑；可在对话中直接提问，也可绑定到智能体知识范围。");
            kb.setIcon(MANUAL_KB_ICON);
            kb.setIsDefault(0);
            kb.setBuiltin(1);
            kb.setCreatedBy("system");
            kb.setShareConfig(READ_GLOBAL_SHARE);
            kb.setEmbeddingRef(ref);
            kb.setDeleted(0);
            LocalDateTime now = LocalDateTime.now();
            kb.setCreateTime(now);
            kb.setUpdateTime(now);
            kbMapper.insert(kb);
            log.info("[ManualSeed] 已创建官方内置知识库「{}」: {}（向量模型 {}）", kb.getName(), kb.getId(), ref);
        } else {
            // 现有绑定仍然可用则沿用（避免无谓的全量重嵌）；不可用才换新绑定并全篇重建
            String current = kb.getEmbeddingRef();
            if (!refResolves(current)) {
                String ref = resolveEmbeddingRef(current);
                if (ref == null) return;
                kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, kb.getId())
                        .set(KnowledgeBase::getEmbeddingRef, ref)
                        .set(KnowledgeBase::getEmbeddingDimensions, null)
                        .set(KnowledgeBase::getUpdateTime, LocalDateTime.now()));
                log.warn("[ManualSeed] 手册库原向量模型 {} 已不可解析，切换为 {}，本轮全篇重建", current, ref);
                rebuildAll = true;
            }
        }

        // 2. 逐篇同步（指纹未变跳过；变更/新增/上次失败 → 整篇重建）
        int added = 0, rebuilt = 0, kept = 0, failed = 0;
        for (ManualFile mf : files) {
            AiDocument doc = documentMapper.selectOne(new LambdaQueryWrapper<AiDocument>()
                    .eq(AiDocument::getKbId, kb.getId())
                    .eq(AiDocument::getFileName, mf.fileName())
                    .eq(AiDocument::getDeleted, 0)
                    .last("LIMIT 1"));
            boolean unchanged = doc != null && doc.getStatus() != null && doc.getStatus() == 0
                    && mf.sha256().equals(doc.getSourceHash());
            if (unchanged && !rebuildAll) {
                kept++;
                continue;
            }
            boolean ok = rebuildDoc(kb, mf, doc);
            if (ok) {
                if (doc == null) added++; else rebuilt++;
            } else {
                failed++;
            }
        }

        // 3. 清理包里已不存在的篇目（手册文件被移除 → 文档与向量同步移除）
        int removed = 0;
        for (AiDocument doc : documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getKbId, kb.getId())
                .eq(AiDocument::getDeleted, 0))) {
            boolean bundled = files.stream().anyMatch(f -> f.fileName().equals(doc.getFileName()));
            if (bundled) continue;
            try {
                documentService.delete(doc.getId());
                removed++;
                log.info("[ManualSeed] 已移除不再随包分发的篇目: {}", doc.getFileName());
            } catch (Exception e) {
                log.error("[ManualSeed] 移除篇目 {} 失败（向量清理未完成，可重试）: {}", doc.getFileName(), e.getMessage());
            }
        }
        log.info("[ManualSeed] 同步完成：新增 {}，重建 {}，跳过 {}，失败 {}，移除 {}（库 {}）",
                added, rebuilt, kept, failed, removed, kb.getId());
    }

    /**
     * 重建单篇：删旧文档（全套清理：向量/知识块/关键词索引/版本快照）→ 建新文档 → 分块 → 逐块向量化+关键词索引。
     *
     * @return true=成功（文档 status=0 且指纹已落）；false=失败（文档 status=3、指纹留空 → 下次启动自愈重建）
     */
    private boolean rebuildDoc(KnowledgeBase kb, ManualFile mf, AiDocument old) {
        String docId;
        try {
            if (old != null) {
                documentService.delete(old.getId());
            }
        } catch (Exception e) {
            // 旧文档向量删除失败：保留旧行下次重试（不产生"MySQL 已删、向量残留"孤儿）
            log.error("[ManualSeed] 篇目 {} 旧文档清理失败，本轮跳过: {}", mf.fileName(), e.getMessage());
            return false;
        }
        AiDocument doc = new AiDocument();
        doc.setFileName(mf.fileName());
        doc.setFileType("md");
        doc.setFileSize((long) mf.bytes().length);
        doc.setStatus(2);
        doc.setParseProgress(0);
        doc.setParseDesc("内置手册同步中");
        doc.setDescription(MANUAL_DOC_DESC);
        doc.setKbId(kb.getId());
        doc.setCreatedBy("system");
        doc.setDeleted(0);
        doc.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        documentMapper.insert(doc);
        docId = doc.getId();

        try {
            Path tmp = Files.createTempFile("wenqu-manual-", ".md");
            try {
                Files.write(tmp, mf.bytes());
                List<com.wisesoft.ai.model.Chunk> chunks = textParser.parse(tmp, mf.fileName(), docId);
                if (chunks.isEmpty()) {
                    throw new IllegalStateException("手册文件未解析出任何内容");
                }
                int embedFailed = 0;
                for (int i = 0; i < chunks.size(); i++) {
                    com.wisesoft.ai.model.Chunk c = chunks.get(i);
                    Knowledge k = new Knowledge();
                    k.setDocId(docId);
                    k.setTitle(c.title());
                    k.setContent(c.content());
                    k.setTitlePath(c.titlePath());
                    k.setImages(c.images().isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(c.images()));
                    k.setChunkIndex(i);
                    k.setStatus(0);
                    k.setContentHash(documentService.contentHash(c.title(), c.titlePath(), c.content(), c.images()));
                    k.setCreateTime(now);
                    knowledgeMapper.insert(k);
                    // 单块向量化+关键词索引（与「手动新增知识块」同口径）；失败计数不中断——
                    // 只要有一块失败，整篇置失败状态（半成品篇目不进检索：检索只取 status=0）
                    if (!documentService.embedAndStore(k, c.content())) {
                        embedFailed++;
                    }
                }
                doc.setChunkCount(chunks.size());
                if (embedFailed > 0) {
                    doc.setStatus(3);
                    doc.setFailReason("向量化失败（" + embedFailed + "/" + chunks.size() + " 块未同步）：向量模型不可用或调用失败；修复后重启服务自动重建");
                    doc.setParseDesc("内置手册同步失败");
                    log.error("[ManualSeed] 篇目 {} 向量化失败 {}/{} 块，已置失败状态（重启自愈）",
                            mf.fileName(), embedFailed, chunks.size());
                } else {
                    doc.setStatus(0);
                    doc.setFailReason(null);
                    doc.setParseProgress(100);
                    doc.setParseDesc("内置手册同步完成（" + chunks.size() + " 块）");
                    doc.setVersion(1);
                    doc.setSourceHash(mf.sha256());
                    log.info("[ManualSeed] 篇目同步完成: {}（{} 块）", mf.fileName(), chunks.size());
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            doc.setStatus(3);
            doc.setFailReason("手册解析失败: " + truncate(e.getMessage()));
            doc.setParseDesc("内置手册同步失败");
            log.error("[ManualSeed] 篇目 {} 解析失败: {}", mf.fileName(), e.getMessage(), e);
        }
        doc.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(doc);
        return doc.getStatus() != null && doc.getStatus() == 0;
    }

    /**
     * 解析官方库应绑定的向量模型引用：
     * 既有知识库绑定的众数（平台事实标准）→ 注册表第一个可用 embedding。
     *
     * @param excludeRef 需要排除的引用（原绑定失效场景）
     * @return 可用引用；null=无可用向量模型（已 ERROR 留痕，调用方直接终止本轮）
     */
    private String resolveEmbeddingRef(String excludeRef) {
        Map<String, Integer> count = new HashMap<>();
        for (KnowledgeBase other : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDeleted, 0)
                .isNotNull(KnowledgeBase::getEmbeddingRef)
                .ne(KnowledgeBase::getEmbeddingRef, ""))) {
            String r = other.getEmbeddingRef();
            if (r.equals(excludeRef)) continue;
            count.merge(r, 1, Integer::sum);
        }
        String best = count.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(Map.Entry::getKey)
                .filter(this::refResolves)
                .findFirst().orElse(null);
        if (best != null) return best;
        String first = modelRegistryService.firstAvailableEmbeddingRef();
        if (first != null && !first.equals(excludeRef) && refResolves(first)) return first;
        log.error("[ManualSeed] 未检测到任何可用向量模型（请在「供应商管理」登记 embedding 模型，或在任一知识库完成绑定）——"
                + "官方内置手册本轮未同步，恢复后重启服务自动补齐");
        return null;
    }

    /** 引用是否可解析且确为向量类型（ModelRegistryService 系统级解析，与运行时 storeForKb 同源） */
    private boolean refResolves(String ref) {
        if (ref == null || ref.isBlank()) return false;
        try {
            if (modelRegistryService.resolveReference(ref) == null) return false;
            String type = modelRegistryService.referenceType(ref);
            return type == null || ModelRegistryService.TYPE_EMBEDDING.equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取 classpath:manual/*.md（按文件名排序，编号前缀即篇目顺序） */
    private List<ManualFile> loadBundledFiles() {
        List<ManualFile> out = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:manual/*.md");
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name == null || !name.toLowerCase().endsWith(".md")) continue;
                byte[] bytes = r.getContentAsByteArray();
                if (bytes.length == 0) continue;
                out.add(new ManualFile(name, bytes, sha256Hex(bytes)));
            }
        } catch (Exception e) {
            log.error("[ManualSeed] 读取 classpath 手册资源失败: {}", e.getMessage(), e);
        }
        out.sort((a, b) -> a.fileName().compareTo(b.fileName()));
        return out;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    /** 包内手册文件：文件名 + 字节 + SHA-256 指纹 */
    record ManualFile(String fileName, byte[] bytes, String sha256) {
    }
}
