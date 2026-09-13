package com.wisesoft.ai.config;

import com.wisesoft.ai.service.DynamicOpenAiChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨厂商热切换的 ChatClient：底层为 {@link DynamicOpenAiChatModel}，
 * 网关地址/API Key/补全路径（c_ai_config 的 chat.baseUrl / chat.apiKey / chat.completionsPath）
 * 在设置页修改保存即生效，下一次请求自动用新网关，无需重启服务。
 * <p>
 * 替换 Spring AI 自动配置基于 yml 单例 ChatModel 的 ChatClient.Builder 注入（RagService 改为注入本 ChatClient）。
 *
 * <h3>工具调用（Function Calling）</h3>
 * 本配置注册 {@link ToolCallAdvisor} 作为默认 advisor：它持有 {@link ToolCallingManager}，
 * 在流式回答中检测模型发出的 tool_calls，执行 {@code @Tool} 注册的工具并把结果回传，
 * 自动完成多轮 tool-calling 循环。当请求未挂载任何工具（RagService.enabledTools() 返回空）
 * 时，advisor 是纯透传，对现有行为零侵入。
 *
 * @author yuanke
 */
@Configuration
public class DynamicChatClientConfig {

    /** 工具执行管理器：从请求 options 解析 ToolCallback 并执行（供 ToolCallAdvisor 使用） */
    @Bean
    public ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder().build();
    }

    /** 工具调用 Advisor：让 @Tool 工具在流式回答中真正执行（多轮 tool-calling 循环） */
    @Bean
    public ToolCallAdvisor toolCallAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                // 执行工具期间暂停文本下发，执行完再把最终回答 token 流式输出（不把中间 tool 片段混入正文）
                .streamToolCallResponses(false)
                .build();
    }

    @Bean
    public ChatClient chatClient(DynamicOpenAiChatModel dynamicOpenAiChatModel,
                                 ToolCallAdvisor toolCallAdvisor) {
        return ChatClient.builder(dynamicOpenAiChatModel)
                .defaultAdvisors(toolCallAdvisor)
                .build();
    }
}
