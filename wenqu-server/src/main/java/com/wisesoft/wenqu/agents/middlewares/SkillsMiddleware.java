package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.McpService;
import com.wisesoft.wenqu.agents.McpTool;
import com.wisesoft.wenqu.agents.SkillRuntime;
import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.agents.ToolkitsRegistry;
import com.wisesoft.wenqu.common.PosixPathLite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * Skills 中间件（对应参考实现 {@code agents/middlewares/skills.py}）。
 *
 * <p>处理 skills 提示词注入、依赖展开、动态激活：
 * <ul>
 *   <li>lazy skills 摘要段 + preloaded skills 完整说明注入
 *       （{@link #buildSkillsSection} / {@link #buildPreloadedSkillsSection}）。</li>
 *   <li>依赖展开（预加载配置 + 动态激活）→ 由 {@link SkillRuntime#buildDependencyBundle} 完成。</li>
 *   <li>本地/MCP 依赖工具的模型可见性门控：未激活 Skill 的依赖工具对模型不可见
 *       （工具已注册进 ToolNode，剔除只影响模型可见性、不影响可执行性）。</li>
 *   <li>{@code read_file} 命中 {@code SKILL.md} 时动态激活（工具侧拦截器
 *       {@link SkillActivationInterceptor}）。</li>
 * </ul>
 *
 * <h3>承载点</h3>
 * <ul>
 *   <li>{@code awrap_model_call}（提示词注入 + 门控 + 依赖展开）→ {@link #interceptModel}。</li>
 *   <li>{@code awrap_tool_call} / {@code wrap_tool_call}（read_file 触发 skill 动态激活）→
 *       {@link SkillActivationInterceptor#interceptToolCall}（同一个中间件在参考实现里同时
 *       提供两个装饰器；Java 侧 {@link ModelInterceptor} 与 {@link ToolInterceptor} 是两个
 *       抽象基类，故拆成主类 + 内部类，由构图方同时注册）。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code deepagents.middleware.skills.SKILLS_SYSTEM_PROMPT} → 本类内联常量
 *       {@link #SKILLS_SYSTEM_PROMPT}（deepagents 不在本工程依赖，按参考实现
 *       {@code _build_skills_section} 的 {@code .format(skills_locations=..., skills_load_warnings=...,
 *       skills_list=...)} 三个占位符的结构重建；占位符名与顺序逐字一致）。</li>
 *   <li>{@code deepagents.middleware._utils.append_to_system_message} → {@link #appendToSystemMessage}
 *       （空系统消息时返回追加内容本身，非空时 {@code "\n\n"} 拼接）。</li>
 *   <li>{@code request.runtime.context} → {@link ModelRequest} 无 runtime 字段，改从
 *       {@link ModelRequest#getContext()} 取 {@link BaseContext}
 *       （键 {@link ContextAwareInterceptor#CONTEXT_KEY}）；{@code _skill_runtime_snapshot} →
 *       {@link BaseContext#getDynamic} 读（键 {@link SkillRuntime#SKILL_RUNTIME_SNAPSHOT_ATTR}）。</li>
 *   <li>{@code request.tools}（参考为工具对象列表）→ 本工程 {@link ModelRequest#getTools()}
 *       是<b>工具名列表</b>（{@code AgentLlmNode} 按名过滤）。</li>
 *   <li>{@code request.state["activated_skills"]} → {@link ModelRequest} 无 state 字段，
 *       改读 {@link BaseContext#getDynamic} 的 {@code activated_skills}（由构图方在图状态与
 *       上下文之间搬运，键名与参考实现的 state 键逐字一致）。</li>
 *   <li>{@code get_enabled_mcp_tools} → {@link McpService#getEnabledMcpTools(String)}；
 *       {@code get_all_tool_instances} → {@link ToolkitsRegistry#getAllToolInstances()}。</li>
 *   <li>{@code pathlib.PurePosixPath} → {@link PosixPathLite}。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>Command 状态写回</b>：参考实现 {@code _merge_activated_skill_update} 返回
 *       {@code Command(update={"activated_skills": [...]})} 直接写回 LangGraph state；本工程
 *       {@link ToolCallResponse} 无此能力，激活结果改写入响应的 metadata
 *       （键 {@link #ACTIVATED_SKILLS_META}，值取 {@code _activated_skills_reducer} 的合并结果），
 *       由构图方（ToolNode / 图节点）落回 state。语义与参考实现一致，落状态的责任上移一层。</li>
 *   <li><b>MCP 工具并行加载</b>：参考实现用 {@code asyncio.gather} 并发拉取多个 MCP 服务器工具；
 *       本工程按 {@code list(dict.fromkeys(...))} 的<b>同一去重与顺序</b>逐个拉取（顺序等价，
 *       并发度差异不影响结果集合与顺序）。</li>
 *   <li><b>{@code _bind_active_mcp_tool} 无对位</b>：参考实现在工具未注册到 ToolNode 时
 *       用 {@code request.override(tool=tool)} 现场绑定；本工程工具由 {@code ToolCallback}
 *       注册表按名解析，拦截器无法注入实例 —— 本轮模型轮次开放的 MCP 依赖工具名仍按参考实现
 *       写入上下文动态属性 {@code _active_skill_mcp_tools}（键名逐字保留），供构图方注册/核对。</li>
 *   <li><b>只保留同步链路</b>：等价参考实现同步 {@code wrap_model_call} / {@code wrap_tool_call}。</li>
 * </ol>
 */
public class SkillsMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SkillsMiddleware.class);

    /** 参考实现 {@code _active_skill_mcp_tools} 动态属性名（逐字保留）。 */
    static final String ACTIVE_SKILL_MCP_TOOLS_ATTR = "_active_skill_mcp_tools";

    /** 参考实现 {@code activated_skills} 状态键（逐字保留）。 */
    static final String ACTIVATED_SKILLS_STATE_KEY = "activated_skills";

    /** 动态激活经 {@link ToolCallResponse#getMetadata()} 回传的键（与 state 键同名）。 */
    public static final String ACTIVATED_SKILLS_META = ACTIVATED_SKILLS_STATE_KEY;

    /** 触发动态激活的工具名（参考实现硬编码 {@code read_file}）。 */
    static final String ACTIVATION_TOOL_NAME = "read_file";

    /** 参考实现 {@code SKILL.md} 文件名判定（逐字）。 */
    static final String SKILL_MD_FILE_NAME = "SKILL.md";

    /**
     * deepagents {@code SKILLS_SYSTEM_PROMPT} 的等价重建。
     *
     * <p>占位符名与顺序对齐参考实现 {@code _build_skills_section} 的
     * {@code .format(skills_locations=..., skills_load_warnings=..., skills_list=...)}。
     */
    static final String SKILLS_SYSTEM_PROMPT = """
            ## Skills

            This environment has the following skills available. Read a skill's SKILL.md before using it.

            {skills_locations}

            {skills_load_warnings}

            {skills_list}
            """;

    private final SkillRuntime skillRuntime;
    private final McpService mcpService;
    private final boolean enableSkillsPrompt;
    private final List<String> skillsSourcesForPrompt;

    public SkillsMiddleware(SkillRuntime skillRuntime, McpService mcpService) {
        this(skillRuntime, mcpService, true, List.of(
                BackendPaths.VIRTUAL_SKILLS_PATH + "/",
                BackendPaths.VIRTUAL_PERSONAL_SKILLS_PATH + "/"));
    }

    public SkillsMiddleware(
            SkillRuntime skillRuntime,
            McpService mcpService,
            boolean enableSkillsPrompt,
            List<String> skillsSourcesForPrompt) {
        this.skillRuntime = skillRuntime;
        this.mcpService = mcpService;
        this.enableSkillsPrompt = enableSkillsPrompt;
        this.skillsSourcesForPrompt = skillsSourcesForPrompt == null ? List.of() : List.copyOf(skillsSourcesForPrompt);
    }

    @Override
    public String getName() {
        return "skills";
    }

    /**
     * 工具侧拦截器（对应参考实现同一个类的 {@code awrap_tool_call} / {@code wrap_tool_call}）。
     *
     * <p>见类注释「能力差异 1」：{@code Command} 写回改为 metadata 回传。
     */
    public SkillActivationInterceptor asToolInterceptor() {
        return new SkillActivationInterceptor(this);
    }

    /** 对应参考实现 {@code awrap_model_call}：提示词注入 + 门控 + 依赖展开。 */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (request == null) {
            return handler.call(request);
        }
        BaseContext runtimeContext = contextFrom(request);
        ModelRequest effective = request;

        if (enableSkillsPrompt && runtimeContext != null) {
            Object effectiveSkillsRaw = skillSnapshot(runtimeContext).get("effective_skills");
            if (effectiveSkillsRaw instanceof List<?> rawList) {
                List<String> effectiveSkills = SkillService.normalizeStringList(rawList);
                List<String> preloadedSkills = getPreloadedSkills(runtimeContext);
                Set<String> preloadedSet = new LinkedHashSet<>(preloadedSkills);

                List<String> lazySkills = new ArrayList<>();
                for (String slug : effectiveSkills) {
                    if (!preloadedSet.contains(slug)) {
                        lazySkills.add(slug);
                    }
                }

                List<String> promptSections = new ArrayList<>();
                if (!lazySkills.isEmpty()) {
                    List<SkillRuntime.RuntimeSkill> skillsMeta = collectPromptMetadata(lazySkills, runtimeContext);
                    if (!skillsMeta.isEmpty()) {
                        promptSections.add(buildSkillsSection(skillsMeta));
                    }
                }
                if (!preloadedSkills.isEmpty()) {
                    promptSections.add(buildPreloadedSkillsSection(preloadedSkills, runtimeContext));
                }
                if (!promptSections.isEmpty()) {
                    String addition = String.join("\n\n", promptSections);
                    effective = ModelRequest.builder(effective)
                            .systemMessage(appendToSystemMessage(effective.getSystemMessage(), addition))
                            .build();
                }
            }
        }

        if (runtimeContext == null) {
            return handler.call(effective);
        }

        List<String> activated = activatedSkills(runtimeContext);
        Map<String, List<String>> depsBundle =
                skillRuntime.buildDependencyBundle(activated, getRuntimeSkills(runtimeContext));
        Set<String> activatedToolNames = new LinkedHashSet<>(depsBundle.getOrDefault("tools", List.of()));
        List<String> dependencyMcps = depsBundle.getOrDefault("mcps", List.of());

        // 门控：未激活 Skill 的依赖工具对模型不可见（保持按需加载）。
        Set<String> gatedToolNames = resolveGatedToolNames(runtimeContext);
        gatedToolNames.removeAll(activatedToolNames);

        List<String> modelTools = effective.getTools() == null
                ? new ArrayList<>()
                : new ArrayList<>(effective.getTools());
        if (!gatedToolNames.isEmpty()) {
            modelTools.removeIf(gatedToolNames::contains);
        }

        List<McpTool> activeMcpTools =
                dependencyMcps.isEmpty() ? List.of() : getMcpToolsFromContext(runtimeContext, dependencyMcps);

        // 与参考实现一致：先建立 name → tool 映射，重名（不同实例）直接报错。
        Map<String, McpTool> activeMcpToolsByName = new LinkedHashMap<>();
        for (McpTool tool : activeMcpTools) {
            McpTool existing = activeMcpToolsByName.get(tool.getName());
            if (existing != null && existing != tool) {
                throw new IllegalStateException("Skill MCP 工具名冲突：" + tool.getName());
            }
            activeMcpToolsByName.put(tool.getName(), tool);
        }
        runtimeContext.setDynamic(ACTIVE_SKILL_MCP_TOOLS_ATTR, activeMcpToolsByName);

        // 追加已激活或预加载 Skill 的依赖工具（本地工具）。
        Set<String> existingToolNames = new LinkedHashSet<>(modelTools);
        if (!activatedToolNames.isEmpty()) {
            for (ToolkitsRegistry.ToolDefinition tool : ToolkitsRegistry.getAllToolInstances()) {
                String name = tool == null ? null : tool.getName();
                if (name != null && activatedToolNames.contains(name) && existingToolNames.add(name)) {
                    modelTools.add(name);
                }
            }
        }
        for (McpTool mcpTool : activeMcpTools) {
            if (!existingToolNames.add(mcpTool.getName())) {
                throw new IllegalStateException("Skill MCP 工具名冲突：" + mcpTool.getName());
            }
            modelTools.add(mcpTool.getName());
        }

        if (!gatedToolNames.isEmpty() || !activatedToolNames.isEmpty() || !activeMcpTools.isEmpty()) {
            effective = ModelRequest.builder(effective).tools(modelTools).build();
        }

        return handler.call(effective);
    }

    // ==================== 提示词构建 ====================

    /** 收集指定 slugs 的提示词元数据（对应 {@code _collect_prompt_metadata}）。 */
    List<SkillRuntime.RuntimeSkill> collectPromptMetadata(List<String> slugs, BaseContext runtimeContext) {
        Map<String, SkillRuntime.RuntimeSkill> runtimeSkills = getRuntimeSkills(runtimeContext);
        List<SkillRuntime.RuntimeSkill> result = new ArrayList<>();
        for (String slug : slugs) {
            SkillRuntime.RuntimeSkill item = runtimeSkills.get(slug);
            if (item == null) {
                log.debug("Skill slug not found in prompt metadata, skip: {}", slug);
                continue;
            }
            result.add(item);
        }
        return result;
    }

    /** 构建 lazy skills 摘要段（对应 {@code _build_skills_section}）。 */
    String buildSkillsSection(List<SkillRuntime.RuntimeSkill> skillsMeta) {
        String skillsLocations = formatSkillsLocations(skillsSourcesForPrompt);
        String skillsList = formatSkillsList(skillsMeta);
        return SKILLS_SYSTEM_PROMPT
                .replace("{skills_locations}", skillsLocations)
                .replace("{skills_load_warnings}", "")
                .replace("{skills_list}", skillsList);
    }

    /** 构建已预加载 Skill 的完整系统提示段（对应 {@code _build_preloaded_skills_section}）。 */
    String buildPreloadedSkillsSection(List<String> slugs, BaseContext runtimeContext) {
        Map<String, Object> contents = asMap(skillSnapshot(runtimeContext).get("preloaded_skill_contents"));
        List<String> sections = new ArrayList<>();
        sections.add("# Preloaded Skills");
        sections.add("The following Skill instructions are already loaded and active.");
        for (String slug : slugs) {
            Object content = contents.get(slug);
            if (content instanceof String text) {
                sections.add("<preloaded_skill slug=\"" + slug + "\">\n" + text + "\n</preloaded_skill>");
            }
        }
        return String.join("\n\n", sections);
    }

    /** 格式化 skills 位置信息（对应 {@code _format_skills_locations}）。 */
    static String formatSkillsLocations(List<String> sources) {
        List<String> locations = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            String sourcePath = sources.get(i);
            String name = PosixPathLite.nameOf(sourcePath.replaceAll("/+$", ""));
            String suffix = (i == sources.size() - 1) ? " (higher priority)" : "";
            locations.add("**" + capitalize(name) + " Skills**: `" + sourcePath + "`" + suffix);
        }
        return String.join("\n", locations);
    }

    /** 格式化 skills 列表（对应 {@code _format_skills_list}）。 */
    String formatSkillsList(List<SkillRuntime.RuntimeSkill> skillsMeta) {
        if (skillsMeta.isEmpty()) {
            return "(No skills available yet. You can create skills in "
                    + String.join(" or ", skillsSourcesForPrompt) + ")";
        }
        List<String> lines = new ArrayList<>();
        for (SkillRuntime.RuntimeSkill skill : skillsMeta) {
            lines.add("- **" + skill.name() + "**: " + skill.description());
            lines.add("  -> Read `" + skill.path() + "` for full instructions");
        }
        return String.join("\n", lines);
    }

    // ==================== 门控与依赖 ====================

    /** 所有可见 Skill 依赖、且不属于基础工具集的工具名集合（对应 {@code _resolve_gated_tool_names}）。 */
    Set<String> resolveGatedToolNames(BaseContext runtimeContext) {
        Map<String, SkillRuntime.RuntimeSkill> runtimeSkills = getRuntimeSkills(runtimeContext);
        Set<String> baseToolNames = new LinkedHashSet<>(SkillService.normalizeStringList(asList(runtimeContext.get("tools"))));
        Set<String> gated = new LinkedHashSet<>();
        for (String slug : getEffectiveSkills(runtimeContext)) {
            SkillRuntime.RuntimeSkill node = runtimeSkills.get(slug);
            if (node == null) {
                continue;
            }
            gated.addAll(nullToEmpty(node.tools()));
        }
        gated.removeAll(baseToolNames);
        return gated;
    }

    /** 从上下文配置中取 MCP 工具（对应 {@code _get_mcp_tools_from_context}）。 */
    List<McpTool> getMcpToolsFromContext(BaseContext context, List<String> extraMcps) {
        Set<String> configuredMcps = new LinkedHashSet<>(SkillService.normalizeStringList(asList(context.get("mcps"))));
        List<String> allMcpNames = new ArrayList<>();
        for (String serverName : nullToEmpty(extraMcps)) {
            if (serverName != null && !configuredMcps.contains(serverName)) {
                allMcpNames.add(serverName);
            }
        }
        List<String> uniqueMcpNames = new ArrayList<>(new LinkedHashSet<>(allMcpNames));

        List<McpTool> selectedTools = new ArrayList<>();
        for (String serverName : uniqueMcpNames) {
            List<McpTool> mcpTools;
            try {
                mcpTools = mcpService == null ? List.of() : mcpService.getEnabledMcpTools(serverName);
                if (mcpTools == null || mcpTools.isEmpty()) {
                    log.warn("SkillsMiddleware: mcp dependency unavailable, skip: {}", serverName);
                    continue;
                }
            } catch (Exception exc) {
                log.warn("SkillsMiddleware: failed to load mcp dependency '{}': {}", serverName, exc.getMessage());
                continue;
            }
            selectedTools.addAll(mcpTools);
        }
        return selectedTools;
    }

    // ==================== 快照读取 ====================

    /** 参考实现 {@code _get_effective_skills}。 */
    Set<String> getEffectiveSkills(BaseContext runtimeContext) {
        Object selected = skillSnapshot(runtimeContext).get("effective_skills");
        List<?> raw = selected instanceof List<?> list ? list : List.of();
        return new LinkedHashSet<>(SkillService.normalizeStringList(raw));
    }

    /** 参考实现 {@code _get_runtime_skills}。 */
    Map<String, SkillRuntime.RuntimeSkill> getRuntimeSkills(BaseContext runtimeContext) {
        Object raw = skillSnapshot(runtimeContext).get("runtime_skills");
        Map<String, Object> rawMap = asMap(raw);
        Map<String, SkillRuntime.RuntimeSkill> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            result.put(entry.getKey(), asRuntimeSkill(entry.getValue()));
        }
        return result;
    }

    /** 参考实现 {@code _get_preloaded_skills}（只保留 effective 内的）。 */
    List<String> getPreloadedSkills(BaseContext runtimeContext) {
        Object selected = skillSnapshot(runtimeContext).get("preloaded_skills");
        List<?> raw = selected instanceof List<?> list ? list : List.of();
        Set<String> effective = getEffectiveSkills(runtimeContext);
        List<String> result = new ArrayList<>();
        for (String slug : SkillService.normalizeStringList(raw)) {
            if (effective.contains(slug)) {
                result.add(slug);
            }
        }
        return result;
    }

    /** 参考实现 {@code awrap_model_call} 里的激活归并（先过滤 effective，再与预加载合并）。 */
    List<String> activatedSkills(BaseContext runtimeContext) {
        Set<String> effectiveSkills = getEffectiveSkills(runtimeContext);
        Object stateActivated = runtimeContext == null ? null : runtimeContext.getDynamic(ACTIVATED_SKILLS_STATE_KEY, null);
        List<String> filtered = new ArrayList<>();
        for (String slug : SkillService.normalizeStringList(asList(stateActivated))) {
            if (effectiveSkills.contains(slug)) {
                filtered.add(slug);
            }
        }
        return activatedSkillsReducer(getPreloadedSkills(runtimeContext), filtered);
    }

    /** 参考实现 {@code _activated_skills_reducer}（模块级函数）。 */
    static List<String> activatedSkillsReducer(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        merged.addAll(nullToEmpty(left));
        merged.addAll(nullToEmpty(right));
        return SkillService.normalizeStringList(merged);
    }

    /** 参考实现 {@code getattr(runtime_context, "_skill_runtime_snapshot", {})}。 */
    static Map<String, Object> skillSnapshot(BaseContext runtimeContext) {
        if (runtimeContext == null) {
            return Map.of();
        }
        Object snapshot = runtimeContext.getDynamic(SkillRuntime.SKILL_RUNTIME_SNAPSHOT_ATTR, null);
        return asMap(snapshot);
    }

    // ==================== 工具侧：动态激活 ====================

    /**
     * 从共享投影或个人 UserWorkspace 的 {@code SKILL.md} 路径中提取 slug
     * （对应 {@code _extract_skill_slug_from_skill_md_path}）。
     */
    String extractSkillSlugFromSkillMdPath(Object fileValue) {
        if (!(fileValue instanceof String file)) {
            return null;
        }
        String raw = file.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String normalized = raw.startsWith("/") ? raw : "/" + raw;
        String pure = normalized.replaceAll("/{2,}", "/").replaceAll("/+$", "");
        for (String root : List.of(BackendPaths.VIRTUAL_SKILLS_PATH, BackendPaths.VIRTUAL_PERSONAL_SKILLS_PATH)) {
            String rootPath = root.replaceAll("/+$", "");
            if (!pure.equals(rootPath) && !pure.startsWith(rootPath + "/")) {
                continue;
            }
            String remainder = pure.substring(rootPath.length()).replaceAll("^/+", "");
            if (remainder.isEmpty()) {
                continue;
            }
            String[] parts = remainder.split("/");
            if (parts.length == 2 && SKILL_MD_FILE_NAME.equals(parts[1]) && SkillService.isValidSkillSlug(parts[0])) {
                return parts[0];
            }
        }
        return null;
    }

    /** 对应参考实现 {@code _process_tool_call_result}（工具侧）。 */
    ToolCallResponse processToolCallResult(ToolCallResponse result, ToolCallRequest request, BaseContext context) {
        if (result == null || request == null) {
            return result;
        }
        if (!ACTIVATION_TOOL_NAME.equals(request.getToolName())) {
            return result;
        }
        Map<String, Object> args = parseArgs(request.getArguments());
        Object filePath = args.get("file_path");
        String slug = extractSkillSlugFromSkillMdPath(filePath);
        if (slug == null) {
            return result;
        }
        if (context == null || !getEffectiveSkills(context).contains(slug)) {
            log.warn("SkillsMiddleware: deny skill activation for invisible slug: {}", slug);
            return result;
        }
        log.debug("SkillsMiddleware: activated skill by read_file: {}", slug);
        return mergeActivatedSkillUpdate(result, slug);
    }

    /**
     * 合并动态激活的 skill 更新（对应 {@code _merge_activated_skill_update}）。
     *
     * <p>参考实现把 {@code activated_skills} 写进 {@code Command.update}；本工程写进
     * {@link ToolCallResponse#getMetadata()}（见类注释「能力差异 1」），合并口径同为
     * {@link #activatedSkillsReducer}。
     */
    ToolCallResponse mergeActivatedSkillUpdate(ToolCallResponse result, String slug) {
        Map<String, Object> metadata = result.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(result.getMetadata());
        Object current = metadata.get(ACTIVATED_SKILLS_META);
        List<String> merged = activatedSkillsReducer(asList(current), List.of(slug));
        metadata.put(ACTIVATED_SKILLS_META, merged);
        return ToolCallResponse.builder()
                .toolName(result.getToolName())
                .toolCallId(result.getToolCallId())
                .content(result.getResult())
                .status(result.getStatus())
                .metadata(metadata)
                .build();
    }

    /**
     * 为当前模型轮次已开放的动态 MCP 调用绑定真实工具（参考实现 {@code _bind_active_mcp_tool}）。
     *
     * <p>能力差异（见类注释「能力差异 3」）：Java 侧工具由注册表按名解析、拦截器无法注入实例，
     * 故此处只返回当前轮次已开放的 MCP 工具名 → 实例映射，供构图方核对与注册。
     */
    Map<String, McpTool> activeMcpToolsOf(BaseContext context) {
        if (context == null) {
            return Map.of();
        }
        Object raw = context.getDynamic(ACTIVE_SKILL_MCP_TOOLS_ATTR, null);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, McpTool> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String name && entry.getValue() instanceof McpTool tool) {
                result.put(name, tool);
            }
        }
        return result;
    }

    // ==================== 通用小工具 ====================

    /** 参考实现 {@code deepagents.middleware._utils.append_to_system_message}。 */
    static SystemMessage appendToSystemMessage(SystemMessage current, String addition) {
        String existing = current == null ? null : current.getText();
        if (existing == null || existing.isEmpty() || addition == null || addition.isEmpty()) {
            return new SystemMessage(addition == null ? "" : addition);
        }
        return new SystemMessage(existing + "\n\n" + addition);
    }

    /** 取 {@link ModelRequest#getContext()} 里的运行时上下文。 */
    static BaseContext contextFrom(ModelRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object value = raw == null ? null : raw.get(ContextAwareInterceptor.CONTEXT_KEY);
        return value instanceof BaseContext context ? context : null;
    }

    /** 工具侧：取 {@link ToolCallRequest#getContext()} 里的运行时上下文。 */
    static BaseContext contextFrom(ToolCallRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object value = raw == null ? null : raw.get(ContextAwareInterceptor.CONTEXT_KEY);
        return value instanceof BaseContext context ? context : null;
    }

    static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    static List<String> asList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item == null ? null : String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    static <T> List<T> nullToEmpty(List<T> value) {
        return value == null ? List.of() : value;
    }

    /** 允许快照里的 runtime skill 以 {@code Map} 或 {@link SkillRuntime.RuntimeSkill} 两种形态存在。 */
    static SkillRuntime.RuntimeSkill asRuntimeSkill(Object value) {
        if (value instanceof SkillRuntime.RuntimeSkill skill) {
            return skill;
        }
        Map<String, Object> data = asMap(value);
        return new SkillRuntime.RuntimeSkill(
                data.get("name") == null ? null : String.valueOf(data.get("name")),
                data.get("description") == null ? null : String.valueOf(data.get("description")),
                data.get("path") == null ? null : String.valueOf(data.get("path")),
                asList(data.get("tools")),
                asList(data.get("mcps")),
                asList(data.get("skills")));
    }

    static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1);
    }

    static Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(arguments);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException exc) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Skill 动态激活的工具侧拦截器（对应参考实现的 {@code awrap_tool_call} / {@code wrap_tool_call}）。
     *
     * <p>与 {@link SkillsMiddleware} 共享全部纯逻辑（effective skills 判定、slug 抽取、激活合并），
     * 只在承载点上不同：这里是 {@link ToolInterceptor#interceptToolCall}。
     */
    public static final class SkillActivationInterceptor extends ToolInterceptor {

        private final SkillsMiddleware owner;

        SkillActivationInterceptor(SkillsMiddleware owner) {
            this.owner = owner;
        }

        @Override
        public String getName() {
            return "skills_activation";
        }

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            ToolCallResponse result = handler.call(request);
            return owner.processToolCallResult(result, request, contextFrom(request));
        }
    }
}
