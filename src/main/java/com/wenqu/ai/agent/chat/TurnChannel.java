package com.wenqu.ai.agent.chat;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本轮的<b>出口通道与计时</b>（P6 刀 0 第二步自 {@link TurnState} 的平铺字段下沉）。
 *
 * <p><b>为什么单独一簇</b>：这一族回答的是「这一轮怎么把事件送出去、花了多久、还要不要送」，
 * 与「本轮知道什么」（引用池、工具档位）是两件事。刀 2 把投递与存活的行为收进了
 * {@link ChatRunRuntime}，但它操作的状态一直还平铺在轮次状态袋里——于是任何一侧改通道语义，
 * 都要在几百个字段里先认出哪几个属于通道。下沉之后 {@code st.channel.*} 就是这条边界。
 *
 * <p>三条纪律：
 * <ul>
 *   <li>{@link #emitter} 是<b>本轮创建时那条通道</b>，全程不换。断线重连接的是运行总线上的新通道
 *       （{@code ChatRunRuntime#resumeTurn}），所以 {@code st.channel.emitter != 接流方的 emitter} 是常态，
 *       不是 bug；</li>
 *   <li>{@link #detached} 与用户叫停（{@code TurnState#userStopped}）口径相反：前者是"没了观众但还要跑完"，
 *       后者是"明确要它停"，两者不许合并成一个标记；</li>
 *   <li>{@link #heartbeat} 是整轮流级心跳句柄，{@link ChatRunRuntime} 启停，run_code 面板那个 ticker
 *       不在这里（属 {@link TurnRunBoard}）。</li>
 * </ul>
 *
 * @author yuanke
 */
public final class TurnChannel {

    /** 本轮创建时的那条 SSE 通道（终态与投递的默认出口；接流方另有自己的通道） */
    public final SseEmitter emitter;
    /** 本轮起点（毫秒时间戳）：分段耗时与整轮台账都按它算 */
    public final long startTime;
    /** 流式正文的跨 token 缓冲（&lt;related&gt;/&lt;process&gt; 标签可能被切在两个 token 之间） */
    public final StringBuilder emitBuf = new StringBuilder();
    /** 分段耗时（距开始的累计 ms）：改写/检索由主流程 putAll 合并进来，生成/自检由流回调写入 */
    public final Map<String, Long> stageMs = new LinkedHashMap<>();
    /** 生成流的订阅句柄：客户端断开与叫停都经它 dispose 上游 */
    public final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
    /** 整轮流级心跳句柄（buildAnswerStream 启动、终态路径停止）：覆盖工具执行与最终回答首 token 两段静默区 */
    public volatile ScheduledFuture<?> heartbeat;
    /**
     * 断线后台续跑：本轮失去了通道（人在作答时才断开），此后 SSE 事件一律丢弃，
     * 但生成继续走完并按正常路径完整落库——用户回到本会话就能看到答案。
     */
    public volatile boolean detached;

    public TurnChannel(SseEmitter emitter, long startTime) {
        this.emitter = emitter;
        this.startTime = startTime;
    }
}
