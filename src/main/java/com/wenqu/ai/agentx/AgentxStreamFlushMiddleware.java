package com.wenqu.ai.agentx;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.session.SessionRecorder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 模型调用增量的<b>可见性闸</b>：把「一次模型调用跑完才对观众可见」压回「按时间成片可见」。
 *
 * <p><b>症状与成因</b>：框架的 {@code SessionRecorder#append} 只把 {@code model/chunk} 放进内存
 * {@code pending} 缓冲，落盘发生在 {@code record()/checkpoint()/flush()} 上——{@code SessionModels}
 * 在 {@code model/end} 才 record，{@code ReActAgent#saveStateToSession} 在整次调用结束才 checkpoint。
 * 而问渠的观众跟的是<b>落盘后</b>的 {@code SessionLog}（那是唯一能跨线程、能被迟到观众接回的通道），
 * 于是可见粒度＝一次模型调用。真机实测的产物就是用户报的那句「一下粘贴出几百个字」：
 * 500 字的回答只落 3 次屏（93 → 346 → 566 字），相邻 token 帧最大间隔 1.03 秒。
 *
 * <p><b>为什么挂在这里</b>：{@link MiddlewareBase#onModelCall} 是唯一既能包住原始模型调用事件流、
 * 又拿得到<b>本轮</b> {@link SessionRecorder} 的位置（{@code SessionRecorder.from(ctx)} 读的就是这个
 * per-call 上下文；{@code AgentSession} 自己不暴露任何实时流）。事件每过一条，距上次落盘超过
 * {@link #MIN_INTERVAL_MS} 就落一次盘。
 *
 * <p><b>为什么同步 {@code flushNow} 而不是异步 {@code flush}</b>：这不是风格选择，实测出来的——
 * 走 {@code flush()}（框架自己把它丢到共享 {@code boundedElastic}）时，落盘动作排在别人后面，
 * 500 字仍只落 2-3 次屏；当场调用它，一次 2-4ms（提交 5-16 条事件），同一题落屏变成 9 次、
 * 每档约 53 字、最大间隔 0.41 秒。代价是这段时间里 {@code append} 会等这把锁——占空比约 2%。
 *
 * <p><b>要认的账</b>：commit 文件按这个间隔变密（200ms 一次，一次 30 秒的调用多约 150 个 commit
 * 与等量 head.json 写），而 {@code JournalSessionLog#scan} 是从 head 沿父链回走的，所以
 * {@code pending()}/{@code transcript()} 那类全量读会更贵。跟随循环已经不再每跳做全量读
 * （见 {@code AgentscopeChatEngine} 的 {@code TurnMirror}），这里才敢把批拆小。
 */
public final class AgentxStreamFlushMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentxStreamFlushMiddleware.class);

    /**
     * 两次落盘的最小间隔（毫秒）。取 200ms 的依据：前端正文本身按 120ms 批量落屏
     * （{@code useChatEngine.js} 的 {@code flushSoon}），再密也没人看得见，只会多写盘。
     */
    private static final long MIN_INTERVAL_MS = 200L;

    private final AtomicLong lastFlushAt = new AtomicLong();
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        SessionRecorder recorder = SessionRecorder.from(ctx);
        if (recorder == null) {
            // 这一跑没挂会话日志：没有可见性问题要解，原样透传
            return next.apply(input);
        }
        return next.apply(input).doOnNext(event -> {
            long now = System.currentTimeMillis();
            long prev = lastFlushAt.get();
            if (now - prev < MIN_INTERVAL_MS) return;
            // CAS 取时间戳：并发事件只放一个过去，节流口径不因抢跑而失真
            if (!lastFlushAt.compareAndSet(prev, now)) return;
            // 一次落盘在跑就不再进第二次：flushNow 每次都先 renew 写者租约（一整轮 head.json CAS），
            // 堆叠起来是白做的写
            if (!inFlight.compareAndSet(false, true)) return;
            try {
                recorder.flushNow();
            } catch (RuntimeException e) {
                // 只告警不冒泡：这一闸做的是「让别人早一点看见」，不是持久性本身。
                // 真失败了框架自己的 checkpoint 仍在同一条链上兜底，把它抛进模型读取线程只会打死这一轮。
                log.warn("[FAIL-LOUD] 流式提前落盘失败（这段要等到本次模型调用结束才可见）session={}: {}",
                        ctx.getSessionId(), e.toString());
            } finally {
                inFlight.set(false);
            }
        });
    }
}
