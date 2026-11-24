package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.AgentVersionMapper;
import com.wenqu.ai.mapper.TenantMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.util.RequestUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 派遣候选清单判据单测：{@code subAgentIds} 是运行时的派遣白名单，不是只给编排图看的声明。
 * <p>
 * 不起 Spring 上下文：Mapper/可见性全部 Mockito 假件。覆盖空清单不设限（且主智能体也在候选里，
 * 「@ 助手」靠的正是它）、显式清单收窄、清单里已删除或读不到的 id 逐个跳过。
 *
 * @author yuanke
 */
class AgentConsultCandidatesTest {

    private AgentMapper mapper;
    private ResourceVisibilityService visibility;
    private PlatformAgentLookup platformAgentLookup;
    private AgentService agentService;

    @BeforeEach
    void setUp() throws Exception {
        // LambdaQueryWrapper 依赖 MP 的 TableInfo lambda 缓存；纯单测无 MyBatis 上下文，手动补登记
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Agent.class);
        RequestUser.set("u1", null, "user");
        mapper = mock(AgentMapper.class);
        visibility = mock(ResourceVisibilityService.class);
        platformAgentLookup = mock(PlatformAgentLookup.class);
        when(visibility.canRead(any(), any())).thenReturn(true);
        agentService = new AgentService(
                platformAgentLookup,
                mapper,
                mock(AgentVersionMapper.class),
                mock(TenantMapper.class),
                mock(ConfigService.class),
                visibility,
                mock(RoleService.class),
                mock(com.wenqu.ai.agent.tool.ToolRegistry.class),
                mock(com.wenqu.ai.service.httptool.HttpToolService.class),
                mock(com.wenqu.ai.service.workflowtool.WorkflowToolService.class),
                mock(ConfigCryptoService.class),
                mock(com.wenqu.ai.service.AgentBindingService.class),
                mock(com.wenqu.ai.mapper.AgentBindingMapper.class));
    }

    private static Agent agent(String id, Integer isSubagent, String subAgentIds) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(id);
        a.setCreatedBy("u1");
        a.setIsSubagent(isSubagent);
        a.setSubAgentIds(subAgentIds);
        return a;
    }

    private void givenRows(Agent... rows) {
        when(mapper.selectList(any())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("空清单不设限：列出全部可读助手（含主智能体，@ 助手要靠它）")
    void emptyListKeepsAllReadableExceptSelf() {
        Agent self = agent("main", 0, null);
        Agent otherMain = agent("other-main", 0, null);
        Agent sub = agent("sub", 1, null);
        givenRows(self, otherMain, sub);
        when(mapper.selectById("main")).thenReturn(self);

        List<Agent> out = agentService.consultCandidates("main");

        assertThat(out).extracting(Agent::getId).containsExactlyInAnyOrder("other-main", "sub");
    }

    @Test
    @DisplayName("显式清单收窄：只派清单里的那几位")
    void declaredListNarrowsCandidates() {
        Agent self = agent("main", 0, "sub-a, sub-b");
        Agent subA = agent("sub-a", 1, null);
        Agent subB = agent("sub-b", 1, null);
        Agent subC = agent("sub-c", 1, null);
        givenRows(self, subA, subB, subC);
        when(mapper.selectById("main")).thenReturn(self);

        List<Agent> out = agentService.consultCandidates("main");

        assertThat(out).extracting(Agent::getId).containsExactlyInAnyOrder("sub-a", "sub-b");
    }

    @Test
    @DisplayName("清单里已删除的 id 逐个跳过，不拖累其余项")
    void danglingIdIsSkipped() {
        Agent self = agent("main", 0, "gone,sub-a");
        Agent subA = agent("sub-a", 1, null);
        givenRows(self, subA);
        when(mapper.selectById("main")).thenReturn(self);

        List<Agent> out = agentService.consultCandidates("main");

        assertThat(out).extracting(Agent::getId).containsExactly("sub-a");
    }

    @Test
    @DisplayName("清单里读不到的 id 跳过（白名单不越权）")
    void unreadableIdIsSkipped() {
        Agent self = agent("main", 0, "sub-a");
        Agent subA = agent("sub-a", 1, null);
        subA.setCreatedBy("someone-else");
        givenRows(self, subA);
        when(mapper.selectById("main")).thenReturn(self);
        when(visibility.canRead(any(), eq("someone-else"))).thenReturn(false);

        List<Agent> out = agentService.consultCandidates("main");

        assertThat(out).isEmpty();
    }
}
