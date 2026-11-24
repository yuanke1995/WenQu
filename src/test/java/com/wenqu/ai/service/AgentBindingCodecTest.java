package com.wenqu.ai.service;

import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力列 ↔ 绑定行的双向等价：双写与切读的正确性全靠这一层。
 * <p>任何一侧漏字段，都会表现为「切读后某个能力静默失效」——所以逐能力断言，而不是只看整体指纹。
 */
class AgentBindingCodecTest {

    private static Agent fullAgent() {
        Agent a = new Agent();
        a.setId("a1");
        a.setToolKnowledge(1);
        a.setKnowledgeBaseIds("kb1,kb2");
        a.setKnowledgeTags("tag1");
        a.setKnowledgeFilter("{\"fileTypes\":[\"pdf\"]}");
        a.setKnowledgeScope("all");
        a.setKnowledgeDisabled(0);
        a.setToolSkill(1);
        a.setSkills("user:u1:review");
        a.setToolMcp(0);
        a.setMcps("github");
        a.setToolHttp(1);
        a.setHttpTools("[{\"id\":\"h1\"}]");
        a.setToolWorkflow(1);
        a.setWorkflowTools("[\"wf1\"]");
        a.setToolBuiltin(1);
        a.setBuiltinTools("askUser");
        a.setToolArtifact(0);
        a.setToolBrowser(1);
        a.setToolWebsearch(1);
        a.setToolDeny("grep_files,wen_qugithub_search");
        return a;
    }

    @Test
    @DisplayName("列 → 绑定 → 列 往返等价（逐能力断言）")
    void roundTrip() {
        Agent src = fullAgent();
        List<AgentBinding> rows = AgentBindingCodec.fromAgent(src);

        Agent dst = new Agent();
        dst.setId("a1");
        AgentBindingCodec.applyTo(dst, rows);

        assertThat(dst.getToolKnowledge()).isEqualTo(1);
        assertThat(dst.getKnowledgeBaseIds()).isEqualTo("kb1,kb2");
        assertThat(dst.getKnowledgeTags()).isEqualTo("tag1");
        assertThat(dst.getKnowledgeFilter()).isEqualTo("{\"fileTypes\":[\"pdf\"]}");
        assertThat(dst.getKnowledgeScope()).isEqualTo("all");
        assertThat(dst.getKnowledgeDisabled()).isEqualTo(0);
        assertThat(dst.getToolSkill()).isEqualTo(1);
        assertThat(dst.getSkills()).isEqualTo("user:u1:review");
        assertThat(dst.getToolMcp()).isEqualTo(0);
        assertThat(dst.getMcps()).isEqualTo("github");
        assertThat(dst.getToolHttp()).isEqualTo(1);
        assertThat(dst.getHttpTools()).isEqualTo("[{\"id\":\"h1\"}]");
        assertThat(dst.getToolWorkflow()).isEqualTo(1);
        assertThat(dst.getWorkflowTools()).isEqualTo("[\"wf1\"]");
        assertThat(dst.getToolBuiltin()).isEqualTo(1);
        assertThat(dst.getBuiltinTools()).isEqualTo("askUser");
        assertThat(dst.getToolArtifact()).isEqualTo(0);
        assertThat(dst.getToolBrowser()).isEqualTo(1);
        assertThat(dst.getToolWebsearch()).isEqualTo(1);
        assertThat(dst.getToolDeny()).isEqualTo("grep_files,wen_qugithub_search");

        assertThat(AgentBindingCodec.fingerprint(dst)).isEqualTo(AgentBindingCodec.fingerprint(src));
    }

    @Test
    @DisplayName("三态 NULL 与「显式空 selection」语义不混淆")
    void nullVsEmptySelection() {
        Agent inherit = new Agent();
        inherit.setId("a1");
        inherit.setToolMcp(null);
        inherit.setMcps(null);
        assertThat(AgentBindingCodec.fromAgent(inherit)).isEmpty();

        Agent explicitNone = new Agent();
        explicitNone.setId("a1");
        explicitNone.setToolMcp(0);
        explicitNone.setMcps("");
        List<AgentBinding> rows = AgentBindingCodec.fromAgent(explicitNone);
        assertThat(rows).hasSize(1);

        Agent back = new Agent();
        back.setId("a1");
        AgentBindingCodec.applyTo(back, rows);
        assertThat(back.getToolMcp()).isEqualTo(0);
        assertThat(back.getMcps()).isEmpty();   // 空串 ≠ null：显式「一个都不用」
    }

    @Test
    @DisplayName("applyTo 先清空，避免复用对象残留旧能力")
    void applyClearsStaleCapabilities() {
        Agent reused = fullAgent();
        AgentBindingCodec.applyTo(reused, List.of());
        assertThat(reused.getToolKnowledge()).isNull();
        assertThat(reused.getSkills()).isNull();
        assertThat(reused.getToolDeny()).isNull();
        assertThat(reused.getKnowledgeBaseIds()).isNull();
    }

    @Test
    @DisplayName("未知能力标识被忽略（向前兼容）")
    void unknownCapabilityIgnored() {
        AgentBinding b = new AgentBinding();
        b.setAgentId("a1");
        b.setCapability("future-capability");
        b.setFlag(1);
        Agent a = new Agent();
        a.setId("a1");
        AgentBindingCodec.applyTo(a, List.of(b));
        assertThat(a.getToolKnowledge()).isNull();
    }
}
