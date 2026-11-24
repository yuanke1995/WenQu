package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import com.wenqu.ai.agent.permission.SandboxEnforcement;
import com.wenqu.ai.agent.permission.SandboxMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-3「委派不放大权限」纯逻辑验证：成员策略 ∩ 根策略 = 取更严沙盒/审批、deny 并集。
 * 不依赖 DB/Redis，直接验证 {@link WenquAgentxTools#capPolicy} 与「根会话有无策略」的判据。
 */
class WenquAgentxPolicyCeilingTest {

    private static ExecutionPolicy policy(PermissionPreset preset, String approval, Set<String> deny) {
        SandboxMode sm = preset.sandboxMode();
        SandboxEnforcement ef = sm == SandboxMode.WORKSPACE_WRITE && sm.executable()
                ? SandboxEnforcement.PARTIAL : SandboxEnforcement.FULL;
        return new ExecutionPolicy(preset, sm, approval, ef, "/work", deny);
    }

    private static com.wenqu.ai.model.Agent agent(String id) {
        com.wenqu.ai.model.Agent a = new com.wenqu.ai.model.Agent();
        a.setId(id);
        return a;
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
    @DisplayName("根会话没有作答方也没选档位：不封顶（首轮派遣不该把成员的 auto 压成 ask）")
    void rootWithoutAgentAndPresetDoesNotCap() {
        // 线上实测场景：新建会话 c_ai_session.agent_id 恒为 NULL（那一列的语义是「最近一次作答的智能体」），
        // 若把它当成策略来源就会回落 standard/ask，把取数助手显式配的 auto 压掉。
        assertThat(WenquAgentxTools.rootHasNoPolicy(null, null)).isTrue();
        assertThat(WenquAgentxTools.rootHasNoPolicy(null, "")).isTrue();
        assertThat(WenquAgentxTools.rootHasNoPolicy(null, "  ")).isTrue();
    }

    @Test
    @DisplayName("根会话有作答方或显式档位：仍要封顶（委派不放大权限这条不变量不变）")
    void rootWithAgentOrPresetStillCaps() {
        // 根有作答方：它的 auto/ask 是真实配置，必须参与取严
        assertThat(WenquAgentxTools.rootHasNoPolicy(agent("a1"), null)).isFalse();
        // 根没作答方但会话栏选了档位：那也是用户显式选的
        assertThat(WenquAgentxTools.rootHasNoPolicy(null, "autonomous")).isFalse();
        assertThat(WenquAgentxTools.rootHasNoPolicy(null, "standard")).isFalse();
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
