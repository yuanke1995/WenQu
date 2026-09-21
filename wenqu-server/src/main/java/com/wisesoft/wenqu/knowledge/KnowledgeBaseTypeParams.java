package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.models.ModelInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库类型的「创建参数 / 查询参数 / 附加参数校验」配置。
 *
 * <p>由参考实现 base.py 的 {@code get_create_params_config} / {@code validate_additional_params} /
 * {@code normalize_additional_params} / {@code get_query_params_config} /
 * {@code get_default_query_params}，以及 implementations/{milvus,dify,notion}.py 的类型特定覆写翻译。
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>Python 用 {@code @dataclass field(metadata=...)} 声明 {@code MilvusRetrievalConfig}，
 *       本工程无 dataclass，改为显式 List/Map 常量——字段名、label、type、min/max/step、
 *       depend_on、默认值、选项文案逐字对齐 {@code _retrieval_config_options()} 的产出。
 *   <li>{@code options_provider="rerank_models"}（{@code reranker_model} 的选项来源）在参考实现里由
 *       {@code model_cache.get_all_specs("rerank")} 动态供给；本工程因该工具类为静态方法、
 *       无依赖注入，改由调用方把当前重排模型清单传入
 *       {@link #queryParamsConfig(String, java.util.List)} 后填充，产出结构不变。
 *   <li>{@code Get DEFAULT_QUERY_PARAMS} 只提取带 {@code default} 的项，与参考实现口径一致。
 * </ul>
 */
public final class KnowledgeBaseTypeParams {

    private KnowledgeBaseTypeParams() {}

    /**
     * MILVUS：查询参数配置字段（对应 MilvusRetrievalConfig + _retrieval_config_options）。
     *
     * <p>每次调用重建列表（与参考实现逐字段遍历 metadata 产出选项的语义一致，且不共享可变实例——
     * 调用方会就地把当前保存值写回 {@code default}）。
     *
     * <p>{@code options_provider="rerank_models"} 分支：{@code reranker_model} 的选项由
     * {@code rerankModels} 动态填充，与参考实现的
     * {@code [{"label": info.display_name, "value": info.spec} for info in model_cache.get_all_specs("rerank")]}
     * 逐项对应。
     */
    private static List<Map<String, Object>> milvusQueryOptions(List<ModelInfo> rerankModels) {
        return List.of(
                option("search_mode", "检索模式", "select", "vector",
                        Map.of("options", List.of(
                                selectItem("vector", "向量检索", "仅使用向量相似度检索"),
                                selectItem("keyword", "BM25 全文检索", "仅使用 Milvus BM25 检索"),
                                selectItem("hybrid", "混合检索", "Milvus 向量检索与 BM25 融合检索"))),
                        Map.of("description", "选择检索模式")),
                option("final_top_k", "最终返回 Chunk 数", "number", 10,
                        Map.of("min", 1, "max", 100),
                        Map.of("description", "重排序后返回给前端的文档数量")),
                option("similarity_threshold", "相似度阈值（0-1）", "number", 0.0d,
                        Map.of("min", 0.0d, "max", 1.0d, "step", 0.1d),
                        Map.of("description", "过滤相似度低于此值的结果")),
                option("bm25_top_k", "BM25 召回数量", "number", 50,
                        Map.of("min", 1, "max", 200),
                        Map.of("description", "BM25 全文检索和混合检索中的 BM25 候选数量")),
                option("vector_weight", "向量检索权重", "number", 0.7d,
                        Map.of("min", 0.0d, "max", 1.0d, "step", 0.1d),
                        Map.of("description", "混合检索中向量召回结果的融合权重")),
                option("bm25_weight", "BM25 权重", "number", 0.3d,
                        Map.of("min", 0.0d, "max", 1.0d, "step", 0.1d),
                        Map.of("description", "混合检索中 BM25 召回结果的融合权重")),
                option("bm25_drop_ratio_search", "BM25 稀疏项丢弃比例", "number", 0.0d,
                        Map.of("min", 0.0d, "max", 1.0d, "step", 0.1d),
                        Map.of("description", "BM25 检索时丢弃低分稀疏项的比例，数值越大检索越快但可能降低召回")),
                option("include_distances", "显示相似度", "boolean", Boolean.TRUE,
                        Map.of(),
                        Map.of("description", "在结果中显示相似度分数")),
                option("use_graph_retrieval", "启用图检索", "boolean", Boolean.FALSE,
                        Map.of(),
                        Map.of("description", "是否启用实体和三元组扩散检索")),
                option("graph_entity_top_k", "图实体召回数量", "number", 10,
                        Map.of("min", 1, "max", 100, "depend_on", List.of("use_graph_retrieval", true)),
                        Map.of("description", "通过 Query 召回的实体数量")),
                option("graph_triple_top_k", "图三元组召回数量", "number", 10,
                        Map.of("min", 1, "max", 100, "depend_on", List.of("use_graph_retrieval", true)),
                        Map.of("description", "通过 Query 召回的三元组数量")),
                option("graph_max_nodes", "图检索最大节点数", "number", 10000,
                        Map.of("min", 100, "max", 50000, "depend_on", List.of("use_graph_retrieval", true)),
                        Map.of("description", "2-hop 扩散子图最多读取的节点数量")),
                option("graph_top_k", "图召回 Chunk 数", "number", 20,
                        Map.of("min", 1, "max", 200, "depend_on", List.of("use_graph_retrieval", true)),
                        Map.of("description", "PPR 后从图谱路径召回的 Chunk 数量")),
                option("graph_weight", "图检索融合权重", "number", 1.0d,
                        Map.of("min", 0.0d, "max", 5.0d, "step", 0.1d, "depend_on", List.of("use_graph_retrieval", true)),
                        Map.of("description", "排名融合时图检索结果的权重")),
                option("ppr_damping", "PPR 阻尼系数", "number", 0.85d,
                        Map.of("min", 0.1d, "max", 0.99d, "step", 0.01d, "depend_on", List.of("use_graph_retrieval", true)),
                        Map.of("description", "Personalized PageRank 的阻尼系数")),
                option("use_reranker", "启用重排序", "boolean", Boolean.FALSE,
                        Map.of(),
                        Map.of("description", "是否使用精排模型对检索结果进行重排序")),
                option("reranker_model", "重排序模型", "select", "",
                        rerankerModelOption(rerankModels),
                        Map.of("description", "选择用于本次查询的重排序模型")),
                option("recall_top_k", "召回数量", "number", 50,
                        Map.of("min", 10, "max", 200, "depend_on", List.of("use_reranker", true)),
                        Map.of("description", "向量检索或混合检索保留的候选数量（启用重排序时有效）")));
    }

    /**
     * {@code reranker_model} 选项的额外字段：{@code depend_on} 与动态填充的 {@code options}。
     *
     * <p>用显式表承载而非 {@code Map.of(...)} 内联：两个值的静态类型不同
     * （{@code List<Object>} 与 {@code List<Map<String, Object>>}），内联会依赖目标类型推断，
     * 显式表更稳且与其它选项的写法区分开。
     */
    private static Map<String, Object> rerankerModelOption(List<ModelInfo> rerankModels) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("depend_on", List.of("use_reranker", true));
        extra.put("options", rerankModelOptions(rerankModels));
        return extra;
    }

    /**
     * 重排模型下拉项（对应参考实现 {@code options_provider="rerank_models"} 的产出结构：
     * {@code {"label": info.display_name, "value": info.spec}}）。
     */
    private static List<Map<String, Object>> rerankModelOptions(List<ModelInfo> rerankModels) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (rerankModels == null) {
            return items;
        }
        for (ModelInfo info : rerankModels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", info.displayName());
            item.put("value", info.spec());
            items.add(item);
        }
        return items;
    }

    /** DIFY：创建参数配置（对应 DifyKB.get_create_params_config）。 */
    private static final List<Map<String, Object>> DIFY_CREATE_OPTIONS = List.of(
            createOption("dify_api_url", "Dify API URL", "text", true,
                    "例如: https://api.dify.ai/v1", null, "Dify API 地址，必须以 /v1 结尾"),
            createOption("dify_token", "Dify Token", "password", true,
                    "请输入 Dify API Token", null, null),
            createOption("dify_dataset_id", "Dataset ID", "text", true,
                    "请输入 Dify dataset_id", null, null));

    /** DIFY：查询参数配置（对应 DifyKB.get_query_params_config）。 */
    private static final List<Map<String, Object>> DIFY_QUERY_OPTIONS = List.of(
            queryOptionWithItems("search_mode", "检索模式", "select", "vector",
                    List.of(selectItem("vector", "向量检索", "映射为 semantic_search"),
                            selectItem("keyword", "关键词检索", "映射为 keyword_search"),
                            selectItem("hybrid", "混合检索", "映射为 hybrid_search")),
                    Map.of("description", "Dify 检索方法映射")),
            queryOption("final_top_k", "最终返回 Chunk 数", "number", 10,
                    Map.of("min", 1, "max", 100), "映射为 Dify retrieval_model.top_k"),
            queryOption("score_threshold_enabled", "启用分数阈值", "boolean", Boolean.FALSE,
                    Map.of(), "映射为 Dify retrieval_model.score_threshold_enabled"),
            queryOption("similarity_threshold", "分数阈值（0-1）", "number", 0.0d,
                    Map.of("min", 0.0d, "max", 1.0d, "step", 0.1d),
                    "映射为 Dify retrieval_model.score_threshold"));

    /** NOTION：默认 API 版本（对应 NOTION_DEFAULT_VERSION）。 */
    public static final String NOTION_DEFAULT_VERSION = "2026-03-11";

    /** NOTION：默认全文读取页数（对应 NOTION_DEFAULT_MAX_HYDRATE_PAGES）。 */
    public static final int NOTION_DEFAULT_MAX_HYDRATE_PAGES = 20;

    /** NOTION：创建参数配置（对应 NotionKB.get_create_params_config）。 */
    private static final List<Map<String, Object>> NOTION_CREATE_OPTIONS = List.of(
            createOption("notion_token", "Notion Token", "password", false,
                    "留空则使用 NOTION_TOKEN / NOTION_API_KEY", null,
                    "Notion integration token，需要 read content 权限"),
            createOption("notion_data_source_id", "Data Source ID", "text", true,
                    "请输入 Notion data_source_id", null, null),
            createOption("notion_version", "Notion API Version", "text", false,
                    NOTION_DEFAULT_VERSION, NOTION_DEFAULT_VERSION, null));

    /** NOTION：查询参数配置（对应 NotionKB.get_query_params_config）。 */
    private static final List<Map<String, Object>> NOTION_QUERY_OPTIONS = List.of(
            queryOptionWithItems("search_mode", "检索模式", "select", "hybrid",
                    List.of(selectItem("hybrid", "混合检索", "先 Notion 搜索，不足时扫描 Data Source"),
                            selectItem("notion_search", "Notion 搜索", "使用 Notion search API"),
                            selectItem("data_source_scan", "Data Source 扫描", "扫描 Data Source 并本地匹配")),
                    Map.of("description", "选择 Notion 检索方式")),
            queryOption("final_top_k", "最终返回数量", "number", 10,
                    Map.of("min", 1, "max", 50), "返回给前端和智能体的页面数量"),
            queryOption("max_scan_pages", "最大扫描页面数", "number", 100,
                    Map.of("min", 10, "max", 1000), "Data Source 扫描模式最多读取的页面数量"),
            queryOption("max_hydrate_pages", "最大读取全文页数", "number", NOTION_DEFAULT_MAX_HYDRATE_PAGES,
                    Map.of("min", 1, "max", 100), "候选排序后最多读取 block 全文的页面数量"),
            queryOption("snippet_window_lines", "片段窗口行数", "number", 12,
                    Map.of("min", 3, "max", 40), "搜索结果片段包含的上下文行数"));

    /** Dify 必填连接参数（对应 DIFY_REQUIRED_PARAMS）。 */
    private static final List<String> DIFY_REQUIRED_PARAMS =
            List.of("dify_api_url", "dify_token", "dify_dataset_id");

    // ==================== 创建参数配置 ====================

    /** 创建知识库时的类型特定参数配置（对应 get_create_params_config，默认 {"options": []}）。 */
    public static Map<String, Object> createParamsConfig(String kbType) {
        return switch (normalizeKbType(kbType)) {
            case "dify" -> options(DIFY_CREATE_OPTIONS);
            case "notion" -> options(NOTION_CREATE_OPTIONS);
            default -> options(new ArrayList<>());
        };
    }

    // ==================== 查询参数配置 ====================

    /** 查询参数配置（对应 get_query_params_config）；不含动态选项，重排模型清单为空。 */
    public static Map<String, Object> queryParamsConfig(String kbType) {
        return queryParamsConfig(kbType, List.of());
    }

    /**
     * 查询参数配置（对应 get_query_params_config）。
     *
     * <p>{@code rerankModels} 供 {@code options_provider="rerank_models"} 动态填充选项；
     * 调用方应传入当前模型缓存中的重排模型清单（见 {@code ModelProviderCache#getAllSpecs(String)}）。
     */
    public static Map<String, Object> queryParamsConfig(String kbType, List<ModelInfo> rerankModels) {
        String type = normalizeKbType(kbType);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("options", switch (type) {
            case "dify" -> DIFY_QUERY_OPTIONS;
            case "notion" -> NOTION_QUERY_OPTIONS;
            default -> milvusQueryOptions(rerankModels);
        });
        return config;
    }

    /** 从查询参数配置提取默认值（对应 get_default_query_params，返回 {"options": {...}}）。 */
    public static Map<String, Object> defaultQueryParams(String kbType) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        Object rawOptions = queryParamsConfig(kbType).get("options");
        if (rawOptions instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> option && option.containsKey("default")) {
                    defaults.put(String.valueOf(option.get("key")), option.get("default"));
                }
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("options", defaults);
        return output;
    }

    // ==================== 附加参数校验 / 规范化 ====================

    /** 校验并规范化类型特定配置（对应 validate_additional_params，基类为恒等）。 */
    public static Map<String, Object> validateAdditionalParams(String kbType, Map<String, Object> additionalParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (additionalParams != null) {
            params.putAll(additionalParams);
        }
        switch (normalizeKbType(kbType)) {
            case "dify" -> validateDify(params);
            case "notion" -> validateNotion(params);
            default -> { }
        }
        return params;
    }

    /**
     * 规范化 additional_params（对应 normalize_additional_params）。
     *
     * <p>仅文档型知识库（{@code apply_chunk_defaults=true}）补充分块默认值；
     * 只读连接器（dify/notion）不补。
     */
    public static Map<String, Object> normalizeAdditionalParams(String kbType, Map<String, Object> additionalParams) {
        Map<String, Object> params = validateAdditionalParams(kbType, additionalParams);
        if (applyChunkDefaults(kbType)) {
            return com.wisesoft.wenqu.service.ChunkPresets.ensureChunkDefaultsInAdditionalParams(params);
        }
        return params;
    }

    /** 是否对该类型补充分块默认值（对应类属性 apply_chunk_defaults）。 */
    public static boolean applyChunkDefaults(String kbType) {
        return "milvus".equals(normalizeKbType(kbType));
    }

    // ==================== 内部实现 ====================

    /** 类型名归一：空值回落 milvus（与参考实现「milvus 为默认实现」口径一致）。 */
    private static String normalizeKbType(String kbType) {
        return kbType == null || kbType.isBlank() ? "milvus" : kbType;
    }

    private static void validateDify(Map<String, Object> params) {
        List<String> missing = new ArrayList<>();
        for (String field : DIFY_REQUIRED_PARAMS) {
            if (String.valueOf(params.get(field) == null ? "" : params.get(field)).isBlank()) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Dify 参数缺失: " + String.join(", ", missing));
        }
        for (String field : DIFY_REQUIRED_PARAMS) {
            params.put(field, String.valueOf(params.get(field)).strip());
        }
        String apiUrl = String.valueOf(params.get("dify_api_url"));
        if (!apiUrl.endsWith("/v1")) {
            throw new IllegalArgumentException("Dify api_url 必须以 /v1 结尾");
        }
    }

    private static void validateNotion(Map<String, Object> params) {
        String token = str(params.get("notion_token"));
        String dataSourceId = str(params.get("notion_data_source_id"));
        String notionVersion = str(params.get("notion_version"));
        if (notionVersion.isEmpty()) {
            notionVersion = NOTION_DEFAULT_VERSION;
        }
        if (dataSourceId.isEmpty()) {
            throw new IllegalArgumentException("Notion 参数缺失: notion_data_source_id");
        }
        String envToken = System.getenv("NOTION_TOKEN");
        String envApiKey = System.getenv("NOTION_API_KEY");
        boolean envPresent = (envToken != null && !envToken.isBlank()) || (envApiKey != null && !envApiKey.isBlank());
        if (token.isEmpty() && !envPresent) {
            throw new IllegalArgumentException(
                    "Notion 参数缺失: notion_token 或环境变量 NOTION_TOKEN/NOTION_API_KEY");
        }
        params.clear();
        params.put("notion_token", token);
        params.put("notion_data_source_id", dataSourceId);
        params.put("notion_version", notionVersion);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static Map<String, Object> options(List<Map<String, Object>> items) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("options", items);
        return config;
    }

    private static Map<String, Object> selectItem(String value, String label, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        item.put("description", description);
        return item;
    }

    private static Map<String, Object> createOption(String key, String label, String type, boolean required,
                                                    String placeholder, String defaultValue, String description) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("key", key);
        option.put("label", label);
        option.put("type", type);
        option.put("required", required);
        option.put("placeholder", placeholder);
        if (defaultValue != null) {
            option.put("default", defaultValue);
        }
        if (description != null) {
            option.put("description", description);
        }
        return option;
    }

    private static Map<String, Object> queryOption(String key, String label, String type, Object defaultValue,
                                                   Map<String, Object> extra, String description) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("key", key);
        option.put("label", label);
        option.put("type", type);
        option.put("default", defaultValue);
        option.putAll(extra);
        if (description != null) {
            option.put("description", description);
        }
        return option;
    }

    private static Map<String, Object> queryOptionWithItems(String key, String label, String type, Object defaultValue,
                                                            List<Map<String, Object>> items,
                                                            Map<String, Object> extra) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("key", key);
        option.put("label", label);
        option.put("type", type);
        option.put("default", defaultValue);
        option.put("options", items);
        option.putAll(extra);
        return option;
    }

    private static Map<String, Object> option(String key, String label, String type, Object defaultValue,
                                              Map<String, Object> extra, Map<String, Object> description) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("key", key);
        option.put("default", defaultValue);
        option.put("label", label);
        option.put("type", type);
        option.putAll(extra);
        option.putAll(description);
        return option;
    }
}
