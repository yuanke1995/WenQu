package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.agents.McpBuiltinImmutableException;
import com.wisesoft.wenqu.agents.MCPServerNotFoundException;
import com.wisesoft.wenqu.agents.McpServerViews;
import com.wisesoft.wenqu.agents.McpService;
import com.wisesoft.wenqu.agents.McpTool;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.RequestUser;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.MCPServer;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 服务器管理路由，逐端点对齐参考实现 {@code server/routers/mcp_router.py}
 * （路由器前缀 {@code /system/mcp-servers}，参考实现聚合后对外为 {@code /api/system/mcp-servers}）。
 *
 * <p>10 个端点（与对拍前端 {@code web/src/apis/mcp_api.js} 的 10 处调用一一对应）：
 * <pre>
 * GET    /api/system/mcp-servers                        列表（登录用户；普通用户脱敏）
 * POST   /api/system/mcp-servers                        新建（管理员）
 * GET    /api/system/mcp-servers/{slug}                 详情（管理员）
 * PUT    /api/system/mcp-servers/{slug}                 更新（管理员）
 * DELETE /api/system/mcp-servers/{slug}                 删除（管理员）
 * POST   /api/system/mcp-servers/{slug}/test            连通性测试（管理员）
 * PUT    /api/system/mcp-servers/{slug}/status          启用/停用（管理员）
 * GET    /api/system/mcp-servers/{slug}/tools           工具清单（管理员）
 * POST   /api/system/mcp-servers/{slug}/tools/refresh   刷新工具列表（管理员）
 * PUT    /api/system/mcp-servers/{slug}/tools/{tool_name}/toggle  单工具启用开关（管理员）
 * </pre>
 *
 * <p>成功响应体为参考实现的普通字典（<b>不是</b>本产品既有 {@code ResultJson} 契约）：
 * 列表/详情/新建/更新为 {@code {"success": true, "data": ...}}，状态切换额外带
 * {@code enabled} 与 {@code message}，删除为 {@code {"success": true, "message": ...}}，
 * 连通性测试与工具刷新为 {@code {"success": true, "message": ..., "tool_count": ...}}。
 * 失败响应体由 {@link ApiHttpException} → {@code {"detail": ...}}。
 *
 * <h3>平台差异（必要替换，不影响状态码与文案）</h3>
 * <ul>
 *   <li>{@code Depends(get_admin_user / get_required_user)} → {@link AuthGuards} 的
 *       {@code requireAdmin()} / {@code requireUser()}（方法首行显式调用），并按 uid 载入
 *       {@link User} 取 {@code username}（参考实现把 ORM 实体注入路由）。</li>
 *   <li>{@code except Exception as e → HTTPException(500, detail=str(e))}：Python 的 {@code str(e)}
 *       恒为字符串；Java 异常的 message 可能为 null，故 {@link #errorDetail(Exception)}
 *       回落到异常类名（保证 detail 非 null）。</li>
 *   <li>pydantic 请求模型的 {@code extra="forbid"} / 必填 / 类型校验 → 方法内显式校验并抛 422
 *       （本工程既有路由层处理方式；参考实现的 422 响应体是结构化列表，此处为文本 detail，
 *       这一差异与 {@code KnowledgeEvalController} 的标注一致）。</li>
 *   <li>{@code MCPServerNotFoundError} / {@code PermissionError} / {@code ValueError} 的捕获顺序
 *       照搬不动：{@code MCPServerNotFoundException} 是 {@code IllegalArgumentException} 的子类，
 *       必须先捕（否则 404 会被降级成 400）；{@code PermissionError} →
 *       {@link McpBuiltinImmutableException}（Java 无内建类型）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/system/mcp-servers")
@RequiredArgsConstructor
@Tag(name = "mcp", description = "MCP 服务器管理（配置、启用开关、工具清单）")
public class McpController {

    /** {@code CreateMcpServerRequest} 的字段白名单（对应 pydantic 声明，extra=forbid）。 */
    private static final Set<String> CREATE_FIELDS = Set.of(
            "slug", "name", "transport", "url", "description", "headers", "timeout", "sse_read_timeout", "tags", "icon");

    /** {@code CreateMcpServerRequest} 的必填字段。 */
    private static final List<String> CREATE_REQUIRED = List.of("slug", "name", "transport");

    /** {@code UpdateMcpServerRequest} 的字段白名单（无 slug —— 改 slug 是 422，不是 400）。 */
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "name", "transport", "url", "description", "headers", "timeout", "sse_read_timeout", "tags", "icon");

    /** 参考实现里创建/更新路由显式接受的传输类型（与 service 层的可自建集合分别声明，照搬）。 */
    private static final List<String> VALID_TRANSPORTS = List.of("sse", "streamable_http");

    /** {@code UpdateMcpServerStatusRequest} 的字段白名单。 */
    private static final Set<String> STATUS_FIELDS = Set.of("enabled");

    private final McpService mcpService;

    private final UserRepository userRepository;

    // =========================================================================
    // === MCP 服务器 CRUD ===
    // =========================================================================

    /** 获取所有 MCP 服务器配置（普通用户仅获取脱敏的基础信息）。 */
    @GetMapping
    @Operation(summary = "获取所有 MCP 服务器配置", description = "响应 {\"success\":true,\"data\":[...]}")
    public Map<String, Object> getMcpServers() {
        AuthGuards.requireUser();
        try {
            List<MCPServer> servers = mcpService.getAllMcpServers();
            if (AuthService.isAdminRole(RequestUser.role())) {
                List<Map<String, Object>> data = new ArrayList<>();
                for (MCPServer server : servers) {
                    data.add(McpServerViews.serializeMcpServer(server));
                }
                return success(data);
            }

            List<Map<String, Object>> data = new ArrayList<>();
            for (MCPServer server : servers) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", nullSafe(server.getName()));
                item.put("description", server.getDescription());
                item.put("icon", server.getIcon());
                item.put("enabled", enabledFlag(server) && !McpService.requiresMcpStdioMigration(server));
                Object tags = McpServerViews.parsed(server.getTags());
                item.put("tags", tags instanceof List<?> list ? list : new ArrayList<>());
                data.add(item);
            }
            return success(data);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get MCP servers: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 创建新的 MCP 服务器。 */
    @PostMapping
    @Operation(summary = "创建 MCP 服务器", description = "响应 {\"success\":true,\"data\":{...}}")
    public Map<String, Object> createMcpServerRoute(@RequestBody(required = false) Map<String, Object> rawBody) {
        User currentUser = requireAdminUser();
        Map<String, Object> body = validateBody(rawBody, "CreateMcpServerRequest", CREATE_FIELDS, CREATE_REQUIRED);

        String transport = requireString(body, "transport", true);
        // 校验传输类型
        if (!VALID_TRANSPORTS.contains(transport)) {
            throw new ApiHttpException(400, "传输类型必须是 " + String.join(", ", VALID_TRANSPORTS) + " 之一");
        }
        // 根据传输类型校验必填字段
        String url = requireString(body, "url", false);
        if (url == null || url.isEmpty()) {
            throw new ApiHttpException(400, "传输类型为 " + transport + " 时，url 必填");
        }

        try {
            MCPServer server = mcpService.createMcpServer(
                    requireString(body, "slug", true),
                    requireString(body, "name", true),
                    transport,
                    url,
                    requireString(body, "description", false),
                    requireObject(body, "headers"),
                    requireInteger(body, "timeout"),
                    requireInteger(body, "sse_read_timeout"),
                    requireList(body, "tags"),
                    requireString(body, "icon", false),
                    currentUser.getUsername());
            return success(McpServerViews.serializeMcpServer(server));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, errorDetail(exc));
        } catch (Exception exc) {
            log.error("Failed to create MCP server: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 获取单个 MCP 服务器配置。 */
    @GetMapping("/{slug}")
    @Operation(summary = "获取单个 MCP 服务器配置", description = "响应 {\"success\":true,\"data\":{...}}")
    public Map<String, Object> getMcpServerRoute(@PathVariable("slug") String slug) {
        requireAdminUser();
        try {
            MCPServer server = getServerOr404(slug);
            return success(McpServerViews.serializeMcpServer(server));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get MCP server: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 更新 MCP 服务器配置。 */
    @PutMapping("/{slug}")
    @Operation(summary = "更新 MCP 服务器配置", description = "响应 {\"success\":true,\"data\":{...}}")
    public Map<String, Object> updateMcpServerRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> rawBody) {
        User currentUser = requireAdminUser();
        Map<String, Object> body = validateBody(rawBody, "UpdateMcpServerRequest", UPDATE_FIELDS, List.of());

        String transport = requireString(body, "transport", false);
        // 校验传输类型
        if (transport != null && !VALID_TRANSPORTS.contains(transport)) {
            throw new ApiHttpException(400, "传输类型必须是 " + String.join(", ", VALID_TRANSPORTS) + " 之一");
        }

        try {
            MCPServer server = mcpService.updateMcpServer(
                    slug,
                    requireString(body, "name", false),
                    requireString(body, "description", false),
                    transport,
                    requireString(body, "url", false),
                    requireObject(body, "headers"),
                    requireInteger(body, "timeout"),
                    requireInteger(body, "sse_read_timeout"),
                    requireList(body, "tags"),
                    requireString(body, "icon", false),
                    currentUser.getUsername());
            return success(McpServerViews.serializeMcpServer(server));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (MCPServerNotFoundException exc) {
            throw new ApiHttpException(404, errorDetail(exc));
        } catch (McpBuiltinImmutableException exc) {
            throw new ApiHttpException(403, errorDetail(exc));
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, errorDetail(exc));
        } catch (Exception exc) {
            log.error("Failed to update MCP server: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 删除 MCP 服务器。 */
    @DeleteMapping("/{slug}")
    @Operation(summary = "删除 MCP 服务器", description = "响应 {\"success\":true,\"message\":\"...\"}")
    public Map<String, Object> deleteMcpServerRoute(@PathVariable("slug") String slug) {
        requireAdminUser();
        try {
            // 检查是否为系统内置服务器
            MCPServer server = mcpService.getMcpServer(slug);
            if (server != null && McpService.isBuiltinMcpServer(server)) {
                throw new ApiHttpException(403, "系统内置的 MCP 服务器无法删除");
            }

            boolean deleted = mcpService.deleteMcpServer(slug);
            if (!deleted) {
                throw new ApiHttpException(404, "服务器 '" + slug + "' 不存在");
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "服务器 '" + slug + "' 已删除");
            return response;
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to delete MCP server: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    // =========================================================================
    // === MCP 服务器操作 ===
    // =========================================================================

    /** 测试 MCP 服务器连接。 */
    @PostMapping("/{slug}/test")
    @Operation(summary = "测试 MCP 服务器连接", description = "响应 {\"success\":true,\"message\":\"...\",\"tool_count\":n}")
    public Map<String, Object> testMcpServer(@PathVariable("slug") String slug) {
        requireAdminUser();
        try {
            MCPServer server = getServerOr404(slug);
            ensureMcpServerRunnable(server);

            int count;
            try {
                count = mcpService.getAllMcpTools(slug).size();
            } catch (ApiHttpException exc) {
                throw exc;
            } catch (Exception testError) {
                throw new ApiHttpException(500, "连接失败: " + errorDetail(testError));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "连接成功，共发现 " + count + " 个工具");
            response.put("tool_count", count);
            return response;
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to test MCP server: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 更新 MCP 服务器启用状态。 */
    @PutMapping("/{slug}/status")
    @Operation(summary = "更新 MCP 服务器启用状态", description = "响应 {\"success\":true,\"enabled\":bool,\"data\":{...}}")
    public Map<String, Object> updateMcpServerStatusRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> rawBody) {
        User currentUser = requireAdminUser();
        Map<String, Object> body = validateBody(
                rawBody, "UpdateMcpServerStatusRequest", STATUS_FIELDS, List.of("enabled"));
        boolean enabled = requireBoolean(body, "enabled");

        try {
            McpService.EnabledResult result = mcpService.setServerEnabled(slug, enabled, currentUser.getUsername());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("enabled", result.enabled());
            response.put("data", McpServerViews.serializeMcpServer(result.server()));
            response.put("message", "MCP '" + slug + "' 已" + (result.enabled() ? "添加" : "移除"));
            return response;
        } catch (MCPServerNotFoundException exc) {
            throw new ApiHttpException(404, errorDetail(exc));
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, errorDetail(exc));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to toggle MCP server: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    // =========================================================================
    // === MCP 工具管理 ===
    // =========================================================================

    /** 获取 MCP 服务器的工具列表。 */
    @GetMapping("/{slug}/tools")
    @Operation(summary = "获取 MCP 服务器工具列表", description = "响应 {\"success\":true,\"data\":[...],\"total\":n}")
    public Map<String, Object> getMcpServerTools(@PathVariable("slug") String slug) {
        requireAdminUser();
        try {
            MCPServer server = getServerOr404(slug);
            ensureMcpServerRunnable(server);
            List<String> disabledTools = McpServerViews.parseStringList(server.getDisabledTools());

            try {
                // 获取所有工具（不过滤 disabled_tools）
                List<McpTool> tools = mcpService.getAllMcpTools(slug);
                List<Map<String, Object>> toolList = new ArrayList<>();

                for (McpTool tool : tools) {
                    String originalName = tool.getName();
                    Map<String, Object> metadata = tool.getMetadata();
                    Object uniqueId = metadata == null || metadata.isEmpty() ? null : metadata.get("id");

                    Map<String, Object> toolInfo = new LinkedHashMap<>();
                    toolInfo.put("name", originalName);
                    toolInfo.put("id", uniqueId == null ? originalName : uniqueId);
                    toolInfo.put("description", tool.getDescription() == null ? "" : tool.getDescription());
                    toolInfo.put("enabled", !disabledTools.contains(originalName));

                    // 提取参数信息
                    Map<String, Object> schema = tool.getArgsSchema();
                    if (schema != null && !schema.isEmpty()) {
                        Object properties = schema.get("properties");
                        Object required = schema.get("required");
                        toolInfo.put("parameters", properties instanceof Map<?, ?> map ? map : new LinkedHashMap<>());
                        toolInfo.put("required", required instanceof List<?> list ? list : new ArrayList<>());
                    } else {
                        toolInfo.put("parameters", new LinkedHashMap<>());
                        toolInfo.put("required", new ArrayList<>());
                    }
                    toolList.add(toolInfo);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("data", toolList);
                response.put("total", toolList.size());
                return response;
            } catch (ApiHttpException exc) {
                throw exc;
            } catch (Exception toolError) {
                log.error("Failed to get tools from MCP server '{}': {}", slug, toolError.getMessage());
                throw new ApiHttpException(500, "获取工具失败: " + errorDetail(toolError));
            }
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get MCP server tools: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 刷新 MCP 服务器的工具列表（清除缓存重新获取）。 */
    @PostMapping("/{slug}/tools/refresh")
    @Operation(summary = "刷新 MCP 服务器工具列表", description = "响应 {\"success\":true,\"message\":\"...\",\"tool_count\":n}")
    public Map<String, Object> refreshMcpServerTools(@PathVariable("slug") String slug) {
        requireAdminUser();
        try {
            MCPServer server = getServerOr404(slug);
            ensureMcpServerRunnable(server);

            try {
                // 获取所有工具（不过滤 disabled_tools）
                List<McpTool> tools = mcpService.getAllMcpTools(slug);

                // 获取统计信息
                Map<String, Integer> stats = mcpService.getMcpToolsStats(slug);
                int enabledCount = stats != null && stats.get("enabled") != null
                        ? stats.get("enabled") : tools.size();
                int disabledCount = stats != null && stats.get("disabled") != null
                        ? stats.get("disabled") : 0;

                String message = "工具列表已刷新";
                if (disabledCount > 0) {
                    message += "，" + enabledCount + " 个已启用，" + disabledCount + " 个已禁用";
                } else {
                    message += "，共发现 " + enabledCount + " 个工具";
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("message", message);
                response.put("tool_count", enabledCount);
                response.put("enabled_count", enabledCount);
                response.put("disabled_count", disabledCount);
                return response;
            } catch (ApiHttpException exc) {
                throw exc;
            } catch (Exception toolError) {
                throw new ApiHttpException(500, "刷新失败: " + errorDetail(toolError));
            }
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to refresh MCP server tools: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    /** 切换单个工具的启用状态。 */
    @PutMapping("/{slug}/tools/{tool_name}/toggle")
    @Operation(summary = "切换 MCP 工具启用状态", description = "响应 {\"success\":true,\"tool_name\":\"...\",\"enabled\":bool}")
    public Map<String, Object> toggleMcpServerToolRoute(
            @PathVariable("slug") String slug, @PathVariable("tool_name") String toolName) {
        User currentUser = requireAdminUser();
        try {
            McpService.EnabledResult result =
                    mcpService.toggleToolEnabled(slug, toolName, currentUser.getUsername());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("tool_name", toolName);
            response.put("enabled", result.enabled());
            response.put("message", "工具 '" + toolName + "' 已" + (result.enabled() ? "启用" : "禁用"));
            return response;
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (IllegalArgumentException exc) {
            // 参考实现此处把 ValueError（含「服务器不存在」）统一映射为 404
            throw new ApiHttpException(404, errorDetail(exc));
        } catch (Exception exc) {
            log.error("Failed to toggle MCP server tool: {}", exc.getMessage());
            throw new ApiHttpException(500, errorDetail(exc));
        }
    }

    // =========================================================================
    // === 辅助 ===
    // =========================================================================

    /** 取服务器或 404（对应 {@code get_server_or_404}）。 */
    private MCPServer getServerOr404(String slug) {
        MCPServer server = mcpService.getMcpServer(slug);
        if (server == null) {
            throw new ApiHttpException(404, "服务器 '" + slug + "' 不存在");
        }
        return server;
    }

    /** 拒绝连接尚未迁移的历史用户 stdio MCP（对应 {@code ensure_mcp_server_runnable}）。 */
    private static void ensureMcpServerRunnable(MCPServer server) {
        if (McpService.requiresMcpStdioMigration(server)) {
            throw new ApiHttpException(400, "历史 stdio MCP 已被禁用，请先迁移为远程 MCP");
        }
    }

    /** 参考实现 {@code get_admin_user}：管理员 + 载入用户实体（取 username 写审计字段）。 */
    private User requireAdminUser() {
        String uid = AuthGuards.requireAdmin();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** {@code {"success": true, "data": ...}}。 */
    private static Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /**
     * 请求体校验（pydantic 请求模型的等价物）。
     *
     * <p>与参考实现的三条约束对齐：{@code extra="forbid"} → 未声明字段 422；
     * 必填字段缺失 → 422；类型不符 → 422。
     */
    private static Map<String, Object> validateBody(
            Map<String, Object> body, String modelName, Set<String> allowed, List<String> required) {
        Map<String, Object> value = body == null ? new LinkedHashMap<>() : body;
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw new ApiHttpException(422, modelName + " 不允许的字段: " + key);
            }
        }
        for (String field : required) {
            if (!value.containsKey(field) || value.get(field) == null) {
                throw new ApiHttpException(422, modelName + " 缺少必填字段: " + field);
            }
        }
        return value;
    }

    /** 取字符串字段；{@code required} 时缺失/类型不符 → 422。 */
    private static String requireString(Map<String, Object> body, String field, boolean required) {
        Object value = body.get(field);
        if (value == null) {
            if (required) {
                throw new ApiHttpException(422, field + " 不能为空");
            }
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ApiHttpException(422, field + " 必须是字符串");
        }
        return text;
    }

    /** 取整数字段（pydantic 的 int 兼容数字字符串）。 */
    private static Integer requireInteger(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            throw new ApiHttpException(422, field + " 必须是整数");
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                throw new ApiHttpException(422, field + " 必须是整数");
            }
        }
        throw new ApiHttpException(422, field + " 必须是整数");
    }

    /** 取布尔字段。 */
    private static boolean requireBoolean(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ApiHttpException(422, field + " 必须是布尔值");
    }

    /** 取对象字段（对应 pydantic 的 {@code dict | None}）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ApiHttpException(422, field + " 必须是对象");
    }

    /** 取数组字段（对应 pydantic 的 {@code list | None}）。 */
    @SuppressWarnings("unchecked")
    private static List<Object> requireList(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new ApiHttpException(422, field + " 必须是数组");
    }

    /** 服务器的启用标志（对应 {@code bool(getattr(s, "enabled", True))}）。 */
    private static boolean enabledFlag(MCPServer server) {
        return server.getEnabled() != null && server.getEnabled() != 0;
    }

    /** {@code str(exception)} 的等价物（Java 的 message 可能为 null，回落到类名）。 */
    private static String errorDetail(Exception exc) {
        String message = exc.getMessage();
        return message == null ? exc.getClass().getSimpleName() : message;
    }

    /** 名称字段的非空兜底（对应 {@code getattr(s, "name", "")}）。 */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
