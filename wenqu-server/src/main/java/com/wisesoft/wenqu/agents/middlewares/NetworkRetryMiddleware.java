package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * 网络类错误的持续重试中间件，非网络错误沿用次数重试
 * （对应参考实现 {@code agents/middlewares/network_retry.py}）。
 *
 * <p>断网/连接抖动恢复后任务应自动继续：网络类异常（连接拒绝/超时/DNS）按指数退避<b>持续重试</b>，
 * 总预算内不向 graph 抛错；预算耗尽显式抛出，Run 以 failed 结束，不再出现"假完成"。
 *
 * <p>参考实现的结构是「{@code NetworkRetryMiddleware} 继承 {@code ModelRetryMiddleware}，
 * 网络重试通过 <b>handler 包装</b>实现（在包装内吞掉网络异常退避重试），非网络异常原样抛出
 * 交给父类按 {@code retry_on} 处理；预算起点在包装创建时固定，跨父类非网络重试保持，不放大」。
 * 本类逐条对位该结构。
 *
 * <p>父类 {@code ModelRetryMiddleware}（langchain 1.3.17，见 {@code _retry.py}）的语义也逐条对位：
 * <ul>
 *   <li><b>次数</b>：{@code max_retries} 是「初始调用<b>之后</b>的重试次数」，总调用 = {@code max_retries + 1}；
 *       循环写成 {@code for (attempt = 0; attempt <= maxRetries; attempt++)}。</li>
 *   <li><b>退避</b>：{@code calculate_delay(attempt)} = {@code initial_delay * backoff_factor^attempt}，
 *       上限 {@code max_delay}，{@code backoff_factor == 0} 时恒为 {@code initial_delay}；
 *       默认叠加 ±25% jitter。见 {@link #calculateDelay}。</li>
 *   <li><b>不可重试异常</b>：{@code retry_on} 返回 false 时<b>立即原样上抛</b>，
 *       <b>不</b>交给 {@code on_failure}（1.3.17 语义，与旧版"进 on_failure"不同）。</li>
 *   <li><b>耗尽处理</b>：{@code on_failure} 默认 {@code "continue"} —— <b>不抛异常</b>，
 *       返回内容为 {@code Model call failed after N attempts with <ExcType>: <msg>} 的
 *       {@link AssistantMessage}，让 Run 能优雅收尾；{@code "error"} 才重新抛出。</li>
 *   <li><b>控制流异常</b>：{@code GraphBubbleUp} 原样上抛，不消耗重试次数。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code os.getenv("YUXI_NETWORK_RETRY_BUDGET_SECONDS", "600")} →
 *       环境变量 {@code WENQU_NETWORK_RETRY_BUDGET_SECONDS}（品牌前缀替换，默认值 600 不变）。</li>
 *   <li>{@code langchain_core.exceptions.ModelError/ModelConnectionError/...} →
 *       本工程按<b>异常类型名与 HTTP 状态码</b>判定（见 {@link #isNetworkError}）；
 *       参考实现的判定顺序（结构化类型 → 状态码 → 文本兜底）完整保留。</li>
 *   <li>{@code ModelError.is_retryable} → Spring AI 的
 *       {@link NonTransientAiException}（不可重试）/ {@link TransientAiException}（可重试），
 *       这是本工程依赖栈里语义对位物；判定见 {@link #isRetryableModelError}。</li>
 *   <li>{@code GraphBubbleUp} → {@link #isGraphBubbleUp}：本工程按异常类名匹配框架的中断/跳转
 *       类异常（引擎抛出的控制流异常必须原样上抛，不能被重试吞掉）。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>同步重试</b>：参考实现同时提供同步与异步两套（{@code wrap_model_call} /
 *       {@code awrap_model_call}）；本工程为同步调用链，只保留同步路径（
 *       {@code ModelInterceptor.interceptModel} 本身即同步契约）。</li>
 *   <li><b>退避为阻塞 sleep</b>：参考实现异步路径用 {@code await asyncio.sleep}；
 *       本工程用 {@code Thread.sleep}（等价于其同步路径）。</li>
 *   <li><b>jitter 默认开启</b>：与参考实现 {@code jitter=True} 一致，用
 *       {@link ThreadLocalRandom} 取 ±25%；关闭时行为与不开启 jitter 逐字相同。</li>
 *   <li><b>on_failure 仅支持 "continue"/"error"</b>：参考实现还支持自定义 callable
 *       格式化错误文案；本工程按需只保留两档（默认 {@code "continue"}），
 *       自定义文案可后续以函数式参数扩展，不影响默认语义。</li>
 * </ol>
 */
public class NetworkRetryMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(NetworkRetryMiddleware.class);

    /** 必要替换 1：品牌前缀。 */
    public static final String BUDGET_ENV = "WENQU_NETWORK_RETRY_BUDGET_SECONDS";

    /**
     * 网络恢复类异常标记（与参考实现 {@code _NETWORK_ERROR_MARKERS} 逐字一致）。
     */
    static final List<String> NETWORK_ERROR_MARKERS = List.of(
            "connectionerror",
            "connection error",
            "connection refused",
            "connection reset",
            "connecttimeout",
            "readtimeout",
            "apitimeouterror",
            "timeout",
            "temporarily unavailable",
            "service unavailable",
            "bad gateway",
            "remote_protocol");

    /**
     * 明确非网络的错误（与参考实现 {@code _NON_NETWORK_MARKERS} 逐字一致）。
     */
    static final List<String> NON_NETWORK_MARKERS = List.of(
            "ratelimit", "authentication", "permission", "invalid_request", "not_found", "context_length");

    private final int maxRetries;
    private final double networkBudgetSeconds;
    private final double networkInitialDelaySeconds;
    private final double networkMaxDelaySeconds;

    /** 非网络错误重试的退避参数（对应参考实现父类 {@code backoff_factor} / {@code initial_delay} / {@code max_delay}）。 */
    private final double backoffFactor;
    private final double initialDelaySeconds;
    private final double maxDelaySeconds;

    /** 是否叠加 ±25% jitter（对应参考实现父类 {@code jitter}，默认 true）。 */
    private final boolean jitter;

    /** 耗尽后的行为：{@code "continue"}（默认，返回错误消息）/ {@code "error"}（重新抛出）。 */
    private final String onFailure;

    /** 非网络错误的重试谓词（对应参考实现 {@code retry_on=_retry_non_network_errors}）。 */
    private final Predicate<Exception> retryOn;

    /** 默认装配形态：max_retries=2 / budget 读环境变量 / 网络 2s→30s / 父类 1s→60s、factor 2.0、jitter。 */
    public NetworkRetryMiddleware() {
        this(2, null, 2.0, 30.0, 2.0, 1.0, 60.0, true, "continue");
    }

    public NetworkRetryMiddleware(int maxRetries) {
        this(maxRetries, null, 2.0, 30.0, 2.0, 1.0, 60.0, true, "continue");
    }

    /**
     * 便利构造：显式给出网络预算与网络退避，父类退避取参考实现默认值
     * （{@code initial_delay=1.0} / {@code max_delay=60.0} / {@code backoff_factor=2.0} / {@code jitter=true}）。
     *
     * @param maxRetries                 非网络错误的最大重试次数（参考实现默认 2，总调用 = maxRetries + 1）
     * @param networkBudgetSeconds       网络重试总预算秒数；{@code null} 时读环境变量，缺省 600
     * @param networkInitialDelaySeconds 网络初始退避秒数（参考实现默认 2.0）
     * @param networkMaxDelaySeconds     网络退避上限秒数（参考实现默认 30.0）
     */
    public NetworkRetryMiddleware(
            int maxRetries,
            Double networkBudgetSeconds,
            double networkInitialDelaySeconds,
            double networkMaxDelaySeconds) {
        this(maxRetries, networkBudgetSeconds, networkInitialDelaySeconds, networkMaxDelaySeconds,
                2.0, 1.0, 60.0, true, "continue");
    }

    /**
     * 全参数构造（与参考实现 {@code __init__} + 父类 {@code ModelRetryMiddleware.__init__} 参数一一对位）。
     *
     * @param maxRetries                非网络错误的最大重试次数（{@code max_retries}，需 &gt;= 0）
     * @param networkBudgetSeconds      网络重试总预算秒数；{@code null} 时读环境变量，缺省 600
     * @param networkInitialDelaySeconds 网络初始退避秒数
     * @param networkMaxDelaySeconds    网络退避上限秒数
     * @param backoffFactor             非网络重试的退避乘数（{@code backoff_factor}，0 表示恒定延迟）
     * @param initialDelaySeconds       非网络重试的初始退避秒数（{@code initial_delay}）
     * @param maxDelaySeconds           非网络重试的退避上限秒数（{@code max_delay}）
     * @param jitter                    是否叠加 ±25% jitter
     * @param onFailure                 耗尽行为：{@code "continue"} 或 {@code "error"}
     */
    public NetworkRetryMiddleware(
            int maxRetries,
            Double networkBudgetSeconds,
            double networkInitialDelaySeconds,
            double networkMaxDelaySeconds,
            double backoffFactor,
            double initialDelaySeconds,
            double maxDelaySeconds,
            boolean jitter,
            String onFailure) {
        // 对应参考实现 validate_retry_params：负值直接拒绝，避免静默退化成"不重试"
        if (maxRetries < 0) {
            throw new IllegalArgumentException("max_retries must be >= 0");
        }
        if (initialDelaySeconds < 0) {
            throw new IllegalArgumentException("initial_delay must be >= 0");
        }
        if (maxDelaySeconds < 0) {
            throw new IllegalArgumentException("max_delay must be >= 0");
        }
        if (backoffFactor < 0) {
            throw new IllegalArgumentException("backoff_factor must be >= 0");
        }
        if (!"continue".equals(onFailure) && !"error".equals(onFailure)) {
            throw new IllegalArgumentException("on_failure must be 'continue' or 'error'");
        }
        this.maxRetries = maxRetries;
        this.networkBudgetSeconds = networkBudgetSeconds != null
                ? networkBudgetSeconds
                : readBudgetFromEnv();
        this.networkInitialDelaySeconds = networkInitialDelaySeconds;
        this.networkMaxDelaySeconds = networkMaxDelaySeconds;
        this.backoffFactor = backoffFactor;
        this.initialDelaySeconds = initialDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.jitter = jitter;
        this.onFailure = onFailure;
        // 网络异常已由包装处理，这里排除；其余按默认语义（参考实现 _retry_non_network_errors）
        this.retryOn = exc -> {
            if (isNetworkError(exc)) {
                return false;
            }
            return isRetryableModelError(exc);
        };
    }

    @Override
    public String getName() {
        return "network_retry";
    }

    /**
     * 对应参考实现 {@code awrap_model_call}：先用网络重试包装 handler，
     * 再交给"按次数重试"的外层（{@link #retryNonNetwork}）处理非网络异常。
     */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelCallHandler networkWrapped = networkRetryHandler(handler);
        return retryNonNetwork(request, networkWrapped);
    }

    /**
     * 把 handler 包装成"网络异常在预算内退避重试、其余原样抛出"的形态。
     *
     * <p>对应参考实现 {@code _wrap_network_retry}。预算起点在<b>包装创建时</b>固定，
     * 因此跨外层的非网络重试不会放大预算（与参考实现的注释同义）。
     */
    ModelCallHandler networkRetryHandler(ModelCallHandler handler) {
        long started = System.nanoTime();
        double[] delay = {networkInitialDelaySeconds};
        int[] attempt = {0};
        return request -> {
            while (true) {
                try {
                    return handler.call(request);
                } catch (RuntimeException exc) {
                    if (isGraphBubbleUp(exc)) {
                        throw exc;
                    }
                    if (!isNetworkError(exc)) {
                        throw exc;
                    }
                    double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
                    if (networkRetryExhausted(exc, elapsed, delay[0], attempt[0] + 1)) {
                        throw exc;
                    }
                    attempt[0]++;
                    sleep(delay[0]);
                    delay[0] = Math.min(delay[0] * 2, networkMaxDelaySeconds);
                }
            }
        };
    }

    /**
     * 非网络错误按次数重试。
     *
     * <p>逐条对位参考实现父类 {@code ModelRetryMiddleware.wrap_model_call}（langchain 1.3.17）：
     * <pre>
     * for attempt in range(self.max_retries + 1):
     *     try: return handler(request)
     *     except GraphBubbleUp: raise
     *     except Exception as exc:
     *         attempts_made = attempt + 1
     *         if not should_retry_exception(exc, self.retry_on): raise          # 不可重试 → 立即上抛
     *         if attempt &lt; self.max_retries: time.sleep(calculate_delay(attempt)); continue
     *         return self._handle_failure(exc, attempts_made)                    # 耗尽 → on_failure
     * </pre>
     * 即：总调用 = {@code max_retries + 1}；不可重试异常立即上抛且不进 {@code on_failure}；
     * 耗尽后默认 {@code on_failure="continue"} 返回错误消息而非抛异常。
     */
    ModelResponse retryNonNetwork(ModelRequest request, ModelCallHandler handler) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return handler.call(request);
            } catch (RuntimeException exc) {
                if (isGraphBubbleUp(exc)) {
                    throw exc;
                }
                int attemptsMade = attempt + 1;
                if (!retryOn.test(exc)) {
                    // 不可重试 → 原样上抛，不交给 on_failure（1.3.17 语义）
                    throw exc;
                }
                if (attempt < maxRetries) {
                    double delay = calculateDelay(attempt);
                    log.info("Retry model call, on the {}th attempt (out of {} attempts), after {} ms.",
                            attemptsMade + 1, maxRetries + 1, Math.round(delay * 1000));
                    sleep(delay);
                    continue;
                }
                log.error("The maximum number of retries has been reached {}, and the model call has failed.",
                        maxRetries);
                return handleFailure(exc, attemptsMade);
            }
        }
        // 不可达：循环要么成功返回，要么在 attempt == maxRetries 时经 handleFailure 返回
        throw new IllegalStateException("Unexpected: retry loop completed without returning");
    }

    /**
     * 耗尽后的处理（对应参考实现父类 {@code _handle_failure}）。
     *
     * <p>{@code "error"} 重新抛出；默认 {@code "continue"} 返回
     * {@code Model call failed after N attempts with <ExcType>: <msg>} 的助手消息，
     * 让 Run 能优雅收尾而不是整体失败。
     */
    ModelResponse handleFailure(RuntimeException exc, int attemptsMade) {
        if ("error".equals(onFailure)) {
            throw exc;
        }
        return ModelResponse.of(formatFailureMessage(exc, attemptsMade));
    }

    /**
     * 错误文案（与参考实现 {@code _format_failure_message} 逐字一致，含单复数处理）：
     * {@code Model call failed after {attempts_made} attempt(s) with {exc_type}: {exc_msg}}。
     */
    static AssistantMessage formatFailureMessage(RuntimeException exc, int attemptsMade) {
        String attemptWord = attemptsMade == 1 ? "attempt" : "attempts";
        String content = "Model call failed after " + attemptsMade + " " + attemptWord
                + " with " + exc.getClass().getSimpleName() + ": " + exc.getMessage();
        return new AssistantMessage(content);
    }

    /**
     * 退避时长（与参考实现 {@code calculate_delay} 逐字一致）：
     * {@code initial_delay * backoff_factor^retry_number}，上限 {@code max_delay}，
     * {@code backoff_factor == 0} 时恒为 {@code initial_delay}；{@code jitter} 开启时叠加 ±25%。
     *
     * @param retryNumber 第几次重试（0 起）
     */
    double calculateDelay(int retryNumber) {
        double delay = backoffFactor == 0.0
                ? initialDelaySeconds
                : initialDelaySeconds * Math.pow(backoffFactor, retryNumber);
        delay = Math.min(delay, maxDelaySeconds);
        if (jitter && delay > 0) {
            double jitterAmount = delay * 0.25;
            delay += ThreadLocalRandom.current().nextDouble(-jitterAmount, jitterAmount);
            delay = Math.max(0, delay);
        }
        return delay;
    }

    /**
     * 预算耗尽返回 true（调用方抛出），否则记日志并返回 false 继续重试。
     *
     * <p>对应参考实现 {@code _network_retry_exhausted}。
     */
    boolean networkRetryExhausted(RuntimeException exc, double elapsed, double delay, int attempt) {
        if (networkBudgetSeconds <= 0 || elapsed + delay > networkBudgetSeconds) {
            log.warn("[network-retry] 预算耗尽({}s)，抛出网络错误: {}: {}",
                    Math.round(networkBudgetSeconds), exc.getClass().getSimpleName(), exc.getMessage());
            return true;
        }
        log.warn("[network-retry] 网络错误(第{}次，已等待{}s，{}s后重试): {}: {}",
                attempt, Math.round(elapsed), Math.round(delay), exc.getClass().getSimpleName(), exc.getMessage());
        return false;
    }

    /**
     * 优先按异常链的类型和 HTTP 状态分类，未知异常才匹配文本。
     *
     * <p>对应参考实现 {@code _is_network_error}：沿 {@code __cause__/__context__} 遍历异常链，
     * 用 {@code seen} 去重防环，逐层收集描述文本作为兜底。判定顺序逐条对位：
     * <ol>
     *   <li>先读 {@code status_code}（本工程等价物：{@code getStatusCode()} /
     *       {@code getResponse().getStatusCode()}）；{@code 400 <= status < 600} 时直接
     *       {@code return status >= 500} —— <b>状态码优先于异常类型</b>。</li>
     *   <li>标准模型错误类型：{@code ModelAPIError/ModelConnectionError/ModelTimeoutError} → 网络；
     *       本工程对位物见下。</li>
     *   <li>{@code ModelError} → 非网络（{@link NonTransientAiException} / {@link TransientAiException}）。</li>
     *   <li>标准网络异常类型（{@code ConnectionError/TimeoutError/httpx.NetworkError/...}）→ 网络。</li>
     *   <li>其余收集文本，先看 {@code _NON_NETWORK_MARKERS} 再看 {@code _NETWORK_ERROR_MARKERS}。</li>
     * </ol>
     */
    static boolean isNetworkError(Throwable exc) {
        Set<Integer> seen = new LinkedHashSet<>();
        List<String> details = new ArrayList<>();
        Throwable cursor = exc;
        while (cursor != null && seen.add(System.identityHashCode(cursor))) {
            Integer status = statusCodeOf(cursor);
            if (status != null && status >= 400 && status < 600) {
                return status >= 500;
            }
            String simpleName = cursor.getClass().getSimpleName();
            String lowerName = simpleName.toLowerCase(Locale.ROOT);
            // 参考实现：ModelAPIError/ModelConnectionError/ModelTimeoutError → 网络
            if (lowerName.contains("modelconnection") || lowerName.contains("modeltimeout")
                    || lowerName.contains("modelapierror") || lowerName.contains("apierror")) {
                return true;
            }
            // 参考实现：ModelError → 非网络（本工程对位物为 Spring AI 的两档 AI 异常）
            //   ModelAPIError（可重试）→ TransientAiException → 走网络预算重试
            //   ModelInvalidRequestError 等（不可重试）→ NonTransientAiException → 非网络
            if (cursor instanceof TransientAiException) {
                return true;
            }
            if (cursor instanceof NonTransientAiException) {
                return false;
            }
            if (cursor instanceof java.net.ConnectException
                    || cursor instanceof java.net.SocketTimeoutException
                    || cursor instanceof java.net.UnknownHostException
                    || cursor instanceof java.net.SocketException
                    || cursor instanceof java.io.InterruptedIOException
                    || cursor instanceof java.util.concurrent.TimeoutException
                    || cursor instanceof java.io.IOException) {
                return true;
            }
            details.add((simpleName + " " + cursor.getMessage()).toLowerCase(Locale.ROOT));
            cursor = cursor.getCause();
        }

        // 未知包装层的文本不能覆盖内层 SDK 的结构化错误语义
        String detail = String.join(" ", details);
        for (String marker : NON_NETWORK_MARKERS) {
            if (detail.contains(marker)) {
                return false;
            }
        }
        for (String marker : NETWORK_ERROR_MARKERS) {
            if (detail.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 不可重试的模型错误判定（对应参考实现 {@code _retry_non_network_errors} 里的
     * {@code if isinstance(exc, ModelError): return exc.is_retryable}）。
     *
     * <p>逐条对位：{@link NonTransientAiException} → {@code is_retryable == false}（不重试，立即上抛）；
     * {@link TransientAiException} → 可重试；<b>其余未分类异常一律可重试</b>（参考实现
     * {@code return True}），因此不做类名文本猜测 —— 参考实现的单测明确要求
     * {@code AuthenticationError} / {@code invalid_request} 这类<b>非 ModelError</b> 的异常
     * 仍按 {@code max_retries} 重试（见 {@code test_non_network_error_retried_by_max_retries_then_continue}）。
     */
    static boolean isRetryableModelError(Throwable exc) {
        Throwable cursor = exc;
        while (cursor != null) {
            if (cursor instanceof NonTransientAiException) {
                return false;
            }
            if (cursor instanceof TransientAiException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return true;
    }

    /**
     * 是否为引擎的控制流异常（必须原样上抛，不能被重试吞掉）。
     *
     * <p>对应参考实现对 {@code GraphBubbleUp} 的显式 re-raise。
     */
    static boolean isGraphBubbleUp(Throwable exc) {
        String name = exc.getClass().getSimpleName();
        return name.contains("GraphBubbleUp") || name.contains("GraphInterrupt")
                || name.contains("NodeInterrupt") || name.contains("GraphTerminate");
    }

    /** 从异常链上取 HTTP 状态码（对应参考实现读 {@code status_code} / {@code response.status_code}）。 */
    private static Integer statusCodeOf(Throwable exc) {
        try {
            java.lang.reflect.Method getter = exc.getClass().getMethod("getStatusCode");
            Object value = getter.invoke(exc);
            if (value instanceof Integer code) {
                return code;
            }
        } catch (ReflectiveOperationException ignored) {
            // 无 status_code 属性，继续看 response.status_code
        }
        try {
            java.lang.reflect.Method responseGetter = exc.getClass().getMethod("getResponse");
            Object response = responseGetter.invoke(exc);
            if (response != null) {
                java.lang.reflect.Method statusGetter = response.getClass().getMethod("getStatusCode");
                Object value = statusGetter.invoke(response);
                if (value instanceof Integer code) {
                    return code;
                }
                if (value instanceof org.springframework.http.HttpStatusCode httpStatus) {
                    return httpStatus.value();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // 无 response 属性
        }
        return null;
    }

    private static void sleep(double seconds) {
        long millis = (long) Math.max(0, seconds * 1000);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("网络重试等待被中断", interrupted);
        }
    }

    /** 必要替换 1：读本产品前缀的环境变量，缺省 600（与参考实现默认值一致）。 */
    private static double readBudgetFromEnv() {
        String raw = System.getenv(BUDGET_ENV);
        if (raw == null || raw.isBlank()) {
            return 600.0;
        }
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException ignored) {
            return 600.0;
        }
    }
}
