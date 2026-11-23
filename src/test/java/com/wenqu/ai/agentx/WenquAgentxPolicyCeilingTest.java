package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import com.wenqu.ai.agent.permission.SandboxEnforcement;
import com.wenqu.ai.agent.permission.SandboxMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-3「委派不放大权限」纯逻辑验证：成员策略 ∩ 根策略 = 取更严沙盒/审批、deny 并集。
 * 不依赖 DB/Redis，直接验证 {@link WenquAgentxTools#capPolicy}。
 */
class WenquAgentxPolicyCeilingTest {

    private static ExecutionPolicy policy(PermissionPreset preset, String approval, Set<String> deny) {
        SandboxMode sm = preset.sandboxMode();
        SandboxEnforcement ef = sm == SandboxMode.WORKSPACE_WRITE && sm.executable()
                ? SandboxEnforcement.PARTIAL : SandboxEnforcement.FULL;
        return new ExecutionPolicy(preset, sm, approval, ef, "/work", deny);
    }

    @Test
    void memberCannotEscalateSandboxOrApproval() {
        // 成员想要完全权限 + auto；根只有 standard + ask → 被钳到 standard/ask
        ExecutionPolicy member = policy(PermissionPreset.AUTONOMOUS, "auto", Set.of("a"));
        ExecutionPolicy root = policy(PermissionPreset.STANDARD, "ask", Set.of("b"));
        ExecutionPolicy capped = WenquAgentxTools.capPolicy(member, root);
        assertThat(capped.sandboxMode()).isEqualTo(SandboxMode.WORKSPACE_WRITE);
        assertThat(capped.preset()).isEqualTo(PermissionPreset.STANDARD);
        assertThat(capped.approvalMode()).isEqualTo("ask");
        assertThat(capped.deny()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void memberKeepsItsStricterSide() {
        // 成员只读 + ask；根全权 + auto → 成员保持只读/ask（不因根宽而放宽）
        ExecutionPolicy member = policy(PermissionPreset.RESEARCH, "ask", Set.of());
        ExecutionPolicy root = policy(PermissionPreset.AUTONOMOUS, "auto", Set.of());
        ExecutionPolicy capped = WenquAgentxTools.capPolicy(member, root);
        assertThat(capped.sandboxMode()).isEqualTo(SandboxMode.READ_ONLY);
        assertThat(capped.preset()).isEqualTo(PermissionPreset.RESEARCH);
        assertThat(capped.approvalMode()).isEqualTo("ask");
    }

    @Test
    void offApprovalIsStricterThanAsk() {
        ExecutionPolicy member = policy(PermissionPreset.STANDARD, "auto", Set.of());
        ExecutionPolicy root = policy(PermissionPreset.STANDARD, "off", Set.of());
        ExecutionPolicy capped = WenquAgentxTools.capPolicy(member, root);
        assertThat(capped.approvalMode()).isEqualTo("off");
    }
}
