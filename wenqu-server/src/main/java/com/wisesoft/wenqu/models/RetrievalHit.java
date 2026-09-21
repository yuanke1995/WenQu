package com.wisesoft.wenqu.models;

import java.util.List;

/**
 * 基础检索的一次命中（向量 / 关键词 / 混合召回统一形态）。
 *
 * <p>原是 {@code HybridRetrievalService} 的内部 record；该类随旧检索层下线移除后，
 * 这个载体仍被 {@code knowledge/graphs/KnowledgeGraphRetrieval#buildChunkFromHit}
 * （对应参考实现 {@code _build_chunk_from_hit} 的入参）使用，故提取为独立 record，
 * 字段与取值语义保持不变：
 * Milvus {@code hit.entity} 的 {@code file_id / chunk_id / chunk_index / content / images / score}
 * + 本工程补充的 {@code title / titlePath / knowledgeId}。
 *
 * @param knowledgeId 命中的知识记录 id（chunk id）
 * @param docId       所属文档 id
 * @param title       文档标题
 * @param content     召回内容
 * @param images      关联图片链接（可为空）
 * @param score       检索分数
 * @param chunkIndex  分片序号（可为空）
 * @param titlePath   标题路径（可为空）
 */
public record RetrievalHit(
        String knowledgeId,
        String docId,
        String title,
        String content,
        List<String> images,
        double score,
        Integer chunkIndex,
        String titlePath) {}
