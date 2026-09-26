package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.UserMcpMapper;
import com.wisesoft.ai.model.UserMcp;
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
 * MCP（Model Context Protocol）客户端管理：把用户登记的 MCP Server 的工具动态接入 Function Calling
 * 链路（"工具生态层"——用户可自行添加外部工具，无需改代码）。
 * <p>
 * <b>归属：每人连自己的服务</b>。原形态是「管理员在系统设置里配一个全局 JSON 数组 + 全局总开关」，
 * 现改为 {@code c_ai_user_mcp} 按 uid 登记，连接池也按 uid 分池——取工具时只 disasters 自己那份，
 * 不会因为别人连了什么服务而影响自己。
 * <p>
 * 连接策略（容错优先，MCP 故障绝不影响问答主链路）：
 * - 首次取工具时按该用户的配置懒连接；单个 server 连接/初始化失败仅告警并跳过；
 * - 该用户的配置发生变化（增删改/启停）由指纹比对自动重建连接（旧连接 closeGracefully）；
 * - 某个用户长时间不用（{@link #IDLE_EVICT_MILLIS}）其连接池会被关闭回收，避免长连接无限堆积。
 * <p>
 * 工具暴露：每个成功连接的 server 的工具列表转 {@link SyncMcpToolCallback}（Spring AI ToolCallback），
 * 由 {@code RagService.enabledToolCallbacks()} 合并进请求；工具名自动带 server 前缀防冲突（Spring AI 默认行为）。
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
    /** 每个用户最多接入的 server 数（防误配超长列表拖垮问答） */
    private static final int MAX_SERVERS = 10;
    /** 空闲连接池回收阈值：该用户的连接超过这个时长没被用过就关掉（SSE/streamable 是长连接，不能无限留着） */
    private static final long IDLE_EVICT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final UserMcpMapper userMcpMapper;

    /** 连接池按用户隔离：uid → 该用户的连接与状态 */
    private final Map<String, UserPool> pools = new ConcurrentHashMap<>();

    public McpClientService(UserMcpMapper userMcpMapper) {
        this.userMcpMapper = userMcpMapper;
    }

    /**
     * 返回某用户当前所有已启用 MCP 服务的工具（供 RagService.enabledToolCallbacks() 合并）。
     * 内部先按该用户的配置对齐连接（懒加载 + 配置变更重连），连接失败的 server 工具自动跳过。
     *
     * @param uid 归属用户（只取这个人的服务）
     */
    public List<ToolCallback> toolCallbacks(String uid) {
        return toolCallbacks(uid, null);
    }

    /**
     * 同上，但只取指定 server 的工具（智能体级「具体项筛选」）。
     *
     * @param uid         归属用户
     * @param onlyServers null=不筛选（该用户全部已启用服务）；空集合=一个都不取；非空=只取这些（按服务名）
     */
    public List<ToolCallback> toolCallbacks(String uid, java.util.Set<String> onlyServers) {
        if (onlyServers != null && onlyServers.isEmpty()) {
            return List.of(); // 智能体显式"不使用任何 MCP"：连接都不必建立
        }
        if (uid == null || uid.isBlank()) return List.of();
        ensureConnections(uid);
        UserPool pool = pools.get(uid);
        if (pool == null) return List.of();
        List<ToolCallback> callbacks = new ArrayList<>();
        synchronized (pool) {
            for (Map.Entry<String, McpSyncClient> entry : pool.clients.entrySet()) {
                if (onlyServers != null && !onlyServers.contains(entry.getKey())) continue;
                McpSyncClient client = entry.getValue();
                try {
                    for (McpSchema.Tool tool : client.listTools().tools()) {
                        callbacks.add(new SyncMcpToolCallback(client, tool));
                    }
                } catch (Exception e) {
                    log.warn("[MCP] uid={} 拉取 server {} 工具列表失败（跳过该 server）: {}",
                            uid, entry.getKey(), e.getMessage());
                    pool.states.put(entry.getKey(), "failed:" + e.getMessage());
                }
            }
        }
        return callbacks;
    }

    /**
     * 某用户的服务一览：每条服务的名称/地址/类型/启停/连接状态/可用工具。
     * 没登记服务时返回空列表（前端据此显示"还没有 MCP 服务"）。
     *
     * @param uid 归属用户
     */
    public List<Map<String, Object>> serverStatuses(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        ensureConnections(uid);
        UserPool pool = pools.get(uid);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (pool) {
            for (UserMcp row : pool.rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.getId());
                m.put("name", row.getName());
                m.put("url", row.getUrl());
                m.put("type", row.getType());
                m.put("enabled", Integer.valueOf(1).equals(row.getEnabled()));
                String st = pool.states.get(row.getName());
                // 停用不建立连接，也不该报"失败"——给出明确语义
                m.put("state", Integer.valueOf(1).equals(row.getEnabled()) ? (st == null ? "unknown" : st) : "disabled");
                m.put("connected", "connected".equals(st));
                List<Map<String, String>> tools = new ArrayList<>();
                McpSyncClient c = pool.clients.get(row.getName());
                if (c != null) {
                    try {
                        for (McpSchema.Tool t : c.listTools().tools()) {
                            tools.add(Map.of("name", t.name(), "description", brief(t.description())));
                        }
                    } catch (Exception ignore) {
                        // 拉列表失败不阻断状态展示（工具列表为空，连接状态仍以 states 为准）
                    }
                }
                m.put("tools", tools);
                m.put("toolCount", tools.size());
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 重连某个用户的全部服务（增删/改地址后想立刻生效时用；不重建也会在下一次取工具时按指纹自动生效）。
     */
    public void reload(String uid) {
        if (uid == null || uid.isBlank()) return;
        UserPool pool = pools.computeIfAbsent(uid, k -> new UserPool());
        synchronized (pool) {
            pool.fingerprint = ""; // 清指纹 → ensureConnections 重建
        }
        ensureConnections(uid);
    }

    /**
     * 临时连接测试（"添加服务"弹窗里用）：**不落配置、不进该用户的连接池**，连上取工具清单即关闭。
     * 用于"先测再存"，避免填错地址还得先保存再回来删。
     */
    public Map<String, Object> probe(String url, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null || url.isBlank()) {
            out.put("available", false);
            out.put("error", "地址为空");
            return out;
        }
        String t = (type == null || type.isBlank()) ? "streamable" : type;
        McpSyncClient client = null;
        try {
            client = connect("probe", url.trim(), t);
            client.initialize();
            List<Map<String, String>> tools = new ArrayList<>();
            for (McpSchema.Tool tool : client.listTools().tools()) {
                tools.add(Map.of("name", tool.name(), "description", brief(tool.description())));
            }
            out.put("available", true);
            out.put("tools", tools);
            out.put("toolCount", tools.size());
            return out;
        } catch (Exception e) {
            out.put("available", false);
            out.put("error", e.getMessage() == null ? "连接失败" : e.getMessage());
            return out;
        } finally {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Exception ignore) {
                    // 探测用连接，关闭失败无需处理
                }
            }
        }
    }

    /** 工具描述截断（界面展开用，避免超长描述撑坏卡片） */
    private static String brief(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 100 ? one.substring(0, 100) + "…" : one;
    }

    /** 按该用户当前配置对齐连接（懒加载；配置指纹变化才重建） */
    private void ensureConnections(String uid) {
        UserPool pool = pools.computeIfAbsent(uid, k -> new UserPool());
        evictIdlePools();
        synchronized (pool) {
            pool.lastAccess = System.currentTimeMillis();
            List<UserMcp> rows = userMcpMapper.selectList(new LambdaQueryWrapper<UserMcp>()
                    .eq(UserMcp::getUid, uid).orderByAsc(UserMcp::getCreateTime));
            if (rows.size() > MAX_SERVERS) {
                rows = rows.subList(0, MAX_SERVERS);
                log.warn("[MCP] uid={} 登记的 server 超过上限 {}，其余忽略", uid, MAX_SERVERS);
            }
            String fingerprint = fingerprintOf(rows);
            if (fingerprint.equals(pool.fingerprint)) {
                return; // 配置未变，沿用现有连接
            }
            closePool(pool);
            pool.fingerprint = fingerprint;
            pool.rows = new ArrayList<>(rows);
            for (UserMcp row : rows) {
                String name = row.getName();
                String url = row.getUrl();
                String type = row.getType() == null || row.getType().isBlank() ? "streamable" : row.getType();
                if (name == null || name.isBlank() || url == null || url.isBlank()) continue;
                if (!Integer.valueOf(1).equals(row.getEnabled())) {
                    pool.states.put(name, "disabled");   // 停用的服务直接跳过连接
                    continue;
                }
                try {
                    McpSyncClient client = connect(name, url, type);
                    client.initialize();
                    pool.clients.put(name, client);
                    pool.states.put(name, "connected");
                    log.info("[MCP] uid={} server {} ({}) 连接成功，工具 {} 个",
                            uid, name, type, client.listTools().tools().size());
                } catch (Exception e) {
                    pool.states.put(name, "failed:" + e.getMessage());
                    log.warn("[MCP] uid={} server {} ({}) 连接失败（跳过，不影响问答）: {}",
                            uid, name, type, e.getMessage());
                }
            }
        }
    }

    /** 关闭并清空某个连接池（保留行信息本身由调用方重建） */
    private void closePool(UserPool pool) {
        pool.clients.forEach((name, c) -> {
            try {
                c.closeGracefully();
            } catch (Exception ignore) {
                // 关闭失败不影响重建
            }
        });
        pool.clients.clear();
        pool.states.clear();
        pool.rows = List.of();
    }

    /**
     * 回收长时间未使用的用户连接池：SSE/streamable 都是有状态长连接，用户下线不再问答后
     * 不能一直挂着（尤其 SSE 会占用服务端连接槽）。仅回收空闲的，正在用的不受影响。
     */
    private void evictIdlePools() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, UserPool> e : pools.entrySet()) {
            UserPool pool = e.getValue();
            if (now - pool.lastAccess < IDLE_EVICT_MILLIS) continue;
            synchronized (pool) {
                if (now - pool.lastAccess < IDLE_EVICT_MILLIS) continue;
                closePool(pool);
                pool.fingerprint = "";
                pools.remove(e.getKey(), pool);
                log.info("[MCP] 用户 {} 的 MCP 连接池空闲超时已回收", e.getKey());
            }
        }
    }

    /** 配置指纹：服务名/地址/类型/启停任一变化都要重建连接 */
    private String fingerprintOf(List<UserMcp> rows) {
        StringBuilder sb = new StringBuilder();
        for (UserMcp r : rows) {
            sb.append(r.getName()).append('|').append(r.getUrl()).append('|')
                    .append(r.getType()).append('|').append(r.getEnabled()).append(";");
        }
        return sb.toString();
    }

    /** 按类型构建传输层并创建同步客户端（未 initialize） */
    private McpSyncClient connect(String name, String url, String type) {
        McpSchema.Implementation clientInfo = new McpSchema.Implementation("wen-qu", "1.0.0");
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

    /**
     * 单个用户的连接池：连接、状态与本次对齐的配置快照。
     * 所有读写都在 pool 监视器内（避免同一用户并发取工具时重复重连）。
     */
    private static final class UserPool {
        /** 本次对齐的配置指纹（变化即重建） */
        String fingerprint = "";
        /** 最近一次被使用的时间（闲置回收用） */
        long lastAccess = System.currentTimeMillis();
        /** name → 已连接客户端 */
        final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
        /** name → 连接状态（connected / failed:原因 / disabled） */
        final Map<String, String> states = new LinkedHashMap<>();
        /** 本次对齐的服务清单（状态展示按此顺序） */
        List<UserMcp> rows = new ArrayList<>();
    }
}
