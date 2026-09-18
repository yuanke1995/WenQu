package com.wisesoft.wenqu.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块预设与参数解析。
 * <p>
 * 本类逐字对应参考实现的 {@code knowledge/chunking/ragflow_like/presets.py}：
 * 常量、预设清单（id/label/description）、归一化与别名规则、深合并语义、解析顺序均保持一致；
 * 仅语言由 Python 译为 Java。**不得在此增删预设或自行发明默认参数**——
 * 参考实现的 {@code get_default_chunk_parser_config()} 返回空字典，即预设本身不携带参数。
 *
 * <h3>职责边界（与参考实现一致）</h3>
 * <ul>
 *   <li>{@code chunk_preset_id}：选择切分方式（经 {@link #mapToInternalParserId} 映射到内部实现），
 *       由 请求 → 文件 → 知识库 的顺序取第一个非空值</li>
 *   <li>{@code chunk_parser_config}：具体参数，按 <b>知识库 → 文件 → 请求</b> 深合并（后者覆盖前者），
 *       基准为空字典——预设不提供默认参数</li>
 *   <li>{@code chunk_engine_version}：引擎版本，随解析结果落快照，用于判断旧块是否需要重切</li>
 * </ul>
 *
 * <h3>当前实现的兑现范围（差异如实标注，不隐瞒）</h3>
 * 参考实现的预设各自对应一种独立切分器（问答结构抽取、语义聚类、严格分隔符等）。
 * 本系统当前只有一种切分实现：**结构感知切分 + 长度兜底**。
 * 因此 {@link #mapToInternalParserId} 对暂未提供独立实现的预设返回通用标识，
 * 其行为等同通用切分，**参数仍可按层配置**。此处保留全部预设 id 与文案（与参考实现一致），
 * 未实现的算法不删除、也不谎称已支持。
 */
public final class ChunkPresets {

    /** 默认预设（对应参考实现 DEFAULT_CHUNK_PRESET_ID） */
    public static final String DEFAULT_CHUNK_PRESET_ID = "general";

    /** 通用预设对应的内部实现标识（对应参考实现 GENERAL_INTERNAL_PARSER_ID） */
    public static final String GENERAL_INTERNAL_PARSER_ID = "naive";

    /**
     * 引擎版本。语义同参考实现的 CHUNK_ENGINE_VERSION：切分算法或参数语义不兼容变更时递增，
     * 用于判断已解析的块是否需要用新引擎重新解析。
     * <p>此处取本系统自己的标识而非照搬参考实现的名字——版本号标识的是**本系统的切分实现**，
     * 写成外部项目名会让版本对比失去意义。
     */
    public static final String CHUNK_ENGINE_VERSION = "wenqu_chunker_v1";

    /** 预设清单（id → label/description），与参考实现逐字一致 */
    private static final Map<String, String[]> CHUNK_PRESETS = new LinkedHashMap<>();

    static {
        CHUNK_PRESETS.put("general", new String[]{"General", "通用分块：按分隔符和长度切分，适合大多数普通文档。"});
        CHUNK_PRESETS.put("qa", new String[]{"QA", "问答分块：优先抽取问题-回答结构，适合 FAQ、题库、问答手册。"});
        CHUNK_PRESETS.put("book", new String[]{"Book", "书籍分块：强化章节标题识别并做层级合并，适合教材、手册、长章节文档。"});
        CHUNK_PRESETS.put("laws", new String[]{"Laws", "法规分块：按法条层级组织与合并，适合法律法规、制度规范类文本。"});
        CHUNK_PRESETS.put("semantic", new String[]{"Semantic", "语义分块：利用嵌入和聚类算法进行语义切分，并自动增强标题上下文。"});
        CHUNK_PRESETS.put("separator", new String[]{"Separator", "严格分隔：命中分隔符即切分，仅超长片段内部继续按长度切分。"});
    }

    private ChunkPresets() {
    }

    /** deep_merge 的等价实现：两边都是字典时递归合并，否则后者覆盖前者 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        if (override == null) return result;
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object value = e.getValue();
            Object existing = result.get(e.getKey());
            if (value instanceof Map && existing instanceof Map) {
                result.put(e.getKey(), deepMerge((Map<String, Object>) existing, (Map<String, Object>) value));
            } else {
                result.put(e.getKey(), value);
            }
        }
        return result;
    }

    /** 归一化预设 id：空 → general；naive 别名 → general；未知 → general */
    public static String normalizeChunkPresetId(String value) {
        if (value == null || value.isBlank()) return DEFAULT_CHUNK_PRESET_ID;
        String normalized = value.trim().toLowerCase();
        if (GENERAL_INTERNAL_PARSER_ID.equals(normalized)) return DEFAULT_CHUNK_PRESET_ID;
        if (CHUNK_PRESETS.containsKey(normalized)) return normalized;
        // 未知预设回落通用（调用方如需要可自行记日志）
        return DEFAULT_CHUNK_PRESET_ID;
    }

    /**
     * 预设 → 内部切分实现标识。
     * <p>本系统仅提供通用切分实现，故除通用外的预设当前返回通用标识（行为等同通用切分）；
     * 参数差异仍通过 chunk_parser_config 生效。
     */
    public static String mapToInternalParserId(String presetId) {
        String normalized = normalizeChunkPresetId(presetId);
        return GENERAL_INTERNAL_PARSER_ID;
    }

    /** 预设默认参数：与参考实现一致，**返回空字典**（预设不携带参数） */
    public static Map<String, Object> getDefaultChunkParserConfig(String presetId) {
        normalizeChunkPresetId(presetId);   // 保持与参考实现相同的入参校验行为
        return new LinkedHashMap<>();
    }

    /** 预设下拉项（值 / 名称 / 说明），供设置界面渲染 */
    public static List<Map<String, String>> getChunkPresetOptions() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : CHUNK_PRESETS.entrySet()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("value", e.getKey());
            m.put("label", e.getValue()[0]);
            m.put("description", e.getValue()[1]);
            out.add(m);
        }
        return out;
    }

    public static boolean isValidPreset(String id) {
        return id != null && CHUNK_PRESETS.containsKey(id.trim().toLowerCase());
    }

    /**
     * 解析生效的分块参数（对应参考实现 resolve_chunk_processing_params）。
     *
     * @param kbAdditionalParams   知识库级：{@code {"chunk_preset_id":..,"chunk_parser_config":{..}}}
     * @param fileProcessingParams 文件级：同结构
     * @param requestParams        请求级：同结构
     * @return {chunk_preset_id, chunk_parser_config, chunk_engine_version}
     */
    public static Map<String, Object> resolveChunkProcessingParams(Map<String, Object> kbAdditionalParams,
                                                                   Map<String, Object> fileProcessingParams,
                                                                   Map<String, Object> requestParams) {
        Map<String, Object> request = requestParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(requestParams);
        Map<String, Object> file = fileProcessingParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fileProcessingParams);
        Map<String, Object> kb = ensureChunkDefaultsInAdditionalParams(kbAdditionalParams);

        // 1) 预设选择：请求 > 文件 > 知识库
        String presetId = normalizeChunkPresetId(firstNonBlank(
                str(request.get("chunk_preset_id")),
                str(file.get("chunk_preset_id")),
                str(kb.get("chunk_preset_id"))));

        // 2) 基准 = 预设默认参数（参考实现为空字典）
        Map<String, Object> parserConfig = getDefaultChunkParserConfig(presetId);

        // 3) 逐层深合并：知识库 → 文件 → 请求
        Map<String, Object> kbConfig = mapOf(kb.get("chunk_parser_config"));
        if (kbConfig != null) parserConfig = deepMerge(parserConfig, kbConfig);
        Map<String, Object> fileConfig = mapOf(file.get("chunk_parser_config"));
        if (fileConfig != null) parserConfig = deepMerge(parserConfig, fileConfig);
        Map<String, Object> reqConfig = mapOf(request.get("chunk_parser_config"));
        if (reqConfig != null) parserConfig = deepMerge(parserConfig, reqConfig);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunk_preset_id", presetId);
        out.put("chunk_parser_config", parserConfig);
        out.put("chunk_engine_version", CHUNK_ENGINE_VERSION);
        return out;
    }

    /** 对应参考实现 ensure_chunk_defaults_in_additional_params：补全 preset 键并校正 config 类型 */
    public static Map<String, Object> ensureChunkDefaultsInAdditionalParams(Map<String, Object> additionalParams) {
        Map<String, Object> params = additionalParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(additionalParams);
        params.put("chunk_preset_id", normalizeChunkPresetId(str(params.get("chunk_preset_id"))));
        Object cfg = params.get("chunk_parser_config");
        if (cfg != null && !(cfg instanceof Map)) {
            params.put("chunk_parser_config", new LinkedHashMap<>());
        }
        return params;
    }

    // ==================== 内部工具 ====================

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }
}
