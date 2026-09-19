package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.models.MCPServer;
import com.wisesoft.wenqu.repositories.MCPServerRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 服务 —— 服务器配置、内置同步、客户端与工具管理。
 *
 * <p>由参考实现的 {@code agents/mcp/service.py} 全量逐函数翻译（671 行，无遗漏）：
 * <pre>
 * 全局缓存与状态      _mcp_lock / _mcp_tools_cache / _mcp_tools_stats
 *                     _DEFAULT_MCP_SERVERS / _BUILTIN_MCP_SERVER_SLUGS /
 *                     _RETIRED_BUILTIN_MCP_SERVER_SLUGS / _SYNCED_MCP_FIELDS /
 *                     _USER_CONFIGURABLE_TRANSPORTS
 * 判定                is_builtin_mcp_server / requires_mcp_stdio_migration
 * 内置同步            ensure_builtin_mcp_servers_in_db
 * 客户端与工具        get_mcp_client / to_camel_case / _load_enabled_mcp_server_configs /
 *                     get_enabled_mcp_server_config / get_enabled_mcp_server_slugs /
 *                     get_mcp_tools / get_tools_from_all_servers / clear_mcp_cache /
 *                     clear_mcp_server_tools_cache / get_mcp_tools_stats
 * 配置 CRUD           get_mcp_server / get_all_mcp_servers / create_mcp_server /
 *                     update_mcp_server / delete_mcp_server
 * 开关                set_server_enabled / toggle_tool_enabled
 * 统一入口            get_enabled_mcp_tools / get_servers_config / get_all_mcp_tools
 * </pre>
 *
 * <h3>平台差异（必要替换，逐条说明）</h3>
 * <ul>
 *   <li><b>数据访问</b>：参考实现的 {@code db: AsyncSession} 参数 → 容器注入的
 *       {@link MCPServerRepository}（方法级对应关系见该类注释）。原函数上的 {@code db=None}
 *       分支（「自己开会话」）在本工程不存在——repository 自带连接管理。</li>
 *   <li><b>模块级全局状态 → 单例 Bean 字段</b>：{@code _mcp_tools_cache} / {@code _mcp_tools_stats}
 *       变为实例字段；{@code asyncio.Lock} → {@link ReentrantLock}（本工程同步执行，加锁语义一致：
 *       保护缓存读写的临界区）。</li>
 *   <li><b>工具对象</b>：{@code langchain_core BaseTool} → {@link McpTool}（见该类注释）。
 *       返回类型 {@code list[Callable]} → {@code List<McpTool>}。</li>
 *   <li><b>异常映射</b>：{@code ValueError} → {@link IllegalArgumentException}；
 *       {@code MCPServerNotFoundError} → {@link MCPServerNotFoundException}（同为
 *       {@code IllegalArgumentException} 子类，保证路由层的捕获顺序可照搬）；
 *       {@code PermissionError} → {@link McpBuiltinImmutableException}（Java 无内建类型）。</li>
 *   <li><b>{@code ExceptionGroup}</b>：参考实现区分 {@code except ExceptionGroup}（多服务器并发失败）
 *       与 {@code except Exception}；Java 无 ExceptionGroup（SDK 逐服务器同步取工具，失败即为普通异常），
 *       故两级合并为一次捕获——对外行为一致（日志 + 返回空工具列表）。</li>
 *   <li><b>缓存键哈希</b>：{@code json.dumps(server_config, sort_keys=True, ensure_ascii=True,
 *       separators=(",",":"))} → {@link #pythonJsonDumps}（同语义实现，保证同一配置得到同一键）。
 *       该键是<b>进程内</b>缓存键，不参与跨进程/跨系统比较（参考实现的同名缓存亦为进程内字典）。</li>
 *   <li><b>事务</b>：参考实现用 session 上下文管理器统一 commit；本工程的 repository 每次调用即提交，
 *       故 {@link #ensureBuiltinMcpServersInDb()} 加 {@code @Transactional} 还原「多处改动一起落库 +
 *       失败整体回滚」的语义（该方法本身<b>不吞异常</b>，异常继续上抛给启动期调用方）。</li>
 * </ul>
 *
 * <p><b>未接线说明（如实标注）</b>：参考实现中本模块还有两类消费方，均属于尚未照搬的 agents 运行时：
 * {@code agents/toolkits/service.py::resolve_configured_runtime_tools}（装配到智能体）与
 * {@code agents/middlewares/{skills,dynamic_tool}.py}、{@code agents/skills/service.py}、
 * {@code agents/context.py}（按启用范围过滤）。本类已把它们的依赖函数
 * （{@code get_enabled_mcp_tools} / {@code get_mcp_tools} / {@code get_enabled_mcp_server_slugs} /
 * {@code get_servers_config} / {@code get_tools_from_all_servers}）全部备齐，运行时照搬时直接调用即可。
 */
@Slf4j
@Service
public class McpService {

    // =========================================================================
    // === 全局缓存 & 常量（对应模块级状态） ===
    // =========================================================================

    /** 用户可自建的传输类型（标准 MCP 的 sse / streamable_http）。 */
    private static final List<String> USER_CONFIGURABLE_TRANSPORTS = List.of("sse", "streamable_http");

    /** 内置 MCP 服务器定义（首次运行导入数据库）。 */
    private static final Map<String, Map<String, Object>> DEFAULT_MCP_SERVERS = new LinkedHashMap<>();

    /** 内置 slug 集合（= {@code tuple(_DEFAULT_MCP_SERVERS)}）。 */
    private static final List<String> BUILTIN_MCP_SERVER_SLUGS;

    /** 已下线的内置 slug（同步时从数据库清除）。 */
    private static final List<String> RETIRED_BUILTIN_MCP_SERVER_SLUGS = List.of("sequentialthinking");

    /** 内置定义回写数据库时参与比对的字段（顺序照搬）。 */
    private static final List<String> SYNCED_MCP_FIELDS = List.of(
            "description",
            "transport",
            "url",
            "command",
            "args",
            "env",
            "headers",
            "timeout",
            "sse_read_timeout",
            "tags",
            "icon");

    static {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("command", "npx");
        chart.put("args", List.of("-y", "@antv/mcp-server-chart"));
        chart.put("transport", "stdio");
        chart.put("description", "图表生成工具，支持生成各类图表（柱状图、折线图、饼图等）");
        chart.put("icon", "📊");
        chart.put("tags", List.of("内置", "图表"));
        DEFAULT_MCP_SERVERS.put("mcp-server-chart", chart);
        BUILTIN_MCP_SERVER_SLUGS = List.copyOf(DEFAULT_MCP_SERVERS.keySet());
    }

    /** 本地仅缓存工具对象。配置始终以数据库为准，每次按 slug 现查。 */
    private final Map<String, List<McpTool>> toolsCache = new LinkedHashMap<>();

    /** MCP 工具统计（供上报启用/禁用数量）。 */
    private final Map<String, Map<String, Integer>> toolsStats = new LinkedHashMap<>();

    /** 保护上述两个缓存的临界区（对应 {@code _mcp_lock}）。 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 驼峰化正则（对应 {@code re.sub(r"[-_]+(.)", ...)}）。 */
    private static final Pattern CAMEL_PATTERN = Pattern.compile("[-_]+(.)");

    private final MCPServerRepository repository;

    public McpService(MCPServerRepository repository) {
        this.repository = repository;
    }

    // =========================================================================
    // === 判定（对应 is_builtin_mcp_server / requires_mcp_stdio_migration） ===
    // =========================================================================

    /** 该 MCP 是否由代码中的内置定义管理。 */
    public static boolean isBuiltinMcpServer(MCPServer server) {
        return server != null && BUILTIN_MCP_SERVER_SLUGS.contains(server.getSlug());
    }

    /** 该 MCP 是否为升级后需要迁移的用户 stdio 配置。 */
    public static boolean requiresMcpStdioMigration(MCPServer server) {
        return server != null
                && "stdio".equals(server.getTransport())
                && !isBuiltinMcpServer(server);
    }

    /** 生成运行时 MCP 配置；内置服务器的连接字段始终以代码定义为准（对应 {@code _to_runtime_mcp_config}）。 */
    private static Map<String, Object> toRuntimeMcpConfig(MCPServer server) {
        if (!isBuiltinMcpServer(server)) {
            return McpServerViews.toMcpConfig(server);
        }

        Map<String, Object> builtin = DEFAULT_MCP_SERVERS.get(server.getSlug());
        Map<String, Object> config = new LinkedHashMap<>();
        for (String key : List.of(
                "transport", "url", "command", "args", "env", "headers", "timeout", "sse_read_timeout")) {
            Object value = builtin.get(key);
            if (value != null) {
                config.put(key, value);
            }
        }
        Object disabledTools = McpServerViews.parsed(server.getDisabledTools());
        if (McpServerViews.isTruthy(disabledTools)) {
            config.put("disabled_tools", disabledTools);
        }
        return config;
    }

    // =========================================================================
    // === 核心逻辑：内置同步 ===
    // =========================================================================

    /**
     * 确保内置 MCP 服务器定义存在于数据库（对应 {@code ensure_builtin_mcp_servers_in_db}）。
     *
     * <p>三步（顺序照搬）：① 停用遗留的用户 stdio 服务器；② 删除已下线的内置服务器
     * （仅 {@code created_by == "system"} 的那条，用户自建的同名记录保留）；
     * ③ 内置定义逐字段回写（缺则新建，{@code enabled=0}，{@code created_by=system}）。
     *
     * <p><b>刻意不吞异常</b>：参考实现的集成测试断言「初始化失败必须上抛到入口」，故本方法不加
     * try/catch，由启动期调用方决定是记录失败还是中断启动（参考实现里该组件 {@code required=False}）。
     */
    @Transactional
    public void ensureBuiltinMcpServersInDb() {
        boolean anyChanged = false;

        for (MCPServer server : repository.listLegacyEnabledStdio(BUILTIN_MCP_SERVER_SLUGS)) {
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("enabled", 0);
            columns.put("updated_by", "system");
            repository.updateColumns(server, columns);
            clearMcpServerToolsCache(server.getSlug());
            anyChanged = true;
            log.warn("Disabled legacy user stdio MCP server '{}'", server.getSlug());
        }

        for (String slug : RETIRED_BUILTIN_MCP_SERVER_SLUGS) {
            MCPServer retired = repository.getBySlugAndCreatedBy(slug, "system");
            if (retired != null) {
                repository.delete(retired);
                clearMcpServerToolsCache(slug);
                anyChanged = true;
                log.info("Removed retired built-in MCP server '{}' from database", slug);
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : DEFAULT_MCP_SERVERS.entrySet()) {
            String slug = entry.getKey();
            Map<String, Object> config = entry.getValue();
            MCPServer existing = repository.getBySlug(slug);
            if (existing == null) {
                MCPServer created = new MCPServer();
                created.setSlug(slug);
                created.setName(config.get("name") == null ? slug : String.valueOf(config.get("name")));
                created.setDescription(asString(config.get("description")));
                created.setTransport(asString(config.get("transport")));
                created.setUrl(asString(config.get("url")));
                created.setCommand(asString(config.get("command")));
                created.setArgs(jsonText(config.get("args")));
                created.setEnv(jsonText(config.get("env")));
                created.setHeaders(jsonText(config.get("headers")));
                created.setTimeout(toInt(config.get("timeout")));
                created.setSseReadTimeout(toInt(config.get("sse_read_timeout")));
                created.setTags(jsonText(config.get("tags")));
                created.setIcon(asString(config.get("icon")));
                created.setEnabled(0);
                created.setCreatedBy("system");
                created.setUpdatedBy("system");
                repository.insert(created);
                anyChanged = true;
                log.info("Added built-in MCP server '{}' to database", slug);
                continue;
            }

            Map<String, Object> columns = new LinkedHashMap<>();
            for (String field : SYNCED_MCP_FIELDS) {
                Object nextValue = config.get(field);
                if (!syncedFieldEquals(existing, field, nextValue)) {
                    columns.put(field, nextValue);
                }
            }
            if (!"system".equals(existing.getCreatedBy())) {
                columns.put("created_by", "system");
            }
            if (!columns.isEmpty()) {
                columns.put("updated_by", "system");
                repository.updateColumns(existing, columns);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            log.info("Built-in MCP servers synchronized ({} definitions)", DEFAULT_MCP_SERVERS.size());
        }
    }

    /** 单个同步字段的比较（把 JSON 文本列与 Python 结构化值对齐后比较）。 */
    private static boolean syncedFieldEquals(MCPServer existing, String field, Object expected) {
        return switch (field) {
            case "args" -> Objects.equals(McpServerViews.parsed(existing.getArgs()), normalizeJson(expected));
            case "env" -> Objects.equals(McpServerViews.parsed(existing.getEnv()), normalizeJson(expected));
            case "headers" -> Objects.equals(McpServerViews.parsed(existing.getHeaders()), normalizeJson(expected));
            case "tags" -> Objects.equals(McpServerViews.parsed(existing.getTags()), normalizeJson(expected));
            case "timeout" -> Objects.equals(existing.getTimeout(), toInt(expected));
            case "sse_read_timeout" -> Objects.equals(existing.getSseReadTimeout(), toInt(expected));
            case "description" -> Objects.equals(existing.getDescription(), asString(expected));
            case "transport" -> Objects.equals(existing.getTransport(), asString(expected));
            case "url" -> Objects.equals(existing.getUrl(), asString(expected));
            case "command" -> Objects.equals(existing.getCommand(), asString(expected));
            case "icon" -> Objects.equals(existing.getIcon(), asString(expected));
            default -> throw new IllegalStateException("未覆盖的同步字段: " + field);
        };
    }

    // =========================================================================
    // === 核心逻辑：客户端与工具 ===
    // =========================================================================

    /**
     * 按服务器配置构造 MCP 客户端（对应 {@code get_mcp_client}）。
     *
     * <p>与参考实现一致：构造失败只记日志并返回 {@code null}（调用方据此返回空工具列表），不抛异常。
     */
    public McpClientBundle getMcpClient(Map<String, Map<String, Object>> serverConfigs) {
        try {
            McpClientBundle bundle = McpClientBundle.create(serverConfigs);
            log.info("Initialized MCP client with servers: {}", serverConfigs == null
                    ? List.of() : new ArrayList<>(serverConfigs.keySet()));
            return bundle;
        } catch (Exception exc) {
            log.error("Failed to initialize MCP client: {}", exc.getMessage());
            return null;
        }
    }

    /** 转 lowerCamelCase（对应 {@code to_camel_case}：`-`/`_` 后的字符大写，首字符小写）。 */
    public static String toCamelCase(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher matcher = CAMEL_PATTERN.matcher(value);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    builder, Matcher.quoteReplacement(matcher.group(1).toUpperCase()));
        }
        matcher.appendTail(builder);
        String result = builder.toString();
        return Character.toLowerCase(result.charAt(0)) + result.substring(1);
    }

    /** 从数据库载入「已启用且可运行」的服务器配置（对应 {@code _load_enabled_mcp_server_configs}）。 */
    public Map<String, Map<String, Object>> loadEnabledMcpServerConfigs(List<String> names) {
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        for (MCPServer server : repository.listEnabled(BUILTIN_MCP_SERVER_SLUGS, names)) {
            configs.put(server.getSlug(), toRuntimeMcpConfig(server));
        }
        return configs;
    }

    /** 取单个已启用服务器的配置（对应 {@code get_enabled_mcp_server_config}）。 */
    public Map<String, Object> getEnabledMcpServerConfig(String serverSlug) {
        return loadEnabledMcpServerConfigs(List.of(serverSlug)).get(serverSlug);
    }

    /** 取所有已启用服务器的 slug（对应 {@code get_enabled_mcp_server_slugs}）。 */
    public List<String> getEnabledMcpServerSlugs() {
        return repository.listEnabledSlugs(BUILTIN_MCP_SERVER_SLUGS, null);
    }

    /**
     * 取某服务器（或附加配置指定的服务器）的工具（对应 {@code get_mcp_tools}）。
     *
     * <p>架构与参考实现一致：
     * <ol>
     *   <li><b>抓取</b>：连接 MCP 服务器取<b>全部</b>工具；</li>
     *   <li><b>缓存</b>：把完整、未过滤的工具列表存进 {@link #toolsCache}；</li>
     *   <li><b>过滤</b>：仅按 {@code disabledTools} 入参过滤<b>返回值</b>（不影响缓存）。</li>
     * </ol>
     *
     * <p>返回的每个工具都补写了 {@code metadata.id = mcp__<server_cc>__<tool_cc>}
     * 并把 {@code handle_tool_error} 置真（逐字对齐参考实现，其中 server/tool 名都做
     * lowerCamelCase 转换）。
     *
     * @param serverSlug       服务器 slug
     * @param additionalServers 附加的服务器配置（优先于数据库）
     * @param disabledTools    要从<b>返回值</b>里剔除的工具名
     * @param cache            是否使用/写入缓存
     * @param forceRefresh     是否强制向服务器刷新
     */
    public List<McpTool> getMcpTools(
            String serverSlug,
            Map<String, Map<String, Object>> additionalServers,
            List<String> disabledTools,
            boolean cache,
            boolean forceRefresh) {

        Map<String, Object> serverConfig;
        if (additionalServers != null && additionalServers.containsKey(serverSlug)) {
            serverConfig = additionalServers.get(serverSlug);
        } else {
            serverConfig = getEnabledMcpServerConfig(serverSlug);
        }

        if (serverConfig == null) {
            log.warn("MCP server '{}' not found in database or disabled", serverSlug);
            return new ArrayList<>();
        }

        // 配置 hash 直接基于完整配置生成。只要数据库中的配置发生变化，
        // 本地工具缓存 key 就会变化，从而自然触发重建。
        String configHash = sha256Hex16(pythonJsonDumps(serverConfig));
        String cacheKey = serverSlug + ":" + configHash;

        List<McpTool> allProcessedTools = new ArrayList<>();

        lock.lock();
        try {
            if (!forceRefresh && cache && toolsCache.containsKey(cacheKey)) {
                allProcessedTools = toolsCache.get(cacheKey);
            }
        } finally {
            lock.unlock();
        }

        if (allProcessedTools.isEmpty()) {
            List<McpTool> fetched = new ArrayList<>();
            try {
                // disabled_tools 只影响返回值过滤，不参与 MCP client 建连参数。
                Map<String, Object> clientConfig = new LinkedHashMap<>(serverConfig);
                clientConfig.remove("disabled_tools");

                Map<String, Map<String, Object>> single = new LinkedHashMap<>();
                single.put(serverSlug, clientConfig);
                McpClientBundle client = getMcpClient(single);
                if (client == null) {
                    return new ArrayList<>();
                }

                List<McpTool> rawTools = client.getTools();
                String serverCc = toCamelCase(serverSlug);
                for (McpTool tool : rawTools) {
                    String originalName = tool.getName();
                    String toolCc = toCamelCase(originalName);
                    String uniqueId = "mcp__" + serverCc + "__" + toolCc;
                    tool.setMetadataId(uniqueId);
                    // 开启错误处理，防止工具调用抛出异常时击穿服务
                    tool.setHandleToolError(true);
                    fetched.add(tool);
                }
            } catch (Exception exc) {
                // 参考实现分别捕获 ExceptionGroup（多服务器）与 Exception；Java 侧逐服务器同步取
                // 工具，失败即为普通异常，故合并处理（对外行为一致：日志 + 空列表）。
                log.warn("MCP server '{}' failed to load tools: {}", serverSlug, exc.getMessage());
                log.debug("MCP tool loading failure detail", exc);
                return new ArrayList<>();
            }

            allProcessedTools = fetched;

            if (cache) {
                lock.lock();
                try {
                    List<String> staleKeys = new ArrayList<>();
                    for (String key : toolsCache.keySet()) {
                        if (key.startsWith(serverSlug + ":") && !key.equals(cacheKey)) {
                            staleKeys.add(key);
                        }
                    }
                    for (String staleKey : staleKeys) {
                        toolsCache.remove(staleKey);
                    }
                    toolsCache.put(cacheKey, allProcessedTools);
                } finally {
                    lock.unlock();
                }

                Object globalConfigDisabled = serverConfig.get("disabled_tools");
                List<String> disabledNames = asStringList(globalConfigDisabled);
                int enabledCount = 0;
                for (McpTool tool : allProcessedTools) {
                    if (!disabledNames.contains(tool.getName())) {
                        enabledCount++;
                    }
                }
                Map<String, Integer> stats = new LinkedHashMap<>();
                stats.put("total", allProcessedTools.size());
                stats.put("enabled", enabledCount);
                stats.put("disabled", allProcessedTools.size() - enabledCount);
                lock.lock();
                try {
                    toolsStats.put(serverSlug, stats);
                } finally {
                    lock.unlock();
                }

                log.info("Refreshed MCP tools cache for '{}' with key '{}': {} tools loaded.",
                        serverSlug, cacheKey, allProcessedTools.size());
            }
        }

        // 3. Filtering (Apply to Return Value Only)
        if (disabledTools != null && !disabledTools.isEmpty()) {
            List<McpTool> filtered = new ArrayList<>();
            for (McpTool tool : allProcessedTools) {
                if (!disabledTools.contains(tool.getName())) {
                    filtered.add(tool);
                }
            }
            log.debug("Returning {}/{} tools for '{}' (filtered {} by argument)",
                    filtered.size(), allProcessedTools.size(), serverSlug, disabledTools.size());
            return filtered;
        }

        return allProcessedTools;
    }

    /** {@code get_mcp_tools(server_slug)} 的默认入参重载（Python 侧有默认值，Java 需显式给出）。 */
    public List<McpTool> getMcpTools(String serverSlug) {
        return getMcpTools(serverSlug, null, null, true, false);
    }

    /** 取所有已配置服务器的全部工具（对应 {@code get_tools_from_all_servers}）。 */
    public List<McpTool> getToolsFromAllServers() {
        Map<String, Map<String, Object>> serverConfigs = loadEnabledMcpServerConfigs(null);
        List<McpTool> allTools = new ArrayList<>();
        for (String serverSlug : serverConfigs.keySet()) {
            allTools.addAll(getMcpTools(serverSlug, serverConfigs, null, true, false));
        }
        return allTools;
    }

    /** 清空工具缓存（对应 {@code clear_mcp_cache}，供测试使用）。 */
    public void clearMcpCache() {
        lock.lock();
        try {
            toolsCache.clear();
            toolsStats.clear();
        } finally {
            lock.unlock();
        }
    }

    /** 清空某个服务器的工具缓存（对应 {@code clear_mcp_server_tools_cache}）。 */
    public void clearMcpServerToolsCache(String serverSlug) {
        lock.lock();
        try {
            List<String> staleKeys = new ArrayList<>();
            for (String key : toolsCache.keySet()) {
                if (key.startsWith(serverSlug + ":")) {
                    staleKeys.add(key);
                }
            }
            for (String staleKey : staleKeys) {
                toolsCache.remove(staleKey);
            }
            toolsStats.remove(serverSlug);
        } finally {
            lock.unlock();
        }
        log.info("Cleared tools cache for MCP server '{}'", serverSlug);
    }

    /** 取某服务器的工具统计（对应 {@code get_mcp_tools_stats}；无数据返回 null）。 */
    public Map<String, Integer> getMcpToolsStats(String serverSlug) {
        lock.lock();
        try {
            return toolsStats.get(serverSlug);
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // === 服务器配置 CRUD ===
    // =========================================================================

    /** 按 slug 取单条配置（对应 {@code get_mcp_server}）。 */
    public MCPServer getMcpServer(String slug) {
        return repository.getBySlug(slug);
    }

    /** 取全部配置（对应 {@code get_all_mcp_servers}）。 */
    public List<MCPServer> getAllMcpServers() {
        return repository.listAll();
    }

    /**
     * 新建用户自建 MCP 服务器（对应 {@code create_mcp_server}）。
     *
     * <p>两道拦截照搬：内置 slug 保留；传输类型只允许 sse / streamable_http（不允许用户起本地进程）。
     * 入库字段集合与参考实现一致——不接收 {@code command/args/env}。
     */
    public MCPServer createMcpServer(
            String slug,
            String name,
            String transport,
            String url,
            String description,
            Map<String, Object> headers,
            Integer timeout,
            Integer sseReadTimeout,
            List<Object> tags,
            String icon,
            String createdBy) {
        if (BUILTIN_MCP_SERVER_SLUGS.contains(slug)) {
            throw new IllegalArgumentException("系统内置 MCP 的 slug 由代码保留，无法通过接口创建");
        }
        if (!USER_CONFIGURABLE_TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("用户创建的 MCP 仅支持 sse 或 streamable_http，不允许启动 stdio 本地进程");
        }

        MCPServer existing = repository.getBySlug(slug);
        if (existing != null) {
            throw new IllegalArgumentException("Server slug '" + slug + "' already exists");
        }

        MCPServer server = new MCPServer();
        server.setSlug(slug);
        server.setName(name);
        server.setDescription(description);
        server.setTransport(transport);
        server.setUrl(url);
        server.setHeaders(jsonText(headers));
        server.setTimeout(timeout);
        server.setSseReadTimeout(sseReadTimeout);
        server.setTags(jsonText(tags));
        server.setIcon(icon);
        server.setEnabled(1);
        server.setCreatedBy(createdBy);
        server.setUpdatedBy(createdBy);
        repository.insert(server);

        clearMcpServerToolsCache(slug);
        log.info("Created MCP server '{}'", slug);
        return server;
    }

    /**
     * 更新用户自建 MCP 服务器（对应 {@code update_mcp_server}）。
     *
     * <p>逐条对齐参考实现：内置服务器拒绝（403）；传输类型必须可自建；改传输类型后 url 必填；
     * {@code command/args/env} <b>无条件清空</b>；其余字段仅在入参非 null 时覆盖。
     */
    public MCPServer updateMcpServer(
            String slug,
            String name,
            String description,
            String transport,
            String url,
            Map<String, Object> headers,
            Integer timeout,
            Integer sseReadTimeout,
            List<Object> tags,
            String icon,
            String updatedBy) {
        MCPServer server = repository.getBySlug(slug);
        if (server == null) {
            throw new MCPServerNotFoundException("Server '" + slug + "' does not exist");
        }
        if (isBuiltinMcpServer(server)) {
            throw new McpBuiltinImmutableException("系统内置 MCP 的连接配置由代码管理，无法通过接口修改");
        }

        String nextTransport = (transport == null || transport.isEmpty()) ? server.getTransport() : transport;
        if (!USER_CONFIGURABLE_TRANSPORTS.contains(nextTransport)) {
            throw new IllegalArgumentException("用户创建的 MCP 仅支持 sse 或 streamable_http，不允许启动 stdio 本地进程");
        }

        String nextUrl = url != null ? url : server.getUrl();
        if (nextUrl == null || nextUrl.strip().isEmpty()) {
            throw new IllegalArgumentException("传输类型为 " + nextTransport + " 时，url 必填");
        }

        Map<String, Object> columns = new LinkedHashMap<>();
        if (name != null) {
            columns.put("name", name);
        }
        if (description != null) {
            columns.put("description", description);
        }
        if (transport != null) {
            columns.put("transport", transport);
        }
        if (url != null) {
            columns.put("url", url);
        }
        columns.put("command", null);
        columns.put("args", null);
        columns.put("env", null);
        if (headers != null) {
            columns.put("headers", headers);
        }
        if (timeout != null) {
            columns.put("timeout", timeout);
        }
        if (sseReadTimeout != null) {
            columns.put("sse_read_timeout", sseReadTimeout);
        }
        if (tags != null) {
            columns.put("tags", tags);
        }
        if (icon != null) {
            columns.put("icon", icon);
        }
        if (updatedBy != null) {
            columns.put("updated_by", updatedBy);
        }

        MCPServer updated = repository.updateColumns(server, columns);
        clearMcpServerToolsCache(slug);
        log.info("Updated MCP server '{}'", slug);
        return updated;
    }

    /** 删除服务器；不存在返回 false（对应 {@code delete_mcp_server}）。 */
    public boolean deleteMcpServer(String slug) {
        MCPServer server = repository.getBySlug(slug);
        if (server == null) {
            return false;
        }
        repository.delete(server);
        clearMcpServerToolsCache(slug);
        log.info("Deleted MCP server '{}'", slug);
        return true;
    }

    // =========================================================================
    // === 开关 ===
    // =========================================================================

    /**
     * 设置服务器启用状态（对应 {@code set_server_enabled}）。
     *
     * <p>返回 {@code (是否启用, 更新后的服务器)}；遗留 stdio 服务器不允许被启用。
     */
    public EnabledResult setServerEnabled(String slug, boolean enabled, String updatedBy) {
        MCPServer server = repository.getBySlug(slug);
        if (server == null) {
            throw new MCPServerNotFoundException("Server '" + slug + "' does not exist");
        }
        if (enabled && requiresMcpStdioMigration(server)) {
            throw new IllegalArgumentException("历史 stdio MCP 已被禁用，请改为 sse 或 streamable_http 后再启用");
        }

        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("enabled", enabled ? 1 : 0);
        if (updatedBy != null) {
            columns.put("updated_by", updatedBy);
        }
        MCPServer updated = repository.updateColumns(server, columns);

        boolean isEnabled = updated.getEnabled() != null && updated.getEnabled() != 0;
        clearMcpServerToolsCache(slug);
        log.info("Set MCP server '{}' enabled={}", slug, isEnabled);
        return new EnabledResult(isEnabled, updated);
    }

    /** {@link #setServerEnabled} 的返回值载体（对应 Python 的 tuple 返回）。 */
    public record EnabledResult(boolean enabled, MCPServer server) {}

    /**
     * 切换单个工具的启用状态（对应 {@code toggle_tool_enabled}）。
     *
     * <p>语义是「在 disabled_tools 里增删该工具名」：已禁用 → 移除（变为启用）；未禁用 → 追加（变为禁用）。
     */
    public EnabledResult toggleToolEnabled(String serverSlug, String toolName, String updatedBy) {
        MCPServer server = repository.getBySlug(serverSlug);
        if (server == null) {
            throw new MCPServerNotFoundException("Server '" + serverSlug + "' does not exist");
        }

        List<String> disabledTools = McpServerViews.parseStringList(server.getDisabledTools());
        disabledTools = new ArrayList<>(disabledTools);

        boolean enabled;
        if (disabledTools.contains(toolName)) {
            disabledTools.remove(toolName);
            enabled = true;
        } else {
            disabledTools.add(toolName);
            enabled = false;
        }

        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("disabled_tools", disabledTools);
        if (updatedBy != null) {
            columns.put("updated_by", updatedBy);
        }
        MCPServer updated = repository.updateColumns(server, columns);

        clearMcpServerToolsCache(serverSlug);
        log.info("Toggled tool '{}' for server '{}' enabled={}", toolName, serverSlug, enabled);
        return new EnabledResult(enabled, updated);
    }

    // =========================================================================
    // === 统一入口 ===
    // =========================================================================

    /** 取某服务器「启用中的」工具（自动过滤 disabled_tools；对应 {@code get_enabled_mcp_tools}）。 */
    public List<McpTool> getEnabledMcpTools(String serverSlug) {
        Map<String, Object> config = getEnabledMcpServerConfig(serverSlug);
        if (config == null) {
            log.warn("MCP server '{}' not found in database or disabled", serverSlug);
            return new ArrayList<>();
        }
        Object rawDisabled = config.get("disabled_tools");
        List<String> disabledTools = asStringList(rawDisabled);
        Map<String, Map<String, Object>> additional = new LinkedHashMap<>();
        additional.put(serverSlug, config);
        return getMcpTools(serverSlug, additional, disabledTools, true, false);
    }

    /** 批量取服务器配置（只含存在的；对应 {@code get_servers_config}）。 */
    public Map<String, Map<String, Object>> getServersConfig(List<String> names) {
        return loadEnabledMcpServerConfigs(names);
    }

    /**
     * 取某服务器的<b>全部</b>工具（不按 disabled_tools 过滤；对应 {@code get_all_mcp_tools}）。
     *
     * <p>供管理界面展示工具清单与启用状态。与参考实现一致：强制刷新且<b>不写</b>全局工具缓存，
     * 以免污染智能体的已过滤视图。
     */
    public List<McpTool> getAllMcpTools(String serverSlug) {
        Map<String, Object> config = getEnabledMcpServerConfig(serverSlug);
        if (config == null) {
            log.warn("MCP server '{}' not found in database or disabled", serverSlug);
            return new ArrayList<>();
        }
        Map<String, Map<String, Object>> additional = new LinkedHashMap<>();
        additional.put(serverSlug, config);
        return getMcpTools(serverSlug, additional, List.of(), false, true);
    }

    // =========================================================================
    // === 内部工具 ===
    // =========================================================================

    /** SHA-256 十六进制摘要的前 16 位（对应 {@code hashlib.sha256(...).hexdigest()[:16]}）。 */
    static String sha256Hex16(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.substring(0, 16);
        } catch (Exception exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    /**
     * Python {@code json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"))}
     * 的等价实现，用于生成配置哈希。
     *
     * <p>平台差异（如实标注）：Python 的浮点 repr 是「最短可往返表示」，Java 的
     * {@code Double.toString} 在极值/特殊格式上与之不同；本模块的配置值只有字符串、整数、
     * 布尔与容器（{@code timeout}/{@code sse_read_timeout} 均为整数），不含浮点，故该差异在实际
     * 取值范围内不产生影响。该哈希仅作进程内缓存键，不参与跨语言比较。
     */
    static String pythonJsonDumps(Object value) {
        StringBuilder builder = new StringBuilder();
        appendPythonJson(builder, value);
        return builder.toString();
    }

    private static void appendPythonJson(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof Boolean bool) {  // 注意：Python 里 bool 是 int 的子类，必须先判布尔
            builder.append(bool ? "true" : "false");
            return;
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                builder.append(pythonFloat(number.doubleValue()));
            } else {
                builder.append(number.toString());
            }
            return;
        }
        if (value instanceof String text) {
            appendPythonString(builder, text);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            builder.append('{');
            Map<String, Object> byKey = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                byKey.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            List<String> keys = new ArrayList<>(byKey.keySet());
            keys.sort(String::compareTo);  // sort_keys=True（按 Unicode 码位）
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                appendPythonString(builder, key);
                builder.append(':');
                appendPythonJson(builder, byKey.get(key));
            }
            builder.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                appendPythonJson(builder, item);
            }
            builder.append(']');
            return;
        }
        // 其余类型按字符串处理（配置值不会走到这里）
        appendPythonString(builder, String.valueOf(value));
    }

    /** 把 {@code disabled_tools} 一类的值取为字符串表（对应 Python 的 {@code x or []} 迭代语义）。 */
    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String text) {
            result.add(text);
        }
        return result;
    }

    /** Python 字符串字面量（ensure_ascii=True：非 ASCII 一律转 {@code \\uXXXX}）。 */
    private static void appendPythonString(StringBuilder builder, String text) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (ch < 0x20 || ch > 0x7E) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    /** 浮点的 Python repr 近似（见 {@link #pythonJsonDumps} 的差异说明）。 */
    private static String pythonFloat(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value) + ".0";
        }
        return String.valueOf(value);
    }

    /** 取字符串值（null 原样返回）。 */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 取整数值（无法解析时返回 null）。 */
    private static Integer toInt(Object value) {
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

    /** 把结构化值序列化为文本列内容（null 原样返回）。 */
    private static String jsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        return com.alibaba.fastjson2.JSON.toJSONString(value);
    }

    /** 把结构化值规范化为「与 JSON 列解析结果同型」的容器，便于深比较。 */
    private static Object normalizeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return com.alibaba.fastjson2.JSON.parse(com.alibaba.fastjson2.JSON.toJSONString(value));
        } catch (Exception ignored) {
            return value;
        }
    }
}
