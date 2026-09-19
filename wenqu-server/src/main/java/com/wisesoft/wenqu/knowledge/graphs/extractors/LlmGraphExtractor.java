package com.wisesoft.wenqu.knowledge.graphs.extractors;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.common.SpringContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 图谱抽取器：把文本交给大模型抽出实体与关系。
 *
 * <p>由参考实现的 knowledge/graphs/extractors/llm.py 逐项翻译。提示词与选项约束逐字对齐。
 *
 * <h3>能力差异（如实标注）</h3>
 * <ul>
 *   <li>参考用 {@code json_repair.loads} 容错解析；本工程无该库，改用「剥离代码围栏 +
 *       截取最外层 JSON + fastjson2 容错解析 + 常见破损修复（尾逗号、单引号）」的等价近似。</li>
 *   <li>参考 {@code select_model(timeout=60.0)} 可传单次调用超时；本工程 chat 网关由
 *       {@code DynamicOpenAiChatModel} 统一持有 HTTP 客户端，超时不在单次请求上设置。</li>
 * </ul>
 */
public class LlmGraphExtractor extends GraphExtractor {

    /** 默认三元组抽取提示词（与参考实现逐字一致）。 */
    public static final String DEFAULT_TRIPLE_EXTRACTION_PROMPT = """
            请从下面文本中抽取实体和实体关系，返回严格 JSON，不要输出解释。
            JSON 格式：
            {
              "relations": [
                {
                  "source": {"text": "实体文本", "label": "实体类型", "attributes": [{"text": "属性值", "label": "属性名称"}]},
                  "target": {"text": "实体文本", "label": "实体类型", "attributes": [{"text": "属性值", "label": "属性名称"}]},
                  "text": "关系显示文本",
                  "label": "关系类型"
                }
              ]
            }
            """;

    /** Schema 约束片段（与参考实现逐字一致）。 */
    public static final String SCHEMA_INSTRUCTION = """
            抽取 Schema 约束：
            %s
            """;

    public LlmGraphExtractor(Map<String, Object> options) {
        super(options);
    }

    @Override
    public String extractorType() {
        return "llm";
    }

    @Override
    public void validateOptions() {
        if (!hasText(options.get("model_spec"))) {
            throw new IllegalArgumentException("LLM 抽取器需要 model_spec");
        }
        if (hasText(options.get("prompt"))) {
            throw new IllegalArgumentException(
                    "LLM 图谱抽取器不支持自定义完整 Prompt，请使用 schema 配置抽取约束");
        }
        Object concurrencyRaw = options.getOrDefault("concurrency_count", 1);
        int concurrencyCount;
        try {
            concurrencyCount = Integer.parseInt(String.valueOf(concurrencyRaw).strip());
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("LLM 抽取器 concurrency_count 必须是整数");
        }
        if (concurrencyCount < 1 || concurrencyCount > 1000) {
            throw new IllegalArgumentException("LLM 抽取器 concurrency_count 必须在 1 到 1000 之间");
        }
        Object modelParams = options.get("model_params");
        if (modelParams != null && !(modelParams instanceof Map)) {
            throw new IllegalArgumentException("LLM 抽取器 model_params 必须是对象");
        }
    }

    @Override
    public Map<String, Object> extract(String text, Map<String, Object> chunkMetadata) {
        validateOptions();
        String modelSpec = String.valueOf(options.get("model_spec"));
        String prompt = buildPrompt(text);
        String content = callModel(modelSpec, prompt);
        return parseJsonLoose(content);
    }

    /** 组装提示词：默认提示词 + 可选 schema 约束 + 待抽取文本。 */
    public String buildPrompt(String text) {
        String extractionPrompt = DEFAULT_TRIPLE_EXTRACTION_PROMPT;
        Object schemaRaw = options.get("schema");
        String schema = schemaRaw == null ? "" : String.valueOf(schemaRaw).strip();
        if (!schema.isEmpty()) {
            extractionPrompt = extractionPrompt + "\n" + SCHEMA_INSTRUCTION.formatted(schema);
        }
        return extractionPrompt + "\n\n文本：\n" + text;
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private String callModel(String modelSpec, String prompt) {
        ChatClient chatClient = SpringContext.bean(ChatClient.class);
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(modelSpec);
        Object modelParams = options.get("model_params");
        if (modelParams instanceof Map) {
            Map<String, Object> params = (Map<String, Object>) modelParams;
            Object temperature = params.get("temperature");
            if (temperature instanceof Number) {
                optionsBuilder.temperature(((Number) temperature).doubleValue());
            }
            Object maxTokens = params.get("max_tokens");
            if (maxTokens instanceof Number) {
                optionsBuilder.maxTokens(((Number) maxTokens).intValue());
            }
        }
        String content = chatClient.prompt()
                .user(prompt)
                .options(optionsBuilder.build())
                .call()
                .content();
        return content == null ? "" : content;
    }

    /**
     * 容错解析模型返回的 JSON：剥离 ``` 代码围栏 → 截取最外层对象/数组 →
     * 解析失败时修复常见破损（尾逗号、单引号）后重试。
     */
    static Map<String, Object> parseJsonLoose(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.strip();
        }

        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = objectStart < 0 ? arrayStart
                : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
        if (start > 0) {
            text = text.substring(start);
        }
        char open = text.isEmpty() ? '{' : text.charAt(0);
        char close = open == '[' ? ']' : '}';
        int end = text.lastIndexOf(close);
        if (end >= 0) {
            text = text.substring(0, end + 1);
        }

        try {
            Object parsed = JSON.parse(text);
            return asMap(parsed);
        } catch (RuntimeException firstFailure) {
            String repaired = text
                    .replaceAll(",\\s*([}\\]])", "$1")
                    .replace('\'', '"');
            Object parsed = JSON.parse(repaired);
            return asMap(parsed);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed) {
        if (parsed instanceof JSONObject jsonObject) {
            return new LinkedHashMap<>(jsonObject);
        }
        if (parsed instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) parsed);
        }
        throw new IllegalArgumentException("extraction_result 必须是对象");
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).strip().isEmpty();
    }
}
