package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 文档回收站与物理清除：软删、恢复、彻底删除、按库级联清除。
 * <p>
 * 从 {@link DocumentService} 抽出：这一块是「文档生命周期终点」的唯一入口，
 * 与解析、上传、向量重建互不依赖（只单向调用 {@link DocumentVectorOps} 与 {@link DocumentFileStore} 收尾），
 * 抽出的意义在于删除路径的一致性——向量 → 附加索引 → 图谱 → 行 → 文件，顺序只在这里定义一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRecycleService {

    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final com.wenqu.ai.mapper.AiDocumentVersionMapper versionMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KeywordIndexService keywordIndexService;
    private final GraphRagService graphRagService;
    private final AuditService audit;
    private final PlatformTransactionManager txManager;
    private final DocumentMetaCache documentMetaCache;
    private final DocumentFileStore fileStore;
    private final DocumentVectorOps vectorOps;
    private final DocumentParseState parseState;
    private final DocumentKbResolver kbResolver;
    private final KnowledgeBaseService kbService;

    /**
     * 删除文档：进入回收站（软删保留内容）。
     * <p>与旧「硬删」的差异：只落 deleted=1 + deleted_at，并同步移除关键词索引（检索面即时消失）；
     * 向量、知识块、源文件、图片、版本快照全部保留——恢复即原样回来（向量未动，检索即时可用）。
     * 检索安全性靠既有双兜底：向量路命中级可见性过滤对已删行 fail-closed
     * （{@code loadVisibleDocIdsOfHits} 的 selectBatchIds 取不到已删行），关键词路在软删时同步删索引。
     * <p>解析中（status=2）的文档维持硬删：半成品没有可保留的价值，且硬删路径自带解析中断与产物清理。
     */
    public void delete(String docId) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (doc.getStatus() != null && doc.getStatus() == 2) {
            purge(docId);
            return;
        }
        parseState.markDeleted(docId);
        try {
            // 兜底中断（正常不该有：非解析中不持解析线程；重队列边界的竞态窗口仍可能残留）
            Thread parseThread = parseState.threadOf(docId);
            if (parseThread != null && parseThread.isAlive()) {
                parseThread.interrupt();
                log.info("[{}] 删除时中断解析线程", docId);
            }
            keywordIndexService.deleteByDoc(docId); // best-effort：检索面即时消失
            if (documentMapper.softDelete(docId) == 0) {
                throw new BizException("文档不存在或已在回收站");
            }
            audit.record("[AUDIT] 文档进入回收站 operator={} docId={} file={}", RequestUser.uid(), docId, doc.getFileName());
        } finally {
            parseState.clearDeleted(docId);
        }
        documentMetaCache.invalidate(docId);
    }

    /**
     * 回收站列表（kbId 空=全部；默认库语义与文档列表一致：并入 kb_id IS NULL 的历史行）。
     * 只含本轮软删行（deleted_at 非空）——历史硬删行内容已销毁，不进回收站视图。
     */
    public List<AiDocument> recycleList(String kbId) {
        List<AiDocument> all = documentMapper.selectRecycled();
        if (kbId == null || kbId.isBlank()) return all;
        boolean isDefault = kbId.equals(kbResolver.defaultKbId());
        return all.stream()
                .filter(d -> kbId.equals(d.getKbId()) || (isDefault && d.getKbId() == null))
                .toList();
    }

    /** 回收站取单条（供控制器做管理权校验：selectById 对已删行恒 null） */
    public AiDocument getRecycled(String docId) {
        return documentMapper.selectRecycledById(docId);
    }

    /**
     * 从回收站恢复：校验所在库存活与同名冲突后出站；关键词索引灌回（仅生效文档，
     * 与启停用恢复同口径），向量未动、检索即时可用。
     */
    public void restore(String docId) {
        AiDocument doc = documentMapper.selectRecycledById(docId);
        if (doc == null) throw new BizException("回收站中没有该文档（可能已被彻底删除）");
        String effectiveKb = doc.getKbId() == null || doc.getKbId().isBlank() ? kbResolver.defaultKbId() : doc.getKbId();
        com.wenqu.ai.model.KnowledgeBase kb = effectiveKb == null ? null : kbMapper.selectById(effectiveKb);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
            throw new BizException("该文档所属知识库已删除，无法恢复；可先确认知识库，或直接彻底删除该文档");
        }
        if (documentMapper.countActiveSameName(effectiveKb, doc.getFileName()) > 0) {
            throw new BizException("已存在同名生效文档（" + doc.getFileName() + "），无法恢复；请先处理同名文档");
        }
        if (documentMapper.restore(docId) == 0) {
            throw new BizException("恢复失败：文档状态已变化，请刷新后重试");
        }
        if (doc.getStatus() != null && doc.getStatus() == 0) {
            try {
                List<Knowledge> blocks = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
                if (!blocks.isEmpty()) keywordIndexService.indexChunks(blocks);
            } catch (Exception e) {
                log.warn("[{}] 恢复后关键词索引灌回失败（可稍后 /reindex 修复）: {}", docId, e.getMessage());
            }
        }
        documentMetaCache.invalidate(docId);
        audit.record("[AUDIT] 文档已从回收站恢复 operator={} docId={} file={}", RequestUser.uid(), docId, doc.getFileName());
    }

    /**
     * 彻底删除（回收站「彻底删除」/ 清空回收站 / 解析中文档删除共用的物理清除路径）：
     * 向量 → 附加索引 → GraphRAG → 知识块 → 关键词索引 → 版本快照 → 文档行（物理）→ 图片/源文件。
     * 删除顺序保证失败可重试不产生不可追溯孤儿；解析中断逻辑保留（parsing 路入口）。
     */
    public void purge(String docId) {
        // 立即标记删除 + 中断解析线程（图片 join 等待立即响应，不再等阶段检查点）
        // 多实例语义：deletedFlags/parseThreads 为进程内存态——本实例解析的任务可立即中断；
        // 其他实例上运行的解析任务由 isDocAlive 的 DB 兜底感知（删除后 selectById 为 null），
        // 在阶段检查点（入库每 10 块/向量化每批）秒级停止并清理产物，保证跨实例不产生孤儿数据。
        parseState.markDeleted(docId);
        Thread parseThread = parseState.threadOf(docId);
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
                    vectorOps.storeOf(docId).delete(vectorIds);
                } catch (Exception e) {
                    parseState.clearDeleted(docId); // 删除未完成，撤销删除标志避免误停后续解析
                    log.error("[{}] 删除向量失败，已中止删除（MySQL 记录保留，可重试）: {}", docId, e.getMessage());
                    throw new BizException("删除向量失败，文档未删除，请稍后重试");
                }
            }
        }
        // QA/子块附加索引随文档删除（向量失败不中止删除，行删除兜底；向量残留可整库重建清理）
        vectorOps.deleteExtrasByDoc(docId);
        // GraphRAG：该文档的三元组与抽取记录随删（实体保留——无三元组引用的实体不参与图扩展，无害）
        try {
            graphRagService.onDocDeleted(docId);
        } catch (Exception e) {
            log.warn("[{}] GraphRAG 清理失败（不影响删除）: {}", docId, e.getMessage());
        }
        // 核心 DB 删除收进一个事务：知识块 / 版本快照 / 文档行要么一起消失，要么一起保留等重试。
        // 向量与文件不可事务化，仍按「先向量后 DB」；失败可重试，不会留下不可追溯的孤儿。
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
            versionMapper.delete(new LambdaQueryWrapper<com.wenqu.ai.model.AiDocumentVersion>()
                    .eq(com.wenqu.ai.model.AiDocumentVersion::getDocId, docId));
            documentMapper.hardDelete(docId);
        });
        keywordIndexService.deleteByDoc(docId); // 关键词索引同步（best-effort）
        documentMetaCache.invalidate(docId);
        // 引用关系清理（纯派生数据，随文档删除）
        fileStore.cleanupImages(docId);
        fileStore.cleanupSourceFile(docId);
        audit.record("[AUDIT] 文档已彻底删除 operator={} docId={}", RequestUser.uid(), docId);
    }

    /**
     * 级联彻底删除某个知识库的<b>全部</b>文档（活跃 + 回收站），供删库前调用：
     * 向量 → 附加索引 → GraphRAG 三元组 → 知识块 → 关键词索引 → 版本快照 → 文档行 → 图片/源文件，
     * 全部逐条走 {@link #purge(String)}（与「回收站彻底删除」同语义、可重试不产生孤儿）。
     * <p>库级 GraphRAG 实体与抽取记录按 kb 维度残留（purge 只按 doc 删三元组，
     * 跨文档共享的实体不会随单文档删除消失），故末尾再 {@code graphRagService.clear(kbId)} 兜底。
     * <p>本方法只清文档与图谱，<b>不删知识库自身行</b>（由 {@code KnowledgeBaseService.delete} 负责）。
     * 任一文档向量删除失败会抛 {@code BizException} 中止；已删部分不回滚，重试即可——
     * 与单文档 purge「删库属用户显式意图、中途失败可重试」的语义一致。
     *
     * @param kbId 知识库 ID（默认库/官方内置库调用方已先行拦截，不应传入）
     */
    public void purgeByKb(String kbId) {
        // 1) 活跃文档：逐条彻底删除
        for (String docId : kbService.docIdsOf(java.util.List.of(kbId))) {
            purge(docId);
        }
        // 2) 回收站内仍属本库的软删文档：库删了之后这些行无法恢复，必须一并清掉，否则成永久垃圾数据
        for (com.wenqu.ai.model.AiDocument d : recycleList(kbId)) {
            purge(d.getId());
        }
        // 3) 库级 GraphRAG 兜底：实体与抽取记录不会随单文档 purge 消失
        try {
            graphRagService.clear(kbId);
        } catch (Exception e) {
            log.warn("[GRAPH] 清理知识库 {} 的图谱实体/抽取记录失败（不影响删库，可事后重建或手工清理）: {}",
                    kbId, e.getMessage());
        }
    }
}
