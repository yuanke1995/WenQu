package com.wenqu.ai.agent.tool;

import java.util.Map;
import java.util.Set;

/**
 * 智能体对工具族的业务选择（与框架无关的纯值对象）。
 * <p>它是「Agent 列 → 工具选择」翻译后的结果：装配层、提示词侧、provider 都只读这一份，
 * 不再各自 switch Agent 字段。真正的列映射在 service 层的 {@code AgentToolSelectionAdapter}。
 */
public record ToolSelection(Map<ToolFamily, Integer> flags,
                            Map<ToolFamily, Set<String>> selections) {

    public static final ToolSelection EMPTY = new ToolSelection(Map.of(), Map.of());

    public Integer flag(ToolFamily family) {
        return flags == null ? null : flags.get(family);
    }

    /** null=未指定（全挂）；空集=显式一个都不选；非空=只选这些。 */
    public Set<String> selection(ToolFamily family) {
        return selections == null ? null : selections.get(family);
    }
}
