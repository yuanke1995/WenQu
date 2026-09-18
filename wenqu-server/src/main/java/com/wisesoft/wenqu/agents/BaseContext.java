package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * 智能体运行时上下文（各类 graph 的基础 Context）。
 *
 * <p>由参考实现的 agents/context.py 逐字段翻译：BaseContext dataclass（字段元数据
 * name/configurable/hide/description/kind/options/type/auth 逐字照搬）、配置过滤
 * （filter_declared_config / filter_config_by_role）、可配置项投影
 * （get_configurable_items）、工作区基础说明追加、资源键归一化纯函数。
 *
 * <p>必要替换（能力差异，已标注）：
 * <ul>
 *   <li>dataclass 字段元数据 → {@link FieldDef} 声明表（键序与默认值照搬）；
 *       {@code default_factory=lambda: str(uuid4())} → 每实例生成一次 UUID。
 *   <li>常量名按本产品命名：摘要提示词常量为 {@code DEFAULT_SUMMARY_PROMPT}
 *       （提示词正文逐字保留）。
 *   <li>模块级函数 {@code resolve_agent_resource_options} / {@code normalize_agent_context_config} /
 *       {@code prepare_agent_runtime_context} 依赖 toolkits.service、knowledge.runtime、
 *       agents.mcp.service、agents.skills.service/runtime、backends.knowledge_base_backend
 *       （均未照搬），随上述模块一并移植；本类先承载字段声明、过滤与工作区说明部分。
 *   <li>{@code asyncio.to_thread} → 调用方线程直接执行。
 * </ul>
 */
public class BaseContext {

    public static final int WORKSPACE_AGENTS_PROMPT_MAX_BYTES = 64 * 1024;
    public static final List<String> WORKSPACE_BASE_CONTEXT_FILES = List.of("AGENTS.md", "USER.md");
    public static final int DEFAULT_SUMMARY_THRESHOLD_K = 100;
    public static final int DEFAULT_SUMMARY_KEEP_MESSAGES = 10;
    public static final int DEFAULT_SUMMARY_TOOL_RESULT_TOKEN_LIMIT = 300;
    public static final int DEFAULT_MAX_EXECUTION_STEPS = 300;
    public static final int DEFAULT_TOOL_RESULT_EVICTION_K_TOKENS = 3;

    public static final String DEFAULT_SUMMARY_PROMPT = """
            你是对话上下文压缩助手。
            你的任务是把下面的对话历史压缩成后续智能体继续工作所需的高价值上下文。

            请特别保留并清晰记录：

            ## SESSION INTENT
            用户当前的主要目标、任务范围和最终交付物。

            ## USER REQUIREMENTS AND PREFERENCES
            用户明确提出的要求、偏好、禁忌、输出格式、语言风格、技术约束、验收标准，以及对实现方式的取舍意见。只记录仍然可能影响后续回答或执行的内容。

            ## PROGRESS AND DECISIONS
            已经完成的步骤、关键结论、已确认的方案、被否定的方案及原因。

            ## ARTIFACTS AND REFERENCES
            已经创建、修改、读取或需要继续关注的文件、路径、工具输出路径、线程或运行标识。保留具体路径和关键标识符。

            ## NEXT STEPS
            为了完成用户目标，后续最应该继续做的具体步骤。没有待办时写 None。

            要求：
            - 不要逐字复述冗长工具输出；保留结论、路径和必要证据。
            - 不要编造没有出现在对话中的事实。
            - 如果存在未解决的问题或风险，明确记录。
            - 使用与用户主要对话一致的语言。

            <messages>
            {messages}
            </messages>

            只输出压缩后的上下文，不要添加额外说明。""";

    /** 判断角色能否修改字段；auth 不限制读取与运行。 */
    public static boolean roleCanModify(String auth, String role) {
        if (auth == null || auth.isEmpty()) {
            return true;
        }
        if ("admin".equals(auth)) {
            return "admin".equals(role) || "superadmin".equals(role);
        }
        if ("superadmin".equals(auth)) {
            return "superadmin".equals(role);
        }
        return false;
    }

    // ==================== 字段声明表（对应 dataclass 字段与 metadata） ====================

    /** 单个声明字段：名称、默认值、元数据（与参考实现 dataclass field 对齐）。 */
    public record FieldDef(String name, Object defaultValue, Map<String, Object> metadata) {

        public boolean configurable() {
            Object value = metadata.get("configurable");
            return value == null || Boolean.TRUE.equals(value);
        }

        public boolean hidden() {
            return Boolean.TRUE.equals(metadata.get("hide"));
        }

        public String auth() {
            Object value = metadata.get("auth");
            return value == null ? null : String.valueOf(value);
        }
    }

    /** 字段默认值工厂（thread_id/uid 需要每实例新生成 UUID）。 */
    private interface DefaultFactory {
        Object create();
    }

    private record Def(String name, String pyTypeName, DefaultFactory factory, Map<String, Object> metadata) {}

    private static final List<Def> FIELD_DEFS = new ArrayList<>();

    private static void def(String name, String pyTypeName, DefaultFactory factory, Map<String, Object> metadata) {
        FIELD_DEFS.add(new Def(name, pyTypeName, factory, metadata));
    }

    private static Map<String, Object> meta(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static {
        def("thread_id", "str", () -> UUID.randomUUID().toString(), meta(
                "name", "线程ID", "configurable", false, "description", "用来唯一标识一个对话线程"));
        def("uid", "str", () -> UUID.randomUUID().toString(), meta(
                "name", "UID", "configurable", false, "description", "用来唯一标识一个用户"));
        def("run_id", "str | None", () -> null, meta("name", "运行 ID", "configurable", false, "hide", true));
        def("request_id", "str | None", () -> null, meta("name", "请求 ID", "configurable", false, "hide", true));
        def("worker_id", "str | None", () -> null, meta("name", "Worker Attempt Owner", "configurable", false, "hide", true));
        def("runtime_scope_id", "str | None", () -> null, meta("name", "Sandbox Runtime Scope", "configurable", false, "hide", true));
        def("workdir_relative_path", "str | None", () -> null, meta("name", "Workdir Relative Path", "configurable", false, "hide", true));
        def("workdir_path", "str | None", () -> null, meta("name", "Workdir Virtual Path", "configurable", false, "hide", true));
        def("system_prompt", "str", () -> "You are a helpful assistant.", meta(
                "name", "系统提示词", "description", "用来描述智能体的角色和行为", "kind", "prompt"));
        def("model", "str", () -> "", meta(
                "name", "智能体模型", "options", List.of(),
                "description", "智能体的驱动模型，留空时使用系统默认模型。", "kind", "llm"));
        def("tool_approval_mode", "str", () -> ToolApproval.DEFAULT_TOOL_APPROVAL_MODE, meta(
                "name", "工具审批模式",
                "description", "默认审批会在写文件、编辑文件或执行命令前询问；完全信任会自动执行这些工具。",
                "options", List.of(
                        Map.of("key", "default", "name", "默认审批", "description", "敏感工具执行前请求确认"),
                        Map.of("key", "always_trust", "name", "完全信任", "description", "敏感工具无需确认，自动执行")),
                "type", "string", "auth", "admin"));
        def("tools", "list[str] | None", () -> null, meta(
                "name", "工具", "description", "内置的工具。默认选择当前用户可用的全部工具。", "type", "list", "kind", "tools"));
        def("knowledges", "list[str] | None", () -> null, meta(
                "name", "知识库",
                "description", "知识库列表，可以在左侧知识库页面中创建知识库。默认选择当前用户可访问的全部知识库。",
                "type", "list", "kind", "knowledges"));
        def("mcps", "list[str] | None", () -> null, meta(
                "name", "MCP服务器", "options", List.of(),
                "description", "MCP服务器列表，默认选择当前用户可用的全部 MCP 服务器。建议使用支持 SSE 的 MCP 服务器，"
                        + "如果需要使用 uvx 或 npx 运行的服务器，也请在项目外部启动 MCP 服务器，并在项目中配置 MCP 服务器。",
                "type", "list", "kind", "mcps"));
        def("skills", "list[str] | None", () -> null, meta(
                "name", "Skills", "options", List.of(),
                "description", "可选 Skill 拓展列表，默认选择当前用户可用的全部 Skill 拓展。"
                        + "Skill 拓展依赖的工具和 MCP 服务器也会被自动挂载。",
                "type", "list", "kind", "skills"));
        def("preload_skills", "list[str]", () -> new ArrayList<String>(), meta(
                "name", "预加载 Skills", "options", List.of(),
                "description", "创建 Agent Graph 时加载完整 Skill 说明，并从首轮开放其依赖工具。默认不预加载。",
                "type", "list", "kind", "skills"));
        def("summary_threshold", "int", () -> DEFAULT_SUMMARY_THRESHOLD_K, meta(
                "name", "上下文摘要触发阈值 (K)",
                "description", "当上下文大小超过该值时，启用摘要功能以优化上下文使用。单位为 K，默认值为 "
                        + DEFAULT_SUMMARY_THRESHOLD_K + "K。",
                "type", "number", "auth", "admin"));
        def("summary_keep_messages", "int", () -> DEFAULT_SUMMARY_KEEP_MESSAGES, meta(
                "name", "摘要后保留消息数",
                "description", "上下文摘要触发后，除摘要消息外保留最近的消息数量，默认 "
                        + DEFAULT_SUMMARY_KEEP_MESSAGES + " 条。",
                "type", "number", "auth", "admin"));
        def("summary_prompt", "str", () -> DEFAULT_SUMMARY_PROMPT, meta(
                "name", "上下文摘要提示词",
                "description", "触发上下文摘要时使用的提示词，必须能接收 {messages} 作为待摘要消息占位符。",
                "type", "string", "kind", "prompt", "auth", "admin"));
        def("summary_tool_result_token_limit", "int", () -> DEFAULT_SUMMARY_TOOL_RESULT_TOKEN_LIMIT, meta(
                "name", "摘要工具结果 token 上限",
                "description", "确定性压缩历史工具结果时，超过该 token 数的 ToolMessage 会写入 outputs，"
                        + "并在上下文中保留不超过该 token 数的预览；未超过则保持原样。默认 "
                        + DEFAULT_SUMMARY_TOOL_RESULT_TOKEN_LIMIT + "。",
                "type", "number", "auth", "admin"));
        def("max_execution_steps", "int", () -> DEFAULT_MAX_EXECUTION_STEPS, meta(
                "name", "最大执行步数",
                "description", "单次 Agent 运行允许的最大 LangGraph 执行步数，对应 recursion_limit，默认 "
                        + DEFAULT_MAX_EXECUTION_STEPS + "。",
                "type", "number", "auth", "admin"));
        def("model_retry_times", "int", () -> 2, meta(
                "name", "模型重试次数",
                "description", "模型调用失败时的最大重试次数，默认值为 2。",
                "type", "number", "auth", "admin"));
    }

    /** 声明字段定义（对应 dataclasses.fields(BaseContext)，键序即声明序）。 */
    public static List<FieldDef> fields() {
        List<FieldDef> result = new ArrayList<>();
        for (Def def : FIELD_DEFS) {
            result.add(new FieldDef(def.name(), def.factory().create(), def.metadata()));
        }
        return result;
    }

    /** 声明字段名集合。 */
    public static Set<String> fieldNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Def def : FIELD_DEFS) {
            names.add(def.name());
        }
        return names;
    }

    // ==================== 运行时字段值 ====================

    private final Map<String, Object> values = new LinkedHashMap<>();

    public BaseContext() {
        for (Def def : FIELD_DEFS) {
            values.put(def.name(), def.factory().create());
        }
    }

    /** 取字段值（未声明字段返回 null，对应 getattr 容错语义由调用方保证）。 */
    public Object get(String field) {
        return values.get(field);
    }

    public String getString(String field) {
        Object value = values.get(field);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String field) {
        Object value = values.get(field);
        return value == null ? null : (List<String>) value;
    }

    public void set(String field, Object value) {
        if (values.containsKey(field)) {
            values.put(field, value);
        }
    }

    /** 用运行时输入更新已声明的配置字段。 */
    public void update(Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (values.containsKey(entry.getKey())) {
                values.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 仅装载允许用户配置的声明字段，运行身份由执行入口注入。 */
    public void updateConfig(Map<String, Object> data) {
        Map<String, Object> filtered = filterDeclaredConfig(
                mapOf("context", data == null ? new LinkedHashMap<>() : data));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) filtered.get("context");
        update(context == null ? new LinkedHashMap<>() : context);
    }

    // ==================== 配置过滤 ====================

    /** 读取持久配置时仅保留 Schema 可配置字段，不按角色修改权限裁剪。 */
    public static Map<String, Object> filterDeclaredConfig(Map<String, Object> configJson) {
        // 参考实现 declared_fields 只含 metadata.configurable != False 的字段
        Set<String> configurable = new LinkedHashSet<>();
        for (FieldDef field : fields()) {
            if (field.configurable()) {
                configurable.add(field.name());
            }
        }
        return filterDeclaredConfig(configJson, configurable);
    }

    /** schema 参数化版本（context_schema 继承自 BaseContext 时传入其字段名集）。 */
    public static Map<String, Object> filterDeclaredConfig(Map<String, Object> configJson, Set<String> declared) {
        if (configJson == null || !(configJson instanceof Map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> filtered = new LinkedHashMap<>(configJson);
        Object context = filtered.get("context");
        if (context instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) context).entrySet()) {
                if (declared.contains(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            filtered.put("context", result);
        }
        return filtered;
    }

    /** 仅用于写入：按 Context 字段 metadata.auth 过滤可修改配置。 */
    public static Map<String, Object> filterConfigByRole(Map<String, Object> configJson, String role) {
        Map<String, Object> filtered = filterDeclaredConfig(configJson);
        Set<String> restrictedFields = new LinkedHashSet<>();
        for (FieldDef field : fields()) {
            if (!roleCanModify(field.auth(), role)) {
                restrictedFields.add(field.name());
            }
        }
        Object context = filtered.get("context");
        if (context instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) context).entrySet()) {
                if (!restrictedFields.contains(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            filtered.put("context", result);
        }
        return filtered;
    }

    // ==================== 可配置项投影 ====================

    /** 实现一个可配置的参数列表，在 UI 上配置时使用。 */
    public static Map<String, Map<String, Object>> getConfigurableItems(String userRole) {
        Map<String, Map<String, Object>> configurableItems = new LinkedHashMap<>();
        for (Def def : FIELD_DEFS) {
            if (userRole != null && !roleCanModify(
                    def.metadata().get("auth") == null ? null : String.valueOf(def.metadata().get("auth")), userRole)) {
                continue;
            }
            Object configurable = def.metadata().get("configurable");
            if (configurable != null && !Boolean.TRUE.equals(configurable)) {
                continue;
            }
            if (Boolean.TRUE.equals(def.metadata().get("hide"))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", def.metadata().containsKey("type")
                    ? def.metadata().get("type")
                    : typeNameOf(def));
            item.put("name", def.metadata().getOrDefault("name", def.name()));
            Object options = def.metadata().get("options");
            if (options == null) {
                options = List.of();
            }
            item.put("options", options);
            item.put("default", def.factory().create());
            item.put("description", def.metadata().getOrDefault("description", ""));
            item.put("kind", def.metadata().getOrDefault("kind", ""));
            configurableItems.put(def.name(), item);
        }
        return configurableItems;
    }

    /**
     * 对应参考实现 _get_type_name(f.type)：取注解的 origin 简名
     * （list[str] | None → list、str → str、int → int）。
     */
    private static String typeNameOf(Def def) {
        String annotation = def.pyTypeName();
        if (annotation.startsWith("list")) {
            return "list";
        }
        return annotation;
    }

    // ==================== 工作区基础说明 ====================

    /** 读取用户工作区 AGENTS.md/USER.md 的有界内容拼装说明。 */
    public static String loadWorkspaceAgentContext(String uid) {
        List<String> sections = new ArrayList<>();
        Workspace filesystem = new Workspace(uid);
        for (String filename : WORKSPACE_BASE_CONTEXT_FILES) {
            Workspace.FilePrefix prefix;
            try {
                prefix = filesystem.readAuthorizedFilePrefix(
                        "/agents/" + filename, WORKSPACE_AGENTS_PROMPT_MAX_BYTES);
            } catch (Workspace.NoSuchFileRuntime exc) {
                continue;
            } catch (RuntimeException exc) {
                // 对应参考实现 IsADirectoryError/OSError 的告警分支
                org.slf4j.LoggerFactory.getLogger(BaseContext.class)
                        .warn("读取工作区 {} 失败: {}", filename, exc.getMessage());
                continue;
            }

            String prompt = new String(prefix.content(), 0,
                    Math.min(prefix.content().length, WORKSPACE_AGENTS_PROMPT_MAX_BYTES),
                    StandardCharsets.UTF_8).strip();
            if (prompt.isEmpty()) {
                continue;
            }
            if (prefix.truncated()) {
                prompt = prompt + "\n\n[" + filename + " 内容已截断]";
            }
            sections.add("用户工作区 agents/" + filename + " 内容：\n" + prompt);
        }
        return String.join("\n\n", sections);
    }

    /** 在实际生效的系统提示词后追加工作区基础说明。 */
    public void appendWorkspaceAgentPrompt() {
        String workspacePrompt = loadWorkspaceAgentContext(getString("uid"));
        if (workspacePrompt != null && !workspacePrompt.isEmpty()) {
            String basePrompt = getString("system_prompt") == null ? "" : getString("system_prompt").stripTrailing();
            set("system_prompt", basePrompt.isEmpty() ? workspacePrompt : basePrompt + "\n\n" + workspacePrompt);
        }
    }

    // ==================== 资源键归一化纯函数 ====================

    public static List<String> normalizeSelectedResourceKeys(Object value, List<String> available) {
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        Set<String> allowed = new LinkedHashSet<>(available);
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                continue;
            }
            String key = ((String) item).strip();
            if (key.isEmpty() || seen.contains(key) || !allowed.contains(key)) {
                continue;
            }
            seen.add(key);
            normalized.add(key);
        }
        return normalized;
    }

    public static Set<String> resourceFieldsRequiringAvailableKeys(Map<String, Object> normalized, Set<String> resourceFields) {
        Set<String> fieldsToLoad = new LinkedHashSet<>();
        for (String fieldName : resourceFields) {
            Object current = normalized.get(fieldName);
            if (current == null) {
                if (AgentContextFields.AGENT_RUNTIME_RESOURCE_FIELDS.contains(fieldName)) {
                    fieldsToLoad.add(fieldName);
                } else {
                    normalized.put(fieldName, new ArrayList<>());
                }
            } else if (AgentContextFields.EMPTY_ALL_CONTEXT_FIELDS.contains(fieldName)
                    && (current instanceof List && ((List<?>) current).isEmpty())) {
                normalized.put(fieldName, null);
                fieldsToLoad.add(fieldName);
            } else if (current instanceof List && !((List<?>) current).isEmpty()) {
                fieldsToLoad.add(fieldName);
            } else {
                normalized.put(fieldName, new ArrayList<>());
            }
        }
        return fieldsToLoad;
    }

    public static Map<String, String> resourceOption(Object key, Object name, Object description) {
        String keyValue = String.valueOf(key);
        Map<String, String> option = new LinkedHashMap<>();
        option.put("key", keyValue);
        option.put("name", name == null ? keyValue : String.valueOf(name));
        option.put("description", description == null ? "" : String.valueOf(description));
        return option;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

}
