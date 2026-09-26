package com.wisesoft.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * 动态 OpenAI 兼容 EmbeddingModel：向量模型四要素（model / baseUrl / apiKey / embeddingsPath），
 * 经 {@link ModelRegistryService#embeddingRoute} 解析（embedding.model 为引用时取对应供应商网关，
 * 遗留值走全局 embedding.* 配置），设置页保存即生效，@Primary 使自动配置的 RedisVectorStore 注入本类，
 * 切换向量厂商无需重启服务。
 * <p>
 * - 每次调用前校验路由指纹（resolved 后的值比较），变化即重建底层 {@link OpenAiEmbeddingModel}（本地构建，无网络开销）
 * - 路径归一化复用 {@link DynamicOpenAiChatModel#normalize}（智谱 /v4/embeddings、千帆 /v2/embeddings 等）
 * - <b>重要</b>：向量模型切换 ≠ 仅换模型名——新旧模型向量空间不兼容（维度/语义均不同，数学上不可迁移），
 *   必须配合全量重嵌入（DocumentService.reembedAll：DROP 向量索引 → 按新维度重建 schema →
 *   全量重新 embedding → 清空语义缓存），ConfigService 保存检测到向量路由变化时自动触发
 * - DB 未配置时回退 yml/env 的 spring.ai.openai.embedding.*（与原自动配置行为一致）
 *
 * @author yuanke
 */
@Slf4j
@Component
@Primary
public class DynamicEmbeddingModel extends AbstractEmbeddingModel {

    /** Spring AI 默认 embedding 路径（与 OpenAiApi 默认一致） */
    static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";

    private final ModelRegistryService registry;
    private final RetryTemplate retryTemplate;
    private final ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry;

    private volatile String delegateKey = "";
    private volatile OpenAiEmbeddingModel delegate;

    public DynamicEmbeddingModel(ModelRegistryService registry,
                                 ObjectProvider<RetryTemplate> retryTemplate,
                                 ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry) {
        this.registry = registry;
        this.retryTemplate = retryTemplate.getIfAvailable();
        this.observationRegistry = observationRegistry;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return current().call(request);
    }

    /** abstract 方法：embedding 文本提取（委托底层实现） */
    @Override
    public float[] embed(Document document) {
        return current().embed(document);
    }

    /** 覆写父类缓存实现：模型热切换后维度可能变化，必须实时反映（RedisVectorStore 建索引依赖本值） */
    @Override
    public int dimensions() {
        return current().dimensions();
    }

    /** 按 provider 引用解析的向量客户端缓存（KB 自定义向量模型用），key=路由指纹 */
    private final java.util.concurrent.ConcurrentHashMap<String, OpenAiEmbeddingModel> refDelegates =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 按引用解析的向量模型（知识库绑定向量模型的独立索引写入/查询用）。
     * 引用格式 {@code providerId/modelId} → 供应商网关；非引用的遗留纯模型名（如启动迁移回填的
     * 旧全局 embedding.model 值）→ 遗留 embedding.* 网关 + 该模型名，行为与退役前"跟随全局"一致。
     * 按路由指纹缓存底层客户端；解析不出任何路由抛 IllegalArgumentException（调用方应先经 KB 保存校验）。
     */
    public org.springframework.ai.embedding.EmbeddingModel forRef(String ref) {
        String v = ref == null ? "" : ref.trim();
        ModelRegistryService.ModelRoute resolved = registry.resolveReference(v);
        if (resolved == null && !v.isBlank() && !v.contains("/")) {
            // 遗留纯模型名：模型名取 ref 本体，网关回落遗留 embedding.* 配置（与退役前全局路由同源）
            resolved = registry.embeddingRoute(v);
        }
        if (resolved == null) {
            throw new IllegalArgumentException("向量模型引用无效: " + ref);
        }
        final ModelRegistryService.ModelRoute route = resolved;
        return refDelegates.computeIfAbsent(route.embeddingFingerprint(), k -> {
            String[] np = DynamicOpenAiChatModel.normalize(route.baseUrl(), route.embeddingsPath(),
                    DEFAULT_EMBEDDINGS_PATH, "/embeddings");
            return build(np[0], np[1], route.modelId(), route.apiKey());
        });
    }

    /** 读当前向量路由（引用 → 供应商网关；遗留 → 全局 embedding.*），指纹变化即重建底层客户端 */
    private OpenAiEmbeddingModel current() {
        ModelRegistryService.ModelRoute route = registry.embeddingRoute();
        String key = route.embeddingFingerprint();
        OpenAiEmbeddingModel m = delegate;
        if (m != null && key.equals(delegateKey)) {
            return m;
        }
        synchronized (this) {
            m = delegate;
            if (m != null && key.equals(delegateKey)) {
                return m;
            }
            String[] np = DynamicOpenAiChatModel.normalize(route.baseUrl(), route.embeddingsPath(),
                    DEFAULT_EMBEDDINGS_PATH, "/embeddings");
            m = build(np[0], np[1], route.modelId(), route.apiKey());
            delegate = m;
            delegateKey = key;
            return m;
        }
    }

    private OpenAiEmbeddingModel build(String baseUrl, String embeddingsPath, String model, String apiKey) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .embeddingsPath(embeddingsPath);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        log.info("[Embedding] 向量模型客户端已{}: baseUrl={}, embeddingsPath={}, model={}, apiKey={}",
                delegate == null ? "构建" : "重建（配置热切换）",
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
     * 供 ConfigService.update 校验新配置可达/Key 有效/模型名正确（失败拒绝保存，避免配错后全量重嵌任务必然失败），
     * 同时返回新模型维度（与旧索引维度比对记日志，维度变化必然需要重建索引）。
     */
    public static int probe(String baseUrl, String apiKey, String model, String embeddingsPath) {
        String[] np = DynamicOpenAiChatModel.normalize(baseUrl, embeddingsPath, DEFAULT_EMBEDDINGS_PATH, "/embeddings");
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(np[0])
                .apiKey(apiKey == null ? "" : apiKey)
                .embeddingsPath(np[1])
                .build();
        OpenAiEmbeddingModel probeModel = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build(), new RetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP);
        return probeModel.embed("维度探测").length;
    }
}
