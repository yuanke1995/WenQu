package com.wisesoft.wenqu.agents;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 智能体运行时上下文的资源字段集合。
 *
 * <p>由参考实现的 agents/context.py 中三个模块级常量翻译：
 * {@code _DEFAULT_ALL_CONTEXT_FIELDS}、{@code _EMPTY_ALL_CONTEXT_FIELDS} 与
 * 二者并集 {@code AGENT_RUNTIME_RESOURCE_FIELDS}，以及仓储层使用的
 * {@code AGENT_RESOURCE_CONFIG_FIELDS}（并上 preload_skills）。
 *
 * <p>说明：参考实现的 agents/context.py 是完整的运行时上下文模块，本工程当前只需要其中
 * 这几个字段集合（仓储层用它判定"哪些 context 字段是资源引用"），故只搬常量；
 * 其余运行时逻辑留待服务层照搬时一并处理。
 */
public final class AgentContextFields {

    /** 默认情况下"全选"语义的资源字段（值为 null 表示全选）。 */
    public static final Set<String> DEFAULT_ALL_CONTEXT_FIELDS =
            Set.of("tools", "knowledges", "mcps", "skills");

    /** 默认情况下"空集合"语义的资源字段（值为空列表表示不使用）。 */
    public static final Set<String> EMPTY_ALL_CONTEXT_FIELDS = Set.of("subagents");

    /** 运行时资源字段（并集）。 */
    public static final Set<String> AGENT_RUNTIME_RESOURCE_FIELDS = union();

    /** 智能体配置中的资源字段（运行时资源字段 + preload_skills）。 */
    public static final Set<String> AGENT_RESOURCE_CONFIG_FIELDS = unionWithPreloadSkills();

    private AgentContextFields() {}

    private static Set<String> union() {
        Set<String> fields = new LinkedHashSet<>(DEFAULT_ALL_CONTEXT_FIELDS);
        fields.addAll(EMPTY_ALL_CONTEXT_FIELDS);
        return java.util.Collections.unmodifiableSet(fields);
    }

    private static Set<String> unionWithPreloadSkills() {
        Set<String> fields = new LinkedHashSet<>(union());
        fields.add("preload_skills");
        return java.util.Collections.unmodifiableSet(fields);
    }
}
