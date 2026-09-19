package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 MCP 工具对象（langchain tool 对象的最小面）。
 *
 * <p>由参考实现 {@code agents/mcp/service.py} 中 {@code MultiServerMCPClient.get_tools()} 返回的
 * langchain 工具对象翻译。参考实现的工具对象是第三方库类型，本工程没有对位依赖，故按被用到的字段
 * 显式建类（{@link ToolkitsRegistry.ToolDefinition} 已把 name/description/args_schema/metadata
 * 约定为该接口，实现它即可与本地工具并列使用）：
 * <ul>
 *   <li>{@code tool.name} → {@link #getName()}（保持 MCP 服务器返回的<b>原始</b>工具名，
 *       参考实现不改名；唯一标识放在 metadata.id 里）</li>
 *   <li>{@code tool.description} → {@link #getDescription()}</li>
 *   <li>{@code tool.args_schema} → {@link #getArgsSchema()}（JSON schema）</li>
 *   <li>{@code tool.metadata} → {@link #getMetadata()}（可变表；服务层写入 {@code id}）</li>
 *   <li>{@code tool.handle_tool_error} → {@link #isHandleToolError()} / {@link #setHandleToolError(boolean)}</li>
 * </ul>
 *
 * <p>平台差异（必要替换，逐条说明）：
 * <ul>
 *   <li><b>调用入参形态</b>：langchain 工具按 {@code **kwargs} 调用；本工程与 Spring AI 的工具调用
 *       约定一致，按 JSON 字符串入参（{@link #call(String)} 内解析为 MCP 的 arguments 表）。</li>
 *   <li><b>handle_tool_error 的落点</b>：langchain 由框架读取该属性决定是否捕获 {@code ToolException}；
 *       本工程工具执行体属于 agents 运行时（尚未照搬），故此处只<b>如实保留该标志位</b>，
 *       由未来的运行时读取——标志位与参考实现取值一致（恒 true）。</li>
 *   <li><b>连接生命周期</b>：参考实现的工具对象持有会话；本类持有 {@link McpSyncClient}，
 *       只要工具对象被继续引用，连接就保持存活（与参考实现的可达性语义一致）。</li>
 * </ul>
 */
public class McpTool implements ToolkitsRegistry.ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> argsSchema;
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final McpSyncClient client;

    private boolean handleToolError;

    /**
     * @param name        MCP 服务器返回的原始工具名
     * @param description 工具描述（可为 null）
     * @param argsSchema  参数 JSON schema（对应 {@code tool.args_schema.schema()} 的产物）
     * @param client      该工具所属服务器的客户端（调用时使用；可为 null 表示仅元数据）
     */
    public McpTool(String name, String description, Map<String, Object> argsSchema, McpSyncClient client) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.argsSchema = argsSchema == null ? new LinkedHashMap<>() : argsSchema;
        this.client = client;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getArgsSchema() {
        return argsSchema;
    }

    /** 工具元数据（对应 {@code tool.metadata}；参考实现在此写入唯一 id）。 */
    @Override
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** 写入唯一标识（对应 {@code tool.metadata["id"] = unique_id}）。 */
    public void setMetadataId(String identifier) {
        metadata.put("id", identifier);
    }

    /** 是否交由框架捕获工具异常（对应 {@code tool.handle_tool_error}）。 */
    public boolean isHandleToolError() {
        return handleToolError;
    }

    public void setHandleToolError(boolean handleToolError) {
        this.handleToolError = handleToolError;
    }

    /**
     * 调用工具。
     *
     * <p>入参是 JSON 对象文本（对应 langchain 工具的 kwargs 字典）；空入参按空表处理。
     * 返回内容的文本化规则与 MCP 客户端一致：优先拼接文本片段，无文本时回退为结构化内容的 JSON。
     */
    public String call(String argumentsJson) {
        if (client == null) {
            throw new IllegalStateException("MCP 工具 '" + name + "' 未绑定客户端，无法调用");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            Map<String, Object> parsed = JSON.parseObject(argumentsJson);
            if (parsed != null) {
                arguments.putAll(parsed);
            }
        }
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
        return contentToText(result);
    }

    /** 把 MCP 调用结果文本化（与 Spring AI 的 SyncMcpToolCallback 同一处理方式）。 */
    private static String contentToText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text && text.text() != null) {
                builder.append(text.text());
            } else {
                builder.append(JSON.toJSONString(content));
            }
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "McpTool[" + name + "]";
    }
}
