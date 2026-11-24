package com.wenqu.ai.agent.chat;

import com.wenqu.ai.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AgentTurnOverride 的纯值对象契约：稳定指纹、空集合语义、非法输入 fail-loud。 */
class AgentTurnOverrideTest {

    @Test
    @DisplayName("空覆盖指纹为空串，保证旧实例键逐字节不变")
    void emptyFingerprint() {
        assertThat(AgentTurnOverride.none().fingerprint()).isEmpty();
        assertThat(AgentTurnOverride.of(null, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("相同配置顺序无关；未给与空集合不同")
    void fingerprintIsStableAndDistinguishesEmpty() {
        var a = AgentTurnOverride.of(3, new AgentTurnOverride.KnowledgePatch(
                List.of("kb1", "kb2"), List.of("tag1"), "{\"updatedWithinDays\":7}", 0));
        var b = AgentTurnOverride.of(3, new AgentTurnOverride.KnowledgePatch(
                List.of("kb2", "kb1"), List.of("tag1"), "{\"updatedWithinDays\":7}", 0));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());

        var noKb = AgentTurnOverride.of(3, new AgentTurnOverride.KnowledgePatch(
                null, null, null, null));
        var emptyKb = AgentTurnOverride.of(3, new AgentTurnOverride.KnowledgePatch(
                List.of(), null, null, null));
        assertThat(noKb.fingerprint()).isNotEqualTo(emptyKb.fingerprint());
    }

    @Test
    @DisplayName("knowledgePatch 正确写入 Agent 视图，null 字段不动")
    void applyKnowledgePatch() {
        com.wenqu.ai.model.Agent a = new com.wenqu.ai.model.Agent();
        a.setKnowledgeBaseIds("old");
        a.setKnowledgeTags("old-tag");
        a.setKnowledgeFilter("{}");
        a.setKnowledgeDisabled(0);
        var patch = new AgentTurnOverride.KnowledgePatch(List.of("new"), null, "{\"fileTypes\":[\"pdf\"]}", 1);
        patch.applyTo(a);
        assertThat(a.getKnowledgeBaseIds()).isEqualTo("new");
        assertThat(a.getKnowledgeTags()).isEqualTo("old-tag");
        assertThat(a.getKnowledgeFilter()).isEqualTo("{\"fileTypes\":[\"pdf\"]}");
        assertThat(a.getKnowledgeDisabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("非法 revision / filter / disabled 直接拒绝")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> AgentTurnOverride.revision(0)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new AgentTurnOverride.KnowledgePatch(null, null, "not-json", null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new AgentTurnOverride.KnowledgePatch(null, null, null, 2))
                .isInstanceOf(BizException.class);
    }
}
