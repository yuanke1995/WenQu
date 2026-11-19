package com.wenqu.ai.ssh;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * SSH 终端 WebSocket 注册：{@code /api/ai/ssh-terminal?ticket=...}。
 *
 * <p>该路径在 {@code SecurityConfig} 里作为“凭票入场”的公开通道放行：票是一次性、60 秒、
 * 绑定 uid + hostId 的 Redis 键（见 {@link SshTerminalTicketService}），比在 URL 里带长期 token 安全。
 *
 * @author yuanke
 */
@Configuration
@EnableWebSocket
public class SshWebSocketConfig implements WebSocketConfigurer {

    private final SshTerminalWebSocketHandler handler;

    public SshWebSocketConfig(SshTerminalWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ai/ssh-terminal")
                .setAllowedOriginPatterns("*");
    }
}
