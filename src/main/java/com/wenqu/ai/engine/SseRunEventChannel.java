package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，非 legacy 死代码，勿删。

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 {@link RunEvent} 落到既有 SSE 出口。
 * <p>唯一实现走 {@code ChatRunRuntime.sendSseEvent}——那里已经是全链路单点收口：账本
 * （{@code LEDGER_SSE_TYPES}）、运行总线扇出、{@code seq}/文本 {@code pos} 去重、断标标记都在它里面。
 * 两种引擎共用它，前端与断线续跑的行为才不需要各验一遍。
 */
public final class SseRunEventChannel implements RunChannel {

    private final ChatRunRuntime runtime;
    private final SseEmitter emitter;
    private final String sessionId;
    private final boolean deepThink;

    public SseRunEventChannel(ChatRunRuntime runtime, SseEmitter emitter, String sessionId, boolean deepThink) {
        this.runtime = runtime;
        this.emitter = emitter;
        this.sessionId = sessionId;
        this.deepThink = deepThink;
    }

    @Override
    public SseEmitter emitter() {
        return emitter;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean deepThink() {
        return deepThink;
    }

    @Override
    public boolean emit(RunEvent event) {
        return runtime.sendSseEvent(emitter, event.type().sseName(), event.content(), sessionId);
    }

    @Override
    public void complete() {
        runtime.completeEmitter(emitter);
    }
}
