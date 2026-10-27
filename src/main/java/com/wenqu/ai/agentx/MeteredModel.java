package com.wenqu.ai.agentx;

import com.wenqu.ai.service.UsageLedgerService;
import com.wenqu.ai.service.UsageQuotaService;
import com.wenqu.ai.service.contentsafety.ContentSafetyService;
import com.wenqu.ai.util.UsageAttr;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

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
    /** 配额闸（Phase 2）：与 Spring AI 那一路同一个判据，两处共用 {@link UsageQuotaService} 一份实现 */
    private final com.wenqu.ai.service.UsageQuotaService quota;
    /** 内容安全出口闸（Phase 3 · P12）：与 Spring AI 那一路同一个判据，同挂模型出口 */
    private final com.wenqu.ai.service.contentsafety.ContentSafetyService safety;

    public MeteredModel(Model delegate, UsageLedgerService ledger,
                        com.wenqu.ai.service.UsageQuotaService quota,
                        com.wenqu.ai.service.contentsafety.ContentSafetyService safety,
                        UsageAttr.Attr attr, String modelRef) {
        this.delegate = delegate;
        this.ledger = ledger;
        this.quota = quota;
        this.safety = safety;
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
            // 出口闸同样在订阅时起：一次订阅＝一次模型调用＝一道独立的判定状态机
            // （正文与思考各一本累计，命中即在该处收尾并丢弃此后所有文本）。
            ContentSafetyService.OutputGate gate = safety == null ? ContentSafetyService.OutputGate.noop()
                    : safety.outputGate(attr == null ? null : attr.uid(),
                            attr == null ? null : attr.sessionId(), attr == null ? null : attr.kind());
            ChatUsage[] last = {null};
            StringBuilder received = new StringBuilder();
            boolean[] gotOutput = {false};
            String[] chunkId = {null};
            boolean[] tailEmitted = {false};
            boolean[] terminalSeen = {false};
            return delegate.stream(messages, tools, options)
                    .map(response -> {
                        if (response == null) return null;
                        if (response.getId() != null) chunkId[0] = response.getId();
                        boolean terminal = response.getFinishReason() != null || response.getUsage() != null;
                        ChatResponse filtered = rewrite(gate, response);
                        // 末块要把闸里压着没放的尾巴一起交出去：SSE 收回不了已经下发的字，
                        // 所以尾巴必须等判完才能放，而放的位置只能在「这一次调用的最后一块」里。
                        if (terminal && gate.hasHeld()) {
                            tailEmitted[0] = true;
                            terminalSeen[0] = true;
                            return mergeTail(filtered, gate);
                        }
                        return filtered;
                    })
                    .doOnNext(response -> {
                        if (response == null) return;
                        if (response.getUsage() != null) last[0] = response.getUsage();
                        String text = textOf(response);
                        if (text != null && !text.isEmpty()) {
                            received.append(text);
                            gotOutput[0] = true;
                        }
                    })
                    .concatWith(Flux.defer(() -> {
                        // 没见到带 finishReason/usage 的末块（网关直接断流）也要把尾巴放出去，
                        // 否则回答末尾会被闸吃掉一整段。已随末块交过就不再补第二条。
                        if (tailEmitted[0] || terminalSeen[0] || !gate.hasHeld()) return Flux.empty();
                        tailEmitted[0] = true;
                        List<io.agentscope.core.message.ContentBlock> tail = new java.util.ArrayList<>();
                        appendTail(gate, tail);
                        return tail.isEmpty() ? Flux.empty()
                                : Flux.just(new ChatResponse(chunkId[0], tail, null, null, null));
                    }))
                    .doFinally(signal -> {
                        ChatUsage usage = last[0];
                        if (usage != null) {
                            ledger.recordFramework(attr, modelRef, usage.getInputTokens(),
                                    usage.getOutputTokens(), usage.getCachedTokens());
                            return;
                        }
                        // 取消兜底（与 DynamicOpenAiChatModel 同一口径）：网关的 usage 只随流末块下发，
                        // ESC 停止/断开/护栏截断后永远收不到，但已下发内容供应商照单计费——
                        // 不记就是台账与账单的缺口（框架这一路此前整段漏记，实测取消一轮台账 0 行）。
                        // 只在真的收到过输出时估算；报错路径不估算（网关 5xx 通常不计费）。
                        if (signal == reactor.core.publisher.SignalType.CANCEL && gotOutput[0]) {
                            ledger.recordEstimated(attr, modelRef,
                                    com.wenqu.ai.util.TokenCounter.estimate(promptText(messages)),
                                    com.wenqu.ai.util.TokenCounter.estimate(received.toString()));
                        }
                    });
        });
    }

    /** 把一次模型调用的文本增量过闸后再交下去：其余块（工具调用等）不参与判定 */
    private static ChatResponse rewrite(ContentSafetyService.OutputGate gate, ChatResponse response) {
        if (gate.isPassive()) return response;
        List<io.agentscope.core.message.ContentBlock> content = response.getContent();
        if (content == null || content.isEmpty()) return response;
        List<io.agentscope.core.message.ContentBlock> out = new java.util.ArrayList<>(content.size());
        boolean changed = false;
        for (io.agentscope.core.message.ContentBlock block : content) {
            if (block instanceof io.agentscope.core.message.TextBlock text && text.getText() != null) {
                changed = true;
                String kept = gate.transform("text", text.getText());
                if (kept != null && !kept.isEmpty()) {
                    out.add(io.agentscope.core.message.TextBlock.builder().text(kept).build());
                }
            } else if (block instanceof io.agentscope.core.message.ThinkingBlock thinking
                    && thinking.getThinking() != null) {
                changed = true;
                String kept = gate.transform("thinking", thinking.getThinking());
                if (kept != null && !kept.isEmpty()) {
                    out.add(io.agentscope.core.message.ThinkingBlock.builder()
                            .thinking(kept)
                            .metadata(thinking.getMetadata() == null ? Map.of() : thinking.getMetadata())
                            .build());
                }
            } else {
                out.add(block);
            }
        }
        // 整块被压住（还没判够一段）时也要留一个空块下去：网关本来就会发空增量，下游按空串处理
        if (!changed) return response;
        return new ChatResponse(response.getId(), out, response.getUsage(),
                response.getMetadata(), response.getFinishReason());
    }

    /** 把闸里压着的尾巴取成正文/思考块（空的那条不补） */
    private static void appendTail(ContentSafetyService.OutputGate gate,
                                   List<io.agentscope.core.message.ContentBlock> blocks) {
        String text = gate.drain("text");
        if (text != null && !text.isEmpty()) {
            blocks.add(io.agentscope.core.message.TextBlock.builder().text(text).build());
        }
        String thinking = gate.drain("thinking");
        if (thinking != null && !thinking.isEmpty()) {
            blocks.add(io.agentscope.core.message.ThinkingBlock.builder().thinking(thinking).build());
        }
    }

    /** 末块要把闸里压着的尾巴一起交出：已下发的字收不回来，尾巴只能等判完在这一块放 */
    private static ChatResponse mergeTail(ChatResponse response, ContentSafetyService.OutputGate gate) {
        List<io.agentscope.core.message.ContentBlock> blocks = new java.util.ArrayList<>();
        if (response.getContent() != null) blocks.addAll(response.getContent());
        appendTail(gate, blocks);
        return new ChatResponse(response.getId(), blocks, response.getUsage(),
                response.getMetadata(), response.getFinishReason());
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
