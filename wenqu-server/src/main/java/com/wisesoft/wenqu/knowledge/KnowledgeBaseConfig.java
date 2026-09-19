package com.wisesoft.wenqu.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库类型实现执行一次操作所需的最小配置。
 *
 * <p>由参考实现的 knowledge/read_models.py 中 KnowledgeBaseConfig 逐字段翻译。
 * {@code queryOptions} 对应其 {@code query_options} 属性：取持久化查询参数中的 options，
 * 非对象时回落为空 Map。
 */
public record KnowledgeBaseConfig(
        String kbId,
        String kbType,
        String embeddingModelSpec,
        Map<String, Object> queryParams,
        Map<String, Object> additionalParams) {

    /** 返回持久化查询参数中的 options。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryOptions() {
        Object options = queryParams == null ? null : queryParams.get("options");
        return options instanceof Map ? (Map<String, Object>) options : new LinkedHashMap<>();
    }
}
