package com.wenqu.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量模型引用路由器：按知识库绑定（{@code kb.embedding_ref = {providerId}/{modelId}}）解析并缓存
 * 独立的 {@link OpenAiEmbeddingModel} 客户端（{@link KbVectorStoreRegistry} 的 per-KB 索引与
 * 用户长期记忆向量化共用）。
 * <p>
 * - <b>无兜底</b>：引用为空 / 非引用 / 供应商不存在 → 抛出，由调用方 fail-loud 引导绑定
 *   （全局 embedding.* 网关与遗留裸模型名回落已移除，须使用可解析的供应商引用）
 * - 按路由指纹缓存底层客户端；供应商网关/密钥变更（Redis 广播 reload）后指纹变化 → 自动重建
 * - 路径归一化复用 {@link DynamicOpenAiChatModel#normalize}（智谱 /v4/embeddings、千帆 /v2/embeddings 等）
 * - <b>重要</b>：向量模型切换 ≠ 仅换模型名——新旧模型向量空间不兼容（维度/语义均不同，数学上不可迁移），
 *   知识库换绑由 DocumentService.reembedKbAsync 按库重嵌入兜住
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DynamicEmbeddingModel {

    /** Spring AI 默认 embedding 路径（与 OpenAiApi 默认一致） */
    static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";

    private final ModelRegistryService registry;
    private final RetryTemplate retryTemplate;
    private final ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry;
    /** 额度状态登记：向量侧的额度失败发生在异步线程里，不登记就只会有一行没人看的日志 */
    private final ModelQuotaService quotaService;

    /** 按引用解析的向量客户端缓存（KB 自定义向量模型用），key=路由指纹 */
    private final java.util.concurrent.ConcurrentHashMap<String, EmbeddingModel> refDelegates =
            new java.util.concurrent.ConcurrentHashMap<>();

    public DynamicEmbeddingModel(ModelRegistryService registry,
                                 ObjectProvider<RetryTemplate> retryTemplate,
                                 ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry,
                                 ModelQuotaService quotaService) {
        this.registry = registry;
        this.retryTemplate = retryTemplate.getIfAvailable();
        this.observationRegistry = observationRegistry;
        this.quotaService = quotaService;
    }

    /**
     * 按引用解析的向量模型：引用格式 {@code providerId/modelId} → 供应商网关。
     * 引用无效（空/非引用/供应商不存在）抛 IllegalArgumentException——调用方应先经 KB 保存校验
     * 或显式配置（如 memory.platformEmbeddingRef），运行时触达即配置缺失。
     *
     * <p>返回的是<b>带额度登记的包装</b>：真正发起 embed 的调用（大多在异步线程：重嵌入、
     * QA/子块向量、记忆向量化）失败时会被静默吞成一行日志，套上这层才能让额度不足
     * 登记到供应商并通知归属人，而不是消失在日志里。
     */
    public EmbeddingModel forRef(String ref) {
        String v = ref == null ? "" : ref.trim();
        ModelRegistryService.ModelRoute resolved = registry.resolveReference(v);
        if (resolved == null) {
            throw new IllegalArgumentException("向量模型引用无效: " + (v.isEmpty() ? "（未绑定）" : v)
                    + "（请重新绑定向量模型）");
        }
        EmbeddingModel delegate = refDelegates.computeIfAbsent(resolved.embeddingFingerprint(), k -> {
            String[] np = DynamicOpenAiChatModel.normalize(resolved.baseUrl(), resolved.embeddingsPath(),
                    DEFAULT_EMBEDDINGS_PATH, "/embeddings");
            return build(np[0], np[1], resolved.modelId(), resolved.apiKey());
        });
        return new QuotaAwareEmbeddingModel(delegate, resolved, v);
    }

    /**
     * 向量调用的额度登记包装（不改变任何向量语义，只在成功/失败两个时刻打点）。
     *
     * <p>「成功即清标记」这一条在这里尤其重要：向量调用是文档解析/重建索引的日常路径，
     * 充值后用户重解析一个文档就能自动解除禁选，不需要任何手动操作。
     */
    private final class QuotaAwareEmbeddingModel implements EmbeddingModel {
        private final EmbeddingModel delegate;
        private final ModelRegistryService.ModelRoute route;
        private final String ref;

        QuotaAwareEmbeddingModel(EmbeddingModel delegate, ModelRegistryService.ModelRoute route, String ref) {
            this.delegate = delegate;
            this.route = route;
            this.ref = ref;
        }

        @Override
        public float[] embed(Document document) {
            try {
                float[] r = delegate.embed(document);
                quotaService.markRecovered(route.providerId());
                return r;
            } catch (RuntimeException e) {
                throw quotaAware(e);
            }
        }

        @Override
        public float[] embed(String text) {
            try {
                float[] r = delegate.embed(text);
                quotaService.markRecovered(route.providerId());
                return r;
            } catch (RuntimeException e) {
                throw quotaAware(e);
            }
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            try {
                List<float[]> r = delegate.embed(texts);
                quotaService.markRecovered(route.providerId());
                return r;
            } catch (RuntimeException e) {
                throw quotaAware(e);
            }
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            try {
                EmbeddingResponse r = delegate.call(request);
                quotaService.markRecovered(route.providerId());
                return r;
            } catch (RuntimeException e) {
                throw quotaAware(e);
            }
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        /** 额度不足 → 登记 + 换成可行动文案；其余原样抛出（不猜测其它失败的归因） */
        private RuntimeException quotaAware(RuntimeException err) {
            if (!ModelQuotaGuard.isQuotaExhausted(err)) return err;
            quotaService.markExhausted(route.providerId(), ref, err.getMessage());
            com.wenqu.ai.model.Provider p = registry.providerById(route.providerId());
            return new IllegalStateException(ModelQuotaGuard.userMessage(p == null ? null : p.getName()), err);
        }
    }

    private EmbeddingModel build(String baseUrl, String embeddingsPath, String model, String apiKey) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .embeddingsPath(embeddingsPath)
                // 网关共享 HTTP 客户端（连接/读超时，见 GatewayHttpClients）：嵌入是实体调用，
                // 无读超时时网关挂起会让索引线程无限等待
                .webClientBuilder(com.wenqu.ai.config.GatewayHttpClients.webClientBuilder())
                .restClientBuilder(com.wenqu.ai.config.GatewayHttpClients.restClientBuilder());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        log.info("[Embedding] 向量模型客户端已构建: baseUrl={}, embeddingsPath={}, model={}, apiKey={}",
                baseUrl, embeddingsPath, model,
                apiKey == null || apiKey.length() <= 8 ? (apiKey == null || apiKey.isEmpty() ? "(空)" : "****")
                        : "****" + apiKey.substring(apiKey.length() - 4));
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED, options,
                retryTemplate != null ? retryTemplate : new RetryTemplate(),
                observationRegistry != null && observationRegistry.getIfAvailable() != null
                        ? observationRegistry.getIfAvailable() : io.micrometer.observation.ObservationRegistry.NOOP);
    }

    /**
     * 保存前探测：用「尚未入库」的新配置构建临时客户端并对探测文本做一次真实 embedding。
     * 供保存流程校验新配置可达/Key 有效/模型名正确（失败拒绝保存，避免配错后重嵌任务必然失败），
     * 同时返回新模型维度（与旧索引维度比对记日志，维度变化必然需要重建索引）。
     */
    public static int probe(String baseUrl, String apiKey, String model, String embeddingsPath) {
        String[] np = DynamicOpenAiChatModel.normalize(baseUrl, embeddingsPath, DEFAULT_EMBEDDINGS_PATH, "/embeddings");
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(np[0])
                .apiKey(apiKey == null ? "" : apiKey)
                .embeddingsPath(np[1])
                .webClientBuilder(com.wenqu.ai.config.GatewayHttpClients.webClientBuilder())
                .restClientBuilder(com.wenqu.ai.config.GatewayHttpClients.restClientBuilder())
                .build();
        OpenAiEmbeddingModel probeModel = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build(), new RetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP);
        return probeModel.embed("维度探测").length;
    }
}
