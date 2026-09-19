package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.AgentContextFields;
import com.wisesoft.wenqu.agents.AgentContextService;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.models.User;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 智能体配置写入前的过滤与资源权限解析。
 *
 * <p>由参考实现的 {@code services/agent_config_service.py} 逐行翻译（该文件只有
 * {@code prepare_agent_config_write} 一个函数）。
 *
 * <h3>职责</h3>
 * 写入智能体配置时做两件事：
 * <ol>
 *   <li>按角色过滤可写字段（{@link BaseContext#filterConfigByRole}）；</li>
 *   <li>解析"本次提交的资源补丁"里每个资源字段对应的**可访问键集合**，
 *       供仓储层在落库前剔除越权引用。</li>
 * </ol>
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code AsyncSession} 入参 → 无（本工程仓储/Mapper 即调用即提交，参考实现该函数只用
 *       {@code db} 透传给资源解析，故 Java 侧直接透传 uid）。</li>
 *   <li>{@code await resolve_agent_resource_options(...)} → 同步 {@link AgentContextService}
 *       （本工程无异步 DB 层）。</li>
 *   <li>参考实现返回 {@code tuple[dict, dict[str, set[str]]]} → {@link WriteResult} 记录型
 *       （Java 无多返回值，用 record 承载同一对结果，字段名与语义对齐）。</li>
 * </ul>
 */
@Service
public class AgentConfigService {

    /** {@code prepare_agent_config_write} 的返回对（过滤后的配置 + 每个资源字段的可访问键集）。 */
    public record WriteResult(Map<String, Object> filtered, Map<String, Set<String>> resourceAccess) {}

    private final AgentContextService agentContextService;

    public AgentConfigService(AgentContextService agentContextService) {
        this.agentContextService = agentContextService;
    }

    /**
     * 过滤可写配置，并解析本次资源补丁对应的可访问键
     * （对应 {@code prepare_agent_config_write}）。
     *
     * @param configJson 提交的 config_json
     * @param contextSchema 目标智能体后端声明的上下文 schema（可为 null，等价参考实现的默认值）
     * @param user 提交者
     */
    public WriteResult prepareAgentConfigWrite(
            Map<String, Object> configJson, Class<? extends BaseContext> contextSchema, User user) {
        Map<String, Object> filtered = BaseContext.filterConfigByRole(configJson, user.getRole(), contextSchema);
        Object context = filtered.get("context");
        if (!(context instanceof Map<?, ?> contextMap)) {
            return new WriteResult(filtered, new LinkedHashMap<>());
        }

        // AGENT_RESOURCE_CONFIG_FIELDS & context.keys()，且值必须是非空 list
        Set<String> submittedFields = new LinkedHashSet<>();
        for (String fieldName : AgentContextFields.AGENT_RESOURCE_CONFIG_FIELDS) {
            if (!contextMap.containsKey(fieldName)) {
                continue;
            }
            Object value = contextMap.get(fieldName);
            if (value instanceof List<?> list && !list.isEmpty()) {
                submittedFields.add(fieldName);
            }
        }
        if (submittedFields.isEmpty()) {
            return new WriteResult(filtered, new LinkedHashMap<>());
        }

        // option_fields = submitted_fields - {"preload_skills"}；若含 preload_skills 则并上 "skills"
        Set<String> optionFields = new LinkedHashSet<>(submittedFields);
        optionFields.remove("preload_skills");
        if (submittedFields.contains("preload_skills")) {
            optionFields.add("skills");
        }
        Map<String, List<Map<String, String>>> options =
                agentContextService.resolveAgentResourceOptions(optionFields, user.getUid());

        Map<String, Set<String>> resourceAccess = new LinkedHashMap<>();
        for (String fieldName : submittedFields) {
            String optionField = "preload_skills".equals(fieldName) ? "skills" : fieldName;
            if (!options.containsKey(optionField)) {
                throw new IllegalStateException("智能体资源字段 " + fieldName + " 缺少权限解析结果");
            }
            Set<String> accessibleKeys = new LinkedHashSet<>();
            for (Map<String, String> option : options.get(optionField)) {
                accessibleKeys.add(option.get("key"));
            }
            resourceAccess.put(fieldName, accessibleKeys);
        }
        return new WriteResult(filtered, resourceAccess);
    }
}
