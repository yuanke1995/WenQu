package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.AgentBindingMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 能力绑定的读写门面（双写期与切读期共用）。
 * <p>开关 {@code wenqu.agent.binding-read} 控制读侧来源：
 * {@code false}（默认）= 读旧列、绑定表只做双写影子；{@code true} = 读绑定表。
 * 这样迁移可以分两步上线：先跑一段时间双写并核对差异，再切读，最后删列。
 * <p>写侧永远双写：旧列与绑定表同时更新，任何一步失败都回滚整笔（由调用方事务保证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBindingService {

    private final AgentBindingMapper bindingMapper;

    /** 读侧是否以绑定表为准（false=读旧列；上线顺序：先双写、核对、再切 true）。 */
    @Value("${wenqu.agent.binding-read:false}")
    private boolean bindingRead;

    public boolean isBindingRead() {
        return bindingRead;
    }

    /** 某智能体的全部绑定（按能力名稳定排序，便于对比与测试）。 */
    public List<AgentBinding> listOf(String agentId) {
        if (agentId == null || agentId.isBlank()) return List.of();
        return bindingMapper.selectList(new LambdaQueryWrapper<AgentBinding>()
                .eq(AgentBinding::getAgentId, agentId)
                .orderByAsc(AgentBinding::getCapability));
    }

    /**
     * 用 Agent 行的能力列覆盖绑定表（全量替换语义）。
     * <p>全量替换而不是逐条 merge：保存是「整份配置」语义，残留的旧绑定会让「取消某个能力」
     * 在读侧切表后又冒出来。
     */
    public void syncFromAgent(Agent agent) {
        if (agent == null || agent.getId() == null) return;
        List<AgentBinding> rows = AgentBindingCodec.fromAgent(agent);
        bindingMapper.delete(new LambdaQueryWrapper<AgentBinding>()
                .eq(AgentBinding::getAgentId, agent.getId()));
        for (AgentBinding b : rows) {
            bindingMapper.insert(b);
        }
    }

    /** 读侧：把绑定表覆盖到 Agent 视图上（切读开关为 true 时由 AgentService 调用）。 */
    public void applyTo(Agent agent) {
        if (agent == null || agent.getId() == null) return;
        AgentBindingCodec.applyTo(agent, listOf(agent.getId()));
    }

    /**
     * 双写一致性核对：返回旧列与绑定表的差异描述（空串=一致）。
     * <p>双写期用它做灰度校验与测试断言；切读前必须连续一致。
     */
    public String diff(Agent agent) {
        if (agent == null || agent.getId() == null) return "agent is null";
        Agent fromColumns = new Agent();
        fromColumns.setId(agent.getId());
        copyCapabilities(agent, fromColumns);
        Agent fromBindings = new Agent();
        fromBindings.setId(agent.getId());
        AgentBindingCodec.applyTo(fromBindings, listOf(agent.getId()));
        String a = AgentBindingCodec.fingerprint(fromColumns);
        String b = AgentBindingCodec.fingerprint(fromBindings);
        return a.equals(b) ? "" : "columns=" + a + " bindings=" + b;
    }

    /** 只拷贝能力列（不碰身份/人设/策略），用于对比。 */
    private static void copyCapabilities(Agent from, Agent to) {
        to.setToolKnowledge(from.getToolKnowledge());
        to.setToolSkill(from.getToolSkill());
        to.setToolMcp(from.getToolMcp());
        to.setToolHttp(from.getToolHttp());
        to.setToolWorkflow(from.getToolWorkflow());
        to.setToolBuiltin(from.getToolBuiltin());
        to.setToolArtifact(from.getToolArtifact());
        to.setToolBrowser(from.getToolBrowser());
        to.setToolWebsearch(from.getToolWebsearch());
        to.setToolDeny(from.getToolDeny());
        to.setSkills(from.getSkills());
        to.setMcps(from.getMcps());
        to.setBuiltinTools(from.getBuiltinTools());
        to.setHttpTools(from.getHttpTools());
        to.setWorkflowTools(from.getWorkflowTools());
        to.setKnowledgeBaseIds(from.getKnowledgeBaseIds());
        to.setKnowledgeTags(from.getKnowledgeTags());
        to.setKnowledgeFilter(from.getKnowledgeFilter());
        to.setKnowledgeScope(from.getKnowledgeScope());
        to.setKnowledgeDisabled(from.getKnowledgeDisabled());
    }
}
