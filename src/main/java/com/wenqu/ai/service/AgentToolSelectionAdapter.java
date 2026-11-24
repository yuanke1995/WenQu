package com.wenqu.ai.service;

import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolSelection;
import com.wenqu.ai.model.Agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 列 → {@link ToolSelection} 的<strong>业务适配器</strong>。
 * <p>这是问渠唯一认识「哪个工具族对应 Agent 哪一列」的地方；装配层与 provider 都不再直读 Agent 字段。
 * 未来 Agent 表收敛成通用绑定后，只需要替换本适配器，不用改装配与工具实现。
 */
public final class AgentToolSelectionAdapter {

    private AgentToolSelectionAdapter() {
    }

    public static ToolSelection from(Agent agent) {
        if (agent == null) return ToolSelection.EMPTY;
        Map<ToolFamily, Integer> flags = new EnumMap<>(ToolFamily.class);
        Map<ToolFamily, Set<String>> selections = new EnumMap<>(ToolFamily.class);
        for (ToolFamily family : ToolFamily.values()) {
            Integer flag = flagOf(family, agent);
            if (flag != null) flags.put(family, flag);
            Set<String> selected = selectionOf(family, agent);
            if (selected != null) selections.put(family, selected);
        }
        return new ToolSelection(Map.copyOf(flags), Map.copyOf(selections));
    }

    /** 族 → 智能体级三态列（null = 本族没有智能体级开关，跟随全局）。 */
    public static Integer flagOf(ToolFamily family, Agent agent) {
        if (agent == null) return null;
        return switch (family) {
            case ASK_USER -> agent.getToolBuiltin();
            case KNOWLEDGE -> agent.getToolKnowledge();
            case WEBSEARCH -> agent.getToolWebsearch();
            case HTTP_API -> agent.getToolHttp();
            case WORKFLOW -> agent.getToolWorkflow();
            case ARTIFACT -> agent.getToolArtifact();
            case SKILL -> agent.getToolSkill();
            case MCP -> agent.getToolMcp();
            case BROWSER -> agent.getToolBrowser();
            case SANDBOX, SSH, TODO, TEAM, LOCAL_BROWSER, OFFICE, COMPUTER_USE,
                    SESSION_RETRIEVAL, MCP_RESOURCE -> null;
        };
    }

    /** 族 → 智能体的「具体项」选择（null=未指定，本族全挂；只有内置与 MCP 有这一层）。 */
    public static Set<String> selectionOf(ToolFamily family, Agent agent) {
        if (agent == null) return null;
        return switch (family) {
            case ASK_USER -> scopeOf(agent.getBuiltinTools());
            case MCP -> scopeOf(agent.getMcps());
            default -> null;
        };
    }

    /** null=跟随全局不筛具体项；空串=显式一个都不用；"a,b"=只用这些。 */
    public static Set<String> scopeOf(String v) {
        if (v == null) return null;
        if (v.isBlank()) return Collections.emptySet();
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
