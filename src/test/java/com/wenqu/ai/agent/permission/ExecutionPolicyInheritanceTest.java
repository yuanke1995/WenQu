package com.wenqu.ai.agent.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批档位继承语义：显式 &gt; 继承 &gt; 预设。
 * <p>这次线上事故的根因就是「子助手未设置却落到预设 ask，与父轮 auto 不一致」，
 * 这三组断言把修复后的优先级钉住。
 */
class ExecutionPolicyInheritanceTest {

    private static String approval(PermissionPreset preset, String agentMode, String inherited) {
        return ExecutionPolicy.of(preset, agentMode, inherited, "root", null).approvalMode();
    }

    @Test
    void 显式设置优先于父轮继承() {
        // 父轮 ask，子助手显式 auto/off：尊重子助手，不被父轮覆盖
        assertThat(approval(PermissionPreset.STANDARD, "auto", "ask")).isEqualTo("auto");
        assertThat(approval(PermissionPreset.STANDARD, "off", "ask")).isEqualTo("off");
        // 父轮 auto，子助手显式 ask：子助手自己要求审批
        assertThat(approval(PermissionPreset.STANDARD, "ask", "auto")).isEqualTo("ask");
    }

    @Test
    void 未设置时继承父轮() {
        assertThat(approval(PermissionPreset.STANDARD, null, "auto")).isEqualTo("auto");
        assertThat(approval(PermissionPreset.STANDARD, "  ", "ask")).isEqualTo("ask");
    }

    @Test
    void 未设置且父轮也没有时落预设默认() {
        assertThat(approval(PermissionPreset.STANDARD, null, null)).isEqualTo("ask");
        assertThat(approval(PermissionPreset.STANDARD, null, "  ")).isEqualTo("ask");
        assertThat(approval(PermissionPreset.AUTONOMOUS, null, null)).isEqualTo("auto");
    }
}