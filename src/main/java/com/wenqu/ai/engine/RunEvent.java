package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，不是死代码，勿删。

/**
 * 一条要送到前端的事件。
 * <p>{@code content} 沿用既有形状：<b>调用方负责序列化</b>（纯文本就传文本，卡片传 JSON 串），
 * 由 {@code ChatRunRuntime} 统一装进 {@code {"type","content","sessionId","seq","pos"}} 信封。
 * 刻意不在此建模成 per-event POJO——前端拿到的是同一串字节，建模只会多一层没有消费者的转换。
 *
 * @param type    SSE 事件类型（前端契约，别改）
 * @param content 载荷（调用方序列化后的字符串）
 * @param ledger  是否落会话事件账本（{@code true}=允许落盘；高频增量如流式中间帧传 {@code false}，
 *                只让终态快照落一份）
 */
public record RunEvent(RunEventType type, String content, boolean ledger) {

    /** 默认落账本（绝大多数事件都是结构性终态，落盘才有回放价值） */
    public static RunEvent of(RunEventType type, String content) {
        return new RunEvent(type, content == null ? "" : content, true);
    }

    /** 显式控制是否落账本：流式中间帧传 {@code ledger=false}，避免每 4 秒一条的用量快照淹没账本 */
    public static RunEvent of(RunEventType type, String content, boolean ledger) {
        return new RunEvent(type, content == null ? "" : content, ledger);
    }
}
