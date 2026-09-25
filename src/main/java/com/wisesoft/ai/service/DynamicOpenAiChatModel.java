package com.wisesoft.ai.service;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态 OpenAI 兼容 ChatModel（多供应商路由）：模型引用感知的客户端路由器。
 * <p>
 * - 请求 options.model 为模型引用（{@code {providerId}/{modelId}}）时，经
 *   {@link ModelRegistryService#chatRoute} 解析出对应供应商网关（baseUrl/apiKey/completionsPath），
 *   按「归一化 baseUrl|path|apiKey」指纹缓存并委派对应 {@link OpenAiChatModel} 实例；
 *   下发前把 options.model 改写为裸模型名（引用前缀不透传给网关）
 * - 遗留纯模型名 / 解析失败 → 全局 chat.* 网关（c_ai_config 三要素，保存即热生效），与原行为一致
 * - 网关地址/密钥变更（设置页或供应商管理保存）后指纹变化 → 自动构建新客户端，旧实例由缓存淘汰
 * - 默认网关配置变更经由 ConfigService 本地保存 / Redis 广播 reload / 周期兜底 reload 刷新，下一次请求自动感知
 * - 替换 Spring AI 自动配置的单例 ChatModel：RagService 注入基于本类的 ChatClient（DynamicChatClientConfig）
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DynamicOpenAiChatModel implements ChatModel {

    /** 配置为空时与 Spring AI 默认一致的兜底网关地址 */
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final ModelRegistryService registry;
    private final Environment environment;
    /** 容器存在则复用（与自动配置构建的 ChatModel 行为一致），缺失时用 builder 内部默认值 */
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;

    /** 客户端缓存：指纹（归一化 baseUrl|path|apiKey）→ 实例（供应商数量级，无需淘汰） */
    private final Map<String, OpenAiChatModel> delegates = new ConcurrentHashMap<>();

    public DynamicOpenAiChatModel(ModelRegistryService registry, Environment environment,
                                  ObjectProvider<RetryTemplate> retryTemplate,
                                  ObjectProvider<ObservationRegistry> observationRegistry) {
        this.registry = registry;
        this.environment = environment;
        this.retryTemplate = retryTemplate.getIfAvailable();
        this.observationRegistry = observationRegistry.getIfAvailable();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelRegistryService.ModelRoute route = registry.chatRoute(modelOf(prompt));
        return current(route).call(rewriteModel(prompt, route));
    }

    /** 必须覆写：接口 default 实现抛 UnsupportedOperationException（不支持流式） */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ModelRegistryService.ModelRoute route = registry.chatRoute(modelOf(prompt));
        return current(route).stream(rewriteModel(prompt, route));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // 无请求上下文：用全局路由的默认 options（与原单委托行为一致，仅作兜底）
        OpenAiChatModel m = delegates.get(registry.chatRoute("").chatFingerprint());
        return m != null ? m.getDefaultOptions() : ChatOptions.builder().build();
    }

    /** 请求模型名（options.model，可能为引用/遗留名/null） */
    private static String modelOf(Prompt prompt) {
        return prompt.getOptions() instanceof OpenAiChatOptions o ? o.getModel() : null;
    }

    /** 引用 → 裸模型名：引用前缀（providerId/）不透传给网关；遗留名原样 */
    private static Prompt rewriteModel(Prompt prompt, ModelRegistryService.ModelRoute route) {
        if (prompt.getOptions() instanceof OpenAiChatOptions o && o.getModel() != null) {
            OpenAiChatOptions copy = OpenAiChatOptions.fromOptions(o);
            copy.setModel(route.modelId());
            return new Prompt(prompt.getInstructions(), copy);
        }
        return prompt;
    }

    /** 按路由指纹取客户端（缓存命中复用；未命中构建并缓存，本地构建无网络开销） */
    private OpenAiChatModel current(ModelRegistryService.ModelRoute route) {
        String key = route.chatFingerprint();
        OpenAiChatModel m = delegates.get(key);
        if (m != null) {
            return m;
        }
        synchronized (this) {
            m = delegates.get(key);
            if (m != null) {
                return m;
            }
            String[] np = normalize(route.baseUrl(), route.completionsPath(), DEFAULT_COMPLETIONS_PATH, "/chat/completions");
            m = build(np[0], np[1], route.apiKey());
            delegates.put(key, m);
            return m;
        }
    }

    /**
     * 网关地址与补全路径归一化：兼容三种主流填写习惯（覆盖主流国产模型 OpenAI 兼容端点）。
     * 仅当 path 未显式配置（空或等于默认值）时对 baseUrl 智能处理：
     * 1) 完整端点粘贴（以 tail 结尾，如 /chat/completions、/embeddings）→ 剥掉尾部；
     * 2) OpenAI SDK 风格版本段尾缀（…/v1、…/compatible-mode/v1、智谱 …/v4、方舟 …/v3、千帆 …/v2）
     *    → 版本段移入 path（根地址+默认path 的拼接结果不变，无损容错）；
     * 显式配置 path 时仅去 baseUrl 尾部斜杠（完全尊重用户拼接结果）。
     * 例：https://open.bigmodel.cn/api/paas/v4 + 默认path → …/api/paas + /v4/chat/completions。
     * chat 与 embedding 共用（DynamicEmbeddingModel/ModelRegistryService 亦调用）。
     */
    static String[] normalize(String baseUrl, String completionsPath, String defaultPath, String tail) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String path = completionsPath == null ? "" : completionsPath.trim();
        if (path.isEmpty() || defaultPath.equals(path)) {
            if (url.endsWith(tail)) {
                url = url.substring(0, url.length() - tail.length());
            }
            java.util.regex.Matcher m = VERSION_SUFFIX.matcher(url);
            if (m.matches()) {
                url = m.group(1);
                path = "/v" + m.group(2) + tail;
            }
            if (path.isEmpty()) {
                path = defaultPath;
            }
        }
        return new String[]{url, path};
    }

    /** OpenAI SDK 风格版本段尾缀：…/v1 ~ …/v9（如 /compatible-mode/v1、/api/paas/v4、/api/v3、/v2） */
    private static final java.util.regex.Pattern VERSION_SUFFIX = java.util.regex.Pattern.compile("^(.+)/v([1-9])$");
    /** Spring AI 默认补全路径（与 OpenAiApi 默认一致） */
    static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** baseUrl/path 需已归一化（normalize） */
    private OpenAiChatModel build(String baseUrl, String completionsPath, String apiKey) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl)
                .apiKey(apiKey)
                // 归一化后 path 恒非空（含默认值），显式设置等价 Spring AI 默认行为；
                // GLM(/v4)、方舟(/v3)、千帆(/v2) 等非 /v1 网关由此支持热切换
                .completionsPath(completionsPath);
        OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                // 空 default options：模型名/温度等均由 per-request options 提供（RagService 三处调用均已显式传入）
                .defaultOptions(OpenAiChatOptions.builder().build());
        if (retryTemplate != null) {
            builder.retryTemplate(retryTemplate);
        }
        if (observationRegistry != null) {
            builder.observationRegistry(observationRegistry);
        }
        log.info("[ChatModel] LLM 客户端已构建: baseUrl={}, completionsPath={}, apiKey={}",
                baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl,
                completionsPath.isBlank() ? "/v1/chat/completions" : completionsPath,
                mask(apiKey));
        return builder.build();
    }

    /** 日志脱敏：仅显示末 4 位 */
    private static String mask(String key) {
        if (key == null || key.length() <= 8) {
            return key == null || key.isEmpty() ? "(空)" : "****";
        }
        return "****" + key.substring(key.length() - 4);
    }
}
