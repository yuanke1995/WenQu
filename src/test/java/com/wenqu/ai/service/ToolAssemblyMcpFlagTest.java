package com.wenqu.ai.service;

import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.model.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 族三态列必须被装配判据读到：UI 的「不使用 / 指定 / 跟随全局」写进 toolMcp，
 * 但 agentFlagFor 若把 MCP 排除在映射之外，McpToolProvider 的 flag=0 判据永远拿不到值——
 * 用户关掉 MCP 仍然挂载。本测试锁住这条映射，防止以后再漏。
 */
class ToolAssemblyMcpFlagTest {

    private final ToolAssemblyService service = new ToolAssemblyService(null, null);

    @Test
    @DisplayName("MCP 三态列映射到 MCP 族：0=关、1=开、null=继承")
    void mcpFlagIsMapped() {
        Agent a = new Agent();

        a.setToolMcp(0);
        assertThat(service.agentFlagFor(ToolFamily.MCP, a)).isEqualTo(0);

        a.setToolMcp(1);
        assertThat(service.agentFlagFor(ToolFamily.MCP, a)).isEqualTo(1);

        a.setToolMcp(null);
        assertThat(service.agentFlagFor(ToolFamily.MCP, a)).isNull();
    }

    @Test
    @DisplayName("MCP 具体项选择仍由 mcps 独立承载")
    void mcpSelectionIsIndependent() {
        Agent a = new Agent();
        a.setToolMcp(1);
        assertThat(service.agentSelectionFor(ToolFamily.MCP, a)).isNull();
        a.setMcps("");
        assertThat(service.agentSelectionFor(ToolFamily.MCP, a)).isEmpty();
        a.setMcps("github,pg");
        assertThat(service.agentSelectionFor(ToolFamily.MCP, a))
                .containsExactlyInAnyOrder("github", "pg");
    }
}
