package com.wenqu.ai.service;

import com.wenqu.ai.agent.permission.PermissionPolicyResolver;
import com.wenqu.ai.model.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolAssemblyServiceToolStepsTest {

    private final ToolAssemblyService service = new ToolAssemblyService(
            mock(ConfigService.class), mock(PermissionPolicyResolver.class));

    @Test
    @DisplayName("分支沿用智能体自己的步数，不再按派遣深度右移砍半")
    void subagentKeepsAgentBudget() {
        Agent a = new Agent();
        a.setMaxToolSteps(50);
        assertThat(service.maxToolSteps(a, 0)).isEqualTo(50);
        assertThat(service.maxToolSteps(a, 1)).isEqualTo(50);
        assertThat(service.maxToolSteps(a, 3)).isEqualTo(50);
    }

    @Test
    @DisplayName("没设步数与平台默认不限制时，分支也不额外造一个限制")
    void noBudgetStaysUnlimited() {
        assertThat(service.maxToolSteps(null, 1)).isZero();
        Agent a = new Agent();
        assertThat(service.maxToolSteps(a, 1)).isZero();
    }

    @Test
    @DisplayName("显式 0（不限制）在分支里仍是 0，不被翻译成一个很小的上限")
    void explicitZeroStaysUnlimited() {
        Agent a = new Agent();
        a.setMaxToolSteps(0);
        assertThat(service.maxToolSteps(a, 2)).isZero();
    }
}