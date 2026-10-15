package com.wenqu.ai.engine;

import com.wenqu.ai.agent.chat.ChatCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单一引擎分派：终态只保留 AgentScope 框架引擎。
 * <p>手写 legacy 运行时与 ab 灰度分流已在框架平移完成后下线——推理循环、工具派发、会话状态、上下文压缩
 * 全部由 AgentScope 原生接管，本类只负责把框架引擎实例暴露给 {@code ChatService}。未注册前选中即 fail-loud
 * 报错，绝不静默兜底。
 */
@Component
public class ChatEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatEngineFactory.class);

    private ChatEngine agentscope;

    /** 由 {@code AgentxConfiguration} 注册框架引擎。 */
    public void bindFramework(ChatEngine engine) {
        this.agentscope = engine;
        log.info("框架问答引擎已注册: {}", engine.name());
    }

    /** 单一引擎：这一轮恒由 AgentScope 框架引擎执行。 */
    public ChatEngine select(ChatCommand cmd) {
        return framework(cmd.getSessionId());
    }

    /** 单一引擎：会话级操作（如压缩上下文）恒由框架引擎持有。 */
    public boolean frameworkOwns(String sessionId) {
        return true;
    }

    private ChatEngine framework(String sessionId) {
        ChatEngine engine = agentscope;
        if (engine == null) {
            throw new IllegalStateException("框架问答引擎尚未就绪（AgentScope 平移未注册），会话=" + sessionId);
        }
        return engine;
    }
}