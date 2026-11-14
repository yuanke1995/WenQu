package com.wenqu.ai.agentx;

import com.wenqu.ai.service.UsageLedgerService;
import com.wenqu.ai.service.UsageQuotaService;
import com.wenqu.ai.util.UsageAttr;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 框架模型出口的记账装饰器：一次 {@code stream} 订阅记一行用量台账。
 *
 * <p>为什么需要它：问答主链路与记忆/压缩的辅助调用都直接走
 * {@link io.agentscope.extensions.model.openai.OpenAIChatModel}，绕过了
 * {@code DynamicOpenAiChatModel} 这一层记账出口，故在这里按订阅补记用量台账。
 *
 * <p>按调用类型各包一层：主对话 {@code chat}、子智能体 {@code subagent}、记忆提炼 {@code memory}、
 * 上下文压缩 {@code compress}。归属沿用 {@link UsageAttr}，与既有台账同一套 uid/session 口径。
 *
 * <p>只记最后一次带 usage 的响应：OpenAI 兼容网关通常只在流末块给 usage；若某些网关每块都给，
 * 以最后一块为准比逐块累加安全。
 */
public final class MeteredModel implements Model {

    private final Model delegate;
    private final UsageLedgerService ledger;
    private final UsageAttr.Attr attr;
    private final String modelRef;
    /** 配额闸：与 Spring AI 那一路同一个判据，两处共用 {@link UsageQuotaService} 一份实现 */
    private final com.wenqu.ai.service.UsageQuotaService quota;
    /**
     * 以「流出过内容、但整条流没有末块」收场的调用次数（断流计数）。
     * <p>实例按会话常驻、跨轮复用，所以问答侧读的是<b>本轮前后的差值</b>，不是这个数本身。
     */
    private final java.util.concurrent.atomic.AtomicLong abruptCloses =
            new java.util.concurrent.atomic.AtomicLong();

    /** 断流次数（见 {@link #abruptCloses}）：问答侧在开轮时取一次、收尾时再取一次，差值就是本轮的。 */
    public long abruptCloses() {
        return abruptCloses.get();
    }

    /**
     * 以<b>报错</b>收场的模型调用次数与最后一次的真错误原文。
     * <p>要它是因为框架把重试压成了一句 "Retries exhausted: 2/2" 交给上层——429 的响应体、
     * {@code AccountQuotaExceeded} 这类额度语义全留在它挂住的那次失败里，问答侧按那句分类
     * 只能得到「模型无响应」，而该给的是「额度不足，去充值或换模型」。
     * <p>实例按会话常驻、跨轮复用，所以问答侧读的是<b>差值</b>；原文只取最深一层能说出原因的那几句。
     */
    private final java.util.concurrent.atomic.AtomicLong modelErrors =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicReference<String> lastModelError =
            new java.util.concurrent.atomic.AtomicReference<>("");

    /** 报错次数（见 {@link #modelErrors}）：开轮取一次、收尾再取一次。 */
    public long modelErrors() {
        return modelErrors.get();
    }

    /** 最后一次报错的剥链原文（没有过就是空串）：只用于失败分类，不直接给用户看。 */
    public String lastModelError() {
        return lastModelError.get();
    }

    /**
     * 一次失败的全部原文：顶层 + cause 链 + 抑制项，逐层拼起来。
     * <p>三个都要：Reactor 重试烧完时抛的是只带一句描述的异常，最后一次真错误挂在 cause 或
     * suppressed 上（各家版本不同），只读 {@code getMessage()} 就又回到那句没有信息量的话上。
     */
    private static String errorText(Throwable err) {
        if (err == null) return "";
        StringBuilder sb = new StringBuilder();
        java.util.Deque<Throwable> stack = new java.util.ArrayDeque<>();
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        stack.push(err);
        while (!stack.isEmpty()) {
            Throwable t = stack.pop();
            if (t == null || !seen.add(t)) continue;
            String m = t.getMessage();
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(m.strip());
            }
            if (t.getCause() != null) stack.push(t.getCause());
            for (Throwable suppressed : t.getSuppressed()) stack.push(suppressed);
        }
        String out = sb.toString();
        return out.length() > 900 ? out.substring(0, 900) : out;
    }

    public MeteredModel(Model delegate, UsageLedgerService ledger,
                        com.wenqu.ai.service.UsageQuotaService quota,
                        UsageAttr.Attr attr, String modelRef) {
        this.delegate = delegate;
        this.ledger = ledger;
        this.quota = quota;
        this.attr = attr;
        this.modelRef = modelRef;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return Flux.defer(() -> {
            // 配额闸在订阅时判：一次订阅＝一次真实计费请求。工具循环里每一轮都会再过这道闸，
            // 所以触顶后最多再花「当前这一轮」的钱，不会把整轮的预算烧完。
            quota.assertWithinBudget(attr == null ? null : attr.uid(), attr == null ? null : attr.kind());
            ChatUsage[] last = {null};
            StringBuilder received = new StringBuilder();
            boolean[] gotOutput = {false};
            // 「这一跑有没有见过末块」的判据：流式收尾分类靠它分辨「回答完了」与「网关断流」。
            boolean[] sawTerminalBlock = {false};
            int[] responseCount = {0};
            return delegate.stream(messages, tools, options)
                    .doOnError(err -> {
                        modelErrors.incrementAndGet();
                        lastModelError.set(errorText(err));
                    })
                    .map(response -> {
                        if (response == null) return null;
                        if (response.getFinishReason() != null || response.getUsage() != null) {
                            sawTerminalBlock[0] = true;
                        }
                        return response;
                    })
                    .doOnNext(response -> {
                        if (response == null) return;
                        responseCount[0]++;
                        if (response.getUsage() != null) last[0] = response.getUsage();
                        String text = textOf(response);
                        if (text != null && !text.isEmpty()) {
                            received.append(text);
                            gotOutput[0] = true;
                        }
                    })
                    .doFinally(signal -> {
                        ChatUsage usage = last[0];
                        if (usage != null) {
                            ledger.recordFramework(attr, modelRef, usage.getInputTokens(),
                                    usage.getOutputTokens(), usage.getCachedTokens());
                            return;
                        }
                        // 取消兜底（与 DynamicOpenAiChatModel 同一口径）：网关的 usage 只随流末块下发，
                        // ESC 停止/断开/护栏截断后永远收不到，但已下发内容供应商照单计费——
                        // 不记就是台账与账单的缺口。
                        // 只在真的收到过输出时估算；报错路径不估算（网关 5xx 通常不计费）。
                        if (signal == reactor.core.publisher.SignalType.CANCEL && gotOutput[0]) {
                            ledger.recordEstimated(attr, modelRef,
                                    com.wenqu.ai.util.TokenCounter.estimate(promptText(messages)),
                                    com.wenqu.ai.util.TokenCounter.estimate(received.toString()));
                        }
                        // 断流留痕：这条流走到头了，却从没出现过带 finishReason/usage 的末块，
                        // 也不是取消或报错——那就是「网关把连接直接收了」的样子。框架在这一刻看到的是
                        // 一个正常完成的 Flux，于是那一跑以「回答完了」收场，屏幕上没有任何一处说得清
                        // 「这只是半截」。
                        // 判据不挂 gotOutput：流式块经框架解析后 content 块可能为空（正文由事件日志那份
                        // 原始 chunk 渲染），gotOutput 未必置位——只有「见过末块没有」这一条靠得住。
                        // 这里只计数、不改写流：怎么说、说给谁由问答侧收尾决定
                        // （见 AgentscopeChatEngine 的 UPSTREAM_STREAM_CUT）。
                        if (signal == reactor.core.publisher.SignalType.ON_COMPLETE
                                && !sawTerminalBlock[0] && responseCount[0] > 0) {
                            abruptCloses.incrementAndGet();
                        }
                    });
        });
    }

    /**
     * 把一次响应块里的计费文本取出来：正文与思考都算——这些厂商把 reasoning 按输出计费，
     * 只认 TextBlock 会在「用户在思考阶段就叫停」那一拍估出 0，等于把真实发生的钱记成没发生。
     */
    private static String textOf(io.agentscope.core.model.ChatResponse response) {
        if (response.getContent() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (io.agentscope.core.message.ContentBlock block : response.getContent()) {
            if (block instanceof io.agentscope.core.message.TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            } else if (block instanceof io.agentscope.core.message.ThinkingBlock think
                    && think.getThinking() != null) {
                sb.append(think.getThinking());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 本次请求送出去的提示词文本（估算输入用） */
    private static String promptText(List<io.agentscope.core.message.Msg> messages) {
        if (messages == null) return "";
        StringBuilder sb = new StringBuilder();
        for (io.agentscope.core.message.Msg m : messages) {
            if (m == null || m.getContent() == null) continue;
            for (io.agentscope.core.message.ContentBlock block : m.getContent()) {
                if (block instanceof io.agentscope.core.message.TextBlock text && text.getText() != null) {
                    sb.append(text.getText()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
