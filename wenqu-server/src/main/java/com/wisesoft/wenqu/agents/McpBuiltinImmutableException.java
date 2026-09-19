package com.wisesoft.wenqu.agents;

/**
 * 系统内置 MCP 的连接配置不可通过接口修改。
 *
 * <p>由参考实现 {@code agents/mcp/service.py} 的 {@code raise PermissionError(...)} 翻译。
 * Java 无 {@code PermissionError} 内建类型，故建本类承载同一语义（附文案），由路由层映射为 403
 * （与参考实现 {@code except PermissionError → HTTPException(403)} 一致）。
 */
public class McpBuiltinImmutableException extends RuntimeException {

    public McpBuiltinImmutableException(String message) {
        super(message);
    }
}
