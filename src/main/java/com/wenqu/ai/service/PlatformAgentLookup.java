package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 平台共享单行智能体的只读取口。
 * <p>全平台一份的行存在保留租户 {@link #PLATFORM_TENANT} 里，普通租户的查询带着
 * {@code tenant_id=当前租户}，永远看不到它。这里只做一件事：按固定 id 切到保留租户取这一行。
 * <p>刻意只依赖 {@link AgentMapper}：定时任务、轨迹回显这些直查 mapper 的点也要解析这一行，
 * 若让它们反过来依赖 {@code AgentService}，会绕出一条服务环。
 */
@Component
@RequiredArgsConstructor
public class PlatformAgentLookup {

    /** 平台保留租户：不登记进 {@code c_ai_tenant}，只给全平台共享行做仓库 */
    public static final String PLATFORM_TENANT = "__platform__";

    /** 平台预置「网页操作助手」的固定 id（全平台唯一一行） */
    public static final String BROWSER_AGENT_ID = "platform-browser-agent";

    private final AgentMapper agentMapper;

    /** 是否平台共享单行（只认固定 id，其它行一律按租户隔离） */
    public static boolean isShared(String id) {
        return BROWSER_AGENT_ID.equals(id);
    }

    /** 按固定 id 取平台共享行；不是这个 id 或行缺失时返回 null */
    public Agent find(String id) {
        if (!isShared(id)) return null;
        return TenantContext.as(PLATFORM_TENANT, () -> agentMapper.selectById(id));
    }
}
