package com.wisesoft.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP（Model Context Protocol）客户端管理：把用户在设置页配置的外部 MCP Server 的工具
 * 动态接入 Function Calling 链路（"工具生态层"——用户可自行添加外部工具，无需改代码）。
 * <p>
 * 配置来源（c_ai_config，设置页可改）：
 * - {@code mcp.enabled}：总开关（默认关）；
 * - {@code mcp.servers}：JSON 数组 {@code [{"name":"xx","url":"http://host:port/path","type":"streamable|sse"}]}。
 * <p>
 * 连接策略（容错优先，MCP 故障绝不影响问答主链路）：
 * - 服务启动后首次使用时按配置懒连接；单个 server 连接/初始化失败仅告警并跳过；
 * - 配置变更通过 {@link #reload()} 重建（旧连接 closeGracefully）；
 * - 连接结果缓存（成功/失败），失败带冷却：{@link #connectionStates()} 供管理接口/状态展示。
 * <p>
 * 工具暴露：每个成功连接的 server 的工具列表转 {@link SyncMcpToolCallback}（Spring AI ToolCallback），
 * 由 {@code RagService.enabledTools()} 合并进请求；工具名自动带 server 前缀防冲突（Spring AI 默认行为）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class McpClientService {

    /** 连接超时（秒） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 工具调用请求超时（秒） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 最多接入的 server 数（防误配超长数组拖垮启动/问答） */
    private static final int MAX_SERVERS = 10;

    private final ConfigService configService;

    /** name → 已连接客户端（reload 时整体替换） */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    /** name → 连接状态（connected / failed:原因 / disabled），供状态查询与管理界面展示 */
    private final Map<String, String> states = new ConcurrentHashMap<>();
    /** 配置指纹：servers JSON 变化才触发 reload */
    private volatile String lastConfigFingerprint = "";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpClientService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 返回当前所有 MCP 工具（供 RagService.enabledTools() 合并）。
     * 内部先按配置对齐连接（懒加载 + 配置变更重连），连接失败的 server 工具自动跳过。
     */
    public List<ToolCallback> toolCallbacks() {
        if (!configService.getBoolean("mcp.enabled")) {
            return List.of();
        }
        ensureConnections();
        List<ToolCallback> callbacks = new ArrayList<>();
        for (McpSyncClient client : clients.values()) {
            try {
                List<McpSchema.Tool> tools = client.listTools().tools();
                for (McpSchema.Tool tool : tools) {
                    callbacks.add(new SyncMcpToolCallback(client, tool));
                }
            } catch (Exception e) {
                String name = client.getServerInfo() != null ? client.getServerInfo().name() : "unknown";
                log.warn("[MCP] 拉取 server {} 工具列表失败（跳过该 server）: {}", name, e.getMessage());
                states.put(name, "failed:" + e.getMessage());
            }
        }
        return callbacks;
    }

    /** 当前连接状态快照（name → connected/failed:…/disabled），供设置页/管理接口展示 */
    public Map<String, String> connectionStates() {
        ensureConnections();
        return new LinkedHashMap<>(states);
    }

    /** 配置变更后强制重建全部连接（设置页保存 mcp.* 后调用或下次取工具时按指纹自动触发） */
    public synchronized void reload() {
        lastConfigFingerprint = ""; // 清指纹 → 下次 ensureConnections 重建
        ensureConnections();
    }

    /** 按当前配置对齐连接（懒加载；配置指纹变化才重建） */
    private synchronized void ensureConnections() {
        String serversJson = configService.get("mcp.servers");
        String fingerprint = configService.getBoolean("mcp.enabled") + "|" + serversJson;
        if (fingerprint.equals(lastConfigFingerprint)) {
            return; // 配置未变，沿用现有连接
        }
        // 1) 关闭旧连接
        clients.forEach((name, c) -> {
            try {
                c.closeGracefully();
            } catch (Exception ignore) { /* 关闭失败不影响重建 */ }
        });
        clients.clear();
        states.clear();
        lastConfigFingerprint = fingerprint;
        // 2) 解析配置
        if (!configService.getBoolean("mcp.enabled") || serversJson == null || serversJson.isBlank()) {
            return;
        }
        List<Map<String, String>> servers = parseServers(serversJson);
        // 3) 逐个连接（单个失败不影响其他）
        for (Map<String, String> s : servers) {
            String name = s.get("name");
            String url = s.get("url");
            String type = s.getOrDefault("type", "streamable");
            if (name == null || name.isBlank() || url == null || url.isBlank()) {
                continue;
            }
            try {
                McpSyncClient client = connect(name, url, type);
                client.initialize();
                clients.put(name, client);
                states.put(name, "connected");
                log.info("[MCP] server {} ({}) 连接成功，工具 {} 个", name, type,
                        client.listTools().tools().size());
            } catch (Exception e) {
                states.put(name, "failed:" + e.getMessage());
                log.warn("[MCP] server {} ({}) 连接失败（跳过，不影响问答）: {}", name, type, e.getMessage());
            }
        }
    }

    /** 按类型构建传输层并创建同步客户端（未 initialize） */
    private McpSyncClient connect(String name, String url, String type) {
        McpSchema.Implementation clientInfo = new McpSchema.Implementation("ai-doc-assistant", "1.0.0");
        McpClient.SyncSpec spec = McpClient.sync(buildTransport(url, type))
                .clientInfo(clientInfo)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(CONNECT_TIMEOUT);
        return spec.build();
    }

    /** streamable（默认）与 sse 两种传输；其余值按 streamable 处理 */
    private io.modelcontextprotocol.spec.McpClientTransport buildTransport(String url, String type) {
        if ("sse".equalsIgnoreCase(type)) {
            // SSE：url 拆 base + sse 端点（默认 /sse）
            String base = url, ssePath = "/sse";
            int idx = url.indexOf("://");
            int pathStart = idx > 0 ? url.indexOf('/', idx + 3) : -1;
            if (pathStart > 0) {
                base = url.substring(0, pathStart);
                String path = url.substring(pathStart);
                ssePath = path.endsWith("/sse") ? path : path + "/sse";
                // 完整 URL 已含端点时直接用
                if (path.endsWith("/sse")) {
                    ssePath = path;
                }
            }
            String finalBase = base;
            return HttpClientSseClientTransport.builder(finalBase).sseEndpoint(ssePath).build();
        }
        // streamable（默认）：builder 拆 baseUri + endpoint(path)，二者拼接为完整请求地址；
        // url 可能带路径（http://host:port/mcp）也可能不带（http://host:port，默认端点 /mcp）
        String baseUri = url;
        String endpoint = "/mcp";
        int schemeIdx = url.indexOf("://");
        int pathStart = schemeIdx > 0 ? url.indexOf('/', schemeIdx + 3) : -1;
        if (pathStart > 0) {
            baseUri = url.substring(0, pathStart);
            endpoint = url.substring(pathStart);
        }
        return HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpoint)
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .build();
    }

    /** 解析 servers JSON 数组；元素仅取 name/url/type 三个字符串字段；畸形配置返回空列表（不抛错） */
    private List<Map<String, String>> parseServers(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.warn("[MCP] mcp.servers 配置不是 JSON 数组，忽略（当前值前 80 字符: {}）",
                        json.substring(0, Math.min(80, json.length())));
                return List.of();
            }
            List<Map<String, String>> out = new ArrayList<>();
            int i = 0;
            for (JsonNode n : root) {
                if (++i > MAX_SERVERS) {
                    log.warn("[MCP] servers 超过上限 {}，其余忽略", MAX_SERVERS);
                    break;
                }
                Map<String, String> s = new LinkedHashMap<>();
                s.put("name", n.path("name").asText(null));
                s.put("url", n.path("url").asText(null));
                s.put("type", n.path("type").asText("streamable"));
                out.add(s);
            }
            return out;
        } catch (Exception e) {
            log.warn("[MCP] mcp.servers 解析失败（忽略）: {}", e.getMessage());
            return List.of();
        }
    }
}
