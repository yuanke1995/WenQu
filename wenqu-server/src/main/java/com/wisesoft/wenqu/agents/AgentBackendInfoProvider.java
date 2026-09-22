package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.repositories.AgentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link AgentRepository.BackendInfoProvider} 的装配实现：把仓储的后端能力查询接到
 * {@link AgentManager} 上。
 *
 * <p>参考实现里 {@code AgentRepository.serialize} 直接迟延导入 {@code agents/buildin} 包的
 * {@code agent_manager} 后取 {@code agent_manager.get_agent(agent.backend_id).get_info(...)}
 * （见 {@code repositories/agent_repository.py} 的 {@code serialize}）。本工程把该依赖收敛为
 * {@code BackendInfoProvider} 扩展点（{@code repositories} 层不反向依赖 {@code agents} 层），
 * 本类即那个扩展点的实现——装配后 {@code serialize} 的行为与参考实现一致。
 *
 * <p>未装配时 {@code AgentRepository} 会对任何后端返回「后端不存在」的空结构
 * （capabilities/metadata/configurable_items 三键全空）；那会让 {@code GET /api/agent} 的
 * capabilities 恒为空，前端据此隐藏能力入口——属静默降级，故本类必须存在。
 *
 * <h3>候选资源注入（对应参考实现 {@code get_info} 的 {@code user} 入参）</h3>
 * <p>参考实现的 {@code get_info(include_configurable_items=True, user=user)} 在装配
 * {@code configurable_items} 后，会按 {@code kind} 注入「当前用户可见」的候选资源
 * （{@code resolve_agent_resource_options(resource_fields, db=db, user=user)}）。
 * 本工程该函数已由 {@link AgentContextService#resolveAgentResourceOptions} 照搬，
 * 故此处把它包成 {@link BaseAgent.AgentResourceOptionsResolver} 接缝传入。
 *
 * <p>为什么必须传：前端 {@code useAgentMentionConfig} 的 @ 提及候选直接取
 * {@code configurable_items.<kind>.options}；不注入时该数组恒为空，智能体配置里的资源
 * 也一并显示为空，表现为「@ 暂无可引用的项」且能力入口消失——即本类曾经显式标注的缺口。
 */
@Component
public class AgentBackendInfoProvider implements AgentRepository.BackendInfoProvider {

    private final AgentManager agentManager;
    private final ObjectProvider<AgentContextService> agentContextServiceProvider;

    public AgentBackendInfoProvider(
            AgentManager agentManager, ObjectProvider<AgentContextService> agentContextServiceProvider) {
        this.agentManager = agentManager;
        this.agentContextServiceProvider = agentContextServiceProvider;
    }

    @Override
    public Map<String, Object> getInfo(
            String backendId, boolean includeConfigurableItems, String userRole, String uid) {
        BaseAgent backend = agentManager.getAgent(backendId);
        if (backend == null) {
            return null;
        }
        return backend.getInfo(includeConfigurableItems, userRole, resourceOptionsResolver(uid));
    }

    /**
     * 资源候选解析器：委托 {@link AgentContextService#resolveAgentResourceOptions}
     * （即 {@code resolve_agent_resource_options} 的照搬件）。
     *
     * <p>{@code uid} 为空或服务不可用时返回 {@code null}，由 {@link BaseAgent#getInfo} 跳过注入
     * （与「未装配解析器」的既有口径一致，不做静默降级以外的处理）。
     */
    private BaseAgent.AgentResourceOptionsResolver resourceOptionsResolver(String uid) {
        AgentContextService service = agentContextServiceProvider.getIfAvailable();
        if (service == null || uid == null || uid.isEmpty()) {
            return null;
        }
        return resourceFields -> {
            Map<String, List<Map<String, String>>> resolved =
                    service.resolveAgentResourceOptions(resourceFields, uid);
            // 值类型 Map<String,String> → Map<String,Object>：Java 泛型不变性所需的搬运，
            // 不改变数据（参考实现是 duck-typed dict，无此区分）。
            Map<String, List<Map<String, Object>>> options = new LinkedHashMap<>();
            if (resolved == null) {
                return options;
            }
            for (Map.Entry<String, List<Map<String, String>>> entry : resolved.entrySet()) {
                List<Map<String, Object>> items = new ArrayList<>();
                if (entry.getValue() != null) {
                    for (Map<String, String> item : entry.getValue()) {
                        items.add(new LinkedHashMap<>(item));
                    }
                }
                options.put(entry.getKey(), items);
            }
            return options;
        };
    }
}
