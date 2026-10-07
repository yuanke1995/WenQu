package com.wenqu.ai.service;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wenqu.ai.util.TokenCounter;
import com.wenqu.ai.util.UsageAccumulator;
import com.wenqu.ai.util.UsageAttr;

/**
 * 动态 OpenAI 兼容 ChatModel（多供应商路由）：模型引用感知的客户端路由器。
 * <p>
 * - 请求 options.model 为模型引用（{@code {providerId}/{modelId}}）时，经
 *   {@link ModelRegistryService#chatRoute} 解析出对应供应商网关（baseUrl/apiKey/completionsPath），
 *   按「归一化 baseUrl|path|apiKey」指纹缓存并委派对应 {@link OpenAiChatModel} 实例；
 *   下发前把 options.model 改写为裸模型名（引用前缀不透传给网关）
 * - <b>无兜底</b>：模型值缺失 / 非引用 / 供应商不存在 → fail-loud 抛出（全局 chat.* 网关兜底已移除），
 *   由问答入口/调用方的「选择模型」引导兜住
 * - 网关地址/密钥变更（供应商管理保存）后指纹变化 → 自动构建新客户端，旧实例由缓存淘汰
 * - 供应商变更经由 Redis 广播 reload / 周期兜底 reload 刷新，下一次请求自动感知
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
    /** 用量台账：所有推理调用在路由出口统一记账（供应商账单与个人统计的唯一数据源） */
    private final UsageLedgerService usageLedger;
    /** 额度状态登记（识别在 ModelQuotaGuard，本类只负责在路由出口触发登记与恢复） */
    private final ModelQuotaService quotaService;
    /** 容器存在则复用（与自动配置构建的 ChatModel 行为一致），缺失时用 builder 内部默认值 */
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;

    /** 客户端缓存：指纹（归一化 baseUrl|path|apiKey）→ 实例（供应商数量级，无需淘汰） */
    private final Map<String, OpenAiChatModel> delegates = new ConcurrentHashMap<>();

    public DynamicOpenAiChatModel(ModelRegistryService registry, Environment environment,
                                  UsageLedgerService usageLedger,
                                  ModelQuotaService quotaService,
                                  ObjectProvider<RetryTemplate> retryTemplate,
                                  ObjectProvider<ObservationRegistry> observationRegistry) {
        this.registry = registry;
        this.environment = environment;
        this.usageLedger = usageLedger;
        this.quotaService = quotaService;
        this.retryTemplate = retryTemplate.getIfAvailable();
        this.observationRegistry = observationRegistry.getIfAvailable();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelRegistryService.ModelRoute route = requireRoute(prompt);
        String modelRef = modelOf(prompt);
        ChatResponse resp;
        try {
            resp = current(route).call(rewriteModel(prompt, route));
        } catch (Exception e) {
            throw quotaAware(e, route, modelRef);
        }
        // 调用成功 = 该供应商已恢复（清掉可能残留的额度标记，让它重新可选）
        quotaService.markRecovered(route.providerId());
        // 记账必须在路由出口做：这里是全平台 LLM 请求的唯一必经点，任何新增调用方
        // （工作流节点、工具、未来的×××）都不可能绕过，天然不会因为漏改而丢账
        try {
            usageLedger.recordCall(UsageAttr.current(), modelRef,
                    resp.getMetadata() == null ? null : resp.getMetadata().getUsage());
        } catch (Exception e) {
            log.warn("[UsageLedger] 非流式用量记账异常（不影响本次调用）: {}", e.getMessage());
        }
        return resp;
    }

    /**
     * 必须覆写：接口 default 实现抛 UnsupportedOperationException（不支持流式）。
     * <p>
     * 逐轮累加 usage 后于链路终止（含取消/报错）时统一记一行：工具调用循环里每一轮都是
     * 一次真实请求，网关也按轮计费，账必须累加而不是只记某一轮。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ModelRegistryService.ModelRoute route = requireRoute(prompt);
        String modelRef = modelOf(prompt);
        Prompt rewritten = rewriteModel(prompt, route);
        OpenAiChatModel delegate = current(route);
        return Flux.defer(() -> {
            // 每订阅一个累加器：多次订阅互不串账
            UsageAccumulator acc = new UsageAccumulator();
            UsageAttr.Attr[] holder = new UsageAttr.Attr[1];
            // 是否已收到过输出块：只有完整走完才谈得上"调用成功、可清除额度标记"，
            // 中途报错/被取消都不算（网关可能已扣费但本轮没成，不能据此认为额度恢复）
            boolean[] gotOutput = new boolean[1];
            // 已收到的输出文本（取消时的中断估算用）
            StringBuilder received = new StringBuilder();
            return delegate.stream(rewritten)
                    .doOnEach(signal -> {
                        // 归属只能从上 ContextView 取（ThreadLocal 在 netty 线程不可见）
                        if (holder[0] == null) holder[0] = UsageAttr.resolve(signal.getContextView());
                        if (!signal.isOnNext()) return;
                        ChatResponse r = signal.get();
                        if (r == null) return;
                        if (r.getResult() != null && r.getResult().getOutput() != null
                                && r.getResult().getOutput().getText() != null) {
                            received.append(r.getResult().getOutput().getText());
                            gotOutput[0] = true;
                        }
                        if (r.getMetadata() == null) return;
                        acc.accept(r.getMetadata().getId(), r.getMetadata().getUsage());
                    })
                    .doOnError(err -> {
                        // 额度不足在此登记并转成可行动文案——流式错误经 Reactor 冒泡，
                        // 不在这里拦就会一路裸奔到 RagService 的通用兜底（用户只看到"请稍后重试"）
                        throw quotaAware(err, route, modelRef);
                    })
                    .doOnComplete(() -> {
                        // 正常收尾 = 该供应商可用，清除可能残留的额度标记
                        if (gotOutput[0]) quotaService.markRecovered(route.providerId());
                    })
                    .doFinally(sig -> {
                        try {
                            UsageAttr.Attr attr = holder[0] != null ? holder[0] : UsageAttr.current();
                            // 中断兜底：usage 只随流末块下发，取消（ESC 停止/断开/护栏截断）后永远收不到，
                            // 但已下发内容供应商照单计费——漏记就是台账与账单的整轮缺口（实测单轮可达 1.6 万 token）。
                            // 只要收到过输出块（请求确实被网关处理）就按实际下发内容估算入账；usage 已到达
                            // （acc 非空）仍以网关真实值入账。报错路径不估算：网关 5xx 通常不计费。
                            if (acc.isEmpty() && sig == SignalType.CANCEL && !received.isEmpty()) {
                                usageLedger.recordEstimated(attr, modelRef,
                                        TokenCounter.estimate(requestText(rewritten)),
                                        TokenCounter.estimate(received.toString()));
                            } else {
                                usageLedger.recordStream(attr, modelRef, acc);
                            }
                        } catch (Exception e) {
                            log.warn("[UsageLedger] 流式用量记账异常（不影响本次调用）: {}", e.getMessage());
                        }
                    });
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // 空 default options：模型名/温度等均由 per-request options 提供（无请求上下文，不做路由解析）
        return OpenAiChatOptions.builder().build();
    }

    /** 解析请求模型路由；未设置 / 非引用 / 供应商不存在一律 fail-loud（无全局网关兜底） */
    private ModelRegistryService.ModelRoute requireRoute(Prompt prompt) {
        String model = modelOf(prompt);
        ModelRegistryService.ModelRoute route = registry.chatRoute(model);
        if (route == null) {
            throw new com.wenqu.ai.common.BizException(
                    "聊天模型未配置或引用无效（" + (model == null || model.isBlank() ? "未选择模型" : model)
                            + "）：请在对话页模型选择器或个人设置中选择可用模型");
        }
        return route;
    }

    /** 额度不足的识别与文案收口（<b>全平台聊天调用的唯一出口</b>）。
     *
     * <p>为什么放在这里：这是所有 LLM 请求必经之处，识别一次即覆盖问答、工作流节点、
     * 子代理、工具内部调用等全部调用方——任何新增调用方都不可能绕过，也不会漏判。
     *
     * <p>做三件事：① 识别为额度不足时在供应商上登记并发通知（让归属人知道，且知道该改哪几处）；
     * ② 抛带可行动文案的 {@link com.wenqu.ai.common.BizException} 替代网关原文
     * （原文是给开发者看的 "You exceeded your current quota"，用户需要知道「去充值或换模型」）；
     * ③ <b>非额度问题一律原样抛出</b>——不在这里猜测任何其它失败的归因。
     *
     * @param err 原异常（Reactor 侧可能是任意 Throwable，故不收窄为 Exception）
     * @return 需要向上抛的异常（额度不足时是新造的 BizException，否则是原异常）
     */
    private RuntimeException quotaAware(Throwable err, ModelRegistryService.ModelRoute route, String modelRef) {
        if (!ModelQuotaGuard.isQuotaExhausted(err)) {
            // 非额度问题原样上抛（Error 也照抛：吞掉它比原样冒泡危险得多）
            return err instanceof RuntimeException re
                    ? re : new IllegalStateException(String.valueOf(err.getMessage()), err);
        }
        // 登记（内部全捕获，失败只记 WARN：问答已经失败了，登记不能让它更糟）
        quotaService.markExhausted(route.providerId(), modelRef, rootMessage(err));
        com.wenqu.ai.model.Provider p = registry.providerById(route.providerId());
        return new com.wenqu.ai.common.BizException(ModelQuotaGuard.userMessage(p == null ? null : p.getName()));
    }

    /** 异常链根因文案（额度原因要展示给用户看，网关原文比"未知错误"有用） */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        String last = null;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) last = cur.getMessage();
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return last;
    }

    /** 请求模型名（options.model，可能为引用/遗留名/null） */
    private static String modelOf(Prompt prompt) {
        return prompt.getOptions() instanceof OpenAiChatOptions o ? o.getModel() : null;
    }

    /**
     * 中断轮的输入侧估算文本：全部消息正文 + options 里随请求下发的工具/MCP schema——
     * 供应商按「实际收到的东西」计费，schema 不在消息里但同样占 prompt token，必须计入。
     * chat template 开销不在其中（估算略保守）；仅用于 usage 未达时的估算入账，非精确口径。
     */
    private static String requestText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            if (m.getText() != null) {
                sb.append(m.getText()).append('\n');
            }
        }
        if (prompt.getOptions() instanceof OpenAiChatOptions o && o.getToolCallbacks() != null) {
            for (ToolCallback cb : o.getToolCallbacks()) {
                try {
                    var def = cb.getToolDefinition();
                    if (def != null) {
                        sb.append(def.name()).append('\n').append(def.description())
                                .append('\n').append(def.inputSchema()).append('\n');
                    }
                } catch (Exception ignored) {
                    // 单个工具定义取不到就跳过（估算容错，不影响记账主流程）
                }
            }
        }
        return sb.toString();
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
                .completionsPath(completionsPath)
                // 网关共享 HTTP 客户端（连接超时/读超时/流式路径说明见 GatewayHttpClients）：
                // 默认降级实例无任何超时，网关挂起时调用线程无限等待
                .webClientBuilder(com.wenqu.ai.config.GatewayHttpClients.webClientBuilder())
                .restClientBuilder(com.wenqu.ai.config.GatewayHttpClients.restClientBuilder());
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
