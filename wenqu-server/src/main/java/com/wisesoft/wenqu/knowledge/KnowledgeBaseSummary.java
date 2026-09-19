package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.permissions.ResourcePermission;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库列表、权限过滤与资源选择共用的内部摘要。
 *
 * <p>由参考实现的 knowledge/read_models.py 中 KnowledgeBaseSummary 逐字段翻译。
 * 字段名与统计键保持 snake_case 语义一致（Java 侧以 record 组件名承载）。
 */
public record KnowledgeBaseSummary(
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
        ResourcePermission effectivePermission) {

    /** 返回当前调用者是否拥有管理权限。 */
    public boolean canManage() {
        return effectivePermission == ResourcePermission.MANAGE;
    }

    /** 统计字段（与参考实现 _normalize_database_stats 的键一致）。 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
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
