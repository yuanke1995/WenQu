package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，非 legacy 死代码，勿删。

import com.wenqu.ai.agent.chat.ChatCommand;

/**
 * 一整轮问答的执行者。两种实现平级并存、各自完整拥有控制流：
 * {@code agent.*} 的手写轮次调度，和后续落地的 AgentScope 原生推理循环。
 * <p>本接口只做分派，不承载业务逻辑，也不设共享的中间抽象——那样会把两种运行时都阉成最小公倍数。
 */
public interface ChatEngine {

    String name();

    void chat(ChatCommand cmd, RunChannel channel);
}
