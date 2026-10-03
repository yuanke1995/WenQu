package com.wenqu.ai.util;

import reactor.util.context.ContextView;

/**
 * 用量归属上下文：台账需要知道「这笔推理算在谁头上」，但模型层（ChatModel）拿不到业务身份。
 * <p>
 * 两条通道，二者取其可用者：
 * <ul>
 *   <li><b>ThreadLocal</b>：同步调用（{@code chatModel.call(...)}）在同一线程发起，调用方
 *       在发起前 {@link #hold}、结束时 {@link #clear} 即可；</li>
 *   <li><b>Reactor Context</b>：流式链路在别的线程订阅/消费，ThreadLocal 不可见，
 *       由调用方 {@code .contextWrite(ctx -> ctx.put(UsageAttr.CTX_KEY, attr))}
 *       写入，向上游（模型内部）可见。</li>
 * </ul>
 * 取不到归属时不伪造（uid 为 null），台账如实记为无归属的系统调用。
 *
 * @author yuanke
 */
public final class UsageAttr {

    /** Reactor Context 键 */
    public static final String CTX_KEY = "wenqu.usageAttr";

    private static final ThreadLocal<Attr> TL = new ThreadLocal<>();

    private UsageAttr() {
    }

    /** 归属快照 */
    public record Attr(String uid, String sessionId, String messageId, String kind) {
    }

    /** 同步调用方持有归属（务必在 finally 中 clear，避免线程复用污染） */
    public static void hold(Attr attr) {
        if (attr == null) TL.remove();
        else TL.set(attr);
    }

    public static void clear() {
        TL.remove();
    }

    /** 当前调用线程上的归属（同步路径） */
    public static Attr current() {
        return TL.get();
    }

    /** 反应式链路上的归属（contextWrite 写入，向上游可见） */
    public static Attr from(ContextView cv) {
        return cv == null ? null : cv.getOrDefault(CTX_KEY, null);
    }

    /** 流式链路上写入归属的写法（向上游模型侧可见） */
    public static reactor.util.context.Context put(reactor.util.context.Context ctx, Attr attr) {
        return attr == null ? ctx : ctx.put(CTX_KEY, attr);
    }

    /**
     * 归属优先级：Reactor Context（流式）→ ThreadLocal（同步）→ 无归属。
     * 无归属时返回 uid 为 null 的 attr，调用方据此如实落「系统侧」台账。
     */
    public static Attr resolve(ContextView cv) {
        Attr a = from(cv);
        if (a != null) return a;
        return current();
    }

    /** 建 frontier 用：顺序取值（无则为 null），便于快速构造 Attr */
    public static Attr of(String uid, String sessionId, String messageId, String kind) {
        return new Attr(uid, sessionId, messageId, kind);
    }
}
