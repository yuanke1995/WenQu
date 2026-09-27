package com.wisesoft.ai.config;

import com.wisesoft.ai.service.McpServerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * MCP Server 端点路由：把 {@code /mcp/{token}}（叠加 context-path 后为 {@code /ai/mcp/{token}}）
 * 交给 {@link McpServerService} 处理。
 * <p>
 * 用函数式路由而不是 {@code @RestController}：MCP 的 Streamable HTTP 传输层自带
 * {@code RouterFunction}（GET/POST 两个方法），这里只做一层"按 token 转发"——
 * 端点路径里带着凭据，不能写成固定的单条映射。
 * <p>
 * 注意：该路径不在 {@code /api/**} 之下，因此不进 {@link SecurityConfig} 的拦截器链，
 * 鉴权完全由 {@link McpServerService} 按 token 自行完成（fail-loud，无效即 404）。
 *
 * @author yuanke
 */
@Configuration
public class McpEndpointConfig {

    @Bean
    public RouterFunction<ServerResponse> mcpEndpointRouter(McpServerService mcpServerService) {
        return RouterFunctions
                .route(RequestPredicates.POST(McpServerService.ENDPOINT_PATTERN), mcpServerService::handle)
                .andRoute(RequestPredicates.GET(McpServerService.ENDPOINT_PATTERN), mcpServerService::handle);
    }
}
