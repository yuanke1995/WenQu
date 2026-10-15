package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，非 legacy 死代码，勿删。

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.function.Supplier;

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

    /**
     * 本轮最后一条 {@code warn} 帧的正文，没有则 null。
     * <p>收尾要把它落库：警示条此前只活在流式那一瞬——用户当场看见「沙盒写入未执行」，刷新一次就当
     * 没发生过。实时与刷新必须同形，所以出口顺手记下这一份，而不是各引擎自己攒。
     */
    String lastWarn();

    /**
     * 把「本轮到此刻已经产出了什么」交给运行总线：刷新/换设备接流的第一帧据此重建气泡。
     * <p>外壳开轮时只有「还没开始出字」那一份空快照，真正的内容只有握着本轮状态的那条运行时能给，
     * 所以由引擎在状态袋建好之后回填。不回填的后果是接回来的气泡一片空白。
     */
    void offerSnapshot(Supplier<Map<String, Object>> snapshotSource);

    void complete();
}
