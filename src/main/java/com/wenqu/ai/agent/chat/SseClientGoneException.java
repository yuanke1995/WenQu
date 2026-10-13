package com.wenqu.ai.agent.chat;

/**
 * 信号异常：本轮的 SSE 通道已经没了，或者用户按下了停止（P6 刀 4 把它从 {@code ChatService} 的
 * 私有内部类提成共享类型——深度思考与计划阶段两处都在同步消费流，都要靠它中断）。
 * <p>之所以抛异常而不是查标志：这两段都在 {@code blockLast()} 里逐增量消费，唯一的止损方式就是
 * 从增量回调里打断它；抛出去之后调用方按「客户端断开 / 用户叫停」两条口径分别收尾（是否留痕见各自调用点）。
 *
 * @author yuanke
 */
public final class SseClientGoneException extends RuntimeException {

    public SseClientGoneException() {
        super("SSE 通道已断开或本轮已被停止");
    }
}
