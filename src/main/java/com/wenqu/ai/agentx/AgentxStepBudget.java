package com.wenqu.ai.agentx;

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
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 步数预算闸的框架形态（对标 {@code agent/tool/guard/StepBudgetGuard}，语义照抄不另立规则）。
 * <p>为什么用 middleware 而不是 {@code maxIters}：{@code maxIters} 限的是推理轮数，而这一闸限的是
 * <b>工具调用次数</b>——一轮里模型可以一次要 5 把工具，两者不同口径。问渠那条闸同时也是账单闸
 * （失控循环会把整轮 token 预算花光），框架没有等价配置，只能在 {@code onActing} 里数。
 * <p><b>放行即记账</b>：判过就占一步，与手写侧「审批被拒的那一步同样计入预算」保持同一时序。
 * 计数点与判定点一分家，「拒掉算不算用掉一步」就变成两处代码各自的理解——迁移时实测过上限 15
 * 步的会话实际跑了三十几次工具调用。
 * <p>实例按会话常驻（{@code FrameworkSessions}），预算按<b>轮</b>算，所以每轮开始必须由引擎
 * {@link #beginTurn} 归零；不归零就是上一轮的步数吃掉这一轮的预算。
 */
public final class AgentxStepBudget implements MiddlewareBase {

    /** 回给模型的文本与手写守卫逐字一致：模型据此改道直接作答，措辞变了就是第二套行为。 */
    private static final String DENY_MESSAGE =
            "{\"error\":\"已达到本轮工具调用步数上限(%d)，不再执行工具调用。请基于已有信息直接给出最终回答。\"}";

    /**
     * 只剩最后一步时插给模型的那句（走框架 steer，下一次调模型前生效）。
     * <p>为什么要插：真机 2026-10-23 那一轮做好的 20.7 KB 看板页就是这么烂在盘上的——
     * 步数上限掐在最后一步，模型没有机会调交付，而它不会知道「下一步就是最后一步」。
     */
    public static final String WRAP_UP_HINT =
            "本轮工具调用只剩最后一步。如果已经做出需要交给用户的文件，请用这一步交付"
                    + "（沙盒里的成品用 deliver_artifact）；其余信息请基于已有内容直接写进回答，"
                    + "不要再安排新的检索或写入。";

    private final Set<ExtensionPoint> points = EnumSet.of(ExtensionPoint.ON_ACTING);

    /** 本轮上限：0 或负数=不限制（问渠口径）。 */
    private volatile int max;
    private volatile String turnKey = "";
    private volatile BiConsumer<String, String> onDeny = (code, message) -> {
    };
    /** 只剩最后一步时的送达出口（一句给模型的话）；一轮只插一次，不归零就会每步都插。 */
    private volatile java.util.function.Consumer<String> onLastStep = text -> {
    };
    private volatile boolean lastStepNoted;
    private int taken;

    /**
     * 开一轮：归零计数并挂上「拒了要说给谁听」与「只剩一步时要提醒谁」。
     *
     * @param onDeny 可见降级出口（code, message）——静默少跑工具会被读成「配了没生效」
     * @param onLastStep 插话出口（引擎接框架 steer）；不挂就等于最后一步不提醒
     */
    public void beginTurn(String turnKey, int max, BiConsumer<String, String> onDeny,
                          java.util.function.Consumer<String> onLastStep) {
        this.turnKey = turnKey == null ? "" : turnKey;
        this.max = max;
        this.onDeny = onDeny == null ? (code, message) -> {
        } : onDeny;
        this.onLastStep = onLastStep == null ? text -> {
        } : onLastStep;
        this.lastStepNoted = false;
        this.taken = 0;
    }

    public int taken() {
        return taken;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return points;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input == null ? null : input.toolCalls();
        if (calls == null || calls.isEmpty()) return next.apply(input);
        Allocation allocation = allocate(calls, this.max);
        if (allocation.denied().isEmpty()) return next.apply(input);
        String message = String.format(DENY_MESSAGE, this.max);

        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String replyId = state == null ? null : state.getReplyId();
        // 延迟到订阅时再写上下文与发事件，和框架自带 middleware 的时序一致
        Flux<AgentEvent> deniedFlux = Flux.defer(() -> {
            List<AgentEvent> events = new ArrayList<>();
            String agentName = state == null ? null : agent.getName();
            for (ToolUseBlock call : allocation.denied()) {
                ToolResultBlock result = ToolResultBlock.text(message)
                        .withIdAndName(call.getId(), call.getName())
                        .withState(ToolResultState.DENIED);
                if (state != null) {
                    state.contextMutable().add(
                            ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName));
                }
                events.add(new ToolResultStartEvent(replyId, call.getId(), call.getName()));
                events.add(new ToolResultTextDeltaEvent(replyId, call.getId(), call.getName(), message));
                events.add(new ToolResultEndEvent(replyId, call.getId(), call.getName(),
                        ToolResultState.DENIED));
            }
            return Flux.fromIterable(events);
        });
        if (allocation.allowed().isEmpty()) return deniedFlux;
        return deniedFlux.concatWith(next.apply(new ActingInput(allocation.allowed())));
    }

    /** 本轮这次派发里，哪些工具可以跑、哪些超出预算。 */
    public record Allocation(List<ToolUseBlock> allowed, List<ToolUseBlock> denied) {
    }

    /**
     * 按剩余预算切分本次派发（不依赖框架运行时，单测直接吃这一层）。
     * <p>{@code limit<=0}=不限制，整批放行；放行与拒绝同样占预算，且计数落在实例上、跨派发累计。
     * 拒了必须当场说一句可见降级——判定与告知都在这一层，漏到调用方去补就有「静默少跑工具」的空档。
     */
    Allocation allocate(List<ToolUseBlock> calls, int limit) {
        // 只剩最后一步：先插一句再切分。不插的话模型只知道「下一步被拒了」，
        // 而它本来可以把这一步留给交付——真机丢的那份产物就是这么丢的。
        if (!lastStepNoted && limit > 0 && taken == limit - 1) {
            lastStepNoted = true;
            onLastStep.accept(WRAP_UP_HINT);
        }
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            // 判过即占一步：包括被拒的这一次，否则模型反复撞同一把工具就是免费重试
            if (limit <= 0 || taken < limit) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
            taken++;
        }
        if (!denied.isEmpty()) {
            onDeny.accept("engine.stepBudget", "本轮工具调用已达上限（" + limit + " 步），后续工具未执行；"
                    + "发一句「继续」可以接着做");
        }
        return new Allocation(allowed, denied);
    }
}
