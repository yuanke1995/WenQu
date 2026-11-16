package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识块与文档标签的写操作：启停用、批量操作、块编辑/删除、标签规范化。
 * 一致性口径与解析链路一致——写向量成功才写 MySQL，附加索引（QA/子块）随内容失效重建。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChunkService {
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final DocumentMetaCache documentMetaCache;
    private final KeywordIndexService keywordIndexService;
    private final DocumentVectorOps vectorOps;
    private final ConfigService configService;
    private final QaIndexService qaIndexService;
    private final ChildChunkService childChunkService;
    /** 批量删除复用回收站删除链路（向量/索引/文件/版本一并清理） */
    private final DocumentRecycleService recycleService;

    /** 设置文档标签（逗号分隔，中英文逗号都收）：规范化（去重/限长/限数）后落库；空串=清空 */
    public void setTags(String docId, String tags) {
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getTags, normalizeTags(tags))
                .set(AiDocument::getUpdateTime, java.time.LocalDateTime.now()));
    }

    /** 标签规范化：按中英文逗号拆分、去空白、去重、单个限 30 字、最多 20 个 */
    private String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) return "";
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String p : tags.split("[,，]")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            out.add(t.length() > 30 ? t.substring(0, 30) : t);
            if (out.size() >= 20) break;
        }
        return String.join(",", out);
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
     * 批量删除文档（任一失败不中断，继续处理其余）
     */
    public void batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            try {
                recycleService.delete(id);
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
            vectorOps.storeOf(k.getDocId()).add(List.of(new Document(k.getId(),
                    DocumentTextUtils.buildEmbedText(newTitle, k.getTitlePath(), content, null), metadata)));
        } catch (Exception e) {
            log.warn("知识块重新向量化失败 id={}: {}", id, e.getMessage());
            throw new BizException("知识块向量化失败，内容未修改，请稍后重试");
        }

        // 2. 向量写入成功后再更新 MySQL（此时两侧内容一致）
        String oldTitle = k.getTitle();
        String oldContent = k.getContent();
        k.setTitle(newTitle);
        k.setContent(content);
        k.setContentHash(DocumentTextUtils.contentHash(k.getTitle(), k.getTitlePath(), k.getContent(), DocumentTextUtils.parseImages(k.getImages())));
        k.setVectorId(k.getId());
        knowledgeMapper.updateById(k);
        keywordIndexService.indexChunks(List.of(k)); // 关键词索引同步：按 id upsert（best-effort）
        // 3. 清理历史遗留的异 id 旧向量（正常链路 vectorId==knowledgeId，已被 upsert 覆盖，无需删除）
        if (oldVectorId != null && !oldVectorId.isBlank() && !oldVectorId.equals(k.getId())) {
            try {
                vectorOps.storeOf(k.getDocId()).delete(List.of(oldVectorId));
            } catch (Exception e) {
                log.warn("清理旧向量失败 id={} oldVectorId={}: {}", id, oldVectorId, e.getMessage());
            }
        }
        // 4. 附加索引随内容失效：删旧 QA/子块；各自开关开启时按新内容重建
        //    （QA 单块单次 LLM 调用、子块为确定性切分；失败仅告警，下次重解析补齐）
        try {
            vectorOps.deleteExtrasByKnowledge(List.of(id), k.getDocId());
            VectorStore store = vectorOps.storeOf(k.getDocId());
            if (configService.getBoolean("parse.qaEnabled", true)) {
                qaIndexService.generateForBlocks(k.getDocId(), List.of(k), store, null, vectorOps.kbOwnerOfDoc(k.getDocId()));
            }
            if (configService.getBoolean("parse.childEnabled", true)) {
                childChunkService.generateForBlocks(k.getDocId(), List.of(k), store, null);
            }
        } catch (Exception e) {
            log.warn("知识块附加索引更新失败 id={}: {}", id, e.getMessage());
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
                vectorOps.storeOf(k.getDocId()).delete(List.of(k.getVectorId()));
            } catch (Exception e) {
                log.warn("删除知识块向量失败 id={}: {}", id, e.getMessage());
            }
        }
        // QA/子块附加索引随块删除
        vectorOps.deleteExtrasByKnowledge(List.of(id), k.getDocId());
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
}
