package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.Workflow;
import com.wenqu.ai.service.ResourceVisibilityService.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 工具装配期运行态可见性复核的口径锁定：不能只看「配置存在」。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolMountVisibilityServiceTest {

    @Mock AgentMapper agentMapper;
    @Mock PlatformAgentLookup platformAgentLookup;
    @Mock ResourceVisibilityService visibility;
    @Mock UserMapper userMapper;
    @Mock UserRoleService userRoleService;
    @Mock AgentBindingService bindingService;

    private ToolMountVisibilityService service() {
        return new ToolMountVisibilityService(agentMapper, platformAgentLookup, visibility, userMapper, userRoleService, bindingService);
    }

    @Test
    @DisplayName("普通智能体：可读则挂，不可读不挂，停用不挂")
    void agentReadability() {
        Agent mine = new Agent();
        mine.setId("a1");
        mine.setCreatedBy("u1");
        when(agentMapper.selectById("a1")).thenReturn(mine);
        when(visibility.canRead(any(Principal.class), eq("u1"))).thenReturn(true);
        assertThat(service().agentForMount("a1", "u1", false)).isSameAs(mine);

        when(visibility.canRead(any(Principal.class), eq("u1"))).thenReturn(false);
        assertThat(service().agentForMount("a1", "u1", false)).isNull();

        when(visibility.canRead(any(Principal.class), eq("u1"))).thenReturn(true);
        mine.setDisabled(1);
        assertThat(service().agentForMount("a1", "u1", false)).isNull();
    }

    @Test
    @DisplayName("平台共享/内置/预置智能体豁免严格 owner；游客沿用 token 授权")
    void platformAndGuestAreNotBlockedByOwnerCheck() {
        Agent platform = new Agent();
        platform.setId("platform-browser-agent");
        platform.setCreatedBy("system");
        when(agentMapper.selectById("platform-browser-agent")).thenReturn(null);
        when(platformAgentLookup.find("platform-browser-agent")).thenReturn(platform);
        assertThat(service().agentForMount("platform-browser-agent", "u2", false)).isSameAs(platform);

        Agent builtin = new Agent();
        builtin.setId("builtin-1");
        builtin.setIsBuiltin(1);
        builtin.setCreatedBy("system");
        when(agentMapper.selectById("builtin-1")).thenReturn(builtin);
        assertThat(service().agentForMount("builtin-1", "u2", false)).isSameAs(builtin);

        Agent other = new Agent();
        other.setId("a-other");
        other.setCreatedBy("owner");
        when(agentMapper.selectById("a-other")).thenReturn(other);
        when(visibility.canRead(any(Principal.class), eq("owner"))).thenReturn(false);
        assertThat(service().agentForMount("a-other", "guest-owner", true)).isSameAs(other);
    }

    @Test
    @DisplayName("工作流引用：创建者或角色范围可读才挂")
    void workflowVisibility() {
        Workflow wf = new Workflow();
        wf.setUid("owner");
        when(visibility.canRead(any(Principal.class), eq("owner"))).thenReturn(true);
        assertThat(service().canReadWorkflow("reader", wf)).isTrue();
        when(visibility.canRead(any(Principal.class), eq("owner"))).thenReturn(false);
        assertThat(service().canReadWorkflow("reader", wf)).isFalse();
        assertThat(service().canReadWorkflow("reader", null)).isFalse();
    }
}
