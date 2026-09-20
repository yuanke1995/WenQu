package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseSummary;
import com.wisesoft.wenqu.models.MCPServer;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 智能体运行时上下文的资源解析与准备。
 *
 * <p>由参考实现的 {@code agents/context.py} 中尚未照搬的三个函数翻译：
 * {@code resolve_agent_resource_options}（资源可选项解析）、
 * {@code normalize_agent_context_config}（上下文配置规范化）与
 * {@code prepare_agent_runtime_context}（单次运行前的上下文准备）。
 *
 * <h3>为什么单独成类</h3>
 * 参考实现的 {@code agents/context.py} 是完整运行时模块，本工程此前只搬了其中的常量与
 * {@link BaseContext} 数据面（见 {@link AgentContextFields} 的类注释：\"其余运行时逻辑留待
 * 服务层照搬时一并处理\"）。本类正是那次承诺的兑现：这三个函数是服务层
 * （{@code agent_config_service} / {@code agent_run_manifest_service} / 运行入口）的实际依赖。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>参考实现各处的函数内迟延导入（避免循环依赖）→ 构造注入
 *       {@link AgentRepository} / {@link UserRepository} / {@link McpService} /
 *       {@link SkillService} / {@link KnowledgeBaseManager} / {@link OptionsService}。</li>
 *   <li>{@code await …} 全链路同步化（本工程无异步 DB 层，调用方线程承担同一阻塞语义）。</li>
 *   <li>{@code hasattr(context, field)} → {@code BaseContext.fieldNames().contains(field)}
 *       （本工程的 \"有没有该属性\" 等价判定是 \"Schema 是否声明\"）。</li>
 *   <li>{@code setattr(context, "_x", …)} / {@code getattr(context, "_x", …)} →
 *       {@link BaseContext#setDynamic} / {@link BaseContext#getDynamic}（{@code set()} 只写
 *       已声明字段，动态属性交给它会静默失效——本项目已为此单独建表）。</li>
 *   <li>{@code system_options.get(db)["default_model"]} → {@link OptionsService#get} 同键。</li>
 * </ul>
 */
@Service
public class AgentContextService {

    /** 上下文动态属性名（参考实现字面量，逐字保留）。 */
    public static final String RUNTIME_PREPARED_ATTR = "_runtime_prepared";

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final McpService mcpService;
    private final SkillService skillService;
    private final SkillRuntime skillRuntime;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final KnowledgeBaseBackend knowledgeBaseBackend;
    private final OptionsService optionsService;

    public AgentContextService(
            AgentRepository agentRepository,
            UserRepository userRepository,
            McpService mcpService,
            SkillService skillService,
            SkillRuntime skillRuntime,
            KnowledgeBaseManager knowledgeBaseManager,
            KnowledgeBaseBackend knowledgeBaseBackend,
            OptionsService optionsService) {
        this.agentRepository = agentRepository;
        this.userRepository = userRepository;
        this.mcpService = mcpService;
        this.skillService = skillService;
        this.skillRuntime = skillRuntime;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.knowledgeBaseBackend = knowledgeBaseBackend;
        this.optionsService = optionsService;
    }

    // ==================== resolve_agent_resource_options ====================

    /** 解析资源可选项（对应 {@code resolve_agent_resource_options}，无字段时为整个运行时字段集）。 */
    public Map<String, List<Map<String, String>>> resolveAgentResourceOptions(Set<String> resourceFields, String uid) {
        Set<String> fieldsToLoad =
                resourceFields == null ? AgentContextFields.AGENT_RUNTIME_RESOURCE_FIELDS : resourceFields;
        Map<String, List<Map<String, String>>> options = new LinkedHashMap<>();
        if (fieldsToLoad.isEmpty()) {
            return options;
        }

        if (fieldsToLoad.contains("tools")) {
            List<Map<String, String>> tools = new ArrayList<>();
            for (Map<String, Object> tool : ToolkitsService.getToolMetadata("buildin")) {
                Object slug = tool.get("slug");
                if (slug != null && !String.valueOf(slug).isEmpty()) {
                    tools.add(resourceOption(
                            slug, tool.get("name"), tool.get("description")));
                }
            }
            options.put("tools", tools);
        }

        if (fieldsToLoad.contains("knowledges")) {
            List<Map<String, String>> knowledges = new ArrayList<>();
            User user = userRepository.getByUid(uid);
            List<KnowledgeBaseSummary> databases =
                    user == null ? List.of() : knowledgeBaseManager.getDatabasesByUid(uid);
            for (KnowledgeBaseSummary item : databases) {
                if (item.kbId() != null && !item.kbId().isEmpty()) {
                    knowledges.add(resourceOption(item.kbId(), item.name(), item.description()));
                }
            }
            options.put("knowledges", knowledges);
        }

        if (fieldsToLoad.contains("mcps")) {
            List<Map<String, String>> mcps = new ArrayList<>();
            List<String> enabledSlugs = mcpService.getEnabledMcpServerSlugs();
            Set<String> enabled = new LinkedHashSet<>(enabledSlugs == null ? List.of() : enabledSlugs);
            for (MCPServer server : mcpService.getAllMcpServers()) {
                if (server.getSlug() != null && enabled.contains(server.getSlug())) {
                    mcps.add(resourceOption(server.getSlug(), server.getName(), server.getDescription()));
                }
            }
            options.put("mcps", mcps);
        }

        if (fieldsToLoad.contains("skills")) {
            List<Map<String, String>> skills = new ArrayList<>();
            User user = userRepository.getByUid(uid);
            if (user != null) {
                for (ResolvedSkill skill : skillService.listAccessibleSkills(user, true)) {
                    if (skill.slug() != null && !skill.slug().isEmpty()) {
                        skills.add(resourceOption(skill.slug(), skill.name(), skill.description()));
                    }
                }
            }
            options.put("skills", skills);
        }

        if (fieldsToLoad.contains("subagents")) {
            List<Map<String, String>> subagents = new ArrayList<>();
            User user = userRepository.getByUid(uid);
            if (user != null) {
                for (var agent : agentRepository.listVisibleSubagents(
                        com.wisesoft.wenqu.permissions.PermissionSubject.of(user))) {
                    if (agent.getSlug() != null && !agent.getSlug().isEmpty()) {
                        subagents.add(resourceOption(
                                agent.getSlug(), agent.getName(), agent.getDescription()));
                    }
                }
            }
            options.put("subagents", subagents);
        }

        return options;
    }

    /** 资源选项三元组（对应 {@code _resource_option}）。 */
    public static Map<String, String> resourceOption(Object key, Object name, Object description) {
        String keyValue = String.valueOf(key);
        Map<String, String> option = new LinkedHashMap<>();
        option.put("key", keyValue);
        option.put("name", name == null ? keyValue : String.valueOf(name));
        option.put("description", description == null ? "" : String.valueOf(description));
        return option;
    }

    // ==================== normalize_agent_context_config ====================

    /**
     * 规范化上下文配置：只保留声明字段、要求可用键的资源字段按可用集合收敛
     * （对应 {@code normalize_agent_context_config}）。
     */
    public Map<String, Object> normalizeAgentContextConfig(Map<String, Object> context, String uid) {
        Map<String, Object> rawContext = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("context", rawContext);
        Map<String, Object> filtered =
                BaseContext.filterDeclaredConfig(outer, BaseContext.fieldNames());
        @SuppressWarnings("unchecked")
        Map<String, Object> contextFiltered =
                (Map<String, Object>) filtered.get("context");
        Map<String, Object> normalized =
                contextFiltered == null ? new LinkedHashMap<>() : new LinkedHashMap<>(contextFiltered);

        Set<String> fieldNames = BaseContext.fieldNames();
        Set<String> resourceFields = new LinkedHashSet<>();
        for (String field : AgentContextFields.AGENT_RUNTIME_RESOURCE_FIELDS) {
            if (fieldNames.contains(field)) {
                resourceFields.add(field);
            }
        }
        Set<String> fieldsToLoad = BaseContext.resourceFieldsRequiringAvailableKeys(normalized, resourceFields);
        if (!fieldsToLoad.isEmpty()) {
            Map<String, List<Map<String, String>>> resourceOptions = resolveAgentResourceOptions(fieldsToLoad, uid);
            for (Map.Entry<String, List<Map<String, String>>> entry : resourceOptions.entrySet()) {
                List<String> availableKeys = new ArrayList<>();
                for (Map<String, String> option : entry.getValue()) {
                    availableKeys.add(option.get("key"));
                }
                Object current = normalized.get(entry.getKey());
                if (current == null) {
                    normalized.put(entry.getKey(), availableKeys);
                } else {
                    normalized.put(entry.getKey(),
                            BaseContext.normalizeSelectedResourceKeys(current, availableKeys));
                }
            }
        }

        if (fieldNames.contains("preload_skills")) {
            normalized.put("preload_skills", BaseContext.normalizeSelectedResourceKeys(
                    normalized.get("preload_skills"), asStringList(normalized.get("skills"))));
        }
        return normalized;
    }

    // ==================== prepare_agent_runtime_context ====================

    /**
     * 为单次运行解析资源与 Skill；同一对象再次构图时复用准备结果
     * （对应 {@code prepare_agent_runtime_context}）。
     */
    public BaseContext prepareAgentRuntimeContext(BaseContext context) {
        if (context == null || Boolean.TRUE.equals(context.getDynamic(RUNTIME_PREPARED_ATTR, false))) {
            return context;
        }
        String uid = context.getString("uid");
        uid = uid == null ? "" : uid.strip();
        if (uid.isEmpty()) {
            return context;
        }

        context.appendWorkspaceAgentPrompt();

        Set<String> fieldNames = BaseContext.fieldNames();
        Set<String> contextResourceFields = new LinkedHashSet<>(AgentContextFields.AGENT_RUNTIME_RESOURCE_FIELDS);
        contextResourceFields.add("preload_skills");

        String model = context.getString("model");
        if (model == null || model.strip().isEmpty()) {
            Object defaultModel = optionsService.get(OptionsService.SYSTEM_OPTIONS).get("default_model");
            context.set("model", defaultModel == null ? "" : String.valueOf(defaultModel));
        }

        User user = userRepository.getByUid(uid);
        if (user == null) {
            for (String fieldName : contextResourceFields) {
                if (fieldNames.contains(fieldName)) {
                    context.set(fieldName, new ArrayList<>());
                }
            }
            context.setDynamic(SkillRuntime.SKILL_RUNTIME_SNAPSHOT_ATTR, new LinkedHashMap<>());
            context.setDynamic(KnowledgeBaseBackend.VISIBLE_KNOWLEDGE_BASES_ATTR, new ArrayList<>());
            return context;
        }

        Map<String, Object> rawResources = new LinkedHashMap<>();
        for (String fieldName : contextResourceFields) {
            if (fieldNames.contains(fieldName)) {
                rawResources.put(fieldName, context.get(fieldName));
            }
        }
        Map<String, Object> normalized = normalizeAgentContextConfig(rawResources, uid);
        for (String fieldName : contextResourceFields) {
            if (fieldNames.contains(fieldName)) {
                Object value = normalized.get(fieldName);
                context.set(fieldName, value == null ? new ArrayList<>() : value);
            }
        }

        knowledgeBaseBackend.resolveVisibleKnowledgeBasesForContext(context);

        Map<String, Object> skillScope = skillRuntime.resolveRuntimeSkillsForContext(context, user);
        context.setDynamic(SkillRuntime.SKILL_RUNTIME_SNAPSHOT_ATTR, skillScope);
        context.set("skills", skillScope.get("context_skills"));
        context.set("preload_skills", skillScope.get("context_preload_skills"));

        context.setDynamic(RUNTIME_PREPARED_ATTR, true);
        return context;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(item == null ? null : String.valueOf(item));
        }
        return result;
    }
}
