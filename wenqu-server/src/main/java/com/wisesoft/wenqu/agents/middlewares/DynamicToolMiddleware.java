package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.McpService;
import com.wisesoft.wenqu.agents.McpTool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * 动态工具选择中间件 - 支持 MCP 工具的动态加载和注册
 * （对应参考实现 {@code agents/middlewares/dynamic_tool.py}）。
 *
 * <p><b>注意：所有可能用到的 MCP 工具必须在初始化时预加载并注册到 {@link #getTools()}，
 * 运行时只是根据配置筛选工具，不能动态添加新工具</b>（与参考实现的类注释同义）。
 *
 * <p>映射关系：
 * <ul>
 *   <li>{@code AgentMiddleware.awrap_model_call} → {@link #interceptModel}；</li>
 *   <li>{@code self.tools}（中间件提供的工具） → {@link #getTools()}（{@code ModelInterceptor}
 *       的框架契约，{@code DefaultBuilder.java:288} 会把各拦截器的工具并入 agent 工具集）；</li>
 *   <li>{@code request.override(tools=enabled_tools)} → {@code ModelRequest.builder(request)
 *       .tools(names).build()}。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code get_mcp_tools(mcp_name)} → {@link McpService#getMcpTools(String)}（已搬，
 *       同名同义：按服务器 slug 取该服务器当前可用的工具）。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ol>
 *   <li><b>工具标识由对象改为名称</b>：参考实现按 {@code tool.name in selected_tools} 过滤
 *       {@code self.tools} 得到工具<b>对象</b>再塞进 request；Java 框架的
 *       {@link ModelRequest#getTools()} 是 <b>工具名列表</b>（{@code AgentLlmNode.java:479}
 *       按名称过滤回调）。故本类产出名称列表；"筛掉未选工具"的<b>效果等价</b>
 *       （框架对空列表的语义是"不过滤"，见类注释 3）。</li>
 *   <li><b>MCP 工具按服务器整组启用</b>：参考实现从 {@code _all_mcp_tools[mcp]} 取出该服务器的
 *       全部工具并整组 extend；本类同样按 slug 整组取（{@link McpService#getMcpTools(String)}），
 *       语义一致。未预加载的服务器记 warning（与参考实现同）。</li>
 *   <li><b>"选中为空 = 不启用任何工具"与"未配置 = 不过滤"的分野</b>：参考实现里
 *       {@code selected_tools} 为空/None 时 {@code enabled_tools} 保持为空列表，
 *       并把它 override 进 request —— 在 LangChain 里空列表意味着<b>不带工具</b>。
 *       而 Java 框架的 {@code AgentLlmNode.java:479-482} 对空列表的语义是
 *       <b>"不过滤、用全部默认工具"</b>。二者相反。为保持参考实现的行为，本类在
 *       "配置为空"时<b>显式产出空工具集</b>（{@link #EMPTY_TOOL_SENTINEL} 标记路径），
 *       见 {@link #resolveEnabledToolNames} 的注释。</li>
 *   <li><b>异步初始化</b>：参考实现的 {@code initialize_mcp_tools} 是 async；本工程为同步方法
 *       （框架的 MCP 客户端调用为阻塞式），调用方在构图前执行。</li>
 * </ol>
 */
public class DynamicToolMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolMiddleware.class);

    /**
     * "启用零个工具"的显式标记。
     *
     * <p>能力差异 3 的落地：Java 框架把空工具名列表理解为"不过滤"，而参考实现把它理解为
     * "不带工具"。本类在确实要禁用全部工具时产出只含该标记的列表，框架会因
     * "无任何回调名匹配"而得到空工具集（{@code AgentLlmNode.java:483-486} 的
     * {@code requestedTools.contains(...)} 全部为 false）。
     */
    static final String EMPTY_TOOL_SENTINEL = "\u0000__wenqu_no_tools__";

    private final List<ToolCallback> baseTools;
    private final List<String> mcpServers;
    private final McpService mcpService;

    /** 所有已加载的 MCP 工具（对应参考实现 {@code _all_mcp_tools}）。 */
    private final Map<String, List<ToolCallback>> allMcpTools = new java.util.LinkedHashMap<>();

    public DynamicToolMiddleware(List<ToolCallback> baseTools, List<String> mcpServers, McpService mcpService) {
        this.baseTools = baseTools == null ? new ArrayList<>() : new ArrayList<>(baseTools);
        this.mcpServers = mcpServers == null ? new ArrayList<>() : new ArrayList<>(mcpServers);
        this.mcpService = mcpService;
    }

    @Override
    public String getName() {
        return "dynamic_tool";
    }

    /**
     * 本中间件向 agent 注册的工具集（对应参考实现 {@code self.tools}）。
     *
     * <p>预加载后 {@code self.tools} = 基础工具 + 各 MCP 服务器的工具。
     */
    @Override
    public List<ToolCallback> getTools() {
        List<ToolCallback> tools = new ArrayList<>(baseTools);
        for (List<ToolCallback> mcpTools : allMcpTools.values()) {
            tools.addAll(mcpTools);
        }
        return tools;
    }

    /**
     * 异步初始化：预加载所有可能用到的 MCP 工具。
     *
     * <p>对应参考实现 {@code initialize_mcp_tools}。已在 {@link #allMcpTools} 里的服务器跳过。
     */
    public void initializeMcpTools() {
        for (String mcpName : mcpServers) {
            if (allMcpTools.containsKey(mcpName)) {
                continue;
            }
            log.info("Pre-loading MCP tools from: {}", mcpName);
            List<ToolCallback> mcpTools = mcpService == null
                    ? List.of()
                    : toCallbacks(mcpService.getMcpTools(mcpName));
            allMcpTools.put(mcpName, mcpTools);
            log.info("Registered {} tools from {}", mcpTools.size(), mcpName);
        }
    }

    /** 根据配置动态选择工具（从已注册的工具中筛选）。 */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<String> enabledToolNames = resolveEnabledToolNames(request);
        log.info("Dynamic tool selection: {} tools enabled: {}, selected_tools: {}, selected_mcps: {}",
                enabledToolNames.size(), enabledToolNames, selectedTools(request), selectedMcps(request));
        ModelRequest updated = ModelRequest.builder(request)
                .tools(enabledToolNames)
                .build();
        return handler.call(updated);
    }

    /**
     * 按配置筛选已注册工具，返回<b>启用工具的名称列表</b>（见类注释能力差异 1）。
     *
     * <p>与参考实现同构：先按 {@code context.tools} 过滤基础工具，再按 {@code context.mcps}
     * 追加各 MCP 服务器的全部工具。
     */
    List<String> resolveEnabledToolNames(ModelRequest request) {
        List<String> selectedTools = selectedTools(request);
        List<String> selectedMcps = selectedMcps(request);

        Set<String> enabled = new LinkedHashSet<>();

        // 根据配置筛选基础工具
        if (selectedTools != null && !selectedTools.isEmpty()) {
            for (ToolCallback tool : baseTools) {
                String name = toolName(tool);
                if (name != null && selectedTools.contains(name)) {
                    enabled.add(name);
                }
            }
        }

        // 根据配置筛选 MCP 工具（从已注册的工具中选择）
        if (selectedMcps != null && !selectedMcps.isEmpty()) {
            for (String mcp : selectedMcps) {
                List<ToolCallback> tools = allMcpTools.get(mcp);
                if (tools != null) {
                    for (ToolCallback tool : tools) {
                        String name = toolName(tool);
                        if (name != null) {
                            enabled.add(name);
                        }
                    }
                } else {
                    log.warn("MCP server '{}' not pre-loaded. Please add it to mcpServers list.", mcp);
                }
            }
        }

        if (enabled.isEmpty()) {
            // 能力差异 3：配置为空时参考实现产出"不带工具"，Java 框架对空列表理解为"不过滤"，
            // 故用哨兵名保证框架筛出空集（无任何回调名匹配该哨兵）。
            return List.of(EMPTY_TOOL_SENTINEL);
        }
        return new ArrayList<>(enabled);
    }

    /** 对应参考实现 {@code request.runtime.context.tools}。 */
    private static List<String> selectedTools(ModelRequest request) {
        BaseContext context = contextOf(request);
        return context == null ? null : context.getList("tools");
    }

    /** 对应参考实现 {@code request.runtime.context.mcps}。 */
    private static List<String> selectedMcps(ModelRequest request) {
        BaseContext context = contextOf(request);
        return context == null ? null : context.getList("mcps");
    }

    private static BaseContext contextOf(ModelRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object value = raw == null ? null : raw.get(ContextAwareInterceptor.CONTEXT_KEY);
        return value instanceof BaseContext context ? context : null;
    }

    private static String toolName(ToolCallback tool) {
        if (tool == null || tool.getToolDefinition() == null) {
            return null;
        }
        return tool.getToolDefinition().name();
    }

    /**
     * 把 MCP 工具承载类适配为框架 {@link ToolCallback}。
     *
     * <p>{@link McpTool} 是参考实现 langchain tool 的最小面（name/description/args_schema +
     * 调用），本工程用它作为数据面载体；此处按框架契约包装出
     * {@code getToolDefinition()} 与 {@code call(String)} 两个必需方法。
     */
    static List<ToolCallback> toCallbacks(List<McpTool> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (tools == null) {
            return callbacks;
        }
        for (McpTool tool : tools) {
            if (tool == null) {
                continue;
            }
            callbacks.add(new McpToolCallback(tool));
        }
        return callbacks;
    }

    /** {@link McpTool} → {@link ToolCallback} 的适配器。 */
    record McpToolCallback(McpTool tool) implements ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription() == null ? "" : tool.getDescription())
                    .inputSchema(inputSchema())
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return tool.call(toolInput);
        }

        /** MCP 工具的入参 schema（参考实现由 args_schema 承载；本工程透传其 JSON Schema）。 */
        private String inputSchema() {
            Map<String, Object> schema = tool.getArgsSchema();
            if (schema == null || schema.isEmpty()) {
                return "{\"type\":\"object\",\"properties\":{}}";
            }
            return com.alibaba.fastjson2.JSON.toJSONString(schema);
        }
    }
}
