package com.wenqu.ai.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalGatePolicyTest {

    @Test
    void hintNeverBlocks() {
        assertThat(AgentEvalService.blockReason("hint", Map.of("gate", "below"))).isNull();
        assertThat(AgentEvalService.blockReason("hint", Map.of("gate", "changed"))).isNull();
    }

    @Test
    void blockRejectsBelowAndChanged() {
        assertThat(AgentEvalService.blockReason("block", Map.of("gate", "below"))).contains("低于门槛");
        assertThat(AgentEvalService.blockReason("BLOCK", Map.of("gate", "changed"))).contains("尚未重测");
    }

    @Test
    void blockAllowsPassAndUnEvaluatedStates() {
        assertThat(AgentEvalService.blockReason("block", Map.of("gate", "pass"))).isNull();
        assertThat(AgentEvalService.blockReason("block", Map.of("gate", "none"))).isNull();
        assertThat(AgentEvalService.blockReason("block", Map.of("gate", "unevaluated"))).isNull();
        assertThat(AgentEvalService.blockReason("block", Map.of("gate", "running"))).isNull();
        assertThat(AgentEvalService.blockReason("block", null)).isNull();
    }
}
