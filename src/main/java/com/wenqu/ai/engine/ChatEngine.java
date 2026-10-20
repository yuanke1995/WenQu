package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，不是死代码，勿删。

import com.wenqu.ai.agent.chat.ChatCommand;

/**
 * 一整轮问答的执行者。当前只有一个实现：AgentScope 框架引擎（{@code agentx.AgentscopeChatEngine}），
 * 手写轮次调度与灰度分流已随平移完成下线。
 * <p>本接口只做注册接缝：框架引擎由 {@code AgentxConfiguration} 构造后交给工厂，上层不认识具体引擎。
 */
public interface ChatEngine {

    String name();

    void chat(ChatCommand cmd, RunChannel channel);
}
