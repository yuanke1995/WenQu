package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.models.ModelInfo;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import java.util.function.Supplier;
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
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * 动态 OpenAI 兼容 EmbeddingModel：向量模型四要素（model / baseUrl / apiKey / embeddingsPath）。
 *
 * <p><b>解析优先级（2026-09-22 修订，对齐参考实现）</b>：
 * <ol>
 *   <li>{@link #withSpec} 显式指定的 spec（检索/入库时由调用方传该知识库的
 *       {@code embedding_model_spec}）—— 参考实现即「按 KB 的 spec 建实例」
 *       （{@code knowledge/base.py::_create_kb_instance}）；</li>
 *   <li>未指定时取系统级 {@code config_options.system_options.embed_model}（新栈的
 *       "默认 Embedding 模型"）；</li>
 *   <li>两者都没有时回退旧的 {@code embedding.*}（yml/env，
 *       {@code spring.ai.openai.embedding.*}）——迁移前的口径，保留兜底。</li>
 * </ol>
 * spec 命中 {@link ModelProviderCache} 时，baseUrl / apiKey / model 全部来自<b>该 spec 所属供应商行</b>
 * （embedding 基址优先 {@code embedding_base_url}，见 {@link ModelProviderCache#rebuild}），
 * 与对话链路 {@code ModelSelectors#buildChatModel} 同源 —— 这正是「前端在供应商页配了 embedding
 * 端点与密钥却不生效」的修复点。
 *
 * <p>不再落库：配置写入面已随 {@code c_ai_config} 一起移除，故本类只读取 + 内存缓存。
 * {@code @Primary} 使自动配置的 RedisVectorStore 注入本类。
 * <p>
 * - 每次调用前校验配置指纹，变化即重建底层 {@link OpenAiEmbeddingModel}（本地构建，无网络开销）
 * - 路径归一化复用 {@link DynamicOpenAiChatModel#normalize}（智谱 /v4/embeddings、千帆 /v2/embeddings 等）
 * - <b>重要</b>：向量模型切换 ≠ 仅换模型名——新旧模型向量空间不兼容（维度/语义均不同，数学上不可迁移），
 *   必须配合全量重嵌入（DocumentService.reembedAll：DROP 向量索引 → 按新维度重建 schema →
 *   全量重新 embedding → 清空语义缓存），ConfigService 保存检测到 embedding 配置变化时自动触发
 *
 * @author yuanke
 */
@Slf4j
@Component
@Primary
public class DynamicEmbeddingModel extends AbstractEmbeddingModel {

    /** Spring AI 默认 embedding 路径（与 OpenAiApi 默认一致） */
    static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";

    private final ConfigService configService;
    private final Environment environment;
    private final RetryTemplate retryTemplate;
    private final ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry;
    /** 模型清单缓存（spec → 供应商行四要素）。 */
    private final ObjectProvider<ModelProviderCache> modelProviderCache;
    /** 系统级默认 embedding 模型 spec 的来源（{@code system_options.embed_model}）。 */
    private final ObjectProvider<OptionsService> optionsService;

    /** 本次调用使用的 spec（对应参考实现按调用传入的 embedding 配置）。 */
    private final ThreadLocal<String> currentSpec = new ThreadLocal<>();

    private volatile String delegateKey = "";
    private volatile OpenAiEmbeddingModel delegate;

    public DynamicEmbeddingModel(
            ConfigService configService,
            Environment environment,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry,
            ObjectProvider<ModelProviderCache> modelProviderCache,
            ObjectProvider<OptionsService> optionsService) {
        this.configService = configService;
        this.environment = environment;
        this.retryTemplate = retryTemplate.getIfAvailable();
        this.observationRegistry = observationRegistry;
        this.modelProviderCache = modelProviderCache;
        this.optionsService = optionsService;
    }

    /**
     * 在指定 embedding spec 下执行（对应参考实现按 KB 的 {@code embedding_model_spec} 建实例）。
     *
     * <p>用 try/finally 复原上一个 spec：向量库是全局单例，检索/入库必须"进去设、出来清"，
     * 否则并发 Run 之间会串用彼此的向量模型（维度不同即报错）。
     *
     * @param spec 形如 {@code provider_id:model_id}；为空时按系统默认/旧配置执行
     */
    public <T> T withSpec(String spec, Supplier<T> action) {
        String previous = currentSpec.get();
        if (spec != null && !spec.isBlank()) {
            currentSpec.set(spec.strip());
        } else {
            currentSpec.remove();
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                currentSpec.remove();
            } else {
                currentSpec.set(previous);
            }
        }
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

    /** 读当前配置，四要素任一变化即重建底层客户端 */
    private OpenAiEmbeddingModel current() {
        Settings s = settings();
        String key = s.baseUrl() + "|" + s.embeddingsPath() + "|" + s.model() + "|" + s.apiKey();
        OpenAiEmbeddingModel m = delegate;
        if (m != null && key.equals(delegateKey)) {
            return m;
        }
        synchronized (this) {
            m = delegate;
            if (m != null && key.equals(delegateKey)) {
                return m;
            }
            m = build(s.baseUrl(), s.embeddingsPath(), s.model(), s.apiKey(), s.source());
            delegate = m;
            delegateKey = key;
            return m;
        }
    }

    /** 一次调用的四要素与来源（已归一化）。 */
    private record Settings(String baseUrl, String embeddingsPath, String model, String apiKey, String source) {}

    /**
     * 解析本次调用的四要素：spec（调用方指定 → 系统默认）优先，命中供应商行即用该行；
     * 否则回落旧的 {@code embedding.*} 配置。
     */
    private Settings settings() {
        String spec = currentSpec.get();
        if (spec == null || spec.isBlank()) {
            spec = systemEmbedModelSpec();
        }
        if (spec != null && !spec.isBlank()) {
            ModelProviderCache cache = modelProviderCache.getIfAvailable();
            ModelInfo info = cache == null ? null : cache.getModelInfo(spec.strip());
            if (info != null) {
                // 供应商行给的是完整端点（如 …/compatible-mode/v1/embeddings），与 buildChatModel
                // 同一套归一化：把版本段从地址里摘出来交给 embeddingsPath 承载。
                String[] np = DynamicOpenAiChatModel.normalize(
                        info.baseUrl(), null, DEFAULT_EMBEDDINGS_PATH, "/embeddings");
                return new Settings(
                        np[0], np[1], info.modelId(),
                        info.apiKey() == null ? "" : info.apiKey(), "spec=" + info.spec());
            }
            log.warn("embedding spec 未命中供应商配置，回落 embedding.* 配置: {}", spec);
        }
        String baseUrl = resolve("embedding.baseUrl", "spring.ai.openai.embedding.base-url");
        String path = resolve("embedding.embeddingsPath", "");
        String[] np = DynamicOpenAiChatModel.normalize(baseUrl, path, DEFAULT_EMBEDDINGS_PATH, "/embeddings");
        return new Settings(
                np[0], np[1],
                resolve("embedding.model", "spring.ai.openai.embedding.options.model"),
                resolve("embedding.apiKey", "spring.ai.openai.embedding.api-key"),
                "config");
    }

    /** 系统级默认 embedding 模型 spec（{@code config_options.system_options.embed_model}）。 */
    private String systemEmbedModelSpec() {
        OptionsService service = optionsService.getIfAvailable();
        if (service == null) {
            return null;
        }
        try {
            Object value = service.get(OptionsService.SYSTEM_OPTIONS).get("embed_model");
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException exc) {
            log.warn("读取 system_options.embed_model 失败: {}", exc.getMessage());
            return null;
        }
    }

    /** 旧口径兜底：ConfigService 的 {@code embedding.*}（值来自 yml/env），空值再回退 Spring AI 原生属性 */
    private String resolve(String cfgKey, String envKey) {
        String v = configService.get(cfgKey);
        if (v == null || v.isBlank()) {
            v = environment.getProperty(envKey, "");
        }
        return v == null ? "" : v.trim();
    }

    private OpenAiEmbeddingModel build(
            String baseUrl, String embeddingsPath, String model, String apiKey, String source) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .embeddingsPath(embeddingsPath);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        log.info("[Embedding] 向量模型客户端已{}: source={}, baseUrl={}, embeddingsPath={}, model={}, apiKey={}",
                delegate == null ? "构建" : "重建（配置热切换）",
                source, baseUrl, embeddingsPath, model,
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
