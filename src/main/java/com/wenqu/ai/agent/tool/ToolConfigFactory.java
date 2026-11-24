package com.wenqu.ai.agent.tool;

import io.agentscope.harness.agent.tools.ToolsConfig;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 问渠业务策略 → 框架 {@link ToolsConfig} 的薄适配器。
 * <p>只做两件事：把本轮的按名禁用名单交给框架，并把问渠算出的「允许集合」交给框架裁剪。
 * 三态族开关与具体项白名单的业务解析在问渠装配层（{@code ToolRegistry.exposes} 单点），
 * 不在这里重新实现；框架负责 schema/查找/执行三处同时消失。
 */
public final class ToolConfigFactory {

    private ToolConfigFactory() {
    }

    /**
     * 表达「允许集合 + deny」：allow 之外的问渠工具被框架移除，平台工具由框架的 platform 白名单放行。
     * <p>{@code strictAllow=false}：框架自己的 team/subagent/plan/skill_manage 等平台工具不在
     * 问渠 allow 名单里，必须由框架的 platform 判定放行，不能一并裁掉。
     */
    public static ToolsConfig fromPolicyAndAllow(com.wenqu.ai.agent.permission.ExecutionPolicy policy,
                                                 Collection<String> allowedToolNames) {
        ToolsConfig config = base(policy);
        config.setStrictAllow(false);
        config.setDefaultToolsEnabled(false);
        config.setAllow(allowedToolNames == null || allowedToolNames.isEmpty()
                ? new ArrayList<>() : new ArrayList<>(allowedToolNames));
        return config;
    }

    private static ToolsConfig base(com.wenqu.ai.agent.permission.ExecutionPolicy policy) {
        ToolsConfig config = new ToolsConfig();
        config.setDeny(policy == null || policy.deny().isEmpty()
                ? null : new ArrayList<>(policy.deny()));
        return config;
    }
}
