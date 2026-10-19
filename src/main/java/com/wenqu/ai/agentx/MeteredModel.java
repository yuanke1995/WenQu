package com.wenqu.ai.agentx;

import com.wenqu.ai.service.UsageLedgerService;
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
 * <p>为什么需要它：P3 之后问答主链路与记忆/压缩的辅助调用都直接走
 * {@link io.agentscope.extensions.model.openai.OpenAIChatModel}，绕过了
 * {@code DynamicOpenAiChatModel} 这个原记账出口——本机库 {@code kind=memory} 最后一条停在
 * 框架引擎上线前，用量页的「长期记忆提取」一直是 0。
 *
 * <p>按调用类型各包一层：主对话 {@code chat}、子智能体 {@code subagent}、记忆提炼 {@code memory}、
 * 上下文压缩 {@code compress}。归属沿用 {@link UsageAttr}，与既有台账同一套 uid/session 口径。
 *
 * <p>只记最后一次带 usage 的响应：OpenAI 兼容网关通常只在流末块给 usage；若某些网关每块都给，
 * 以最后一块为准比逐块累加安全（后者在本项目曾把流式用量记成数倍）。
 */
public final class MeteredModel implements Model {

    private final Model delegate;
    private final UsageLedgerService ledger;
    private final UsageAttr.Attr attr;
    private final String modelRef;

    public MeteredModel(Model delegate, UsageLedgerService ledger, UsageAttr.Attr attr, String modelRef) {
        this.delegate = delegate;
        this.ledger = ledger;
        this.attr = attr;
        this.modelRef = modelRef;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return Flux.defer(() -> {
            ChatUsage[] last = {null};
            return delegate.stream(messages, tools, options)
                    .doOnNext(response -> {
                        if (response != null && response.getUsage() != null) {
                            last[0] = response.getUsage();
                        }
                    })
                    .doFinally(signal -> {
                        ChatUsage usage = last[0];
                        if (usage == null) return;
                        ledger.recordFramework(attr, modelRef,
                                usage.getInputTokens(), usage.getOutputTokens(), usage.getCachedTokens());
                    });
        });
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
