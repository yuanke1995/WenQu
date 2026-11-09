package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.workflowtool.WorkflowToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流工具族（已发布工作流挂载为对话工具）。
 * <p>
 * 与 {@link HttpApiToolProvider} 同一形状的动态族：<b>没有静态清单</b>——工具由每个智能体自己的
 * {@code workflow_tools} 配置动态合入，{@link #descriptors()} 空、{@code holder()} null（启动核对跳过）。
 * 挂载判据在 HTTP 工具的收紧之上再多两条，全部服务于防环与无人值守边界：
 * <ol>
 *   <li><b>游客一律不挂</b>：与 HTTP 工具同口径——工作流可能携带 KB 写入、HTTP 出站等副作用，
 *       游客能触发等于把业务流程的执行权借给匿名外人；</li>
 *   <li><b>分支轮不挂</b>：框架派遣出去的分支里再调工作流，等于把乘数扇出接进确定性流程；
 *       且分支里没有人看，工作流跑完的结果也无处可去；</li>
 *   <li><b>嵌套到顶不挂</b>：本轮已处于工作流嵌套的第 {@code MAX_WF_DEPTH-1} 层时整族收口
 *       （防环的第二道闸，第一道在 {@code WorkflowService.runForAgent} 的深度拒绝）。</li>
 * </ol>
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowToolProvider implements ToolFamilyProvider {

    private final WorkflowToolService workflowToolService;

    @Override
    public ToolFamily family() {
        return ToolFamily.WORKFLOW;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of();   // 动态接入，无静态清单（同 MCP / HTTP 工具）
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        if (mc == null || !mc.masterOn() || !mc.approvalPolicyAllows(ToolFamily.WORKFLOW)) {
            return List.of();
        }
        if (mc.branchTurn()) {
            return List.of();   // 分支轮不挂（见类注释第 2 条）
        }
        Integer flag = mc.agentFlag(ToolFamily.WORKFLOW);
        if (flag != null && flag != 1) return List.of();   // 智能体显式关
        if (flag == null && !mc.gateOn("workflowTool.enabled")) return List.of();   // 跟随全局闸

        WorkflowToolService.Mount mount;
        try {
            // 游客放行：只挂只读工作流；副作用工作流在 service 内过滤，执行口再校验一次
            mount = workflowToolService.mount(mc.agentId(), mc.userId(), mc.turnWfDepth(), mc.guestMode());
        } catch (Exception e) {
            log.warn("[WF-TOOL] 装配失败（跳过，不影响问答）agent={}: {}", mc.agentId(), e.getMessage());
            return List.of();
        }
        if (mount.problem() != null) {
            mc.degrade("agentWorkflowToolsInvalid", mount.problem());
            return List.of();
        }
        // 按名黑名单：动态工具没有静态描述符，档位裁剪不到它，与 MCP / HTTP 工具同一粒度
        List<ToolCallback> out = new ArrayList<>(mount.tools());
        out.removeIf(cb -> mc.deniesTool(cb.getToolDefinition().name()));
        log.info("[WF-TOOL] 本轮装配 {} 把工作流工具: agent={} depth={}", out.size(), mc.agentId(), mc.turnWfDepth());
        return out;
    }
}
