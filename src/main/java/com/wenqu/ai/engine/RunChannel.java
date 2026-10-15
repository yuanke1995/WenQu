package com.wenqu.ai.engine;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 这一轮的出口。引擎只管往这里写事件，不关心底下是浏览器那条 SSE、断线后的总线扇出，还是
 * 定时任务/MCP 用的收集型通道。
 */
public interface RunChannel {

    /**
     * 手写运行时仍按 emitter 逐点发送（互斥、心跳、账本、seq 全挂在它上面），legacy 引擎据此取回；
     * 框架引擎不该需要它。
     */
    SseEmitter emitter();

    String sessionId();

    /** 本轮是否深度思考——由用户开关与模型能力在入口处裁决，刻意不回写 {@code ChatCommand}。 */
    boolean deepThink();

    /** @return false = 通道已不可用（客户端断开或本轮已终结），调用方据此短路后续 LLM/检索开销。 */
    boolean emit(RunEvent event);

    default boolean emit(RunEventType type, String content) {
        return emit(RunEvent.of(type, content));
    }

    void complete();
}
