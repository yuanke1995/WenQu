package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.AiDocumentVersionMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档版本快照与回滚。
 * <p>
 * 从 {@link DocumentService} 抽出：版本是「解析产物的历史副本」，只依赖知识块表、版本表与向量写入，
 * 不参与解析状态机。回滚语义的关键约束（先向量化后行级切换、失败恢复原向量）都集中在本类，
 * 独立后这段最容易出错的逻辑有了明确的归属与阅读边界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVersionService {

    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final AiDocumentVersionMapper versionMapper;
    private final KeywordIndexService keywordIndexService;
    private final QaIndexService qaIndexService;
    private final ChildChunkService childChunkService;
    private final ConfigService configService;
    private final DocumentMetaCache documentMetaCache;
    private final DocumentAclTags documentAclTags;
    private final DocumentVectorOps vectorOps;
    private final DocumentParseState parseState;

    /**
     * 保存文档当前知识块状态为版本快照（按 chunk_index 有序，快照含原 knowledgeId 便于回滚后可溯源）
     */
    public void saveSnapshot(String docId, int version) {
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
        versionMapper.delete(new LambdaQueryWrapper<com.wenqu.ai.model.AiDocumentVersion>()
                .eq(com.wenqu.ai.model.AiDocumentVersion::getDocId, docId)
                .eq(com.wenqu.ai.model.AiDocumentVersion::getVersion, version));
        com.wenqu.ai.model.AiDocumentVersion v = new com.wenqu.ai.model.AiDocumentVersion();
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
                        new LambdaQueryWrapper<com.wenqu.ai.model.AiDocumentVersion>()
                                .eq(com.wenqu.ai.model.AiDocumentVersion::getDocId, docId)
                                .orderByDesc(com.wenqu.ai.model.AiDocumentVersion::getVersion))
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

        com.wenqu.ai.model.AiDocumentVersion v = versionMapper.selectOne(
                new LambdaQueryWrapper<com.wenqu.ai.model.AiDocumentVersion>()
                        .eq(com.wenqu.ai.model.AiDocumentVersion::getDocId, docId)
                        .eq(com.wenqu.ai.model.AiDocumentVersion::getVersion, version));
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
            // 快照字段：id/title/content/titlePath/images（缺字段按 null/空处理）
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
            k.setContentHash(DocumentTextUtils.contentHash(title, titlePath, content, imgList));
            k.setVectorId(k.getId()); // 向量 id 与行 id 恒同（与 embedAndStore 语义一致）

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("docId", docId);
            metadata.put("title", title);
            metadata.put("knowledgeId", k.getId());
            if (titlePath != null && !titlePath.isBlank()) metadata.put("titlePath", titlePath);
            if (k.getImages() != null) metadata.put("images", k.getImages());
            documentAclTags.putKbId(metadata, docId);
            aiDocs.add(new org.springframework.ai.document.Document(k.getId(),
                    DocumentTextUtils.buildEmbedText(title, titlePath, content, null), metadata));
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
                    vectorOps.storeOf(docId).add(batch);
                } catch (Exception e) {
                    log.warn("[{}] 回滚向量化失败 {}-{}，重试一次: {}", docId, i + 1, end, e.getMessage());
                    try {
                        vectorOps.storeOf(docId).add(batch);
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
                vectorOps.storeOf(docId).delete(staleVectorIds);
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
        // 只写该写的列：全列 updateById + NOT_NULL 策略会让 failReason=null 被跳过，旧失败原因残留
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getChunkCount, snapshot.size())
                .set(AiDocument::getVersion, version)
                .set(AiDocument::getStatus, 0)
                .set(AiDocument::getFailReason, null));
        versionMapper.delete(new LambdaQueryWrapper<com.wenqu.ai.model.AiDocumentVersion>()
                .eq(com.wenqu.ai.model.AiDocumentVersion::getDocId, docId)
                .gt(com.wenqu.ai.model.AiDocumentVersion::getVersion, version));
        documentMetaCache.invalidate(docId);
        keywordIndexService.indexChunks(rebuilt); // 关键词索引同步：写入重建块（best-effort）
        // QA/子块附加索引重建：回滚后块内容变了（且块 id 可能换），旧索引全量作废按回滚后内容重建
        try {
            vectorOps.deleteExtrasByDoc(docId);
            VectorStore store = vectorOps.storeOf(docId);
            if (configService.getBoolean("parse.qaEnabled", true) && !rebuilt.isEmpty()) {
                qaIndexService.generateForBlocks(docId, rebuilt, store, null, vectorOps.kbOwnerOfDoc(docId));
            }
            if (configService.getBoolean("parse.childEnabled", true) && !rebuilt.isEmpty()) {
                childChunkService.generateForBlocks(docId, rebuilt, store, null);
            }
        } catch (Exception e) {
            log.warn("[{}] 回滚后附加索引重建失败（可在重解析时补齐）: {}", docId, e.getMessage());
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
                vectorOps.storeOf(docId).delete(toDelete);
            }
        } catch (Exception e) {
            log.warn("[{}] 回滚失败后清理向量异常: {}", docId, e.getMessage());
        }
        // 按当前行内容恢复向量（逐行 best-effort；行内容始终在库中，缺向量可稍后重解析补齐）
        for (Knowledge k : currentRows) {
            vectorOps.embedAndStore(k, k.getContent());
        }
        parseState.update(docId, 0, "回滚向量化失败，原内容与向量已恢复，请稍后重试");
    }
}
