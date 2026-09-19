package com.wisesoft.wenqu.agents;

/**
 * 指定的 MCP 服务器不存在。
 *
 * <p>由参考实现 {@code agents/mcp/service.py} 的 {@code MCPServerNotFoundError}（继承
 * {@code ValueError}）逐字翻译。Java 侧同样继承 {@link IllegalArgumentException}
 * （{@code ValueError} 的 Java 对位类型），使「先捕子类、后捕父类」的处理器顺序与参考实现一致：
 * <pre>
 * except MCPServerNotFoundError: -&gt; 404
 * except PermissionError:        -&gt; 403
 * except ValueError:             -&gt; 400
 * </pre>
 * 因此调用方必须在 {@code catch (IllegalArgumentException)} 之前先捕本类，否则会把 404 降级成 400。
 *
 * <p>注意 {@code toggle_tool_enabled} 端点的参考实现把 {@code ValueError} 映射为 404
 * （该处不存在「服务器不存在 → 404」与「其它 ValueError → 400」的分叉），本工程照搬不改。
 */
public class MCPServerNotFoundException extends IllegalArgumentException {

    public MCPServerNotFoundException(String message) {
        super(message);
    }
}
