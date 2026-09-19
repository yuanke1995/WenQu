package com.wisesoft.wenqu.agents;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具元数据服务，由参考实现的 {@code agents/toolkits/service.py} 翻译。
 *
 * <p>本类承载参考实现中的工具元数据缓存与两个查询函数：
 * {@code get_tool_metadata}（工具列表，可按分类过滤）与
 * {@code get_tool_instances_by_category}（按分类取工具实例）。
 *
 * <p>能力差异（显式标注，非遗漏）：参考实现还有 {@code resolve_configured_runtime_tools}
 * （按智能体配置并发装配本地工具 + MCP 工具 + Skill 门控工具），它依赖
 * {@code agents/mcp/service.py} 与 {@code agents/skills/runtime.py}，两者尚未照搬，
 * 故本类暂不含该方法（清单中该条目仍为未完成）。
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

    /** 把 Map&lt;?, ?&gt; 规整为字符串键的 Map（保留原值与顺序）。 */
    private static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
