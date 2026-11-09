package com.wenqu.ai.service.workflowtool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.mapper.WorkflowMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.Workflow;
import com.wenqu.ai.service.UserRoleService;
import com.wenqu.ai.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WorkflowToolService 挂载判据测试（agent-as-tool · 工作流工具族）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>agent 未配置 → 空表</li>
 *   <li>嵌套深度到顶 → 整族不挂（防环）</li>
 *   <li>工作流未发布 / 不存在 → 静默跳过</li>
 *   <li>游客只挂安全档（只读节点集合）</li>
 *   <li>超过 MAX_SELECTED 上限 → 抛 BizException（保存期）</li>
 *   <li>normalizeFromSave 对未发布工作流 fail-loud</li>
 *   <li>挂出来的工具名 = wf_{slug}_{id前8位}（重名不撞）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowToolServiceTest {

    @Mock AgentMapper agentMapper;
    @Mock WorkflowMapper workflowMapper;
    @Mock UserMapper userMapper;
    @Mock UserRoleService userRoleService;
    @Mock ObjectProvider<WorkflowService> workflowServiceProvider;

    @InjectMocks
    WorkflowToolService service;

    Agent agent;
    Workflow wfReadOnly;
    Workflow wfWithSideEffect;
    Workflow wfDraft;
    Workflow wfUnpublished;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setId("agent-demo");
        agent.setName("演示助手");
        // 默认：配两个工作流
        agent.setWorkflowTools(JSON.toJSONString(List.of("wf-1", "wf-2")));

        // 纯只读工作流（start + llm + retrieval + end）
        wfReadOnly = new Workflow();
        wfReadOnly.setId("wf-1");
        wfReadOnly.setName("只读查询");
        wfReadOnly.setStatus("published");
        wfReadOnly.setPublishedDsl(readonlyDsl());

        // 有副作用的工作流（execute / http / write_kb 任意一个节点类型）
        wfWithSideEffect = new Workflow();
        wfWithSideEffect.setId("wf-2");
        wfWithSideEffect.setName("带执行的流程");
        wfWithSideEffect.setStatus("published");
        wfWithSideEffect.setPublishedDsl(withSideEffectDsl());

        // 草稿工作流（未发布）
        wfDraft = new Workflow();
        wfDraft.setId("wf-draft");
        wfDraft.setName("草稿");
        wfDraft.setStatus("draft");

        // 已发布但 DSL 里有副作用
        wfUnpublished = new Workflow();
        wfUnpublished.setId("wf-unpub");
        wfUnpublished.setName("未发布");
        wfUnpublished.setStatus("unpublished");
    }

    // ---------------------------------------------------------------------------------------------
    // mount：装配期判据
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("mount 装配期判据")
    class Mount {

        @Test
        @DisplayName("agentId 为空 → 空表")
        void nullAgentId() {
            WorkflowToolService.Mount m = service.mount(null, "u1", 0);
            assertTrue(m.tools().isEmpty());
            assertNull(m.problem());
        }

        @Test
        @DisplayName("wfDepth 到顶 → 整族不挂（MAX_WF_DEPTH=2）")
        void depthReachesCap() {
            assertEquals(2, WorkflowToolService.MAX_WF_DEPTH);
            when(agentMapper.selectById("agent-demo")).thenReturn(agent);
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);
            when(workflowMapper.selectById("wf-2")).thenReturn(wfWithSideEffect);

            // 深度 2 到顶，返回空表但不报错
            WorkflowToolService.Mount m = service.mount("agent-demo", "u1", 2);
            assertTrue(m.tools().isEmpty());
            assertNull(m.problem());

            // 深度 1 可以挂（nextDepth = 2 刚好是 MAX_WF_DEPTH）
            WorkflowToolService.Mount m1 = service.mount("agent-demo", "u1", 1);
            // nextDepth=MAX_WF_DEPTH=2 是允许的，所以这里应该能挂
            assertFalse(m1.tools().isEmpty());
        }

        @Test
        @DisplayName("agent 不存在 → 空表")
        void agentNotExist() {
            when(agentMapper.selectById("agent-nonexist")).thenReturn(null);
            WorkflowToolService.Mount m = service.mount("agent-nonexist", "u1", 0);
            assertTrue(m.tools().isEmpty());
            assertNull(m.problem());
        }

        @Test
        @DisplayName("agent 没配 workflow_tools → 空表")
        void agentNoConfig() {
            Agent a = new Agent();
            a.setId("a2");
            a.setWorkflowTools(null);
            when(agentMapper.selectById("a2")).thenReturn(a);
            WorkflowToolService.Mount m = service.mount("a2", "u1", 0);
            assertTrue(m.tools().isEmpty());
        }

        @Test
        @DisplayName("引用的工作流不存在 → 静默跳过，其余照常挂")
        void workflowMissingSilentlySkipped() {
            when(agentMapper.selectById("agent-demo")).thenReturn(agent);
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);
            when(workflowMapper.selectById("wf-2")).thenReturn(null);   // 被删了

            WorkflowToolService.Mount m = service.mount("agent-demo", "u1", 0);
            assertEquals(1, m.tools().size());
            // 只有 wf-1 挂上了，wf-2 静默跳过
            assertTrue(toolNameOf(m.tools().get(0)).startsWith("wf_"), "工具名以 wf_ 开头");
        }

        @Test
        @DisplayName("引用的工作流未发布 → 静默跳过")
        void workflowUnpublishedSilentlySkipped() {
            when(agentMapper.selectById("agent-demo")).thenReturn(agent);
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);
            when(workflowMapper.selectById("wf-2")).thenReturn(wfWithSideEffect);
            // 让 wf-2 变成未发布
            wfWithSideEffect.setStatus("draft");

            WorkflowToolService.Mount m = service.mount("agent-demo", "u1", 0);
            assertEquals(1, m.tools().size());
        }
    }

    @Nested
    @DisplayName("游客安全档")
    class GuestSafe {

        @Test
        @DisplayName("游客只读工作流 → 挂；有副作用 → 不挂")
        void guestOnlyReadOnly() {
            when(agentMapper.selectById("agent-demo")).thenReturn(agent);
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);
            when(workflowMapper.selectById("wf-2")).thenReturn(wfWithSideEffect);

            WorkflowToolService.Mount m = service.mount("agent-demo", "u1", 0, true /*guest*/);
            assertEquals(1, m.tools().size(), "游客只能挂只读工作流");
            assertTrue(toolNameOf(m.tools().get(0)).startsWith("wf_"), "工具名以 wf_ 开头");
        }

        @Test
        @DisplayName("isGuestSafe 对纯只读 DSL 返回 true")
        void isGuestSafeReadOnly() {
            assertTrue(WorkflowToolService.isGuestSafe(wfReadOnly));
        }

        @Test
        @DisplayName("isGuestSafe 对含副作用节点返回 false")
        void isGuestSafeWithSideEffect() {
            assertFalse(WorkflowToolService.isGuestSafe(wfWithSideEffect));
        }

        @Test
        @DisplayName("isGuestSafe 对 DSL 缺失返回 false（fail-closed）")
        void isGuestSafeNoDsl() {
            Workflow wf = new Workflow();
            wf.setStatus("published");
            assertFalse(WorkflowToolService.isGuestSafe(wf));
        }

        @Test
        @DisplayName("isGuestSafe 对空节点表返回 false")
        void isGuestSafeEmptyNodes() {
            Workflow wf = new Workflow();
            wf.setStatus("published");
            wf.setPublishedDsl("{\"nodes\":[]}");
            assertFalse(WorkflowToolService.isGuestSafe(wf));
        }
    }

    @Nested
    @DisplayName("保存期归一化 normalizeFromSave")
    class NormalizeFromSave {

        @Test
        @DisplayName("不存在的工作流 → 抛 BizException（fail-loud）")
        void nonexistentWorkflowThrows() {
            when(workflowMapper.selectById("wf-missing")).thenReturn(null);

            com.wenqu.ai.common.BizException ex = assertThrows(com.wenqu.ai.common.BizException.class, () ->
                    service.normalizeFromSave(List.of("wf-missing")));
            assertTrue(ex.getMessage().contains("工作流不存在"));
        }

        @Test
        @DisplayName("未发布的工作流 → 抛 BizException")
        void unpublishedWorkflowThrows() {
            when(workflowMapper.selectById("wf-draft")).thenReturn(wfDraft);

            com.wenqu.ai.common.BizException ex = assertThrows(com.wenqu.ai.common.BizException.class, () ->
                    service.normalizeFromSave(List.of("wf-draft")));
            assertTrue(ex.getMessage().contains("尚未发布"));
        }

        @Test
        @DisplayName("超过 MAX_SELECTED(8) → 抛 BizException")
        void exceedsMaxSelected() {
            when(workflowMapper.selectById(anyString())).thenReturn(wfReadOnly);
            // 9 个 id
            List<String> ids = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");

            com.wenqu.ai.common.BizException ex = assertThrows(com.wenqu.ai.common.BizException.class, () ->
                    service.normalizeFromSave(ids));
            assertTrue(ex.getMessage().contains("最多挂 8 个"), "实际: " + ex.getMessage());
        }

        @Test
        @DisplayName("成功路径：已发布的 → 返回去重的 JSON 数组")
        void happyPath() {
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);
            when(workflowMapper.selectById("wf-2")).thenReturn(wfWithSideEffect);

            String out = service.normalizeFromSave(List.of("wf-1", "wf-2"));
            assertNotNull(out);
            List<String> ids = JSON.parseArray(out, String.class);
            assertEquals(2, ids.size());
            assertTrue(ids.contains("wf-1"));
            assertTrue(ids.contains("wf-2"));
        }

        @Test
        @DisplayName("去重 + 空白剔除")
        void dedupAndTrim() {
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);

            List<String> input = new ArrayList<>();
            input.add("wf-1");
            input.add("  wf-1  ");
            input.add("wf-1");
            input.add(null);
            input.add("");
            String out = service.normalizeFromSave(input);
            List<String> ids = JSON.parseArray(out, String.class);
            assertEquals(1, ids.size());
            assertEquals("wf-1", ids.get(0));
        }

        @Test
        @DisplayName("空数组 → 返回 null（归一化语义）")
        void emptyBecomesNull() {
            assertNull(service.normalizeFromSave(List.of()));
        }
    }

    @Nested
    @DisplayName("工具名命名规则")
    class Naming {

        @Test
        @DisplayName("工具名 = wf_{slug}_{id 前 8 位}")
        void toolNamePattern() {
            when(agentMapper.selectById("agent-demo")).thenReturn(agent);
            when(workflowMapper.selectById("wf-1")).thenReturn(wfReadOnly);
            wfReadOnly.setName("只读 查询 v2");   // slug 里空格会变下划线

            WorkflowToolService.Mount m = service.mount("agent-demo", "u1", 0);
            assertEquals(1, m.tools().size());

            String name = m.tools().get(0).getToolDefinition().name();
            assertTrue(name.startsWith("wf_"), "前缀应为 wf_: " + name);
            assertTrue(name.contains("wf"), "id 后 8 位应出现: " + name);
        }

        @Test
        @DisplayName("同名工作流也互不撞（id 后缀区分）")
        void nameCollisionAvoided() {
            Workflow dup1 = new Workflow();
            dup1.setId("wf-aaaabbbbccccdddd");
            dup1.setName("同名工作流");
            dup1.setStatus("published");
            dup1.setPublishedDsl(readonlyDsl());

            Workflow dup2 = new Workflow();
            dup2.setId("wf-eeeffffgggghhhh");
            dup2.setName("同名工作流");
            dup2.setStatus("published");
            dup2.setPublishedDsl(readonlyDsl());

            Agent a = new Agent();
            a.setId("a-dup");
            a.setWorkflowTools(JSON.toJSONString(List.of("wf-aaaabbbbccccdddd", "wf-eeeffffgggghhhh")));
            when(agentMapper.selectById("a-dup")).thenReturn(a);
            when(workflowMapper.selectById("wf-aaaabbbbccccdddd")).thenReturn(dup1);
            when(workflowMapper.selectById("wf-eeeffffgggghhhh")).thenReturn(dup2);

            WorkflowToolService.Mount m = service.mount("a-dup", "u1", 0);
            assertEquals(2, m.tools().size());
            String n1 = m.tools().get(0).getToolDefinition().name();
            String n2 = m.tools().get(1).getToolDefinition().name();
            assertNotEquals(n1, n2, "同名工作流不应撞名");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------------------------------------

    private static String toolNameOf(ToolCallback cb) {
        return cb.getToolDefinition().name();
    }

    /** 纯只读节点集合的 DSL（start / llm / retrieval / condition / loop / template / end） */
    private static String readonlyDsl() {
        Map<String, Object> start = Map.of("type", "start");
        Map<String, Object> llm = Map.of("type", "llm");
        Map<String, Object> retrieval = Map.of("type", "retrieval");
        Map<String, Object> end = Map.of("type", "end");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", List.of(start, llm, retrieval, end));
        root.put("edges", List.of());
        return JSON.toJSONString(root);
    }

    /** 含 execute 节点（副作用）的 DSL */
    private static String withSideEffectDsl() {
        Map<String, Object> start = Map.of("type", "start");
        Map<String, Object> exec = Map.of("type", "execute");    // 副作用节点
        Map<String, Object> end = Map.of("type", "end");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", List.of(start, exec, end));
        root.put("edges", List.of());
        return JSON.toJSONString(root);
    }
}
