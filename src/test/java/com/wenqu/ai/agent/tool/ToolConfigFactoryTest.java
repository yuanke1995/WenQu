package com.wenqu.ai.agent.tool;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 允许集合 + 按名禁用都交给框架 ToolsConfig；不误伤框架平台工具。 */
class ToolConfigFactoryTest {

    @Test
    @DisplayName("policy deny 进入 ToolsConfig.deny，框架 ToolFilter 能拦住")
    void denyReachesFrameworkFilter() {
        ExecutionPolicy policy = ExecutionPolicy.of(PermissionPreset.STANDARD, "auto", "/workspace", "grep_files,wen_qugithub_search");
        ToolsConfig config = ToolConfigFactory.fromPolicyAndAllow(policy, java.util.List.of("searchKnowledge", "grep_files"));
        assertThat(config.getDeny()).containsExactlyInAnyOrder("grep_files", "wen_qugithub_search");
        assertThat(ToolFilter.isAllowed("grep_files", config)).isFalse();
        assertThat(ToolFilter.isAllowed("wen_qugithub_search", config)).isFalse();
        assertThat(ToolFilter.isAllowed("searchKnowledge", config)).isTrue();
    }

    @Test
    @DisplayName("允许集合：名单内工具保留、名单外问渠工具被裁掉、平台工具豁免")
    void allowSetFiltersBusinessTools() {
        ToolsConfig config = ToolConfigFactory.fromPolicyAndAllow(null, java.util.List.of("searchKnowledge"));
        assertThat(config.isStrictAllow()).isFalse();
        assertThat(config.isDefaultToolsEnabled()).isFalse();
        assertThat(ToolFilter.isAllowed("searchKnowledge", config)).isTrue();
        assertThat(ToolFilter.isAllowed("officeConvert", config)).isFalse();
        assertThat(ToolFilter.isAllowed("agent_spawn", config)).isTrue(); // 平台工具由框架放行
    }
}
