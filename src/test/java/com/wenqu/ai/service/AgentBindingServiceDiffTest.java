package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AgentBindingMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 双写一致性：{@link AgentBindingService#diff} 必须在「列与绑定一致」时返回空串，
 * 不一致时能说清差在哪。切读上线前就是靠它做灰度核对。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentBindingServiceDiffTest {

    @Mock AgentBindingMapper bindingMapper;

    private AgentBindingService serviceWith(List<AgentBinding> rows) {
        when(bindingMapper.selectList(any())).thenReturn(rows);
        return new AgentBindingService(bindingMapper);
    }

    private static Agent agent() {
        Agent a = new Agent();
        a.setId("a1");
        a.setToolMcp(1);
        a.setMcps("github");
        a.setToolSkill(0);
        a.setSkills("");
        a.setKnowledgeBaseIds("kb1");
        a.setToolKnowledge(1);
        return a;
    }

    @Test
    @DisplayName("绑定表与旧列一致时 diff 为空")
    void consistentReturnsEmpty() {
        Agent a = agent();
        AgentBindingService svc = serviceWith(AgentBindingCodec.fromAgent(a));
        assertThat(svc.diff(a)).isEmpty();
    }

    @Test
    @DisplayName("绑定表缺一项时 diff 指出差异")
    void missingBindingDetected() {
        Agent a = agent();
        List<AgentBinding> partial = new ArrayList<>(AgentBindingCodec.fromAgent(a));
        partial.removeIf(b -> "mcp".equals(b.getCapability()));
        AgentBindingService svc = serviceWith(partial);
        assertThat(svc.diff(a)).isNotEmpty().contains("bindings=");
    }

    @Test
    @DisplayName("绑定表多一项（旧列已清）同样报差异")
    void staleBindingDetected() {
        Agent a = new Agent();
        a.setId("a1");
        List<AgentBinding> rows = new ArrayList<>(AgentBindingCodec.fromAgent(agent()));
        AgentBindingService svc = serviceWith(rows);
        assertThat(svc.diff(a)).isNotEmpty();
    }
}
