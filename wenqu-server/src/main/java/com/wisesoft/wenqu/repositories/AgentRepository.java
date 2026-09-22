package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.agents.AgentContextFields;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.MinioUrls;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.permissions.ResourcePermission;
import com.wisesoft.wenqu.permissions.ResourcePermissions;
import com.wisesoft.wenqu.permissions.ShareableResource;
import com.wisesoft.wenqu.repository.port.AgentMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能体仓储。
 *
 * <p>由参考实现的 repositories/agent_repository.py 逐方法翻译：内置智能体的落库与自愈、
 * 可见性过滤（超管直通，其余按权限解析）、按 slug 读取并区分主/子入口、默认智能体的
 * 唯一化与固定为内置智能体、slug 去重生成、创建/更新（含配置合并与资源字段的不可见引用保留）、
 * 以及序列化输出（can_manage / effective_permission / is_builtin / permission_locked）。
 *
 * <p>必要替换：
 * <ul>
 *   <li>行锁 {@code with_for_update()} → {@code FOR UPDATE}；取锁方法加 {@code @Transactional}。
 *   <li>JSON 列（pics / config_json / share_config）写入显式序列化；读取统一解析为 JSON 对象/数组。
 *   <li>{@code copy.deepcopy} → JSON 往返深拷贝（语义一致：不受调用方后续修改影响）。
 *   <li>Python 的 {@code Collection[str]} 参数 → Java 的 {@code Collection<String>}。
 * </ul>
 *
 * <p><b>能力差异（显式标注，非遗漏）</b>：{@code serialize} 在参考实现里会向
 * {@code agents/buildin} 的智能体注册表查询后端能力清单（capabilities / metadata /
 * configurable_items）。该注册表属于 agents 运行时模块，本轮未照搬，因此本实现把这一段
 * 收敛为可注入的扩展点 {@link BackendInfoProvider}：未装配时返回与"后端不存在"一致的空结构，
 * 装配后行为与参考实现一致。
 */
@Repository
public class AgentRepository {

    /** 内置默认智能体 slug。 */
    public static final String DEFAULT_AGENT_SLUG = "default-chatbot";

    /** 内置默认智能体名称。 */
    public static final String DEFAULT_AGENT_NAME = "智能助手";

    /** 内置默认智能体后端 id。 */
    public static final String DEFAULT_AGENT_BACKEND_ID = "ChatbotAgent";

    /** 子智能体后端 id。 */
    public static final String SUB_AGENT_BACKEND_ID = "SubAgentBackend";

    /** 内置默认智能体描述。 */
    public static final String DEFAULT_AGENT_DESCRIPTION =
            "基础的对话机器人，可以回答问题，可在配置中启用需要的工具。";

    /** 通用任务子智能体。 */
    public static final String GENERAL_PURPOSE_AGENT_SLUG = "general-purpose";

    public static final String GENERAL_PURPOSE_AGENT_NAME = "通用任务";

    public static final String GENERAL_PURPOSE_AGENT_DESCRIPTION =
            "面向没有专用角色约束的一般任务，使用默认运行配置独立完成分析、整理、写作或文件处理。";

    /** 网页检索子智能体。 */
    public static final String WEB_SEARCH_AGENT_SLUG = "web-search";

    public static final String WEB_SEARCH_AGENT_NAME = "网页检索";

    public static final String WEB_SEARCH_AGENT_DESCRIPTION = "围绕检索目标持续搜索网页，返回带引用来源的摘要资料。";

    public static final String WEB_SEARCH_SYSTEM_PROMPT =
            """
            你是「网页检索」子智能体，专注于面向目标的网页信息检索。

            你的职责：围绕调用方给定的检索目标，使用网页搜索工具持续检索，直到收集到足以回答目标的信息。

            工作方式：
            1. 拆解目标，确定需要检索的关键问题与检索词。
            2. 多轮调用搜索工具：依据上一轮结果调整检索词、补充遗漏角度、交叉验证关键事实，直到信息充分或确认无法获取更多有效信息。
            3. 优先采信权威、时效性强且彼此印证的来源；对存在冲突的信息要说明分歧。

            输出要求：
            - 返回一份结构化的摘要资料，按主题或要点组织。
            - 每条关键结论后使用 <cite source="$URL" type="url">$INDEX</cite> 标注引用来源，$INDEX 从 1 开始递增。
            - 引用不单独成行，直接跟在结论后面。
            - 在结尾汇总「参考来源」列表，逐条列出标题与 URL。
            - 不要编造来源或链接；无法验证的信息要明确标注。""";

    /** 深度研究编排器。 */
    public static final String DEEP_RESEARCH_AGENT_SLUG = "deep-research";

    public static final String DEEP_RESEARCH_AGENT_NAME = "深度研究";

    public static final String DEEP_RESEARCH_AGENT_DESCRIPTION =
            "面向多来源、需事实核查的深度研究任务：规划拆解、并行调度调研子智能体、核验并综合成带引用的结构化报告。";

    public static final String DEEP_RESEARCH_SYSTEM_PROMPT =
            """
            你是「深度研究」智能体，负责一项深度研究任务的整体把控与子智能体调度。

            你的核心定位是编排者，而不是亲自完成所有检索：把繁重、可独立、可并行的调研与核验工作派发给子智能体，自己专注于规划、调度与最终综合。

            工作方式：
            1. 接到研究任务后，先读取 `deep-research` 技能（read_file 其 SKILL.md）获取完整方法论，并严格据此执行。
            2. 问题不明确时先澄清范围，再用待办拆解出可独立调研的子问题。
            3. 优先用 `task` 工具把子问题并行派发给调研子智能体；仅在澄清范围或补少量零散事实时自己直接检索。
            4. 对关键结论与相互冲突的发现派发核查子智能体核验，未通过的结论不写入正文或明确降级标注。
            5. 证据充分后由你统一综合为结构化、带引用的报告，不要简单拼接子智能体返回的原文。

            始终全程跟踪进度，最终交付一份可直接使用、围绕论证组织、来源可追溯的报告。""";

    /** 调研探索员。 */
    public static final String RESEARCH_EXPLORER_AGENT_SLUG = "research-explorer";

    public static final String RESEARCH_EXPLORER_AGENT_NAME = "调研探索员";

    public static final String RESEARCH_EXPLORER_AGENT_DESCRIPTION =
            "围绕单个子问题多轮检索网页与知识库，交叉验证后返回带引用的结构化发现。";

    public static final String RESEARCH_EXPLORER_SYSTEM_PROMPT =
            """
            你是「调研探索员」子智能体。
            专注于围绕调用方给定的**单个子问题**收集充分、可追溯的证据。

            你的职责：围绕该子问题持续检索网页与知识库，直到收集到足以回答它的信息。

            工作方式：
            1. 拆解子问题，确定需要检索的关键点与检索词。
            2. 多轮调用检索工具：依据上一轮结果调整检索词、补充遗漏角度、交叉验证关键事实，直到信息充分或确认无法获取更多有效信息。
            3. 优先采信权威、时效性强且彼此印证的来源；对存在冲突的信息要说明分歧。

            输出要求：
            - 返回一份围绕该子问题、按要点组织的结构化发现，不要展开成完整报告。
            - 每条关键结论后使用 <cite source="$URL" type="url">$INDEX</cite> 标注引用来源，$INDEX 从 1 开始递增。
            - 引用紧跟结论后、不单独成行。
            - 结尾汇总「参考来源」列表，逐条列出标题与 URL。
            - 不要编造来源或链接；无法验证的信息要明确标注证据缺口。""";

    /** 事实核查员。 */
    public static final String FACT_VERIFIER_AGENT_SLUG = "fact-verifier";

    public static final String FACT_VERIFIER_AGENT_NAME = "事实核查员";

    public static final String FACT_VERIFIER_AGENT_DESCRIPTION =
            "对给定论断做对抗式核验，逐条给出支持/存疑/反驳判定、依据来源与置信度，并标注冲突。";

    public static final String FACT_VERIFIER_SYSTEM_PROMPT =
            """
            你是「事实核查员」子智能体，专注于对调用方给定的论断做对抗式核验。

            你的职责：对每一条论断独立查证，默认持怀疑态度——证据不足时倾向判定「存疑」，而不是默认相信。

            工作方式：
            1. 逐条拆出待核验的论断（事实、数字、因果、时间等）。
            2. 主动检索权威、独立的来源交叉比对；优先寻找能反驳该论断的证据。
            3. 对来源之间的冲突如实呈现，不强行调和。

            输出要求：
            - 对每条论断给出：判定（支持 / 存疑 / 反驳）+ 简要依据 + 依据来源 + 置信度（高/中/低）。
            - 关键依据后使用 <cite source="$URL" type="url">$INDEX</cite> 标注来源，$INDEX 从 1 开始递增。
            - 明确标注无法查证或来源相互冲突的论断。
            - 不要编造来源或链接。""";

    /** 管理员角色。 */
    public static final Set<String> ADMIN_ROLES = Set.of("admin", "superadmin");

    /** 默认共享配置：全局可读、未显式声明管理范围。 */
    public static final JSONObject DEFAULT_SHARE_CONFIG = defaultShareConfig();

    private static JSONObject defaultShareConfig() {
        JSONObject config = new JSONObject();
        config.put("version", 2);
        config.put("read_scope", ResourcePermissions.newDefaultScope());
        config.put("manage_scope", null);
        return config;
    }

    /** 智能体入口类型。 */
    public enum AgentEntryKind {
        MAIN,
        SUBAGENT,
        ANY
    }

    /** 后端能力信息提供者（对应参考实现的智能体注册表，见类注释的"能力差异"）。 */
    public interface BackendInfoProvider {
        /**
         * 返回 capabilities / metadata / configurable_items 三项（键名照搬）。
         *
         * <p>{@code uid} 用于解析「当前用户可见」的候选资源（知识库 / MCP / Skill / 子智能体）——
         * 参考实现的 {@code get_info} 就是带着 {@code user} 调
         * {@code resolve_agent_resource_options}；故缓存键必须同时含 role 与 uid，
         * 否则会把某个用户的可选资源集合串给另一个用户。
         */
        Map<String, Object> getInfo(
                String backendId, boolean includeConfigurableItems, String userRole, String uid);
    }

    private final AgentMapper agentMapper;
    private final ObjectProvider<BackendInfoProvider> backendInfoProvider;

    /**
     * 后端信息提供者按可选依赖注入：参考实现的 {@code serialize} 在运行期迟延导入
     * {@code agents.buildin.agent_manager}（见 {@code repositories/agent_repository.py}），
     * 本工程用 {@link ObjectProvider} 承载同一迟延语义——未装配时 serialize 走"后端不存在"分支。
     *
     * <p><b>必须在构造器里保持未解析</b>：{@link BackendInfoProvider} 的实现
     * （{@code AgentBackendInfoProvider}）反过来依赖 {@code AgentManager}，而
     * {@code AgentManager} → {@code ChatbotAgent} → {@code AgentCompositeBackend} →
     * {@code SkillService} → 本类。构造期调用 {@code getIfAvailable()} 会把注入点"拉直"成
     * 装配期强依赖，形成 Spring 单例环（已实测报 BeanCurrentlyInCreationException）。
     * 故此处只存 provider，解析一律推迟到 {@link #serialize} 的调用点。
     */
    public AgentRepository(
            AgentMapper agentMapper,
            ObjectProvider<BackendInfoProvider> backendInfoProvider) {
        this.agentMapper = agentMapper;
        this.backendInfoProvider = backendInfoProvider;
    }

    // ==================== 模块级函数（对应参考实现的同名函数） ====================

    /** 是否内置默认智能体。 */
    public static boolean isBuiltinAgent(Agent agent) {
        return DEFAULT_AGENT_SLUG.equals(agent.getSlug());
    }

    /** 校验并推导是否子智能体：SubAgentBackend 与 is_subagent 必须保持一致。 */
    public static boolean resolveAgentIsSubagent(String backendId, Boolean isSubagent) {
        boolean expected = SUB_AGENT_BACKEND_ID.equals(backendId);
        if (isSubagent != null && isSubagent != expected) {
            throw new IllegalArgumentException("SubAgentBackend 与 is_subagent 必须保持一致");
        }
        return expected;
    }

    /** 当前用户允许使用的共享访问级别。 */
    public static List<String> getAllowedAgentAccessLevels(PermissionSubject user) {
        if (user != null && ADMIN_ROLES.contains(user.role())) {
            return List.of("global", "department", "user");
        }
        return List.of("user");
    }

    /** 规范化智能体共享配置（严格校验、并限定允许的访问级别）。 */
    public static JSONObject normalizeAgentShareConfig(
            JSONObject shareConfig, Collection<String> allowedAccessLevels) {
        return ResourcePermissions.normalizePermissionConfig(
                shareConfig == null ? DEFAULT_SHARE_CONFIG : shareConfig,
                allowedAccessLevels,
                "当前用户无权使用该智能体共享范围",
                true);
    }

    /** 用户是否可访问该智能体。 */
    public static boolean userCanAccessAgent(PermissionSubject user, Agent agent) {
        return ResourcePermissions.resolveAgentPermission(user, ShareableResource.of(agent))
                != ResourcePermission.NONE;
    }

    /** 用户是否可管理该智能体（内置智能体仅管理员可管理）。 */
    public static boolean userCanManageAgent(PermissionSubject user, Agent agent) {
        if (isBuiltinAgent(agent)) {
            return user != null && ADMIN_ROLES.contains(user.role());
        }
        return ResourcePermissions.resolveAgentPermission(user, ShareableResource.of(agent))
                == ResourcePermission.MANAGE;
    }

    private static final Pattern SLUG_UNSAFE = Pattern.compile("[^a-zA-Z0-9_-]+");

    /** slug 生成：不安全字符替换为连字符，全空时用 uuid 兜底。 */
    static String slugify(String value) {
        String base = (value == null ? "" : value).trim().toLowerCase();
        base = SLUG_UNSAFE.matcher(base).replaceAll("-");
        base = trimHyphens(base);
        if (base.length() > 56) {
            base = base.substring(0, 56);
        }
        if (base.isEmpty()) {
            return "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return base;
    }

    private static String trimHyphens(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    // ==================== 内置智能体的落库与自愈 ====================

    /** 确保默认智能体存在，并修正被改坏的字段（共享范围/描述/是否子智能体/默认标记）。 */
    @Transactional
    public Agent ensureDefaultAgent(String createdBy) {
        Agent agent = getBySlug(DEFAULT_AGENT_SLUG);
        if (agent != null) {
            boolean needsUpdate = false;
            if (!DEFAULT_SHARE_CONFIG.equals(RepoValues.parseObject(agent.getShareConfig()))) {
                agent.setShareConfig(JSON.toJSONString(DEFAULT_SHARE_CONFIG));
                needsUpdate = true;
            }
            if (agent.getDescription() == null || agent.getDescription().isEmpty()) {
                agent.setDescription(DEFAULT_AGENT_DESCRIPTION);
                needsUpdate = true;
            }
            if (Boolean.TRUE.equals(agent.getIsSubagent())) {
                agent.setIsSubagent(false);
                needsUpdate = true;
            }
            if (!Boolean.TRUE.equals(agent.getIsDefault())) {
                return setDefault(agent, createdBy);
            }
            if (needsUpdate) {
                agent.setUpdatedBy(createdBy);
                agent.setUpdatedAt(DateTimeUtils.utcNowNaive());
                persistAgent(agent);
            }
            return agent;
        }

        Agent created = new Agent();
        created.setSlug(DEFAULT_AGENT_SLUG);
        created.setBackendId(DEFAULT_AGENT_BACKEND_ID);
        created.setName(DEFAULT_AGENT_NAME);
        created.setDescription(DEFAULT_AGENT_DESCRIPTION);
        created.setIcon(null);
        created.setPics("[]");
        created.setConfigJson("{\"context\":{}}");
        created.setShareConfig(JSON.toJSONString(DEFAULT_SHARE_CONFIG));
        created.setIsDefault(true);
        created.setIsSubagent(false);
        created.setCreatedBy(createdBy);
        created.setUpdatedBy(createdBy);
        created.setCreatedAt(DateTimeUtils.utcNowNaive());
        created.setUpdatedAt(DateTimeUtils.utcNowNaive());
        agentMapper.insert(created);
        return created;
    }

    /** 确保网页检索子智能体存在。 */
    @Transactional
    public Agent ensureWebSearchSubagent(String createdBy) {
        Agent agent = getBySlug(WEB_SEARCH_AGENT_SLUG);
        if (agent != null) {
            return agent;
        }
        return ensureBuiltinAgent(
                WEB_SEARCH_AGENT_SLUG,
                SUB_AGENT_BACKEND_ID,
                WEB_SEARCH_AGENT_NAME,
                WEB_SEARCH_AGENT_DESCRIPTION,
                contextWithSystemPrompt(WEB_SEARCH_SYSTEM_PROMPT),
                true,
                createdBy);
    }

    /** 确保通用任务子智能体存在。 */
    @Transactional
    public Agent ensureGeneralPurposeSubagent(String createdBy) {
        return ensureBuiltinAgent(
                GENERAL_PURPOSE_AGENT_SLUG,
                SUB_AGENT_BACKEND_ID,
                GENERAL_PURPOSE_AGENT_NAME,
                GENERAL_PURPOSE_AGENT_DESCRIPTION,
                new JSONObject(),
                true,
                createdBy);
    }

    /** 落库内置「深度研究」编排器及其配套调研、核查子智能体。 */
    @Transactional
    public void ensureDeepResearchAgents(String createdBy) {
        ensureBuiltinAgent(
                RESEARCH_EXPLORER_AGENT_SLUG,
                SUB_AGENT_BACKEND_ID,
                RESEARCH_EXPLORER_AGENT_NAME,
                RESEARCH_EXPLORER_AGENT_DESCRIPTION,
                contextWithSystemPrompt(RESEARCH_EXPLORER_SYSTEM_PROMPT),
                true,
                createdBy);
        ensureBuiltinAgent(
                FACT_VERIFIER_AGENT_SLUG,
                SUB_AGENT_BACKEND_ID,
                FACT_VERIFIER_AGENT_NAME,
                FACT_VERIFIER_AGENT_DESCRIPTION,
                contextWithSystemPrompt(FACT_VERIFIER_SYSTEM_PROMPT),
                true,
                createdBy);
        JSONObject context = contextWithSystemPrompt(DEEP_RESEARCH_SYSTEM_PROMPT);
        context.put(
                "subagents",
                new JSONArray(List.of(RESEARCH_EXPLORER_AGENT_SLUG, FACT_VERIFIER_AGENT_SLUG)));
        context.put("skills", new JSONArray(List.of(DEEP_RESEARCH_AGENT_SLUG)));
        ensureBuiltinAgent(
                DEEP_RESEARCH_AGENT_SLUG,
                DEFAULT_AGENT_BACKEND_ID,
                DEEP_RESEARCH_AGENT_NAME,
                DEEP_RESEARCH_AGENT_DESCRIPTION,
                context,
                false,
                createdBy);
    }

    /** 落库一个内置 Agent；已存在则原样返回，避免覆盖管理员后续修改。 */
    @Transactional
    public Agent ensureBuiltinAgent(
            String slug,
            String backendId,
            String name,
            String description,
            JSONObject configContext,
            boolean isSubagent,
            String createdBy) {
        Agent agent = getBySlug(slug);
        if (agent != null) {
            return agent;
        }
        Agent created = new Agent();
        created.setSlug(slug);
        created.setBackendId(backendId);
        created.setName(name);
        created.setDescription(description);
        created.setIcon(null);
        created.setPics("[]");
        JSONObject config = new JSONObject();
        config.put("context", configContext);
        created.setConfigJson(JSON.toJSONString(config));
        created.setShareConfig(JSON.toJSONString(DEFAULT_SHARE_CONFIG));
        created.setIsDefault(false);
        created.setIsSubagent(isSubagent);
        created.setCreatedBy(createdBy);
        created.setUpdatedBy(createdBy);
        created.setCreatedAt(DateTimeUtils.utcNowNaive());
        created.setUpdatedAt(DateTimeUtils.utcNowNaive());
        agentMapper.insert(created);
        return created;
    }

    // ==================== 查询 ====================

    /** 列出用户可见的主智能体，只有显式请求时才包含子智能体定义。 */
    public List<Agent> listVisible(PermissionSubject user, boolean includeSubagentDefinitions) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        if (!includeSubagentDefinitions) {
            wrapper.eq(Agent::getIsSubagent, false);
        }
        wrapper.orderByDesc(Agent::getIsDefault).orderByAsc(Agent::getId);
        List<Agent> agents = agentMapper.selectList(wrapper);
        if (user != null && "superadmin".equals(user.role())) {
            return agents;
        }
        List<Agent> visible = new ArrayList<>();
        for (Agent agent : agents) {
            if (userCanAccessAgent(user, agent)) {
                visible.add(agent);
            }
        }
        return visible;
    }

    /** 列出用户可见的子智能体。 */
    public List<Agent> listVisibleSubagents(PermissionSubject user) {
        List<Agent> agents =
                agentMapper.selectList(
                        new LambdaQueryWrapper<Agent>()
                                .eq(Agent::getIsSubagent, true)
                                .orderByAsc(Agent::getName)
                                .orderByAsc(Agent::getId));
        if (user != null && "superadmin".equals(user.role())) {
            return agents;
        }
        List<Agent> visible = new ArrayList<>();
        for (Agent agent : agents) {
            if (userCanAccessAgent(user, agent)) {
                visible.add(agent);
            }
        }
        return visible;
    }

    public Agent getBySlug(String slug) {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getSlug, slug));
    }

    public List<Agent> listBySlugs(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            return new ArrayList<>();
        }
        return agentMapper.selectList(new LambdaQueryWrapper<Agent>().in(Agent::getSlug, slugs));
    }

    /** 按 slug 读取用户可见智能体，并按入口语义过滤主/子智能体。 */
    public Agent getVisibleBySlug(String slug, PermissionSubject user, AgentEntryKind kind) {
        Agent agent = getBySlug(slug);
        if (agent == null) {
            return null;
        }
        if (!userCanAccessAgent(user, agent)) {
            return null;
        }
        return switch (kind) {
            case ANY -> agent;
            case MAIN -> Boolean.TRUE.equals(agent.getIsSubagent()) ? null : agent;
            case SUBAGENT -> Boolean.TRUE.equals(agent.getIsSubagent()) ? agent : null;
        };
    }

    public Agent getDefault() {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getIsDefault, true));
    }

    /** 把智能体设为默认（仅内置智能助手可作默认，且必须全局共享）。 */
    @Transactional
    public Agent setDefault(Agent agent, String updatedBy) {
        if (Boolean.TRUE.equals(agent.getIsSubagent())) {
            throw new IllegalArgumentException("子智能体不能设为默认智能体");
        }
        if (!isBuiltinAgent(agent)) {
            throw new IllegalArgumentException("默认智能体已固定为内置智能助手");
        }
        JSONObject shareConfig =
                agent.getShareConfig() == null
                        ? DEFAULT_SHARE_CONFIG.clone()
                        : RepoValues.parseObject(agent.getShareConfig());
        JSONObject readScope = shareConfig.getJSONObject("read_scope");
        if (readScope == null || !"global".equals(readScope.getString("access_level"))) {
            throw new IllegalArgumentException("内置智能体必须全局共享");
        }

        java.time.LocalDateTime now = DateTimeUtils.utcNowNaive();
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<Agent>()
                        .eq(Agent::getIsDefault, true)
                        .set(Agent::getIsDefault, false)
                        .set(Agent::getUpdatedAt, now));
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<Agent>()
                        .eq(Agent::getId, agent.getId())
                        .set(Agent::getIsDefault, true)
                        .set(Agent::getUpdatedBy, updatedBy)
                        .set(Agent::getUpdatedAt, now));
        agent.setIsDefault(true);
        agent.setUpdatedBy(updatedBy);
        agent.setUpdatedAt(now);
        return agent;
    }

    private boolean slugExists(String slug) {
        Long count = agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getSlug, slug));
        return count != null && count > 0;
    }

    private String uniqueSlug(String desired, String name) {
        String base = slugify(desired == null || desired.isEmpty() ? name : desired);
        String candidate = base;
        int index = 2;
        while (slugExists(candidate)) {
            String suffix = "-" + index;
            int keep = Math.max(0, 80 - suffix.length());
            candidate = base.substring(0, Math.min(base.length(), keep)) + suffix;
            index++;
        }
        return candidate;
    }

    // ==================== 创建 / 更新 / 删除 ====================

    /** 创建智能体。 */
    @Transactional
    public Agent create(
            String name,
            String backendId,
            String slug,
            String description,
            String icon,
            List<String> pics,
            JSONObject configJson,
            Map<String, Collection<String>> configResourceAccess,
            JSONObject shareConfig,
            boolean isDefault,
            Boolean isSubagent,
            String createdBy,
            PermissionSubject creator) {
        boolean resolvedIsSubagent = resolveAgentIsSubagent(backendId, isSubagent);
        if (resolvedIsSubagent && isDefault) {
            throw new IllegalArgumentException("子智能体不能设为默认智能体");
        }
        String ownerUid = createdBy == null ? "" : createdBy;
        JSONObject defaultShareConfig = new JSONObject();
        defaultShareConfig.put("version", 2);
        JSONObject readScope = new JSONObject();
        readScope.put("access_level", "user");
        readScope.put("department_ids", new JSONArray());
        readScope.put("user_uids", new JSONArray(List.of(ownerUid)));
        defaultShareConfig.put("read_scope", readScope);
        defaultShareConfig.put("manage_scope", null);

        List<String> allowedAccessLevels = creator == null ? null : getAllowedAgentAccessLevels(creator);
        JSONObject normalizedShareConfig =
                normalizeAgentShareConfig(shareConfig == null ? defaultShareConfig : shareConfig, allowedAccessLevels);
        JSONObject normalizedReadScope = normalizedShareConfig.getJSONObject("read_scope");
        if (isDefault
                && (normalizedReadScope == null
                        || !"global".equals(normalizedReadScope.getString("access_level")))) {
            throw new IllegalArgumentException("默认智能体必须全局共享");
        }

        Agent agent = new Agent();
        agent.setSlug(uniqueSlug(slug, name));
        agent.setBackendId(backendId);
        String trimmedName = name == null ? "" : name.trim();
        agent.setName(trimmedName.isEmpty() ? "未命名智能体" : trimmedName);
        agent.setDescription(description);
        agent.setIcon(icon);
        agent.setPics(JSON.toJSONString(pics == null ? new ArrayList<>() : pics));
        JSONObject baseConfig = new JSONObject();
        baseConfig.put("context", new JSONObject());
        agent.setConfigJson(
                JSON.toJSONString(
                        mergeAgentConfigJson(
                                baseConfig, configJson == null ? new JSONObject() : configJson, configResourceAccess)));
        agent.setShareConfig(JSON.toJSONString(normalizedShareConfig));
        agent.setIsDefault(false);
        agent.setIsSubagent(resolvedIsSubagent);
        agent.setCreatedBy(createdBy);
        agent.setUpdatedBy(createdBy);
        agent.setCreatedAt(DateTimeUtils.utcNowNaive());
        agent.setUpdatedAt(DateTimeUtils.utcNowNaive());
        agentMapper.insert(agent);
        if (isDefault) {
            return setDefault(agent, createdBy);
        }
        return agent;
    }

    /** 更新智能体（仅写入出现的字段；配置走合并语义）。 */
    @Transactional
    public Agent update(
            Agent agent,
            String name,
            String description,
            String icon,
            List<String> pics,
            JSONObject configJson,
            Map<String, Collection<String>> configResourceAccess,
            JSONObject shareConfig,
            Boolean isSubagent,
            String updatedBy,
            PermissionSubject updater) {
        if (isSubagent != null) {
            agent.setIsSubagent(resolveAgentIsSubagent(agent.getBackendId(), isSubagent));
        }
        if (name != null) {
            String trimmedName = name.trim();
            agent.setName(trimmedName.isEmpty() ? "未命名智能体" : trimmedName);
        }
        if (description != null) {
            agent.setDescription(description);
        }
        if (icon != null) {
            agent.setIcon(icon);
        }
        if (pics != null) {
            agent.setPics(JSON.toJSONString(pics));
        }
        if (configJson != null) {
            Agent locked =
                    agentMapper.selectOne(
                            new LambdaQueryWrapper<Agent>().eq(Agent::getId, agent.getId()).last("FOR UPDATE"));
            if (locked == null) {
                throw new IllegalArgumentException("智能体不存在");
            }
            agent.setConfigJson(
                    JSON.toJSONString(
                            mergeAgentConfigJson(
                                    RepoValues.parseObject(locked.getConfigJson()),
                                    configJson,
                                    configResourceAccess)));
        }
        if (shareConfig != null) {
            if (isBuiltinAgent(agent)) {
                agent.setShareConfig(JSON.toJSONString(DEFAULT_SHARE_CONFIG));
            } else {
                List<String> allowedAccessLevels = updater == null ? null : getAllowedAgentAccessLevels(updater);
                agent.setShareConfig(JSON.toJSONString(normalizeAgentShareConfig(shareConfig, allowedAccessLevels)));
            }
        }

        agent.setUpdatedBy(updatedBy);
        agent.setUpdatedAt(DateTimeUtils.utcNowNaive());
        agentMapper.updateById(agent);
        return agent;
    }

    /** 删除智能体。 */
    @Transactional
    public void delete(Agent agent) {
        agentMapper.deleteById(agent.getId());
    }

    /**
     * 序列化智能体输出。
     *
     * <p>后端能力清单部分见类注释的"能力差异"：未装配 BackendInfoProvider 时，
     * capabilities/metadata/configurable_items 与参考实现"后端不存在"分支一致（空结构）。
     */
    public Map<String, Object> serialize(
            Agent agent,
            PermissionSubject user,
            boolean includeConfigurableItems,
            Map<String, Map<String, Object>> backendInfoCache) {
        Map<String, Object> data = toDict(agent);
        data.put("share_config", ResourcePermissions.normalizePermissionConfig(RepoValues.parseObject(agent.getShareConfig())));
        ResourcePermission permission = ResourcePermissions.resolveAgentPermission(user, ShareableResource.of(agent));
        boolean isBuiltin = isBuiltinAgent(agent);
        data.put("can_manage", userCanManageAgent(user, agent));
        data.put("effective_permission", permission.value());
        data.put("is_builtin", isBuiltin);
        data.put("permission_locked", isBuiltin);

        Map<String, Object> backendInfo = null;
        // 迟延解析（不得提到构造器里，否则形成装配期单例环；见构造器注释）。
        BackendInfoProvider provider = backendInfoProvider.getIfAvailable();
        if (provider != null) {
            String cacheKey = agent.getBackendId() + "|" + includeConfigurableItems + "|"
                    + user.role() + "|" + user.uid();
            if (backendInfoCache != null) {
                backendInfo = backendInfoCache.get(cacheKey);
            }
            if (backendInfo == null) {
                backendInfo =
                        provider.getInfo(
                                agent.getBackendId(), includeConfigurableItems, user.role(), user.uid());
                if (backendInfoCache != null && backendInfo != null) {
                    backendInfoCache.put(cacheKey, backendInfo);
                }
            }
        }
        if (backendInfo != null) {
            data.put("capabilities", backendInfo.getOrDefault("capabilities", new ArrayList<>()));
            data.put("metadata", backendInfo.getOrDefault("metadata", new LinkedHashMap<>()));
            if (includeConfigurableItems) {
                data.put("configurable_items", backendInfo.getOrDefault("configurable_items", new LinkedHashMap<>()));
            }
        } else {
            data.put("capabilities", new ArrayList<>());
            data.put("metadata", new LinkedHashMap<>());
            if (includeConfigurableItems) {
                data.put("configurable_items", new LinkedHashMap<>());
            }
        }
        return data;
    }

    // ==================== 模块级函数：配置合并 ====================

    /** 合并智能体配置补丁，并在资源字段上保留旧的不可见引用。 */
    public static JSONObject mergeAgentConfigJson(
            JSONObject existing, JSONObject patch, Map<String, Collection<String>> resourceAccess) {
        if (patch == null) {
            throw new IllegalArgumentException("智能体配置必须是对象");
        }
        JSONObject current = existing == null ? new JSONObject() : deepCopy(existing);
        JSONObject patchCopy = deepCopy(patch);
        JSONObject merged = new JSONObject();
        merged.putAll(current);
        merged.putAll(patchCopy);
        if (!patch.containsKey("context")) {
            return merged;
        }

        JSONObject patchContext = patchCopy.getJSONObject("context");
        if (patchContext == null) {
            throw new IllegalArgumentException("智能体 context 配置必须是对象");
        }
        JSONObject currentContext = current.getJSONObject("context");
        if (currentContext == null) {
            currentContext = new JSONObject();
        }
        JSONObject mergedContext = new JSONObject();
        mergedContext.putAll(currentContext);
        mergedContext.putAll(patchContext);

        Set<String> patchContextKeys = new LinkedHashSet<>(patchContext.keySet());
        patchContextKeys.retainAll(AgentContextFields.AGENT_RESOURCE_CONFIG_FIELDS);
        for (String fieldName : patchContextKeys) {
            List<String> requested = normalizeResourceReferences(fieldName, patchContext.get(fieldName));
            if (requested == null || requested.isEmpty()) {
                mergedContext.put(fieldName, requested == null ? null : new JSONArray());
                continue;
            }

            if (resourceAccess == null || !resourceAccess.containsKey(fieldName)) {
                throw new IllegalArgumentException("智能体资源字段 " + fieldName + " 未经过权限校验");
            }
            Set<String> accessible = new LinkedHashSet<>();
            for (String item : resourceAccess.get(fieldName)) {
                accessible.add(String.valueOf(item));
            }
            List<String> previous = normalizeResourceReferences(fieldName, currentContext.get(fieldName));
            if (previous == null) {
                previous = new ArrayList<>();
            }
            Set<String> previousSet = new LinkedHashSet<>(previous);

            List<String> unauthorizedNew = new ArrayList<>();
            for (String item : requested) {
                if (!accessible.contains(item) && !previousSet.contains(item)) {
                    unauthorizedNew.add(item);
                }
            }
            if (!unauthorizedNew.isEmpty()) {
                throw new IllegalArgumentException(
                        "无权新增智能体资源 " + fieldName + ": " + String.join(", ", unauthorizedNew));
            }

            Set<String> requestedSet = new LinkedHashSet<>(requested);
            List<String> keptExisting = new ArrayList<>();
            for (String item : previous) {
                if (!accessible.contains(item) || requestedSet.contains(item)) {
                    keptExisting.add(item);
                }
            }
            List<String> newVisible = new ArrayList<>();
            for (String item : requested) {
                if (accessible.contains(item) && !previousSet.contains(item)) {
                    newVisible.add(item);
                }
            }
            List<String> combined = new ArrayList<>(keptExisting);
            combined.addAll(newVisible);
            mergedContext.put(fieldName, new JSONArray(combined));
        }

        merged.put("context", mergedContext);
        return merged;
    }

    /** 规范化一个资源引用字段，并拒绝非字符串列表。 */
    static List<String> normalizeResourceReferences(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof JSONArray) && !(value instanceof List)) {
            throw new IllegalArgumentException("智能体资源字段 " + fieldName + " 必须是字符串列表或 null");
        }
        List<?> raw = value instanceof JSONArray array ? array : (List<?>) value;
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : raw) {
            if (!(item instanceof String text) || text.trim().isEmpty()) {
                throw new IllegalArgumentException("智能体资源字段 " + fieldName + " 必须是字符串列表或 null");
            }
            String key = text.trim();
            if (seen.add(key)) {
                normalized.add(key);
            }
        }
        return normalized;
    }

    /** Agent.to_dict() 的键与时间格式照搬。 */
    static Map<String, Object> toDict(Agent agent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", agent.getId());
        data.put("slug", agent.getSlug());
        data.put("agent_id", agent.getSlug());
        data.put("backend_id", agent.getBackendId());
        data.put("name", agent.getName());
        data.put("description", agent.getDescription());
        data.put("icon", MinioUrls.normalizePublicMinioUrl(agent.getIcon()));
        List<Object> pics = new ArrayList<>();
        JSONArray rawPics = parseArray(agent.getPics());
        for (Object pic : rawPics) {
            pics.add(MinioUrls.normalizePublicMinioUrl(pic == null ? null : String.valueOf(pic)));
        }
        data.put("pics", pics);
        data.put("config_json", RepoValues.parseObject(agent.getConfigJson()));
        data.put("share_config", RepoValues.parseObject(agent.getShareConfig()));
        data.put("is_default", Boolean.TRUE.equals(agent.getIsDefault()));
        data.put("is_subagent", Boolean.TRUE.equals(agent.getIsSubagent()));
        data.put("created_by", agent.getCreatedBy());
        data.put("updated_by", agent.getUpdatedBy());
        data.put("created_at", DateTimeUtils.formatUtcDatetime(agent.getCreatedAt()));
        data.put("updated_at", DateTimeUtils.formatUtcDatetime(agent.getUpdatedAt()));
        return data;
    }

    private static JSONArray parseArray(String json) {
        if (json == null || json.isBlank()) {
            return new JSONArray();
        }
        try {
            JSONArray array = JSON.parseArray(json);
            return array == null ? new JSONArray() : array;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static JSONObject contextWithSystemPrompt(String prompt) {
        JSONObject context = new JSONObject();
        context.put("system_prompt", prompt);
        return context;
    }

    private static JSONObject deepCopy(JSONObject source) {
        return JSON.parseObject(JSON.toJSONString(source));
    }

    private void persistAgent(Agent agent) {
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<Agent>()
                        .eq(Agent::getId, agent.getId())
                        .set(Agent::getShareConfig, agent.getShareConfig())
                        .set(Agent::getDescription, agent.getDescription())
                        .set(Agent::getIsSubagent, agent.getIsSubagent())
                        .set(Agent::getUpdatedBy, agent.getUpdatedBy())
                        .set(Agent::getUpdatedAt, agent.getUpdatedAt()));
    }
}
