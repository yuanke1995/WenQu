package com.wisesoft.wenqu.agents;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具元数据服务，由参考实现的 {@code agents/toolkits/service.py} 翻译。
 *
 * <p>本类承载参考实现的工具元数据缓存与三个函数：
 * {@code get_tool_metadata}（工具列表，可按分类过滤）、
 * {@code get_tool_instances_by_category}（按分类取工具实例）与
 * {@code resolve_configured_runtime_tools}（按智能体配置装配本地工具 + MCP 工具 + Skill 门控工具）。
 *
 * <p>平台差异（必要替换）：模块级可变缓存 {@code _metadata_cache} → 类内静态缓存；
 * {@code hasattr(tool_obj, "args_schema") and tool_obj.args_schema} → 接口方法
 * {@link ToolkitsRegistry.ToolDefinition#getArgsSchema()} 判空；
 * {@code schema} 具备 {@code .schema()} 方法时的转换 → 已由接口约定统一为 Map，
 * 无需运行时判定。
 */
@Slf4j
public final class ToolkitsService {

    /** 工具元数据缓存（对应参考实现的模块级 {@code _metadata_cache}）。 */
    private static final List<Map<String, Object>> METADATA_CACHE = new ArrayList<>();

    private ToolkitsService() {}

    /** 从工具对象提取基础信息（对应 {@code _extract_tool_info}）。 */
    static Map<String, Object> extractToolInfo(ToolkitsRegistry.ToolDefinition tool) {
        Map<String, Object> metadata = tool.getMetadata();
        if (metadata == null) {
            metadata = Map.of();
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("slug", tool.getName());
        info.put("name", metadata.getOrDefault("name", tool.getName()));  // 显示名称优先从 metadata 获取
        info.put("description", tool.getDescription());
        info.put("metadata", metadata);

        List<Map<String, Object>> args = new ArrayList<>();
        Map<String, Object> schema = tool.getArgsSchema();
        if (schema != null && !schema.isEmpty()) {
            Object rawProperties = schema.get("properties");
            if (rawProperties instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    Map<String, Object> argInfo = entry.getValue() instanceof Map<?, ?> raw
                            ? toStringKeyMap(raw) : Map.of();
                    Map<String, Object> arg = new LinkedHashMap<>();
                    arg.put("name", String.valueOf(entry.getKey()));
                    arg.put("type", argInfo.getOrDefault("type", ""));
                    arg.put("description", argInfo.getOrDefault("description", ""));
                    args.add(arg);
                }
            }
        }
        info.put("args", args);
        return info;
    }

    /** 延迟加载工具元数据（首次调用时自动触发，对应 {@code _ensure_metadata_loaded}）。 */
    static void ensureMetadataLoaded() {
        if (!METADATA_CACHE.isEmpty()) {  // 已加载
            return;
        }
        List<ToolkitsRegistry.ToolDefinition> allTools = ToolkitsRegistry.getAllToolInstances();
        Map<String, ToolkitsRegistry.ToolExtraMetadata> extraMeta = ToolkitsRegistry.getAllExtraMetadata();

        for (ToolkitsRegistry.ToolDefinition tool : allTools) {
            String toolName = tool.getName();
            Map<String, Object> runtimeInfo = extractToolInfo(tool);

            ToolkitsRegistry.ToolExtraMetadata extra = extraMeta.get(toolName);
            if (extra != null) {
                // 合并附加元数据
                runtimeInfo.put("category", extra.category);
                runtimeInfo.put("tags", extra.tags);
                runtimeInfo.put("config_guide", extra.configGuide);
                // display_name 优先级高于 tool.name
                if (extra.displayName != null && !extra.displayName.isEmpty()) {
                    runtimeInfo.put("name", extra.displayName);
                }
            } else {
                // 未注册，设为默认分类
                runtimeInfo.put("category", "buildin");
                runtimeInfo.put("tags", new ArrayList<>());
                runtimeInfo.put("config_guide", "");
            }
            METADATA_CACHE.add(runtimeInfo);
        }
        log.info("Tool service loaded {} tools (lazy load)", METADATA_CACHE.size());
    }

    /** 获取工具元数据列表（延迟加载，对应 {@code get_tool_metadata}）。 */
    public static List<Map<String, Object>> getToolMetadata(String category) {
        ensureMetadataLoaded();
        if (category != null && !category.isEmpty()) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> item : METADATA_CACHE) {
                if (category.equals(item.get("category"))) {
                    filtered.add(item);
                }
            }
            return filtered;
        }
        return METADATA_CACHE;
    }

    /** 按分类获取工具实例（对应 {@code get_tool_instances_by_category}）。 */
    public static List<ToolkitsRegistry.ToolDefinition> getToolInstancesByCategory(String category) {
        Map<String, ToolkitsRegistry.ToolExtraMetadata> extraMeta = ToolkitsRegistry.getAllExtraMetadata();
        List<ToolkitsRegistry.ToolDefinition> tools = new ArrayList<>();
        for (ToolkitsRegistry.ToolDefinition tool : ToolkitsRegistry.getAllToolInstances()) {
            ToolkitsRegistry.ToolExtraMetadata toolMeta = extraMeta.get(tool.getName());
            String toolCategory = toolMeta != null ? toolMeta.category : "buildin";
            if (toolCategory.equals(category)) {
                tools.add(tool);
            }
        }
        return tools;
    }

    /**
     * 按智能体配置装配运行时工具（对应 {@code resolve_configured_runtime_tools}）：
     * 基础工具（context.tools）+ MCP 工具（context.mcps）+ Skill 门控的本地工具。
     *
     * <p>与参考实现逐条对位：
     * ① 基础工具按 {@code category="buildin"} 从注册表取，找不到就 warn 跳过；
     * ② MCP server 名去重（保持配置顺序）后逐个取「启用中的」工具，失败 warn 跳过；
     * ③ MCP 工具与已选工具同名 → 抛「工具名冲突」；
     * ④ Skill 依赖的本地工具必须一并注册（否则 Skill 激活后执行器报 not a valid tool），
     *    同名且来源非 local → 抛「工具名冲突：Skill 本地工具 ...」。
     *
     * <p>能力差异（显式标注）：参考实现用 {@code asyncio.gather} 并发加载各 MCP server
     * （注释称单 server 33-300ms、串行 6 个累加 1-2s）；本实现按同一顺序<b>串行</b>加载，
     * 结果集合与顺序一致，仅耗时不同。
     */
    public static List<Object> resolveConfiguredRuntimeTools(
            BaseContext context, McpService mcpService, SkillRuntime skillRuntime) {
        List<Object> selectedTools = new ArrayList<>();
        Set<String> selectedToolNames = new LinkedHashSet<>();
        Map<String, String> selectedToolSources = new LinkedHashMap<>();

        Map<String, ToolkitsRegistry.ToolDefinition> buildinTools = new LinkedHashMap<>();
        for (ToolkitsRegistry.ToolDefinition tool : getToolInstancesByCategory("buildin")) {
            buildinTools.put(tool.getName(), tool);
        }

        Object configuredTools = context == null ? null : context.get("tools");
        if (configuredTools instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String toolName) || selectedToolNames.contains(toolName)) {
                    continue;
                }
                ToolkitsRegistry.ToolDefinition tool = buildinTools.get(toolName);
                if (tool == null) {
                    log.warn("Configured buildin tool not found, skip: {}", toolName);
                    continue;
                }
                selectedTools.add(tool);
                selectedToolNames.add(toolName);
                selectedToolSources.put(toolName, "local");
            }
        }

        Set<String> selectedMcpServers = new LinkedHashSet<>();
        List<String> serverNames = new ArrayList<>();
        Object configuredMcps = context == null ? null : context.get("mcps");
        if (configuredMcps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String serverName && selectedMcpServers.add(serverName)) {
                    serverNames.add(serverName);
                }
            }
        }
        for (String serverName : serverNames) {
            List<McpTool> mcpTools;
            try {
                mcpTools = mcpService == null ? List.of() : mcpService.getEnabledMcpTools(serverName);
            } catch (RuntimeException exc) {
                log.warn("Failed to load configured MCP tools '{}': {}", serverName, exc.getMessage());
                continue;
            }
            if (mcpTools == null || mcpTools.isEmpty()) {
                log.warn("Configured MCP unavailable, skip: {}", serverName);
                continue;
            }
            for (McpTool tool : mcpTools) {
                if (selectedToolNames.contains(tool.getName())) {
                    throw new IllegalStateException("工具名冲突：MCP '" + serverName + "' 的 '" + tool.getName()
                            + "' 与 " + selectedToolSources.get(tool.getName()) + " 工具同名");
                }
                selectedTools.add(tool);
                selectedToolNames.add(tool.getName());
                selectedToolSources.put(tool.getName(), "MCP '" + serverName + "'");
            }
        }

        if (skillRuntime != null) {
            for (ToolkitsRegistry.ToolDefinition tool : skillRuntime.resolveSkillGatedTools(context)) {
                if (selectedToolNames.contains(tool.getName())) {
                    if (!"local".equals(selectedToolSources.get(tool.getName()))) {
                        throw new IllegalStateException("工具名冲突：Skill 本地工具 '" + tool.getName()
                                + "' 与 " + selectedToolSources.get(tool.getName()) + " 同名");
                    }
                    continue;
                }
                selectedTools.add(tool);
                selectedToolNames.add(tool.getName());
                selectedToolSources.put(tool.getName(), "local");
            }
        }
        return selectedTools;
    }

    /** 把 Map&lt;?, ?&gt; 规整为字符串键的 Map（保留原值与顺序）。 */
    private static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
