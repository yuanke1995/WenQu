package com.wisesoft.ai.service;

import com.wisesoft.ai.service.websearch.WebSearchTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具清单（管理员的「本平台有哪些工具」总览）。
 * <p>
 * 数据来源是**运行时注册的 @Tool 定义**（ToolCallbacks.from(...) 反射拿 name/description），
 * 不是硬编码清单——以后加新工具族，这里自动多一行，不会出现「代码里有、清单里没有」的漂移。
 * 中文名映射是唯一需要手维护的部分（@Tool description 是给模型看的英文指令风格，管理界面要中文）。
 * <p>
 * 与「内置工具可选项」（前端 AgentsPage 的 BUILTIN_TOOL_OPTIONS）的关系：那边是**智能体配置时**
 * 的多选框选项（内置族一个族一项），这边是**全景清单**（含所有族 + 开关状态），二者口径不同、
 * 各自维护；新增内置工具时两处都要补中文名。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolInventoryService {

    private final ConfigService configService;
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    private final BuiltinTools builtinTools;
    private final PresentArtifactTool presentArtifactTool;
    private final SandboxTools sandboxTools;
    private final WebSearchTools webSearchTools;
    private final SkillService skillService;

    /** 工具中文名（key=@Tool 方法名）。与前端 AgentsPage 的 BUILTIN_TOOL_OPTIONS 保持同步 */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("searchKnowledge", "知识库精确检索");
        LABELS.put("calculate", "算术计算");
        LABELS.put("currentDateTime", "当前日期时间");
        LABELS.put("daysBetween", "日期相差天数");
        LABELS.put("addDays", "日期推算");
        LABELS.put("randomNumber", "随机数");
        LABELS.put("uuid", "生成 UUID");
        LABELS.put("unitConvert", "单位换算");
        LABELS.put("textStats", "文本统计");
        LABELS.put("base64", "Base64 编解码");
        LABELS.put("hash", "哈希计算");
        LABELS.put("presentArtifact", "生成文件产物");
        LABELS.put("readSkill", "读取技能");
        LABELS.put("execute", "执行沙盒命令");
        LABELS.put("read_file", "读取沙盒文件");
        LABELS.put("write_file", "写入沙盒文件");
        LABELS.put("edit_file", "编辑沙盒文件");
        LABELS.put("ls", "查看沙盒目录");
        LABELS.put("deliver_artifact", "交付沙盒产物");
        LABELS.put("webSearch", "联网搜索");
    }

    /**
     * 汇总当前全部已注册工具。
     *
     * @return family 分组的清单：{family, familyLabel, globalKey, enabled, tools:[{name,label,description,sensitive}]}
     */
    public List<Map<String, Object>> inventory() {
        List<Map<String, Object>> out = new ArrayList<>();
        // 顺序即展示顺序：高频无害在前，敏感在后。
        // 注意：这里传**原始 bean**，由 group() 内部统一 ToolCallbacks.from() 转换——
        // 不能在外面先 from() 再传：转出来的 MethodToolCallback 上没有 @Tool 注解，
        // group 里再 from() 会抛 "No @Tool annotated methods found"（2026-10-01 实际踩过）
        out.add(group("builtin", "内置高频工具", "tool.builtin.enabled", builtinTools));
        out.add(group("knowledge", "知识库精确检索", "tool.knowledgeRetrieval.enabled", knowledgeRetrievalTool));
        out.add(group("websearch", "联网搜索", "webSearch.enabled", webSearchTools));
        out.add(group("artifact", "产物交付", "tool.artifact.enabled", presentArtifactTool));
        out.add(group("skill", "技能（Skills）", "tool.enabled", new SkillTools(skillService, "inventory")));
        out.add(group("sandbox", "沙盒执行", "tool.sandbox.enabled", sandboxTools));
        // MCP 没有静态清单（按用户登记动态接入），给一条说明行
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("family", "mcp");
        mcp.put("familyLabel", "MCP 外部工具");
        mcp.put("globalKey", null);
        mcp.put("enabled", configService.getBoolean("tool.enabled"));
        mcp.put("tools", List.of(Map.of(
                "name", "(按用户动态接入)",
                "label", "用户登记的 MCP Server 工具",
                "description", "每个用户在「MCP 服务」面板登记自己的 server，工具以 w_q_ 前缀动态合入，无静态清单",
                "sensitive", true)));
        out.add(mcp);
        return out;
    }

    private Map<String, Object> group(String family, String familyLabel, String globalKey,
                                      Object... toolHolders) {
        List<Map<String, Object>> tools = new ArrayList<>();
        java.util.Set<String> sensitive = java.util.Set.of("execute", "read_file", "write_file", "edit_file", "ls",
                "deliver_artifact");
        for (Object holder : toolHolders) {
            for (ToolCallback cb : ToolCallbacks.from(holder)) {
                var def = cb.getToolDefinition();
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", def.name());
                t.put("label", LABELS.getOrDefault(def.name(), def.name()));
                t.put("description", def.description());
                t.put("sensitive", sensitive.contains(def.name()));
                tools.add(t);
            }
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("family", family);
        g.put("familyLabel", familyLabel);
        g.put("globalKey", globalKey);
        // 工具总闸关着时所有族都不可用；族开关再决定本族是否挂上
        boolean master = configService.getBoolean("tool.enabled");
        g.put("enabled", master && (globalKey == null || configService.getBoolean(globalKey)));
        g.put("tools", tools);
        return g;
    }
}
