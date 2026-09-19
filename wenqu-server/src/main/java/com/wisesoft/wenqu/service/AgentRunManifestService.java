package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.AgentContextService;
import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.SkillRuntime;
import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.CanonicalJson;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * AgentRun 运行清单与执行指纹。
 *
 * <p>由参考实现的 {@code services/agent_run_manifest_service.py} 逐函数翻译。在 worker 取得
 * 执行所有权后准备唯一的执行 Context，从其实际配置生成只含稳定标识与非敏感摘要的 manifest，
 * 并以规范化 JSON 的 SHA-256 作为指纹。manifest 由 AgentRun 行拥有，write-once 固化后不得改写；
 * 历史 Run 保持 NULL 表示 unknown。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code AgentRepository(db).get_visible_by_slug(...)} → 注入的 {@link AgentRepository}
 *       （本工程仓储即 Spring Bean，无会话传参）。</li>
 *   <li>{@code backend.context_schema()}（对类对象加括号 = 实例化）→
 *       {@link BaseAgent#resolveContextSchema()} 取类 + {@code getDeclaredConstructor().newInstance()}
 *       实例化（拆成两步，语义等价）。</li>
 *   <li>{@code canonical_json} 的 {@code json.dumps(sort_keys=True, ensure_ascii=True,
 *       separators=(",", ":"), default=str)} → {@link CanonicalJson#dumpsAscii}（浮点格式的
 *       残差见该类注释）。</li>
 *   <li>{@code hashlib.sha256(...).hexdigest()} → {@link AuthUtils#sha256Hex(String)}。</li>
 *   <li>{@code dataclass(frozen=True) PreparedRunExecution} → {@link PreparedRunExecution} record。</li>
 * </ul>
 *
 * <h3>品牌替换（必要替换，已标注）</h3>
 * <ul>
 *   <li>{@code os.getenv("YUXI_CODE_REVISION")} → 环境变量 {@code WENQU_CODE_REVISION}
 *       （部署侧变量名随产品改名；部署时须用新名注入，否则 revision 恒为 {@code unresolved}）。</li>
 * </ul>
 */
@Service
public class AgentRunManifestService {

    /** 运行清单 schema 版本（参考实现 MANIFEST_SCHEMA_VERSION）。 */
    public static final int MANIFEST_SCHEMA_VERSION = 2;

    /**
     * 直接进入 manifest 的关键 limit 字段；未列出的 context 字段只以 config_digest 形式存在
     * （参考实现 MANIFEST_LIMIT_FIELDS，条目与顺序逐字保留）。
     */
    public static final List<String> MANIFEST_LIMIT_FIELDS = List.of(
            "max_execution_steps",
            "model_retry_times",
            "summary_threshold",
            "summary_keep_messages",
            "summary_tool_result_token_limit");

    /** 同一次准备产生的执行 Context 与持久化清单（对应 {@code PreparedRunExecution}）。 */
    public record PreparedRunExecution(Map<String, Object> manifest, BaseContext context, String backendId) {}

    private final AgentRepository agentRepository;
    private final AgentManager agentManager;
    private final AgentContextService agentContextService;

    public AgentRunManifestService(
            AgentRepository agentRepository,
            AgentManager agentManager,
            AgentContextService agentContextService) {
        this.agentRepository = agentRepository;
        this.agentManager = agentManager;
        this.agentContextService = agentContextService;
    }

    // ==================== 规范化序列化与指纹 ====================

    /** 键排序 + 紧凑分隔符的确定性序列化，保证字段顺序不影响指纹（对应 {@code canonical_json}）。 */
    public static String canonicalJson(Object payload) {
        return CanonicalJson.dumpsAscii(payload);
    }

    /** 计算运行清单的 SHA-256 指纹（对应 {@code compute_manifest_fingerprint}）。 */
    public static String computeManifestFingerprint(Map<String, Object> manifest) {
        return AuthUtils.sha256Hex(canonicalJson(manifest));
    }

    /** 对完整规范化 context 计算 SHA-256 摘要（对应 {@code compute_config_digest}）。 */
    public static String computeConfigDigest(Map<String, Object> normalizedContext) {
        Map<String, Object> payload = normalizedContext == null ? new LinkedHashMap<>() : normalizedContext;
        return AuthUtils.sha256Hex(canonicalJson(payload));
    }

    /** 提取资源字段中的字符串键；非列表或非字符串项忽略（对应 {@code _resource_keys}）。 */
    public static List<String> resourceKeys(Object value) {
        List<String> keys = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return keys;
        }
        for (Object item : list) {
            if (item instanceof String text) {
                keys.add(text);
            }
        }
        return keys;
    }

    // ==================== manifest 组装 ====================

    /**
     * 从已解析的执行资产组装 manifest（对应 {@code build_manifest_payload}）；直接字段仅限稳定
     * 标识、摘要与关键 limit。
     *
     * <p>limits 由调用方传入实际生效值（含 schema 默认值），不在此处解析。
     */
    public static Map<String, Object> buildManifestPayload(
            String runType,
            String agentSlug,
            String backendId,
            String modelSpec,
            String toolApprovalMode,
            Map<String, Object> normalizedContext,
            List<Map<String, Object>> skillEntries,
            String codeRevision,
            Map<String, Object> limits) {
        Map<String, Object> context = normalizedContext == null ? new LinkedHashMap<>() : normalizedContext;

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("slug", agentSlug);
        agent.put("backend_id", backendId);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("spec", modelSpec != null && !modelSpec.isEmpty() ? modelSpec : null);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("tools", resourceKeys(context.get("tools")));
        resources.put("mcps", resourceKeys(context.get("mcps")));
        resources.put("skills", skillEntries);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifest_version", MANIFEST_SCHEMA_VERSION);
        manifest.put("run_type", runType);
        manifest.put("agent", agent);
        manifest.put("model", model);
        manifest.put("tool_approval_mode", toolApprovalMode);
        manifest.put("resources", resources);
        manifest.put("limits", limits);
        manifest.put("config_digest", computeConfigDigest(context));
        manifest.put("code_revision", codeRevision == null || codeRevision.isEmpty() ? "unresolved" : codeRevision);
        return manifest;
    }

    /**
     * 只从首次授权解析结果投影 Skill 身份与实际预加载内容摘要
     * （对应 {@code build_skill_manifest_entries}）。
     */
    public static List<Map<String, Object>> buildSkillManifestEntries(
            Map<String, Object> config, Map<String, Object> skillScope) {
        Map<String, Object> scope = skillScope == null ? new LinkedHashMap<>() : skillScope;
        // slugs = list(dict.fromkeys([*_resource_keys(config["skills"]), *skill_scope["preloaded_skills"]]))
        Set<String> ordered = new LinkedHashSet<>(resourceKeys(config == null ? null : config.get("skills")));
        ordered.addAll(stringList(scope.get("preloaded_skills")));

        Map<String, Object> metadataBySlug = asMap(scope.get("skill_metadata"));
        Map<String, Object> contents = asMap(scope.get("preloaded_skill_contents"));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String slug : ordered) {
            Map<String, Object> metadata = asMap(metadataBySlug.get(slug));
            boolean personal = SkillService.PERSONAL_SKILL_SOURCE_TYPE.equals(
                    metadata.get("source_scope") == null ? null : String.valueOf(metadata.get("source_scope")));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slug", slug);
            entry.put("version", personal ? null : metadata.get("version"));
            entry.put("content_hash", personal ? null : metadata.get("content_hash"));
            if (contents.containsKey(slug)) {
                String content = contents.get(slug) == null ? "" : String.valueOf(contents.get(slug));
                entry.put("preload_content_hash", AuthUtils.sha256Hex(content));
            }
            entries.add(entry);
        }
        return entries;
    }

    /** 读取部署环境提供的代码 revision（对应 {@code resolve_code_revision}；品牌变量名已替换）。 */
    public static String resolveCodeRevision() {
        String revision = System.getenv("WENQU_CODE_REVISION");
        if (revision == null) {
            return null;
        }
        revision = revision.strip();
        return revision.isEmpty() ? null : revision;
    }

    // ==================== 准备执行 ====================

    /** 准备唯一执行 Context，并从实际配置派生持久化摘要（对应 {@code prepare_run_execution}）。 */
    public PreparedRunExecution prepareRunExecution(
            AgentRun run, User user, WorkdirService.AuthorizedWorkdir workdirBinding, String workerId) {
        Agent agentItem = agentRepository.getVisibleBySlug(
                run.getAgentSlug(),
                PermissionSubject.of(user),
                "subagent".equals(run.getRunType())
                        ? AgentRepository.AgentEntryKind.SUBAGENT
                        : AgentRepository.AgentEntryKind.MAIN);
        if (agentItem == null) {
            throw new IllegalArgumentException("智能体不存在或无权限访问");
        }
        BaseAgent backend = agentManager.getAgent(agentItem.getBackendId());
        if (backend == null) {
            throw new IllegalArgumentException("智能体后端 " + agentItem.getBackendId() + " 不存在");
        }

        BaseContext context = instantiateContext(backend);
        Map<String, Object> agentConfig = AgentRunService.parseJsonObject(agentItem.getConfigJson());
        Object configuredRaw = agentConfig == null ? null : agentConfig.get("context");
        Map<String, Object> configured = configuredRaw instanceof Map<?, ?> map ? stringKeyed(map) : new LinkedHashMap<>();
        Set<String> configurableFields = new LinkedHashSet<>();
        for (BaseContext.FieldDef item : BaseContext.fields()) {
            if (item.configurable()) {
                configurableFields.add(item.name());
            }
        }
        context.updateConfig(configured);

        Map<String, Object> payload = AgentRunService.parseJsonObject(run.getInputPayload());
        Map<String, Object> runtimeFields = new LinkedHashMap<>();
        runtimeFields.put("thread_id", run.getConversationThreadId());
        runtimeFields.put("uid", String.valueOf(user.getUid()));
        runtimeFields.put("run_id", run.getId());
        runtimeFields.put("request_id", run.getRequestId());
        runtimeFields.put("worker_id", workerId);
        runtimeFields.put(
                "runtime_scope_id",
                run.getRuntimeScopeId() == null ? run.getConversationThreadId() : run.getRuntimeScopeId());
        runtimeFields.put("workdir_relative_path", workdirBinding.workdirPath());
        runtimeFields.put("workdir_path", BackendPaths.runtimeWorkdirPath(workdirBinding.workdirPath()));
        context.update(runtimeFields);

        if (AgentRunService.truthy(payload == null ? null : payload.get("model_spec"))) {
            context.set("model", payload.get("model_spec"));
        }
        if (AgentRunService.truthy(payload == null ? null : payload.get("tool_approval_mode"))) {
            context.set("tool_approval_mode", payload.get("tool_approval_mode"));
        }
        if ("subagent".equals(run.getRunType())) {
            Object runtimeRaw = payload == null ? null : payload.get("runtime");
            Object parentThreadRaw = runtimeRaw instanceof Map<?, ?> map ? map.get("parent_thread_id") : null;
            String parentThreadId = parentThreadRaw == null ? "" : String.valueOf(parentThreadRaw).strip();
            if (parentThreadId.isEmpty()) {
                throw new IllegalArgumentException("子智能体运行缺少必需的 parent_thread_id");
            }
            Map<String, Object> subagentFields = new LinkedHashMap<>();
            subagentFields.put("parent_thread_id", parentThreadId);
            subagentFields.put("is_subagent_runtime", true);
            context.update(subagentFields);
        }

        context = agentContextService.prepareAgentRuntimeContext(context);
        if (!AgentRunService.truthy(context.getDynamic(AgentContextService.RUNTIME_PREPARED_ATTR, Boolean.FALSE))) {
            throw new IllegalArgumentException("执行用户不存在，无法准备 Context");
        }

        // 身份、租约与路径不属于可配置字段；完整 prompt 仅通过摘要进入 manifest。
        Map<String, Object> effectiveConfig = new LinkedHashMap<>();
        for (String name : configurableFields) {
            effectiveConfig.put(name, context.get(name));
        }

        Map<String, Object> limits = new LinkedHashMap<>();
        for (String name : MANIFEST_LIMIT_FIELDS) {
            limits.put(name, context.get(name));
        }

        Map<String, Object> manifest = buildManifestPayload(
                run.getRunType(),
                run.getAgentSlug(),
                agentItem.getBackendId(),
                context.getString("model"),
                context.getString("tool_approval_mode"),
                effectiveConfig,
                buildSkillManifestEntries(
                        effectiveConfig,
                        asMap(context.getDynamic(SkillRuntime.SKILL_RUNTIME_SNAPSHOT_ATTR, null))),
                resolveCodeRevision(),
                limits);
        return new PreparedRunExecution(manifest, context, agentItem.getBackendId());
    }

    // ==================== 内部小工具 ====================

    /** {@code backend.context_schema()} 的 Java 等价（取类 + 实例化两步）。 */
    private static BaseContext instantiateContext(BaseAgent backend) {
        Class<? extends BaseContext> schema = backend.resolveContextSchema();
        try {
            return schema.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Context schema 实例化失败: " + schema.getName(), error);
        }
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> raw ? stringKeyed(raw) : new LinkedHashMap<>();
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
