package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.agents.ToolkitsRegistry.ToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具信息展示辅助。
 *
 * <p>由参考实现的 agents/toolkits/utils.py 逐函数翻译：get_tool_info ——
 * 获取所有工具的信息（用于前端展示）。
 */
@Slf4j
public final class ToolkitsUtils {

    private ToolkitsUtils() {}

    /** 获取所有工具的信息（用于前端展示）。 */
    public static List<Map<String, Object>> getToolInfo(List<ToolDefinition> tools) {
        List<Map<String, Object>> toolsInfo = new ArrayList<>();

        try {
            // 获取注册的工具信息
            for (ToolDefinition toolObj : tools) {
                try {
                    Map<String, Object> metadata = toolMetadata(toolObj);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", toolObj.getName());
                    info.put("name", metadata.getOrDefault("name", toolObj.getName()));
                    info.put("description", toolObj.getDescription());
                    info.put("metadata", metadata);
                    info.put("args", new ArrayList<>());

                    Map<String, Object> schema = toolObj.getArgsSchema();
                    if (schema != null && !schema.isEmpty()) {
                        Object properties = schema.get("properties");
                        if (properties instanceof Map) {
                            for (Map.Entry<?, ?> entry : ((Map<?, ?>) properties).entrySet()) {
                                if (!(entry.getValue() instanceof Map)) {
                                    continue;
                                }
                                Map<?, ?> argInfo = (Map<?, ?>) entry.getValue();
                                Map<String, Object> arg = new LinkedHashMap<>();
                                arg.put("name", String.valueOf(entry.getKey()));
                                arg.put("type", argInfo.get("type") == null ? "" : String.valueOf(argInfo.get("type")));
                                arg.put(
                                        "description",
                                        argInfo.get("description") == null ? "" : String.valueOf(argInfo.get("description")));
                                ((List<Map<String, Object>>) info.get("args")).add(arg);
                            }
                        }
                    }

                    toolsInfo.add(info);
                } catch (RuntimeException exc) {
                    log.error(
                            "Failed to process tool {}: {}. Details: {}",
                            toolObj.getName(),
                            exc.getMessage(),
                            toolObj);
                    continue;
                }
            }
        } catch (RuntimeException exc) {
            log.error("Failed to get tools info: {}", exc.getMessage());
            return new ArrayList<>();
        }

        log.info("Successfully extracted info for {} tools", toolsInfo.size());
        return toolsInfo;
    }

    /** langchain tool_obj.metadata（此处由 ToolDefinition 实现自带或空表）。 */
    private static Map<String, Object> toolMetadata(ToolDefinition toolObj) {
        if (toolObj instanceof WithMetadata withMetadata) {
            Map<String, Object> metadata = withMetadata.getMetadata();
            return metadata == null ? new LinkedHashMap<>() : metadata;
        }
        return new LinkedHashMap<>();
    }

    /** 可选能力面：langchain tool 的 metadata 字段（getattr(tool_obj, "metadata", {})）。 */
    public interface WithMetadata {
        Map<String, Object> getMetadata();
    }
}
