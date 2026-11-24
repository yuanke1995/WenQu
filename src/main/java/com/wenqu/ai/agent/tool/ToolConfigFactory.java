package com.wenqu.ai.agent.tool;

import io.agentscope.harness.agent.tools.ToolsConfig;

import java.util.ArrayList;

/**
 * 问渠业务策略 → 框架 {@link ToolsConfig} 的薄适配器。
 * <p>只做一件事：把本轮的按名禁用名单交给框架 {@code ToolFilter}。三态族开关与具体项白名单仍由
 * 问渠业务层解析，不在这里重新实现一遍；等 Agent 表收敛后再把「允许集合」也迁进来。
 */
public final class ToolConfigFactory {

    private ToolConfigFactory() {
    }

    public static ToolsConfig fromPolicy(com.wenqu.ai.agent.permission.ExecutionPolicy policy) {
        ToolsConfig config = new ToolsConfig();
        // 不设 allow 白名单：strictAllow 会误伤框架 build 期后挂的文件/记忆/子智能体工具；
        // deny 是叠加语义，正好表达「其余照旧、只拿掉点名的那几个」。
        config.setStrictAllow(false);
        config.setDefaultToolsEnabled(true);
        config.setAllow(null);
        config.setDeny(policy == null || policy.deny().isEmpty()
                ? null : new ArrayList<>(policy.deny()));
        return config;
    }
}
