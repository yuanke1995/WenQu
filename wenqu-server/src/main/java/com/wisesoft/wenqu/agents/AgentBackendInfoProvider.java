package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.repositories.AgentRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link AgentRepository.BackendInfoProvider} 的装配实现：把仓储的后端能力查询接到
 * {@link AgentManager} 上。
 *
 * <p>参考实现里 {@code AgentRepository.serialize} 直接 {@code from yuxi.agents.buildin import
 * agent_manager} 后取 {@code agent_manager.get_agent(agent.backend_id).get_info(...)}
 * （见 {@code repositories/agent_repository.py} 的 {@code serialize}）。本工程把该依赖收敛为
 * {@code BackendInfoProvider} 扩展点（{@code repositories} 层不反向依赖 {@code agents} 层），
 * 本类即那个扩展点的实现——装配后 {@code serialize} 的行为与参考实现一致。
 *
 * <p>未装配时 {@code AgentRepository} 会对任何后端返回「后端不存在」的空结构
 * （capabilities/metadata/configurable_items 三键全空）；那会让 {@code GET /api/agent} 的
 * capabilities 恒为空，前端据此隐藏能力入口——属静默降级，故本类必须存在。
 *
 * <p><b>能力差异（显式标注）</b>：参考实现的 {@code get_info} 在
 * {@code include_configurable_items=True} 时接收 {@code db} 与 {@code user} 用于注入候选资源
 * （knowledge / mcp / skills 等的可见集合）。本工程 {@link BaseAgent#getInfo} 的对应入参是
 * {@code AgentResourceOptionsResolver}；该解析器尚未装配（依赖 runtime 资源目录），
 * 故此处传 {@code null}——与 {@code BaseAgent} 既有的「未注入时跳过资源选项注入」口径一致。
 *
 * @author yuanke
 */
@Component
public class AgentBackendInfoProvider implements AgentRepository.BackendInfoProvider {

    private final AgentManager agentManager;

    public AgentBackendInfoProvider(AgentManager agentManager) {
        this.agentManager = agentManager;
    }

    @Override
    public Map<String, Object> getInfo(String backendId, boolean includeConfigurableItems, String userRole) {
        BaseAgent backend = agentManager.getAgent(backendId);
        if (backend == null) {
            return null;
        }
        return backend.getInfo(includeConfigurableItems, userRole, null);
    }
}
