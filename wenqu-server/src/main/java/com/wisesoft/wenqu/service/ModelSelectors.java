package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.AppVersion;
import com.wisesoft.wenqu.common.StringUtils;
import com.wisesoft.wenqu.models.ModelInfo;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * 按 spec 构造运行时模型客户端（参考实现 models/chat.py / models/embed.py / models/rerank.py
 * 的"spec → 客户端 + 连接测试"面）。
 *
 * <p>已移植：
 * <ul>
 *   <li>{@code models/embed.py}：{@code BaseEmbeddingModel.test_connection}、
 *       {@code OtherEmbedding}（payload / aencode / 重试策略 / 响应解析 / 超长输入告警）、
 *       {@code select_embedding_model}、{@code get_embedding_model_info_by_id}
 *   <li>{@code models/rerank.py}：{@code BaseReranker}（_batch_rerank 的校验与索引回填、
 *       acompute_score 的批处理与归一化、test_connection）、{@code OpenAIReranker}、
 *       {@code DashscopeReranker}、{@code get_reranker}、{@code sigmoid}
 *   <li>{@code models/chat.py}：{@code select_model}（spec 校验 + 客户端构造）与
 *       {@code LangChainChatAdapter.call} 的非流式路径
 * </ul>
 *
 * <p>未移植（能力差异，显式标注，非遗漏）：
 * <ul>
 *   <li>embedding 的 {@code encode}/{@code batch_encode}/{@code abatch_encode} 及进度状态
 *       （{@code embed_state}/{@code hashstr}）：本工程知识库摄入链路由 Spring AI 的
 *       {@code DynamicEmbeddingModel} 承载，此处只需连接测试面。
 *   <li>chat 的流式（{@code _stream_response}）、工具调用清洗
 *       （{@code _sanitize_wire_invalid_tool_calls}）、{@code resolve_chat_model_spec}、
 *       {@code reasoning_content}、{@code normalize_tool_call_chunks}：随 chat_service 落地。
 *   <li>chat 的多协议分支（{@code ChatAnthropic} / {@code ChatGoogleGenerativeAI}）：
 *       本工程聊天层为 OpenAI 兼容实现，provider_type 为 anthropic / gemini 时明确报错，
 *       不做静默降级。
 * </ul>
 *
 * <p>平台差异（必要替换，已标注）：
 * <ul>
 *   <li>HTTP 客户端：{@code httpx} / {@code aiohttp} / {@code requests} → JDK {@link HttpClient}。
 *   <li>chat 客户端：LangChain {@code ChatOpenAI} → Spring AI {@code OpenAiChatModel}；
 *       {@code api_key}/{@code base_url}/{@code model} 三要素语义不变，
 *       {@code request_body_overrides} → {@code extraBody}，opencode 会话头 → {@code httpHeaders}。
 *   <li>{@code get_docker_safe_url} → {@link #dockerSafeUrl}（同一替换规则与日志）。
 *   <li>Python 异常文案（httpx / aiohttp 的 {@code str(e)}）按各自的等价格式重建，措辞与原库不同——
 *       这些文案会出现在连接测试的失败原因里。
 * </ul>
 */
@Component
public class ModelSelectors {

    private static final Logger log = LoggerFactory.getLogger(ModelSelectors.class);

    /** embedding 请求超时（参考实现 {@code timeout=60}）。 */
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(60);

    /** rerank 请求超时（参考实现 {@code aiohttp.ClientTimeout(total=30)}）。 */
    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(30);

    /** embedding 限流最大重试次数。 */
    private static final int EMBEDDING_RATE_LIMIT_MAX_RETRIES = 10;

    /** embedding 瞬时错误最大重试次数。 */
    private static final int EMBEDDING_TRANSIENT_MAX_RETRIES = 2;

    /** embedding 重试最大退避（秒）。 */
    private static final double EMBEDDING_RETRY_MAX_DELAY_SECONDS = 10.0;

    /** embedding 可重试状态码。 */
    private static final Set<Integer> EMBEDDING_RETRYABLE_STATUS_CODES =
            Set.of(429, 500, 502, 503, 504);

    private final ModelProviderCache modelProviderCache;

    /** spec 指纹 → 已构造的聊天客户端（见 {@link #buildChatModel(String)}）。 */
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();

    /** 上述缓存的条目上限（供应商数量小，超过即整体清空重建）。 */
    private static final int CHAT_MODEL_CACHE_MAX = 64;

    public ModelSelectors(ModelProviderCache modelProviderCache) {
        this.modelProviderCache = modelProviderCache;
    }

    /** 连接测试结果（对应参考实现的 {@code (bool, str)} 元组）。 */
    public record TestResult(boolean success, String message) {}

    // ==================== 通用工具 ====================

    /**
     * Docker 内访问宿主地址的替换（参考实现 {@code utils.get_docker_safe_url}）。
     *
     * <p>仅在 {@code RUNNING_IN_DOCKER=true} 时把 localhost / 127.0.0.1 换成
     * {@code host.docker.internal}。
     */
    public static String dockerSafeUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return baseUrl;
        }
        if ("true".equals(System.getenv("RUNNING_IN_DOCKER"))) {
            String replaced =
                    baseUrl
                            .replace("http://localhost", "http://host.docker.internal")
                            .replace("http://127.0.0.1", "http://host.docker.internal");
            log.info("Running in docker, using {} as base url", replaced);
            return replaced;
        }
        return baseUrl;
    }

    /** 参考实现 {@code min(float(x), 10.0)} 的等价（解析失败即回退）。 */
    private static Double parseRetryAfter(String retryAfter) {
        if (retryAfter == null) {
            return null;
        }
        try {
            return Math.min(Double.parseDouble(retryAfter), EMBEDDING_RETRY_MAX_DELAY_SECONDS);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Python {@code str(x)}，“None” → 空串语义由调用点决定；此处用于错误文本拼装。 */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    // ==================== models/embed.py ====================

    /**
     * embedding 模型基类（参考实现 {@code BaseEmbeddingModel}）。
     *
     * <p>构造语义逐项对齐：{@code base_url = base_url or url}、{@code model = model or name or model_id}、
     * {@code api_key = os.getenv(api_key, api_key)}（把入参当环境变量名，未设置则用字面量）、
     * {@code batch_size = int(batch_size or 40)}。
     */
    public abstract static class BaseEmbeddingModel {
        protected final String model;
        protected final Integer dimension;
        protected final String baseUrl;
        protected final String apiKey;
        protected final int batchSize;

        protected BaseEmbeddingModel(
                String model, Integer dimension, String baseUrl, String apiKey, Integer batchSize) {
            this.model = model;
            this.dimension = dimension;
            this.baseUrl = dockerSafeUrl(baseUrl);
            this.apiKey = lookupEnvFallback(apiKey);
            this.batchSize = batchSize == null ? 40 : batchSize;
        }

        /** {@code os.getenv(name, name)}：环境变量优先，未设置时用字面量。 */
        private static String lookupEnvFallback(String name) {
            if (name == null) {
                return null;
            }
            String value = System.getenv(name);
            return value != null ? value : name;
        }

        /**
         * 连接测试：做一次真实 embedding；配置了维度时校验实际维度。
         *
         * <p>失败文案与参考实现一致（含 {@code maybe you can check ... end with /embeddings} 提示）。
         */
        public TestResult testConnection() {
            try {
                List<List<Double>> embeddings = aencode(List.of("Hello world"));
                if (dimension != null) {
                    int actualDimension = embeddings.isEmpty() ? 0 : embeddings.get(0).size();
                    if (actualDimension != dimension) {
                        return new TestResult(
                                false,
                                "Embedding 维度不一致：配置 " + dimension + "，实际 " + actualDimension);
                    }
                }
                return new TestResult(true, "连接正常");
            } catch (Exception exc) {
                String errorMsg =
                        describe(exc)
                                + ", maybe you can check the `"
                                + baseUrl
                                + "` end with /embeddings as examples.";
                log.error(errorMsg);
                return new TestResult(false, errorMsg);
            }
        }

        /** 异步批编码（参考实现 {@code aencode} 的单批版本 + 同一重试策略）。 */
        public abstract List<List<Double>> aencode(List<String> message);

        /** 供 {@code test_connection} 使用：单条文本编码。 */
        public List<List<Double>> aencode(String message) {
            return aencode(List.of(message));
        }
    }

    /** 通用 OpenAI 兼容 embedding 客户端（参考实现 {@code OtherEmbedding}）。 */
    public static class OtherEmbedding extends BaseEmbeddingModel {

        public OtherEmbedding(
                String model, Integer dimension, String baseUrl, String apiKey, Integer batchSize) {
            super(model, dimension, baseUrl, apiKey, batchSize);
        }

        private Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            headers.put("Content-Type", "application/json");
            return headers;
        }

        Map<String, Object> buildPayload(List<String> message) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", message);
            return payload;
        }

        @Override
        public List<List<Double>> aencode(List<String> message) {
            Map<String, Object> payload = buildPayload(message);
            logLongInputs(message);
            int retryIndex = 0;
            while (true) {
                HttpResponse<String> response;
                try {
                    response = postRaw(baseUrl, payload, headers(), EMBEDDING_TIMEOUT);
                } catch (Exception exc) {
                    int[] retry = prepareRetry(message, retryIndex, null, exc);
                    if (retry != null) {
                        retryIndex = retry[0];
                        sleep(retry[1]);
                        continue;
                    }
                    throw new IllegalStateException(
                            "Embedding async request failed: "
                                    + describe(exc)
                                    + ", "
                                    + payload
                                    + ", base_url="
                                    + baseUrl,
                            exc);
                }
                if (response.statusCode() >= 400) {
                    int[] retry = prepareRetry(message, retryIndex, response, null);
                    if (retry != null) {
                        retryIndex = retry[0];
                        sleep(retry[1]);
                        continue;
                    }
                    throw new IllegalStateException(
                            "Client error '"
                                    + response.statusCode()
                                    + " "
                                    + reasonPhrase(response.statusCode())
                                    + "' for url '"
                                    + baseUrl
                                    + "'");
                }
                return extractEmbeddings(response.body());
            }
        }

        /** 重试退避：优先取 Retry-After，否则指数退避，上限 10 秒。 */
        static double retryDelaySeconds(int retryIndex, String retryAfter) {
            Double fromHeader = parseRetryAfter(retryAfter);
            if (fromHeader != null) {
                return fromHeader;
            }
            return Math.min(Math.pow(2, retryIndex - 1), EMBEDDING_RETRY_MAX_DELAY_SECONDS);
        }

        /**
         * 判定是否重试并给出下一次的下标与退避。
         *
         * <p>与参考实现一致：429 最多 10 次，可重试状态码或连接错误最多 2 次，其余不重试。
         *
         * @return {@code [nextRetryIndex, delaySeconds]}；不重试时返回 null
         */
        protected int[] prepareRetry(
                List<String> message, int retryIndex, HttpResponse<String> response, Exception error) {
            Integer statusCode = response == null ? null : response.statusCode();
            String responseText = response == null ? "" : nullToEmpty(response.body());
            List<String> messages = message;

            if (statusCode != null && statusCode == 400 && response != null) {
                List<Integer> lengths = new ArrayList<>();
                for (String item : messages) {
                    lengths.add(item == null ? 0 : item.length());
                }
                log.warn(
                        "Embedding request returned 400 Bad Request: model={}, base_url={}, input_count={},"
                                + " input_lengths={}, body={}",
                        model,
                        baseUrl,
                        messages.size(),
                        lengths,
                        truncate(responseText, 2000));
            }

            int maxRetries;
            if (statusCode != null && statusCode == 429) {
                maxRetries = EMBEDDING_RATE_LIMIT_MAX_RETRIES;
            } else if (statusCode == null || EMBEDDING_RETRYABLE_STATUS_CODES.contains(statusCode)) {
                maxRetries = EMBEDDING_TRANSIENT_MAX_RETRIES;
            } else {
                maxRetries = 0;
            }
            if (retryIndex >= maxRetries) {
                return null;
            }

            int nextRetryIndex = retryIndex + 1;
            String retryAfter = response == null ? null : response.headers().firstValue("Retry-After").orElse(null);
            double delay = retryDelaySeconds(nextRetryIndex, retryAfter);
            String reason =
                    statusCode != null
                            ? "status=" + statusCode
                            : "error=" + (error == null ? "Unknown" : error.getClass().getSimpleName());
            log.warn(
                    "Retrying embedding request: {}, model={}, base_url={}, retry={}/{}, delay={}s,"
                            + " input_count={}, body={}",
                    reason,
                    model,
                    baseUrl,
                    nextRetryIndex,
                    maxRetries,
                    String.format("%.1f", delay),
                    messages.size(),
                    truncate(responseText, 1000));
            return new int[] {nextRetryIndex, (int) Math.round(delay * 1000)};
        }

        /** 响应解析：要求标准 OpenAI 结构。 */
        static List<List<Double>> extractEmbeddings(String body) {
            Object parsed = JSON.parse(body);
            if (!(parsed instanceof Map<?, ?> result) || !result.containsKey("data")) {
                throw new IllegalArgumentException(
                        "Embedding failed: Invalid response format " + body);
            }
            List<List<Double>> embeddings = new ArrayList<>();
            Object data = result.get("data");
            if (data instanceof List<?> items) {
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> entry)) {
                        throw new IllegalArgumentException(
                                "Embedding failed: Invalid response format " + body);
                    }
                    Object embedding = entry.get("embedding");
                    List<Double> vector = new ArrayList<>();
                    if (embedding instanceof List<?> values) {
                        for (Object value : values) {
                            vector.add(value instanceof Number number ? number.doubleValue() : null);
                        }
                    }
                    embeddings.add(vector);
                }
            }
            return embeddings;
        }

        /** 调试辅助：记录超过字符阈值的输入位置与长度，不输出内容以免泄露用户数据。 */
        private void logLongInputs(List<String> message) {
            for (int index = 0; index < message.size(); index++) {
                String text = message.get(index);
                if (text != null && text.length() > 4000) {
                    log.warn("超长 embedding 输入 index={}, len={}", index, text.length());
                }
            }
        }
    }

    /** 按 spec 取 embedding 模型的可运行信息（参考实现 {@code get_embedding_model_info_by_id}）。 */
    public Map<String, Object> getEmbeddingModelInfoById(String modelId) {
        ModelInfo info = requireModelInfo(modelId, "Unknown embedding model spec: ", "embedding");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", info.modelId());
        result.put("display_name", info.displayName());
        result.put("dimension", info.dimension());
        result.put("base_url", info.baseUrl());
        result.put("api_key", info.apiKey());
        result.put("model_id", info.spec());
        result.put("batch_size", info.batchSize());
        return result;
    }

    /** 按 spec 选择 embedding 模型客户端（参考实现 {@code select_embedding_model}）。 */
    public OtherEmbedding selectEmbeddingModel(String modelId) {
        ModelInfo info = requireModelInfo(modelId, "Unknown embedding model spec: ", "embedding");
        log.info("Selecting embedding model: {} (provider_type={})", modelId, info.providerType());
        return new OtherEmbedding(
                info.modelId(),
                info.dimension(),
                info.baseUrl(),
                info.apiKey(),
                info.batchSize());
    }

    private ModelInfo requireModelInfo(String spec, String missingPrefix, String expectedType) {
        ModelInfo info = modelProviderCache.getModelInfo(spec);
        if (info == null) {
            throw new IllegalArgumentException(missingPrefix + spec);
        }
        if (!expectedType.equals(info.modelType())) {
            throw new IllegalArgumentException(
                    "Model " + spec + " is not an " + expectedType + " model (type=" + info.modelType() + ")");
        }
        return info;
    }

    // ==================== models/rerank.py ====================

    /** 重排分数归一化（参考实现 {@code sigmoid}）。 */
    public static double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    /** 重排模型基类（参考实现 {@code BaseReranker}）。 */
    public abstract static class BaseReranker {
        protected final String url;
        protected final String model;
        protected final String apiKey;
        protected final Map<String, Object> parameters;

        protected BaseReranker(
                String modelName, String apiKey, String baseUrl, Map<String, Object> parameters) {
            this.url = dockerSafeUrl(baseUrl);
            this.model = modelName;
            this.apiKey = apiKey;
            this.parameters = parameters == null ? new LinkedHashMap<>() : parameters;
        }

        /** 构造单批请求体。 */
        protected abstract Map<String, Object> buildPayload(
                String query, List<String> documents, int maxLength);

        /** 从响应中取出结果列表。 */
        protected abstract List<Map<String, Object>> extractResults(Object result);

        /**
         * 批量打分（参考实现 {@code acompute_score}）：按 batch_size 分批，
         * 单批失败时该批以 0.5 兜底并继续；{@code normalize} 时套 sigmoid。
         *
         * <p>签名差异（显式标注）：参考实现接收 {@code sentence_pairs}（{@code (query, docs)} 二元组，
         * docs 可为单串或串列表）。Java 侧改为 {@code queryAndDocuments}——首元素为 query、其余为文档，
         * 批量与兜底语义不变。
         */
        public List<Double> acomputeScore(
                List<String> queryAndDocuments, int batchSize, int maxLength, boolean normalize) {
            if (queryAndDocuments == null || queryAndDocuments.size() < 2) {
                return new ArrayList<>();
            }
            String query = queryAndDocuments.get(0);
            List<String> documents = new ArrayList<>(queryAndDocuments.subList(1, queryAndDocuments.size()));
            if (documents.isEmpty()) {
                return new ArrayList<>();
            }

            List<Double> allScores = new ArrayList<>();
            int effectiveBatchSize = Math.max(1, batchSize);
            int totalBatches = (documents.size() + effectiveBatchSize - 1) / effectiveBatchSize;

            for (int batchNo = 1; batchNo <= totalBatches; batchNo++) {
                int start = (batchNo - 1) * effectiveBatchSize;
                int end = Math.min(start + effectiveBatchSize, documents.size());
                List<String> batch = documents.subList(start, end);
                try {
                    allScores.addAll(batchRerank(query, batch, maxLength));
                    log.debug("Reranking batch {}/{} completed", batchNo, totalBatches);
                } catch (Exception exc) {
                    log.error("Reranking batch {} failed: {}", batchNo, describe(exc));
                    for (int index = 0; index < batch.size(); index++) {
                        allScores.add(0.5);
                    }
                }
            }

            if (normalize) {
                List<Double> normalized = new ArrayList<>();
                for (Double score : allScores) {
                    normalized.add(sigmoid(score));
                }
                return normalized;
            }
            return allScores;
        }

        /**
         * 单批重排（参考实现 {@code _batch_rerank}）。
         *
         * <p>结果条数、index 合法性、分数类型与有限性逐项校验，文案照搬。
         */
        public List<Double> batchRerank(String query, List<String> documents, int maxLength) {
            List<String> docs = new ArrayList<>(documents);
            if (docs.isEmpty()) {
                return new ArrayList<>();
            }

            Map<String, Object> payload = buildPayload(query, docs, maxLength);
            HttpResponse<String> response;
            try {
                response = postRaw(url, payload, headers(), RERANK_TIMEOUT);
            } catch (Exception exc) {
                log.error("Reranking request failed: {}", describe(exc));
                throw new IllegalStateException(describe(exc), exc);
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        "Client error '"
                                + response.statusCode()
                                + " "
                                + reasonPhrase(response.statusCode())
                                + "' for url '"
                                + url
                                + "'");
            }

            List<Map<String, Object>> results = extractResults(JSON.parse(response.body()));
            if (results.size() != docs.size()) {
                throw new IllegalArgumentException("Rerank response must contain one result per document");
            }

            List<Double> scores = new ArrayList<>();
            for (int index = 0; index < docs.size(); index++) {
                scores.add(0.0);
            }
            Set<Integer> seen = new LinkedHashSet<>();
            for (Map<String, Object> entry : results) {
                Object rawIndex = entry.get("index");
                Integer index =
                        rawIndex instanceof Number number ? number.intValue() : null;
                if (index == null || index < 0 || index >= docs.size() || seen.contains(index)) {
                    throw new IllegalArgumentException(
                            "Rerank response contains an invalid or duplicate index");
                }

                Object rawScore = entry.get("relevance_score");
                if (rawScore instanceof Boolean) {
                    throw new IllegalArgumentException(
                            "Rerank response contains an invalid relevance_score");
                }
                Double score = toFiniteDouble(rawScore);
                if (score == null) {
                    throw new IllegalArgumentException(
                            "Rerank response contains an invalid relevance_score");
                }
                if (Double.isNaN(score) || Double.isInfinite(score)) {
                    throw new IllegalArgumentException(
                            "Rerank response contains a non-finite relevance_score");
                }

                seen.add(index);
                scores.set(index, score);
            }
            return scores;
        }

        private Double toFiniteDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                try {
                    return Double.valueOf(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        /** 连接测试：单文档重排一次，能拿到分数即视为可用。 */
        public TestResult testConnection() {
            try {
                List<Double> scores = batchRerank("test query", List.of("test document"), 128);
                if (!scores.isEmpty()) {
                    return new TestResult(true, "连接正常");
                }
                return new TestResult(false, "响应无效");
            } catch (Exception exc) {
                String errorMsg = describe(exc);
                log.error("Rerank connection test failed: {}", errorMsg);
                return new TestResult(false, errorMsg);
            }
        }

        protected Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            headers.put("Content-Type", "application/json");
            return headers;
        }
    }

    /** OpenAI 协议重排（参考实现 {@code OpenAIReranker}）。 */
    public static class OpenAIReranker extends BaseReranker {

        public OpenAIReranker(
                String modelName, String apiKey, String baseUrl, Map<String, Object> parameters) {
            super(modelName, apiKey, baseUrl, parameters);
        }

        @Override
        protected Map<String, Object> buildPayload(String query, List<String> documents, int maxLength) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("query", query);
            payload.put("documents", documents);
            payload.put("max_chunks_per_doc", maxLength);
            return payload;
        }

        @Override
        protected List<Map<String, Object>> extractResults(Object result) {
            return resultMapList(result, "results");
        }
    }

    /** DashScope 协议重排（参考实现 {@code DashscopeReranker}）。 */
    public static class DashscopeReranker extends BaseReranker {

        public DashscopeReranker(
                String modelName, String apiKey, String baseUrl, Map<String, Object> parameters) {
            super(modelName, apiKey, baseUrl, parameters);
        }

        @Override
        protected Map<String, Object> buildPayload(String query, List<String> documents, int maxLength) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("top_n", documents.size());
            params.put("return_documents", false);
            Object instruct = parameters.get("instruct");
            if (instruct instanceof String text && !text.isEmpty()) {
                params.put("instruct", instruct);
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", documents);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", input);
            payload.put("parameters", params);
            return payload;
        }

        @Override
        protected List<Map<String, Object>> extractResults(Object result) {
            if (!(result instanceof Map<?, ?> map)) {
                return new ArrayList<>();
            }
            Object output = map.get("output");
            return output instanceof Map<?, ?> outputMap ? resultMapList(outputMap, "results") : new ArrayList<>();
        }
    }

    /** 按 spec 选择重排客户端（参考实现 {@code get_reranker}）。 */
    public BaseReranker getReranker(String modelId) {
        ModelInfo info = modelProviderCache.getModelInfo(modelId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown reranker model spec: " + modelId);
        }
        if (!"rerank".equals(info.modelType())) {
            throw new IllegalArgumentException(
                    "Model " + modelId + " is not a rerank model (type=" + info.modelType() + ")");
        }
        if (info.apiKey() == null || info.apiKey().isEmpty()) {
            throw new IllegalArgumentException(info.displayName() + " api_key is required");
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        Object extraParameters =
                info.extra() == null ? null : info.extra().get("parameters");
        if (extraParameters instanceof Map<?, ?> map) {
            map.forEach((key, value) -> parameters.put(String.valueOf(key), value));
        }

        Object rawProtocol = info.extra() == null ? null : info.extra().get("rerank_protocol");
        if (rawProtocol == null && info.extra() != null) {
            rawProtocol = info.extra().get("protocol");
        }

        if ("dashscope".equals(rawProtocol)) {
            return new DashscopeReranker(info.modelId(), info.apiKey(), info.baseUrl(), parameters);
        }
        return new OpenAIReranker(info.modelId(), info.apiKey(), info.baseUrl(), parameters);
    }

    // ==================== models/chat.py ====================

    /** 非流式调用的返回载体（对应参考实现 {@code GeneralResponse}：只有 content + is_full）。 */
    public static final class GeneralResponse {
        public final String content;
        public final boolean isFull = false;

        public GeneralResponse(String content) {
            this.content = content;
        }
    }

    /** 聊天调用适配器（参考实现 {@code LangChainChatAdapter} 的非流式路径）。 */
    public static class ChatAdapter {
        private final OpenAiChatModel model;
        private final String modelName;
        private final String baseUrl;

        ChatAdapter(OpenAiChatModel model, String modelName, String baseUrl) {
            this.model = model;
            this.modelName = modelName;
            this.baseUrl = baseUrl;
        }

        /**
         * 入参归一化（对应参考实现 {@code _normalize_messages}）：
         * 字符串 → 原样交给模型（LangChain 视为单条 user 消息）；列表 → 按 role 逐条转换。
         */
        private List<Message> normalizeMessages(Object message) {
            if (message instanceof String text) {
                return List.of(new UserMessage(text));
            }
            List<Message> converted = new ArrayList<>();
            if (message instanceof List<?> rawList) {
                for (Object rawItem : rawList) {
                    if (!(rawItem instanceof Map<?, ?> item)) {
                        continue;
                    }
                    Object role = item.get("role");
                    Object content = item.get("content");
                    String text = content == null ? "" : String.valueOf(content);
                    if ("assistant".equals(role)) {
                        converted.add(new AssistantMessage(text));
                    } else if ("system".equals(role)) {
                        converted.add(new SystemMessage(text));
                    } else {
                        converted.add(new UserMessage(text));
                    }
                }
            }
            return converted;
        }

        /** 对应 {@code call(message, stream=False)}。 */
        public GeneralResponse call(Object message) {
            return call(message, false);
        }

        /**
         * 对应 {@code call(message, stream=False)}。
         *
         * <p>能力差异（已显式标注）：参考实现的 {@code stream=True} 分支返回异步生成器
         * （{@code _stream_response}），本工程尚未移植流式路径，此处显式拒绝而非静默降级。
         */
        public GeneralResponse call(Object message, boolean stream) {
            if (stream) {
                throw new UnsupportedOperationException(
                        "参考实现 LangChainChatAdapter 的流式分支（_stream_response）尚未移植");
            }
            List<Message> messages = normalizeMessages(message);
            try {
                OpenAiChatOptions options =
                        OpenAiChatOptions.builder().model(modelName).build();
                ChatResponse response = model.call(new Prompt(messages, options));
                if (response == null || response.getResult() == null) {
                    return null;
                }
                return new GeneralResponse(response.getResult().getOutput().getText());
            } catch (Exception exc) {
                String error =
                        "Error calling model: "
                                + describe(exc)
                                + ", URL: "
                                + baseUrl
                                + ", Model: "
                                + modelName;
                log.error(error);
                throw new IllegalStateException(error, exc);
            }
        }
    }

    /** 按 spec 选择聊天模型（参考实现 {@code select_model}）。 */
    public ChatAdapter selectModel(String modelSpec) {
        ModelInfo info = requireChatModel(modelSpec);
        return new ChatAdapter(buildChatModel(info), info.modelId(), info.baseUrl());
    }

    /**
     * 按 spec 构造聊天客户端：baseUrl / apiKey / model 三要素全部取自 spec 所属的供应商行
     * （对应参考实现 {@code load_chat_model} 按 {@code ModelInfo} 实例化的那一面）。
     *
     * <p>对话与智能体主链路由此获得"切前端选的模型即切实际出海口"的行为；供应商行的地址或密钥
     * 变更体现为指纹变化，下一次构图自动换新客户端（与 {@code DynamicOpenAiChatModel} 的热切换同口径）。
     *
     * @param modelSpec 模型 spec（{@code provider_id:model_id}）
     * @throws IllegalArgumentException spec 为空 / 未收录 / 不是 chat 模型 / provider_type 不支持
     */
    public OpenAiChatModel buildChatModel(String modelSpec) {
        ModelInfo info = requireChatModel(modelSpec);
        String key = specFingerprint(info);
        OpenAiChatModel cached = chatModelCache.get(key);
        if (cached != null) {
            return cached;
        }
        // 条目数封顶：只可能由频繁改供应商配置累积，整体清空即可（容量小、重建无网络开销）
        if (chatModelCache.size() > CHAT_MODEL_CACHE_MAX) {
            chatModelCache.clear();
        }
        OpenAiChatModel created = buildChatModel(info);
        chatModelCache.put(key, created);
        return created;
    }

    /**
     * 取 spec 对应的 chat 模型信息并校验（参考实现 {@code models/chat.py} 的取值 + type 检查）。
     */
    private ModelInfo requireChatModel(String modelSpec) {
        if (modelSpec == null || modelSpec.isEmpty()) {
            throw new IllegalArgumentException("model_spec 不能为空");
        }
        ModelInfo info = modelProviderCache.getModelInfo(modelSpec);
        if (info == null) {
            List<ModelInfo> available = modelProviderCache.getAllSpecs("chat");
            List<String> availableIds = new ArrayList<>();
            for (ModelInfo item : available.subList(0, Math.min(10, available.size()))) {
                availableIds.add(item.spec());
            }
            throw new IllegalArgumentException(
                    "未找到模型: '"
                            + modelSpec
                            + "'。可用聊天模型 ("
                            + available.size()
                            + "): "
                            + StringUtils.pythonListRepr(availableIds));
        }
        if (!"chat".equals(info.modelType())) {
            throw new IllegalArgumentException(
                    "Model " + modelSpec + " is not a chat model (type=" + info.modelType() + ")");
        }
        log.info("Selecting model: {} (provider_type={})", modelSpec, info.providerType());
        return info;
    }

    /** 供应商三要素指纹：任一变化即重建客户端（key 不入这种内存结构以外的地方，故可直接参与拼接）。 */
    private static String specFingerprint(ModelInfo info) {
        return info.spec()
                + "|"
                + info.baseUrl()
                + "|"
                + info.apiKey()
                + "|"
                + info.providerType()
                + "|"
                + info.headers()
                + "|"
                + info.requestBodyOverrides();
    }

    /**
     * 由 {@link ModelInfo} 构造 OpenAI 兼容聊天客户端。
     *
     * <p>对应参考实现 {@code load_chat_model} 的 OpenAI 兼容分支与
     * {@code ChatCompletionsAdapter} 的客户端构造部分。
     */
    private OpenAiChatModel buildChatModel(ModelInfo info) {
        if (!ModelProviderService.OPENAI_COMPATIBLE_REQUEST_BODY_PROVIDER_TYPES.contains(
                info.providerType())) {
            // 参考实现在此处分流到 ChatAnthropic / ChatGoogleGenerativeAI；本工程聊天层为
            // OpenAI 兼容实现（provider_type 仅支持 openai / openrouter），故明确报错而非静默降级。
            throw new IllegalArgumentException(
                    "provider_type="
                            + info.providerType()
                            + " 的聊天客户端尚未移植：参考实现经 LangChain 的多协议适配，"
                            + "本工程聊天层为 OpenAI 兼容实现");
        }

        // base_url 常已带版本后缀（DashScope 是 .../compatible-mode/v1，OpenAI 是 .../v1），
        // 而 Spring AI 的 completionsPath 默认就是 "/v1/chat/completions" —— 直接拼会得到
        // ".../compatible-mode/v1/v1/chat/completions"，网关返回 404（空 message，极易误判成
        // "模型不存在"）。这里与 DynamicOpenAiChatModel 复用同一套归一化：把版本后缀从
        // base_url 摘出来，交给 completionsPath 承载。
        String[] np =
                DynamicOpenAiChatModel.normalize(
                        info.baseUrl(),
                        null,
                        DynamicOpenAiChatModel.DEFAULT_COMPLETIONS_PATH,
                        "/chat/completions");

        OpenAiApi.Builder apiBuilder =
                OpenAiApi.builder()
                        .baseUrl(dockerSafeUrl(np[0]))
                        .completionsPath(np[1])
                        .apiKey(info.apiKey() == null ? "" : info.apiKey());

        Map<String, String> headers = new LinkedHashMap<>();
        if (info.headers() != null) {
            for (Map.Entry<String, Object> entry : info.headers().entrySet()) {
                headers.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        // 参考实现为 OpenCode 请求绑定稳定会话路由；本工程无 langchain metadata，
        // 会话头直接落到请求头（User-Agent 的品牌前缀取本系统标识）。
        if ("opencode".equals(info.providerId()) || "opencode-go".equals(info.providerId())) {
            headers.put("User-Agent", "wenqu/" + AppVersion.VERSION);
            headers.put("x-opencode-session", UUID.randomUUID().toString());
        }

        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(chatOptions(info, headers))
                .build();
    }

    /** 每次请求的 OpenAI 选项：模型名 + provider 级请求体覆盖（extraBody）+ 自定义请求头。 */
    private static OpenAiChatOptions chatOptions(ModelInfo info, Map<String, String> headers) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(info.modelId());
        if (info.requestBodyOverrides() != null && !info.requestBodyOverrides().isEmpty()) {
            options.extraBody(new LinkedHashMap<>(info.requestBodyOverrides()));
        }
        if (!headers.isEmpty()) {
            options.httpHeaders(headers);
        }
        return options.build();
    }

    // ==================== HTTP / JSON 工具 ====================

    /** POST + 读取字符串响应；连接层异常向上抛出（由调用方决定是否重试）。 */
    private static HttpResponse<String> postRaw(
            String url, Map<String, Object> payload, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload)));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                builder.header(header.getKey(), header.getValue());
            } catch (IllegalArgumentException ignored) {
                // 非法头名跳过
            }
        }
        try {
            return httpClient(timeout).send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(describe(exc), exc);
        } catch (Exception exc) {
            throw new IllegalStateException(describe(exc), exc);
        }
    }

    private static HttpClient httpClient(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultMapList(Object container, String key) {
        Object raw = container instanceof Map<?, ?> map ? map.get(key) : null;
        List<Map<String, Object>> results = new ArrayList<>();
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    entry.forEach((mapKey, value) -> copy.put(String.valueOf(mapKey), value));
                    results.add(copy);
                }
            }
        }
        return results;
    }

    private static String reasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "HTTP " + statusCode;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }
}
