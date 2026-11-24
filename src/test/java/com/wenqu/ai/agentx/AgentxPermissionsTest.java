package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 问渠档位 → 框架 PermissionContextState 的契约：模式与按名 deny 不各自漂移。 */
class AgentxPermissionsTest {

    private static ExecutionPolicy policy(PermissionPreset preset, String denyCsv) {
        return ExecutionPolicy.of(preset, "auto", "/workspace", denyCsv);
    }

    @Test
    @DisplayName("三档映射到 EXPLORE/DEFAULT/DONT_ASK，且不使用 BYPASS")
    void modes() {
        assertThat(AgentxPermissions.modeOf(policy(PermissionPreset.RESEARCH, null))).isEqualTo(PermissionMode.EXPLORE);
        assertThat(AgentxPermissions.modeOf(policy(PermissionPreset.STANDARD, null))).isEqualTo(PermissionMode.DEFAULT);
        assertThat(AgentxPermissions.modeOf(policy(PermissionPreset.AUTONOMOUS, null))).isEqualTo(PermissionMode.DONT_ASK);
    }

    @Test
    @DisplayName("toolDeny 落成框架 DENY 规则，不在执行侧另抄名单")
    void denyRules() {
        PermissionContextState ctx = AgentxPermissions.contextOf(policy(PermissionPreset.STANDARD, "grep_files,wen_qugithub_search"));
        assertThat(ctx.getDenyRules()).containsKeys("grep_files", "wen_qugithub_search");
        assertThat(ctx.getDenyRules().get("grep_files"))
                .allSatisfy(rule -> assertThat(rule.behavior()).isEqualTo(PermissionBehavior.DENY));
    }

    @Test
    @DisplayName("游客授权 MCP 名单把 RESEARCH 提到 DEFAULT，空名单保持 EXPLORE")
    void guestMcpAllow() {
        assertThat(AgentxPermissions.modeOf(policy(PermissionPreset.RESEARCH, null), java.util.Set.of("github")))
                .isEqualTo(PermissionMode.DEFAULT);
        assertThat(AgentxPermissions.modeOf(policy(PermissionPreset.RESEARCH, null), java.util.Set.of()))
                .isEqualTo(PermissionMode.EXPLORE);
    }
}
