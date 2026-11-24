package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.agent.chat.AgentTurnOverride;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.AgentVersionMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** resolveForTurn：历史版本视图只读、patch 叠加、不写库。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentServiceResolveForTurnTest {

    @Mock AgentMapper agentMapper;
    @Mock AgentVersionMapper versionMapper;
    @Mock com.wenqu.ai.service.workflowtool.WorkflowToolService workflowToolService;
    @Mock AgentBindingService bindingService;
    @Mock ConfigService configService;
    @Mock com.wenqu.ai.service.ResourceVisibilityService resourceVisibilityService;

    private AgentService service() {
        when(resourceVisibilityService.canRead(any(), nullable(String.class))).thenReturn(true);
        return new AgentService(null, agentMapper, versionMapper, null, configService,
                resourceVisibilityService, null, null, null, workflowToolService, null,
                bindingService, null);
    }

    @Test
    @DisplayName("无 override 时直接返回当前可读智能体")
    void noOverrideReturnsCurrent() {
        Agent a = new Agent();
        a.setId("a1");
        a.setName("当前");
        a.setDisabled(0);
        when(agentMapper.selectById("a1")).thenReturn(a);
        assertThat(service().resolveForTurn("a1", null)).isSameAs(a);
        assertThat(service().resolveForTurn("a1", AgentTurnOverride.none())).isSameAs(a);
    }

    @Test
    @DisplayName("revision 还原快照 + knowledgePatch 覆盖，不修改数据库行")
    void revisionAndPatch() {
        Agent current = new Agent();
        current.setId("a1");
        current.setName("当前");
        current.setSystemPrompt("当前提示词");
        current.setDisabled(0);
        current.setKnowledgeBaseIds("current-kb");
        when(agentMapper.selectById("a1")).thenReturn(current);

        AgentVersion v = new AgentVersion();
        v.setAgentId("a1");
        v.setVersion(2);
        v.setConfig(com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "name", "旧版",
                "systemPrompt", "旧提示词",
                "knowledgeBaseIds", "old-kb",
                "skills", "review")));
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(v);

        var override = AgentTurnOverride.of(2, new AgentTurnOverride.KnowledgePatch(
                List.of("new-kb"), null, null, null));
        Agent view = service().resolveForTurn("a1", override);

        assertThat(view).isNotSameAs(current);
        assertThat(view.getName()).isEqualTo("旧版");
        assertThat(view.getSystemPrompt()).isEqualTo("旧提示词");
        assertThat(view.getKnowledgeBaseIds()).isEqualTo("new-kb");
        assertThat(view.getSkills()).isEqualTo("review");
        // 原行未被改写
        assertThat(current.getName()).isEqualTo("当前");
        assertThat(current.getKnowledgeBaseIds()).isEqualTo("current-kb");
        // 原行未被改写：只有 selectById，没有 update/insert
        verify(agentMapper).selectById("a1");
        verify(agentMapper, org.mockito.Mockito.never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    @DisplayName("revision 不存在时 fail-loud")
    void missingRevisionFails() {
        Agent current = new Agent();
        current.setId("a1");
        current.setDisabled(0);
        when(agentMapper.selectById("a1")).thenReturn(current);
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service().resolveForTurn("a1", AgentTurnOverride.revision(9)))
                .isInstanceOf(BizException.class);
    }
}
