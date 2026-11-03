package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 重复调用闸：同一把工具、同一份参数连续调用到第 3 次就不再放行。
 * <p>
 * 为什么要有这把闸：步数预算只限「一轮总共跑多少步」，拦不住「每一步都是同一句命令」。
 * 2026-11-03 21:44 真机那一轮，模型连发 33 次
 * {@code ls -la .../gugugaga/; ls -la .../shots/}，每轮 3 秒、一句正文都没有，直到用户手点停止，
 * 烧掉 165 万 prompt token。当时的步数闸是 0（不限制）——而即便设了上限，50 步也只是把空转拖到
 * 第 50 次才结束，账单照样白付。
 * <p>
 * 判据是「连续」而不是「本回合出现过」：同一个文件读两次、同一句命令重跑一次都可能是有意的重试，
 * 只有第一次之后<b>连续</b>第 3 次相同才几乎必然是退化。中间插进任何一次不同的调用即重新计数。
 * 拒掉的那一步不再进入后面的闸（{@link #order()} 排在步数闸外侧），也不占预算——
 * 空转的重复调用不是「试了 N 次」，不该挤掉真正要做的事的那几步。
 * <p>
 * 只拒这一步，不替模型收尾：结果里写清「别再重复这条调用，换做法或直接作答」，
 * 与 {@link AgentxStepBudget} 的措辞同一口径——被拒的那一步要能直接指导下一步怎么做。
 */
public final class AgentxRepeatGuard implements MiddlewareBase {

    /** 连续第几次相同调用开始拦。1 次=首发，2 次=重试，3 次=空转。 */
    static final int THRESHOLD = 3;

    private static final String DENY_MESSAGE =
            "{\"error\":\"同一工具同一参数已连续调用 " + THRESHOLD + " 次，结果不会再有变化，这一步未执行。"
                    + "不要再重复这条调用；换一种做法，或基于已有信息直接给出回答。\"}";

    private static final String USER_SUMMARY = "检测到模型重复调用同一工具，已拦下这一步";

    private final Set<ExtensionPoint> points = EnumSet.of(ExtensionPoint.ON_ACTING);

    /** 连续相同的调用签名；空串=本轮还没有调用。 */
    private volatile String lastSignature = "";
    /** 当前签名已经连续出现的次数（含首次）。 */
    private volatile int repeats;
    /** 本轮是否已经就「重复」提醒过一次：拒 10 次不该弹 10 条警示。 */
    private volatile boolean notified;
    /** 可见降级出口；一轮只挂一次，不挂就静默拦下（用户只看到一张灰掉的工具卡）。 */
    private volatile BiConsumer<String, String> onDeny = (code, message) -> {
    };

    /**
     * 开一轮：归零计数并挂上降级出口。
     * <p>实例按会话常驻，预算按轮算——不归零就是上一轮的调用签名判定这一轮。
     */
    public void beginTurn(BiConsumer<String, String> onDeny) {
        this.onDeny = onDeny == null ? (code, message) -> {
        } : onDeny;
        this.lastSignature = "";
        this.repeats = 0;
        this.notified = false;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return points;
    }

    /** 排在步数闸（默认 1）外侧：被本闸拒掉的重复调用不占步数预算。 */
    @Override
    public int order() {
        return 2;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input == null ? null : input.toolCalls();
        if (calls == null || calls.isEmpty()) return next.apply(input);
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            if (denyThis(call)) {
                denied.add(call);
            } else {
                allowed.add(call);
            }
        }
        if (denied.isEmpty()) return next.apply(input);
        if (!notified) {
            notified = true;
            onDeny.accept("engine.repeatTool", USER_SUMMARY);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String replyId = state == null ? null : state.getReplyId();
        // 延迟到订阅时再写上下文与发事件，与 StepBudget 的时序保持一致
        Flux<AgentEvent> deniedFlux = Flux.defer(() -> {
            List<AgentEvent> events = new ArrayList<>();
            String agentName = state == null ? null : agent.getName();
            for (ToolUseBlock call : denied) {
                ToolResultBlock result = ToolResultBlock.text(DENY_MESSAGE)
                        .withIdAndName(call.getId(), call.getName())
                        .withState(ToolResultState.DENIED);
                if (state != null) {
                    state.contextMutable().add(
                            ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName));
                }
                events.add(new ToolResultStartEvent(replyId, call.getId(), call.getName()));
                events.add(new ToolResultTextDeltaEvent(replyId, call.getId(), call.getName(), DENY_MESSAGE));
                events.add(new ToolResultEndEvent(replyId, call.getId(), call.getName(),
                        ToolResultState.DENIED));
            }
            return Flux.fromIterable(events);
        });
        if (allowed.isEmpty()) return deniedFlux;
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    /**
     * 判一步、并推进计数：签名与上一步相同就累加，否则重开一段。
     * <p>抽出来是为了能脱离框架运行时单测——计数与阈值才是这条规则的全部，事件与上下文由
     * {@link #onActing} 负责。
     */
    boolean denyThis(ToolUseBlock call) {
        String signature = signatureOf(call);
        if (signature.equals(lastSignature)) {
            repeats++;
        } else {
            lastSignature = signature;
            repeats = 1;
        }
        return repeats >= THRESHOLD;
    }

    /**
     * 一次调用的判定签名：工具名 + 参数（键按字典序）。
     * <p>参数是 Map，同一份 JSON 换个键序就是字符串不等——不排序就等于给重复检测开了个后门。
     */
    static String signatureOf(ToolUseBlock call) {
        Map<String, Object> input = call == null ? null : call.getInput();
        String args = (input == null || input.isEmpty())
                ? "" : JSON.toJSONString(new TreeMap<>(input));
        return (call == null ? "" : call.getName()) + "|" + args;
    }
}
