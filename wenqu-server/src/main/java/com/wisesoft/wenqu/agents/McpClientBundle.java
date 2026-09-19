package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多服务器 MCP 客户端（参考实现 {@code langchain_mcp_adapters.client.MultiServerMCPClient}
 * 的等价物）。
 *
 * <p>参考实现的 {@code get_mcp_client(server_configs)} 构造第三方多服务器客户端，
 * 由 {@code client.get_tools()} 返回各服务器的全部工具。本类承载同一职责：
 * 按配置建传输 → 建 {@link McpSyncClient} → {@link #getTools()} 汇总各服务器工具。
 *
 * <h3>平台差异（必要替换，逐条说明）</h3>
 * <ul>
 *   <li><b>传输实现</b>：参考实现用 langchain 适配器；本工程用 MCP 官方 Java SDK
 *       （{@code io.modelcontextprotocol.sdk:mcp-core}）：{@code stdio} →
 *       {@link StdioClientTransport}、{@code sse} → {@link HttpClientSseClientTransport}、
 *       {@code streamable_http} → {@link HttpClientStreamableHttpTransport}。</li>
 *   <li><b>连接时机</b>：SDK 的 {@code McpClient.sync(transport).build()} 是<b>懒连接</b> ——
 *       首次操作（如 {@code listTools()}）才真正建连并完成 initialize 握手，连接失败在那一刻抛出
 *       （被调用方捕获后返回空工具列表，与参考实现「连接失败 → 日志 + 空列表」的对外行为一致）。</li>
 *   <li><b>超时</b>：{@code timeout}（秒）映射为客户端请求超时 + HTTP 连接超时；
 *       {@code sse_read_timeout} 在 Java SDK 的传输层没有独立对位参数（能力差异，如实标注）——
 *       该字段仍会原样随配置落库、随哈希参与缓存键计算。</li>
 *   <li><b>headers</b>：经传输的 {@code customizeRequest} 逐个写入请求头。</li>
 *   <li><b>clientInfo</b>：参考实现未显式设置（由适配器带默认实现标识）；本工程取自身标识
 *       {@link com.wisesoft.wenqu.common.AppVersion} 的 {@code PRODUCT_NAME} + 版本号
 *       （必要替换：客户端标识属产品标识）。</li>
 *   <li><b>传输类型缺省</b>：参考实现所依赖的适配器在缺 {@code transport} 键时按
 *       {@code streamable_http} 处理，此处保持一致（实际配置由
 *       {@code to_mcp_config} / 内置定义生成，恒带 transport）。</li>
 * </ul>
 */
public final class McpClientBundle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClientBundle.class);

    /** 缺省传输类型（参考实现所依赖适配器的缺省值）。 */
    private static final String DEFAULT_TRANSPORT = "streamable_http";

    private final Map<String, McpSyncClient> clients;

    private McpClientBundle(Map<String, McpSyncClient> clients) {
        this.clients = clients;
    }

    /**
     * 按 {@code {server_slug: config}} 建客户端。
     *
     * <p>任一服务器建连参数非法即抛出（由 {@code McpService.getMcpClient} 捕获后返回 null，
     * 与参考实现的 try/except 一致）。
     */
    public static McpClientBundle create(Map<String, Map<String, Object>> serverConfigs) {
        Map<String, McpSyncClient> clients = new LinkedHashMap<>();
        if (serverConfigs != null) {
            for (Map.Entry<String, Map<String, Object>> entry : serverConfigs.entrySet()) {
                clients.put(entry.getKey(), createClient(entry.getValue()));
            }
        }
        return new McpClientBundle(clients);
    }

    /** 建单个服务器的客户端。 */
    private static McpSyncClient createClient(Map<String, Object> config) {
        McpClientTransport transport = createTransport(config);
        McpClient.SyncSpec spec = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(
                        com.wisesoft.wenqu.common.AppVersion.PRODUCT_NAME,
                        com.wisesoft.wenqu.common.AppVersion.VERSION));
        Integer timeoutSeconds = intOf(config.get("timeout"));
        if (timeoutSeconds != null) {
            spec = spec.requestTimeout(Duration.ofSeconds(timeoutSeconds));
        }
        return spec.build();
    }

    /** 按 {@code transport} 建传输（stdio / sse / streamable_http）。 */
    private static McpClientTransport createTransport(Map<String, Object> config) {
        String transport = strOf(config.get("transport"));
        if (transport == null || transport.isEmpty()) {
            transport = DEFAULT_TRANSPORT;
        }
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        Integer timeoutSeconds = intOf(config.get("timeout"));
        return switch (transport) {
            case "stdio" -> {
                String command = strOf(config.get("command"));
                if (command == null || command.isEmpty()) {
                    throw new IllegalArgumentException("stdio 传输类型时，command 必填");
                }
                ServerParameters.Builder params = ServerParameters.builder(command);
                List<String> args = stringList(config.get("args"));
                if (!args.isEmpty()) {
                    params.args(args);
                }
                Map<String, String> env = stringMap(config.get("env"));
                if (!env.isEmpty()) {
                    params.env(env);
                }
                yield new StdioClientTransport(params.build(), jsonMapper);
            }
            case "sse" -> {
                HttpClientSseClientTransport.Builder builder =
                        HttpClientSseClientTransport.builder(requiredUrl(config));
                applyTimeoutAndHeaders(builder::connectTimeout, builder::customizeRequest, timeoutSeconds, config);
                yield builder.jsonMapper(jsonMapper).build();
            }
            case "streamable_http" -> {
                HttpClientStreamableHttpTransport.Builder builder =
                        HttpClientStreamableHttpTransport.builder(requiredUrl(config));
                applyTimeoutAndHeaders(builder::connectTimeout, builder::customizeRequest, timeoutSeconds, config);
                yield builder.jsonMapper(jsonMapper).build();
            }
            default -> throw new IllegalArgumentException("不支持的 MCP 传输类型: " + transport);
        };
    }

    /** SSE / streamable_http 共用的超时与请求头装配。 */
    private static void applyTimeoutAndHeaders(
            java.util.function.Consumer<Duration> connectTimeoutSetter,
            java.util.function.Consumer<java.util.function.Consumer<HttpRequest.Builder>> requestCustomizerSetter,
            Integer timeoutSeconds,
            Map<String, Object> config) {
        if (timeoutSeconds != null) {
            connectTimeoutSetter.accept(Duration.ofSeconds(timeoutSeconds));
        }
        Map<String, String> headers = stringMap(config.get("headers"));
        if (!headers.isEmpty()) {
            requestCustomizerSetter.accept(builder -> headers.forEach(builder::header));
        }
    }

    private static String requiredUrl(Map<String, Object> config) {
        String url = strOf(config.get("url"));
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("远程 MCP 传输类型时，url 必填");
        }
        return url;
    }

    /**
     * 汇总所有服务器的工具（对应 {@code client.get_tools()}）。
     *
     * <p>与参考实现一致：返回的是<b>未加工</b>的工具对象（原始名 + 描述 + 参数 schema），
     * 唯一标识与错误处理标志由服务层统一补写。
     */
    public List<McpTool> getTools() {
        List<McpTool> tools = new ArrayList<>();
        for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
            tools.addAll(listTools(entry.getKey(), entry.getValue()));
        }
        return tools;
    }

    /** 取单个服务器的工具。 */
    private static List<McpTool> listTools(String slug, McpSyncClient client) {
        List<McpTool> tools = new ArrayList<>();
        McpSchema.ListToolsResult result = client.listTools();
        if (result == null || result.tools() == null) {
            return tools;
        }
        for (McpSchema.Tool tool : result.tools()) {
            tools.add(new McpTool(tool.name(), tool.description(), argsSchemaOf(tool), client));
        }
        log.debug("Loaded {} tools from MCP server '{}'", tools.size(), slug);
        return tools;
    }

    /**
     * 工具的参数 schema。
     *
     * <p>对应参考实现的 {@code tool.args_schema.schema()}：MCP SDK 直接给出 {@code inputSchema}
     * （JSON schema），此处按 pydantic 产物同构地组装 {@code type / properties / required}。
     */
    private static Map<String, Object> argsSchemaOf(McpSchema.Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        McpSchema.JsonSchema input = tool.inputSchema();
        if (input == null) {
            return schema;
        }
        if (input.type() != null) {
            schema.put("type", input.type());
        }
        schema.put("properties", input.properties() == null ? new LinkedHashMap<>() : input.properties());
        schema.put("required", input.required() == null ? new ArrayList<>() : input.required());
        return schema;
    }

    /** 服务器数量（用于日志/自检）。 */
    public int size() {
        return clients.size();
    }

    /**
     * 关闭所有客户端。
     *
     * <p><b>注意</b>：参考实现在取完工具后从不关闭客户端（工具对象持有会话，需保持存活才能调用），
     * 故服务层正常路径<b>不调用</b>本方法；保留它只为显式释放（如测试与进程退出）。
     */
    @Override
    public void close() {
        for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception exc) {
                log.warn("关闭 MCP 客户端 '{}' 失败: {}", entry.getKey(), exc.getMessage());
            }
        }
    }

    // ==================== 配置取值（Python 动态类型 → 显式转换） ====================

    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 字符串列表取值（JSON 文本列亦接受）。 */
    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        Object parsed = value;
        if (value instanceof String text) {
            if (text.isBlank()) {
                return result;
            }
            try {
                parsed = JSON.parseArray(text, Object.class);
            } catch (Exception ignored) {
                return result;
            }
        }
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    /** 字符串表取值（JSON 文本列亦接受，非字符串值按 Python {@code str()} 语义转换）。 */
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        Object parsed = value;
        if (value instanceof String text) {
            if (text.isBlank()) {
                return result;
            }
            try {
                parsed = JSON.parseObject(text);
            } catch (Exception ignored) {
                return result;
            }
        }
        if (parsed instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }
}
