package com.wisesoft.wenqu.service;

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

/**
 * 动态 OpenAI 兼容 ChatModel：LLM 网关三要素（baseUrl / apiKey / completionsPath）全部来自
 * c_ai_config（设置页保存即生效），跨厂商热切换（DeepSeek/智谱GLM/百炼Qwen/Kimi/豆包/混元/千帆/
 * MiniMax/SiliconFlow/Ollama 等 OpenAI 兼容端点）无需重启服务。
 * <p>
 * - 每次请求前校验配置指纹（baseUrl|completionsPath|apiKey），变化即重建底层 {@link OpenAiChatModel}；
 *   配置变更经由 ConfigService 本地保存 / Redis 广播 reload / 周期兜底 reload 刷新缓存，下一次请求自动感知
 * - 模型名与温度仍由 RagService 以 per-request options 传递（chat.model 逻辑不变）
 * - DB 未配置时回退 yml/env 的 spring.ai.openai.base-url / api-key（与原自动配置行为一致）
 * - 替换 Spring AI 自动配置的单例 ChatModel：RagService 注入基于本类的 ChatClient（DynamicChatClientConfig）
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DynamicOpenAiChatModel implements ChatModel {

    /** 配置为空时与 Spring AI 默认一致的兜底网关地址 */
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final ConfigService configService;
    private final Environment environment;
    /** 容器存在则复用（与自动配置构建的 ChatModel 行为一致），缺失时用 builder 内部默认值 */
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;

    /** 当前委托实例的配置指纹（baseUrl|completionsPath|apiKey），变化即重建 */
    private volatile String delegateKey = "";
    private volatile OpenAiChatModel delegate;

    public DynamicOpenAiChatModel(ConfigService configService, Environment environment,
                                  ObjectProvider<RetryTemplate> retryTemplate,
                                  ObjectProvider<ObservationRegistry> observationRegistry) {
        this.configService = configService;
        this.environment = environment;
        this.retryTemplate = retryTemplate.getIfAvailable();
        this.observationRegistry = observationRegistry.getIfAvailable();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return current().call(prompt);
    }

    /** 必须覆写：接口 default 实现抛 UnsupportedOperationException（不支持流式） */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return current().stream(prompt);
    }

    /**
     * 委托实例的默认 options（本类自身不设模型名，模型名由 per-request options 提供）。
     *
     * <p>未构建委托时（构图期先读一次默认 options，此时还没发过请求）返回空
     * {@link OpenAiChatOptions}，而<b>不是</b> {@code ChatOptions.builder().build()}：
     * 后者是 {@code DefaultChatOptions}，与委托的类型不一致。spring-ai-alibaba 的
     * {@code AgentLlmNode#buildChatOptions} 对「非 {@code ToolCallingChatOptions}」会告警并
     * 重建只带 toolCallbacks 的 options（模型名随之丢失，请求被上游判 400）；
     * `DefaultBuilder` 也要求两者类型一致才能正确合并。
     */
    @Override
    public ChatOptions getDefaultOptions() {
        OpenAiChatModel m = delegate;
        return m != null ? m.getDefaultOptions() : OpenAiChatOptions.builder().build();
    }

    /** 读当前配置，网关三要素任一变化即重建底层客户端（重建为本地对象构建，无网络开销） */
    private OpenAiChatModel current() {
        String baseUrl = resolve("chat.baseUrl", "spring.ai.openai.base-url");
        String apiKey = resolve("chat.apiKey", "spring.ai.openai.api-key");
        String completionsPath = resolve("chat.completionsPath", "");
        // 指纹用归一化后的值：不同写法但拼接结果相同（…/v1 vs 根地址+默认path）不触发重建
        String[] np = normalize(baseUrl, completionsPath, DEFAULT_COMPLETIONS_PATH, "/chat/completions");
        String key = np[0] + "|" + np[1] + "|" + apiKey;
        OpenAiChatModel m = delegate;
        if (m != null && key.equals(delegateKey)) {
            return m;
        }
        synchronized (this) {
            m = delegate;
            if (m != null && key.equals(delegateKey)) {
                return m;
            }
            m = build(np[0], np[1], apiKey);
            delegate = m;
            delegateKey = key;
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
     * chat 与 embedding 共用（DynamicEmbeddingModel 亦调用）。
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

    /** DB 配置优先（ConfigService 内含 defaults 兜底），空值再回退 Spring AI 原生属性（保持 yml/env 语义） */
    private String resolve(String cfgKey, String envKey) {
        String v = configService.get(cfgKey);
        if ((v == null || v.isBlank()) && !envKey.isEmpty()) {
            v = environment.getProperty(envKey, "");
        }
        return v == null ? "" : v.trim();
    }

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
        log.info("[ChatModel] LLM 客户端已{}: baseUrl={}, completionsPath={}, apiKey={}",
                delegate == null ? "构建" : "重建（配置热切换）",
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
