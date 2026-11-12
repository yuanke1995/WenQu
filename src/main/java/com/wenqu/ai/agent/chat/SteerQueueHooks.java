package com.wenqu.ai.agent.chat;

/**
 * 插话待发队列的信号口：引擎在「这一轮真的开跑」与「框架确认插话已应用」时回调。
 * <p>为什么由引擎回调而不是加定时器：drain 必须发生在框架 run 活着<b>之后</b>——早一步
 * {@code session.steer} 必然被拒，队列就会白等一整轮。
 */
public interface SteerQueueHooks {

    /** 这一轮已经开跑（框架 {@code inbox/opened}）：把该会话排队的插话逐个送进去。 */
    void drain(String sessionId);

    /** 框架确认某条插话写进了上下文（{@code inbox/applied}）：按收据号把队列项置 APPLIED。 */
    void applied(String sessionId, String receiptId);
}
