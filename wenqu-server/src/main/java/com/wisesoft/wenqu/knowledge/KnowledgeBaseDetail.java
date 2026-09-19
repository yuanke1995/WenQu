package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.permissions.ResourcePermission;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识库详情读取模型，在摘要基础上增加详情页字段。
 *
 * <p>由参考实现的 knowledge/read_models.py 中 KnowledgeBaseDetail 逐字段翻译。
 */
public record KnowledgeBaseDetail(
        String kbId,
        String name,
        String description,
        String kbType,
        String embeddingModelSpec,
        String llmModelSpec,
        Map<String, Object> queryParams,
        Map<String, Object> additionalParams,
        Map<String, Object> shareConfig,
        String createdBy,
        OffsetDateTime createdAt,
        int fileCount,
        int folderCount,
        int rowCount,
        long totalSize,
        long chunkCount,
        long tokenCount,
        long pendingParseCount,
        long pendingIndexCount,
        long processingCount,
        ResourcePermission effectivePermission,
        Map<String, Object> mindmap,
        List<String> sampleQuestions,
        Map<String, Map<String, Object>> files,
        boolean filesTruncated,
        Integer filesPageSize) {

    /** 由摘要派生详情（对应参考实现的继承关系）。 */
    public static KnowledgeBaseDetail of(
            KnowledgeBaseSummary summary,
            Map<String, Object> mindmap,
            List<String> sampleQuestions,
            Map<String, Map<String, Object>> files,
            boolean filesTruncated,
            Integer filesPageSize) {
        return new KnowledgeBaseDetail(
                summary.kbId(),
                summary.name(),
                summary.description(),
                summary.kbType(),
                summary.embeddingModelSpec(),
                summary.llmModelSpec(),
                summary.queryParams(),
                summary.additionalParams(),
                summary.shareConfig(),
                summary.createdBy(),
                summary.createdAt(),
                summary.fileCount(),
                summary.folderCount(),
                summary.rowCount(),
                summary.totalSize(),
                summary.chunkCount(),
                summary.tokenCount(),
                summary.pendingParseCount(),
                summary.pendingIndexCount(),
                summary.processingCount(),
                summary.effectivePermission(),
                mindmap,
                sampleQuestions,
                files,
                filesTruncated,
                filesPageSize);
    }

    /** 返回当前调用者是否拥有管理权限。 */
    public boolean canManage() {
        return effectivePermission == ResourcePermission.MANAGE;
    }

    /** 统计字段（与参考实现 _normalize_database_stats 的键一致）。 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("file_count", fileCount);
        stats.put("folder_count", folderCount);
        stats.put("row_count", rowCount);
        stats.put("total_size", totalSize);
        stats.put("chunk_count", chunkCount);
        stats.put("token_count", tokenCount);
        stats.put("pending_parse_count", pendingParseCount);
        stats.put("pending_index_count", pendingIndexCount);
        stats.put("processing_count", processingCount);
        return stats;
    }
}
