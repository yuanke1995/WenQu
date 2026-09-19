package com.wisesoft.wenqu.common;

import com.wisesoft.wenqu.knowledge.KnowledgeBaseDetail;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseSummary;
import com.wisesoft.wenqu.knowledge.KnowledgeSecurity;
import com.wisesoft.wenqu.permissions.ResourcePermission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库读取模型 → HTTP 响应的序列化，逐函数对齐参考实现 {@code server/utils/knowledge_response.py}。
 *
 * <p>字段与顺序逐字对齐：{@code kb_id / name / description / kb_type / embedding_model_spec /
 * llm_model_spec / query_params / metadata / created_by / created_at / status / stats / row_count /
 * share_config / additional_params}，附加权限字段 {@code effective_permission / can_manage}；
 * 详情额外补 {@code mindmap / sample_questions / files / files_truncated / files_page_size}。
 *
 * <p>平台差异（必要替换）：参考实现的 {@code isinstance(database, KnowledgeBaseDetail)} 分支
 * → Java 侧按 {@link KnowledgeBaseDetail} / {@link KnowledgeBaseSummary} 两个重载承载（两者是并列
 * record，Java 无继承关系）。
 */
public final class KnowledgeResponseSerializer {

    private KnowledgeResponseSerializer() {
    }

    /** 兼容既有接口的嵌套统计字段（对应 _knowledge_base_stats）。 */
    private static Map<String, Object> stats(KnowledgeBaseSummary database) {
        return database.stats();
    }

    /** 转换单个知识库摘要。 */
    public static Map<String, Object> serializeKnowledgeBase(KnowledgeBaseSummary database) {
        return serializeKnowledgeBase(database, null, false, false);
    }

    /** 转换单个知识库摘要（可指定权限与脱敏）。 */
    public static Map<String, Object> serializeKnowledgeBase(
            KnowledgeBaseSummary database,
            ResourcePermission permission,
            boolean redactSecrets,
            boolean rowCountFallback) {
        Map<String, Object> response = render(database, permission, redactSecrets, rowCountFallback);
        return response;
    }

    /** 转换单个知识库详情（详情分支额外补 mindmap / sample_questions / files 等字段）。 */
    public static Map<String, Object> serializeKnowledgeBase(
            KnowledgeBaseDetail database,
            ResourcePermission permission,
            boolean redactSecrets,
            boolean rowCountFallback) {
        Map<String, Object> response = render(summaryOf(database), permission, redactSecrets, rowCountFallback);
        response.put("mindmap", database.mindmap());
        response.put("sample_questions", new ArrayList<>(database.sampleQuestions() == null ? List.of() : database.sampleQuestions()));
        if (database.files() != null) {
            response.put("files", database.files());
            response.put("files_truncated", database.filesTruncated());
            response.put("files_page_size", database.filesPageSize());
        }
        return response;
    }

    /** 转换知识库摘要列表为既有列表接口响应（对应 serialize_knowledge_base_list，row_count 走回退）。 */
    public static Map<String, Object> serializeKnowledgeBaseList(List<? extends KnowledgeBaseSummary> databases) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (KnowledgeBaseSummary database : databases) {
            items.add(serializeKnowledgeBase(database, null, false, true));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("databases", items);
        return response;
    }

    /** 详情 → 摘要视图（Java 两 record 并列，无继承；对应参考实现的 isinstance 分支取共同字段）。 */
    private static KnowledgeBaseSummary summaryOf(KnowledgeBaseDetail detail) {
        return new KnowledgeBaseSummary(
                detail.kbId(), detail.name(), detail.description(), detail.kbType(),
                detail.embeddingModelSpec(), detail.llmModelSpec(), detail.queryParams(),
                detail.additionalParams(), detail.shareConfig(), detail.createdBy(), detail.createdAt(),
                detail.fileCount(), detail.folderCount(), detail.rowCount(), detail.totalSize(),
                detail.chunkCount(), detail.tokenCount(), detail.pendingParseCount(),
                detail.pendingIndexCount(), detail.processingCount(), detail.effectivePermission());
    }

    /** 摘要/详情共用的字段装配（对应 serialize_knowledge_base 主体）。 */
    private static Map<String, Object> render(
            KnowledgeBaseSummary database,
            ResourcePermission permission,
            boolean redactSecrets,
            boolean rowCountFallback) {
        Map<String, Object> stats = stats(database);
        Map<String, Object> additionalParams = new LinkedHashMap<>();
        if (database.additionalParams() != null) {
            additionalParams.putAll(database.additionalParams());
        }
        if (redactSecrets) {
            additionalParams = KnowledgeSecurity.redactSensitiveParams(additionalParams);
        }
        additionalParams.put("stats", stats);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kb_id", database.kbId());
        response.put("name", database.name());
        response.put("description", database.description());
        response.put("kb_type", database.kbType());
        response.put("embedding_model_spec", database.embeddingModelSpec());
        response.put("llm_model_spec", database.llmModelSpec());
        response.put("query_params", database.queryParams() == null ? Map.of() : new LinkedHashMap<>(database.queryParams()));
        response.put("metadata", additionalParams);
        response.put("created_by", database.createdBy());
        response.put("created_at", database.createdAt() == null ? null : DateTimeUtils.utcIsoformat(database.createdAt()));
        response.put("status", "已连接");
        response.put("stats", stats);
        response.put("row_count", rowCountFallback
                ? (database.rowCount() != 0 ? database.rowCount() : database.fileCount())
                : database.rowCount());
        response.put("share_config", database.shareConfig());
        response.put("additional_params", additionalParams);

        ResourcePermission effectivePermission = permission != null ? permission : database.effectivePermission();
        if (effectivePermission != null) {
            response.put("effective_permission", effectivePermission.value());
            response.put("can_manage", effectivePermission == ResourcePermission.MANAGE);
        }
        return response;
    }
}
