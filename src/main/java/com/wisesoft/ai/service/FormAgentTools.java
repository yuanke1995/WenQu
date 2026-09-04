package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.config.AiAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 智能表单 Agent 的工具集：暴露给 LLM 调用的「手」。
 * 由 Spring AI 扫描 @Tool 方法注册为 Function Calling 工具（chatClient.defaultTools(this) 注入）。
 *
 * <p>当前能力（P0-b）：
 * <ul>
 *   <li>listFormTables —— 查看可用的表单清单</li>
 *   <li>getFormStructure —— 查看某张表单的字段结构</li>
 *   <li>validateFormDraft —— 校验一组填报 JSON 的字段是否合法</li>
 * </ul>
 * 结构数据会做精简（保留 label/columnName/type 等关键信息），避免把动态表单系统内部
 * 完整 components（含画布坐标/样式）整包塞给模型造成 token 浪费。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormAgentTools {

    private final FormAgentToolService toolService;
    private final AiAppProperties properties;

    @Tool(description = "列出当前可用的动态表单清单。返回每张表的 tableId、表名(name)、表代码(tableCode)等。用户想填表/看表单前先调用本工具确认有哪些表。")
    public String listFormTables() {
        ensureEnabled();
        try {
            JSONObject data = toolService.listTables();
            return data == null ? "无可用表单" : data.toJSONString();
        } catch (Exception e) {
            return "查询失败：" + e.getMessage();
        }
    }

    @Tool(description = "查看指定表单的字段结构，用于了解表单要填什么。入参 tableId 是表单 ID（先调 listFormTables 获取）。返回组件(components)与字段(columns)的精简信息，含字段名(columnName/fieldName)、显示名(name)、类型(widgetType/dataType)与子表关联(childTableId)。")
    public String getFormStructure(
            @ToolParam(description = "表单 ID，来自 listFormTables 返回的 tableId") String tableId) {
        ensureEnabled();
        try {
            JSONObject raw = toolService.getTableStructure(tableId);
            return compact(raw);
        } catch (Exception e) {
            return "查询失败：" + e.getMessage();
        }
    }

    @Tool(description = "校验一份待填报/待提交的 JSON 数据字段是否属于该表单结构（防幻觉字段）。入参 tableId 为表单 ID，data 为形如 {\"字段\":\"值\"} 的填报数据。返回 pass(是否全部通过)与 unknownFields(不在表结构中的字段名)。")
    public String validateFormDraft(
            @ToolParam(description = "表单 ID") String tableId,
            @ToolParam(description = "填报数据对象，如 {\"姓名\":\"张三\",\"年龄\":18}") Map<String, Object> data) {
        ensureEnabled();
        try {
            JSONObject result = toolService.validateDraft(tableId, data);
            return result == null ? "校验无返回" : result.toJSONString();
        } catch (Exception e) {
            return "校验失败：" + e.getMessage();
        }
    }

    /** 工具禁用时统一提示 */
    private void ensureEnabled() {
        if (!toolService.isConfigured()) {
            throw new IllegalStateException("智能表单服务未配置，无法执行此操作");
        }
    }

    /**
     * 精简结构响应：保留 components 的 name/instance.label/prop/type + children，
     * columns 保留核心列；大字段截断，控制注入模型的 token 量。
     */
    private String compact(JSONObject raw) {
        if (raw == null) return "无结构数据";
        JSONObject out = new JSONObject();
        out.put("tableId", raw.get("tableId"));
        out.put("name", raw.get("name"));
        out.put("tableCode", raw.get("tableCode"));
        out.put("dbTableName", raw.get("dbTableName"));
        out.put("tableType", raw.get("tableType"));
        out.put("hasChildren", raw.get("hasChildren"));

        java.util.List<Object> comps = raw.getList("components", Object.class);
        if (comps != null) {
            out.put("components", comps); // 设计器组件树，直接透传（含 label/prop/children）
        }
        java.util.List<Object> cols = raw.getList("columns", Object.class);
        if (cols != null) {
            java.util.List<JSONObject> slim = new java.util.ArrayList<>();
            for (Object o : cols) {
                if (!(o instanceof JSONObject jc)) continue;
                JSONObject s = new JSONObject();
                // 仅保留对"生成填报内容"有意义的核心字段
                s.put("columnName", jc.get("columnName"));
                s.put("fieldName", jc.get("fieldName"));
                s.put("name", jc.get("name"));
                s.put("dataType", jc.get("dataType"));
                s.put("widgetType", jc.get("widgetType"));
                s.put("fieldType", jc.get("fieldType"));
                s.put("childTableId", jc.get("childTableId"));
                // 字典/下拉来源
                s.put("dataSourceType", jc.get("dataSourceType"));
                s.put("dataSourceValue", jc.get("dataSourceValue"));
                slim.add(s);
            }
            out.put("columns", slim);
        }
        String str = out.toJSONString();
        // 超长兜底截断（结构异常大时保护上下文）
        return str.length() > 20000 ? str.substring(0, 20000) + "...(截断)" : str;
    }
}
