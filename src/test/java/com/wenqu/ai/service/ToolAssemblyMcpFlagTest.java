package com.wenqu.ai.service;

import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.model.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 族三态列必须被业务选择适配器读到：UI 的「不使用 / 指定 / 跟随全局」写进 toolMcp，
 * 若映射漏了 MCP，McpToolProvider 的 flag=0 判据永远拿不到值——用户关掉 MCP 仍然挂载。
 */
class ToolAssemblyMcpFlagTest {

    @Test
    @DisplayName("MCP 三态列映射到 MCP 族：0=关、1=开、null=继承")
    void mcpFlagIsMapped() {
        Agent a = new Agent();

        a.setToolMcp(0);
        assertThat(AgentToolSelectionAdapter.flagOf(ToolFamily.MCP, a)).isEqualTo(0);
        assertThat(AgentToolSelectionAdapter.from(a).flag(ToolFamily.MCP)).isEqualTo(0);

        a.setToolMcp(1);
        assertThat(AgentToolSelectionAdapter.flagOf(ToolFamily.MCP, a)).isEqualTo(1);
        assertThat(AgentToolSelectionAdapter.from(a).flag(ToolFamily.MCP)).isEqualTo(1);

        a.setToolMcp(null);
        assertThat(AgentToolSelectionAdapter.flagOf(ToolFamily.MCP, a)).isNull();
        assertThat(AgentToolSelectionAdapter.from(a).flag(ToolFamily.MCP)).isNull();
    }

    @Test
    @DisplayName("MCP 具体项选择仍由 mcps 独立承载")
    void mcpSelectionIsIndependent() {
        Agent a = new Agent();
        a.setToolMcp(1);
        assertThat(AgentToolSelectionAdapter.selectionOf(ToolFamily.MCP, a)).isNull();
        a.setMcps("");
        assertThat(AgentToolSelectionAdapter.selectionOf(ToolFamily.MCP, a)).isEmpty();
        a.setMcps("github,pg");
        assertThat(AgentToolSelectionAdapter.selectionOf(ToolFamily.MCP, a))
                .containsExactlyInAnyOrder("github", "pg");
    }
}
