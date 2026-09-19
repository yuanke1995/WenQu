package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.MCPServer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置的投影与序列化。
 *
 * <p>由参考实现的三处逐字翻译：
 * <ul>
 *   <li>{@link #toDict(MCPServer)} ← {@code storage/postgres/models_business.py} 的
 *       {@code MCPServer.to_dict()}（键顺序与默认值逐字对齐）</li>
 *   <li>{@link #toMcpConfig(MCPServer)} ← 同上的 {@code MCPServer.to_mcp_config()}</li>
 *   <li>{@link #serializeMcpServer(MCPServer)} ← {@code server/routers/mcp_router.py} 的
 *       {@code serialize_mcp_server}（补 {@code is_builtin} / {@code requires_migration}，
 *       需迁移时把 {@code enabled} 压成 false）</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：
 * <ul>
 *   <li><b>JSON 列的反序列化</b>：参考实现的 {@code args/env/headers/tags/disabled_tools} 是
 *       ORM 的 JSON 列，取值即 list/dict；本工程实体把它们映射为文本列，故此处统一做
 *       「文本 → 结构化值」的反序列化（空值/非法值按空容器处理，与 {@code x or []}/{@code x or {}} 一致）。</li>
 *   <li><b>模型方法的位置</b>：参考实现把 {@code to_dict}/{@code to_mcp_config} 写成 ORM 模型方法；
 *       本工程的 {@code models/} 只放纯实体，故投影集中在本类（与
 *       {@code knowledge/KnowledgeFileViews} 同一约定）。</li>
 *   <li><b>时间格式</b>：{@code format_utc_datetime} → {@link DateTimeUtils#formatUtcDatetime}。</li>
 * </ul>
 */
public final class McpServerViews {

    private McpServerViews() {}

    /** {@code MCPServer.to_dict()}：全字段投影（键顺序逐字对齐参考实现）。 */
    public static Map<String, Object> toDict(MCPServer server) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", server.getId());
        data.put("slug", server.getSlug());
        data.put("name", server.getName());
        data.put("description", server.getDescription());
        data.put("transport", server.getTransport());
        data.put("url", server.getUrl());
        data.put("command", server.getCommand());
        data.put("args", listOrEmpty(server.getArgs()));
        data.put("env", mapOrEmpty(server.getEnv()));
        data.put("headers", mapOrEmpty(server.getHeaders()));
        data.put("timeout", server.getTimeout());
        data.put("sse_read_timeout", server.getSseReadTimeout());
        data.put("tags", listOrEmpty(server.getTags()));
        data.put("icon", server.getIcon());
        data.put("enabled", server.getEnabled() != null && server.getEnabled() != 0);
        data.put("disabled_tools", listOrEmpty(server.getDisabledTools()));
        data.put("created_by", server.getCreatedBy());
        data.put("updated_by", server.getUpdatedBy());
        data.put("created_at", DateTimeUtils.formatUtcDatetime(server.getCreatedAt()));
        data.put("updated_at", DateTimeUtils.formatUtcDatetime(server.getUpdatedAt()));
        return data;
    }

    /**
     * {@code MCPServer.to_mcp_config()}：运行时可用的连接配置。
     *
     * <p>逐条对齐参考实现：只带该传输类型适用的键；{@code args/env/headers} 支持
     * 「已是结构化值」与「JSON 文本」两种来源；{@code timeout}/{@code sse_read_timeout}
     * 为 null 时不出现；{@code disabled_tools} 非空才出现。
     */
    public static Map<String, Object> toMcpConfig(MCPServer server) {
        String transport = server.getTransport();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("transport", transport);

        boolean remote = "sse".equals(transport) || "streamable_http".equals(transport);

        if (remote && notBlank(server.getUrl())) {
            config.put("url", server.getUrl());
        }
        if ("stdio".equals(transport)) {
            if (notBlank(server.getCommand())) {
                config.put("command", server.getCommand());
            }
            Object args = parsed(server.getArgs());
            if (isTruthy(args)) {
                config.put("args", args);
            }
            Object env = parsed(server.getEnv());
            if (isTruthy(env)) {
                config.put("env", env);
            }
        }
        // headers 只用于 sse/streamable_http 传输类型
        if (remote) {
            Object headers = parsed(server.getHeaders());
            if (isTruthy(headers)) {
                config.put("headers", headers);
            }
        }
        if (server.getTimeout() != null) {
            config.put("timeout", server.getTimeout());
        }
        if (server.getSseReadTimeout() != null) {
            config.put("sse_read_timeout", server.getSseReadTimeout());
        }
        Object disabledTools = parsed(server.getDisabledTools());
        if (isTruthy(disabledTools)) {
            config.put("disabled_tools", disabledTools);
        }
        return config;
    }

    /** {@code serialize_mcp_server}：{@link #toDict} 之上补内置/迁移状态。 */
    public static Map<String, Object> serializeMcpServer(MCPServer server) {
        Map<String, Object> data = toDict(server);
        data.put("is_builtin", McpService.isBuiltinMcpServer(server));
        boolean requiresMigration = McpService.requiresMcpStdioMigration(server);
        data.put("requires_migration", requiresMigration);
        if (requiresMigration) {
            data.put("enabled", false);
        }
        return data;
    }

    // ==================== JSON 列的反序列化（缺省空容器，不抛异常） ====================

    /**
     * 把 JSON 列文本解析为结构化值；空值/非法值 → null。
     *
     * <p>参考实现的这些列是 ORM 的 {@code JSON} 列，取值已是 list/dict；本工程为文本列，
     * 故统一先解析再套用参考实现原有的真值判断（{@code if self.args:} 等）。
     * 非法文本 → null，与参考实现 {@code try: json.loads(...) except JSONDecodeError: pass}
     * 的效果一致（键不出现）。
     */
    public static Object parsed(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Python 真值语义（{@code bool(value)}）：空容器/空串/0/false/null 为假，其余为真。 */
    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    /** 字符串列表列；解析后为假值时回落空表（对应 {@code self.args or []}）。 */
    public static List<String> parseStringList(String json) {
        Object value = parsed(json);
        List<String> result = new ArrayList<>();
        if (!isTruthy(value)) {
            return result;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return result;
    }

    /** 对象列；解析后为假值时回落空表（对应 {@code self.env or {}}）。 */
    public static Map<String, Object> parseObjectMap(String json) {
        Object value = parsed(json);
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isTruthy(value)) {
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return result;
    }

    /** 列表列取值（缺省空表）。 */
    private static List<String> listOrEmpty(String json) {
        return parseStringList(json);
    }

    /** 对象列取值（缺省空表）。 */
    private static Map<String, Object> mapOrEmpty(String json) {
        return parseObjectMap(json);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }
}
