package com.wenqu.ai.service;

import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具清单（管理员的「本平台有哪些工具」总览）。
 * <p>
 * 数据来源是 {@link ToolRegistry}：工具名、中文名、开关归属、是否算有副作用，都由各工具族的
 * {@code ToolFamilyProvider} 声明一次，这里只负责按族展示。迁移前这个类自己维护一张中文名表
 * 与一份硬编码的 sensitive 集合，与装配处（{@code ChatService.enabledToolCallbacks}）、前端两处
 * 各抄各的——新工具上线时清单里只剩英文名（writeTodo 就漏过），改一个开关要同步三处。
 * <p>
 * 模型可见的 description 仍然是反射出来的（{@code @Tool} 注解原文），不另存一份：
 * 那是喂给模型的提示，抄进清单就成了第四份副本。
 * <p>
 * 与「内置工具可选项」（前端 AgentsPage 的 BUILTIN_TOOL_OPTIONS）的关系不变：那边是智能体配置时
 * 的多选框选项，这边是全景清单，口径不同；<b>但 AgentsPage 那份仍是前端手抄</b>——它是登录用户
 * 都要读的，而本接口按既定口径不开普通用户洞（见 ToolInventoryController 注释），留给后续统一。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolInventoryService {

    private final ConfigService configService;
    private final ToolRegistry registry;

    /**
     * 汇总当前全部已注册工具。
     *
     * @return family 分组的清单：{family, familyLabel, globalKey, enabled, tools:[{name,label,description,sensitive}]}
     */
    public List<Map<String, Object>> inventory() {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean master = configService.getBoolean("tool.enabled");
        // 枚举顺序即展示顺序：高频无害在前，敏感在后
        for (ToolFamily family : ToolFamily.values()) {
            List<ToolDescriptor> ds = registry.ofFamily(family);
            if (ds.isEmpty()) {
                if (family == ToolFamily.MCP) out.add(mcpNoticeRow(master));
                continue;
            }
            String globalKey = ds.stream().map(ToolDescriptor::gateKey)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolDescriptor d : ds) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", d.name());
                t.put("label", d.label());
                t.put("description", orEmpty(registry.modelDescription(d.name())));
                t.put("sensitive", d.requiresApproval(configService::getBoolean));
                // 副作用等级：P2 按档位收紧时要用它区分「只读」与「变更」，先在清单里可见
                t.put("sideEffect", d.sideEffect().name().toLowerCase());
                tools.add(t);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("family", family.code());
            g.put("familyLabel", family.familyLabel());
            g.put("globalKey", globalKey);
            // 工具总闸关着时所有族都不可用；族开关再决定本族是否挂上
            g.put("enabled", master && (globalKey == null || configService.getBoolean(globalKey)));
            g.put("tools", tools);
            out.add(g);
        }
        return out;
    }

    /** MCP 没有静态清单（按用户登记动态接入），给一条说明行 */
    private Map<String, Object> mcpNoticeRow(boolean master) {
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("family", ToolFamily.MCP.code());
        mcp.put("familyLabel", ToolFamily.MCP.familyLabel());
        mcp.put("globalKey", null);
        mcp.put("enabled", master);
        mcp.put("tools", List.of(Map.of(
                "name", "(按用户动态接入)",
                "label", "用户登记的 MCP Server 工具",
                "description", "每个用户在「MCP 服务」面板登记自己的 server，工具以 wen_qu_ 前缀动态合入，无静态清单",
                "sensitive", true,
                "sideEffect", "external")));
        return mcp;
    }

    /**
     * 登录用户可读的能力名册（智能体配置页「指定内置工具」的可选项就从这里来）。
     * <p>与 {@link #inventory()} 的字段差别是有意的：那份给管理员看全景，带全局开关键与 enabled 状态；
     * 这份只出名字与展示字段——普通用户不需要知道平台的开关长什么样，少给一个字段就少一处
     * 可以被拿去猜配置的面。
     * <p>访问控制沿用既有口径：端点挂在 {@code /api/ai/agent/tool-catalog}，落在
     * {@code SecurityConfig.isAgentSelfEndpoint} 已放行的「智能体自助端点」分支里
     * （那条规则按既有形状匹配一段路径），**不新增通配规则、也不给管理员视图
     * {@code /api/ai/tools} 开普通用户洞**（后者按既定口径刻意不开）。
     * <p>它存在的理由是消灭第三份手抄：前端 AgentsPage 原来自己维护一张内置工具中文名表，
     * 后端加一个内置工具就得同时改两处，漏一处的表现是「配置页里看不见这个工具的名字」。
     */
    public List<Map<String, Object>> agentCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        // 平台停用名单（tool.deny）：配置页的「禁用指定工具」据此把已被平台停掉的项标出来，
        // 否则管理员会看到"我能选它"而它其实全局不挂了——那种界面与事实不一致最难排查。
        java.util.Set<String> platformDenied = deniedByName(configService.get("tool.deny"));
        for (ToolDescriptor d : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("family", d.family().code());
            m.put("familyLabel", d.family().familyLabel());
            m.put("name", d.name());
            m.put("label", d.label());
            m.put("cardLabel", d.cardLabel());
            m.put("platformDenied", platformDenied.contains(d.name()));
            out.add(m);
        }
        return out;
    }

    /** 逗号名单 → 名字集合（与执行侧同一份解析口径：去空白、忽略空项） */
    static java.util.Set<String> deniedByName(String csv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return java.util.Set.copyOf(out);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
