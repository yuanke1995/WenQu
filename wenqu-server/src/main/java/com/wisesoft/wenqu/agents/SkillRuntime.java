package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.common.SafeFiles;
import com.wisesoft.wenqu.models.User;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Skill 运行时解析。
 *
 * <p>由参考实现的 {@code agents/skills/runtime.py} 逐函数翻译（6 个函数 + 1 个 TypedDict）：
 * {@code build_runtime_skills} / {@code expand_skill_closure} / {@code resolve_runtime_skills_for_context} /
 * {@code _read_preloaded_skill_contents} / {@code resolve_skill_gated_tools} / {@code build_dependency_bundle}。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code list_accessible_skills(db, user)} → {@link SkillService#listAccessibleSkills(User, boolean)}
 *       （默认 {@code require_enabled=True}，与参考实现签名默认值一致）。</li>
 *   <li>{@code asyncio.to_thread(_read_preloaded_skill_contents, ...)} → 直接同步调用
 *       （本工程无异步 DB/IO 层，调用方线程承担同一阻塞语义）。</li>
 *   <li>{@code open_regular_file_fd(Path(source_dir.anchor), (*source_dir.parts[1:], "SKILL.md"))}
 *       → {@link SafeFiles#openRegularFile(Path, List, boolean)}（同为 no-follow 逐组件校验；
 *       见 {@link SafeFiles} 的 TOCTOU 能力差异说明）。</li>
 *   <li>{@code TypedDict RuntimeSkill} → {@link RuntimeSkill} record（键名与顺序逐字保留）。</li>
 *   <li>{@code _skill_runtime_snapshot} 动态属性 → {@link BaseContext#setDynamic} /
 *       {@link BaseContext#getDynamic}（{@code set()} 只写已声明字段，动态属性会静默失效）。</li>
 * </ul>
 */
@Service
public class SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    /** 上下文动态属性名：Skill 运行时快照（参考实现字面量，逐字保留）。 */
    public static final String SKILL_RUNTIME_SNAPSHOT_ATTR = "_skill_runtime_snapshot";

    /** 单个 Skill 的运行时信息（对应参考实现 {@code RuntimeSkill}）。 */
    public record RuntimeSkill(
            String name,
            String description,
            String path,
            List<String> tools,
            List<String> mcps,
            List<String> skills) {

        /** 返回与参考实现字典等价的视图（供 context 快照序列化）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("description", description);
            data.put("path", path);
            data.put("tools", tools);
            data.put("mcps", mcps);
            data.put("skills", skills);
            return data;
        }
    }

    private final SkillService skillService;

    public SkillRuntime(SkillService skillService) {
        this.skillService = skillService;
    }

    /** 从已授权 Skill 构建运行时信息（对应 {@code build_runtime_skills}）。 */
    public Map<String, RuntimeSkill> buildRuntimeSkills(List<ResolvedSkill> skills) {
        Map<String, RuntimeSkill> result = new LinkedHashMap<>();
        if (skills == null) {
            return result;
        }
        for (ResolvedSkill item : skills) {
            if (item.slug() == null || item.slug().isEmpty()) {
                continue;
            }
            String root = SkillService.PERSONAL_SKILL_SOURCE_TYPE.equals(item.sourceScope())
                    ? BackendPaths.VIRTUAL_PERSONAL_SKILLS_PATH
                    : BackendPaths.VIRTUAL_SKILLS_PATH;
            result.put(item.slug(), new RuntimeSkill(
                    item.name(),
                    item.description(),
                    root + "/" + item.slug() + "/SKILL.md",
                    SkillService.normalizeStringList(item.toolDependencies()),
                    SkillService.normalizeStringList(item.mcpDependencies()),
                    SkillService.normalizeStringList(item.skillDependencies())));
        }
        return result;
    }

    /** 展开 Skill 依赖闭包并保持根与依赖的声明顺序（对应 {@code expand_skill_closure}）。 */
    public List<String> expandSkillClosure(List<String> slugs, Map<String, RuntimeSkill> runtimeSkills) {
        List<String> orderedRoots = SkillService.normalizeStringList(slugs);
        List<String> result = new ArrayList<>();
        if (orderedRoots.isEmpty()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String root : orderedRoots) {
            dfsSkillClosure(root, new LinkedHashSet<>(), seen, result, runtimeSkills);
        }
        return result;
    }

    private void dfsSkillClosure(
            String slug,
            Set<String> stack,
            Set<String> seen,
            List<String> result,
            Map<String, RuntimeSkill> runtimeSkills) {
        if (stack.contains(slug)) {
            List<String> path = new ArrayList<>(stack);
            path.add(slug);
            log.warn("Cycle detected in skill dependencies, skip: {}", String.join(" -> ", path));
            return;
        }
        if (seen.contains(slug)) {
            return;
        }
        RuntimeSkill node = runtimeSkills == null ? null : runtimeSkills.get(slug);
        if (node == null) {
            log.warn("Skill dependency target not found in DB, skip: {}", slug);
            return;
        }
        seen.add(slug);
        result.add(slug);
        Set<String> nextStack = new LinkedHashSet<>(stack);
        nextStack.add(slug);
        for (String dep : node.skills()) {
            dfsSkillClosure(dep, nextStack, seen, result, runtimeSkills);
        }
    }

    /**
     * 从已授权 Skill 派生当前 Agent Run 的运行时 scope 与预加载快照
     * （对应 {@code resolve_runtime_skills_for_context}）。
     *
     * <p>返回键序与参考实现逐字一致：context_skills / context_preload_skills /
     * effective_skills / runtime_skills / skill_metadata / preloaded_skills /
     * preloaded_skill_contents。
     */
    public Map<String, Object> resolveRuntimeSkillsForContext(BaseContext context, User user) {
        List<ResolvedSkill> skillItems = new ArrayList<>();
        for (ResolvedSkill item : skillService.listAccessibleSkills(user, true)) {
            if (item.slug() != null && !item.slug().isEmpty()) {
                skillItems.add(item);
            }
        }
        Map<String, RuntimeSkill> runtimeSkills = buildRuntimeSkills(skillItems);
        Set<String> available = new LinkedHashSet<>(runtimeSkills.keySet());

        List<String> selected = SkillService.normalizeStringList(asList(context == null ? null : context.get("skills")));
        List<String> contextSkills = new ArrayList<>();
        for (String slug : selected) {
            if (available.contains(slug)) {
                contextSkills.add(slug);
            }
        }

        List<String> effectiveSkills = expandSkillClosure(contextSkills, runtimeSkills);

        List<String> configuredPreloads = SkillService.normalizeStringList(
                asList(context == null ? null : context.get("preload_skills")));
        List<String> contextPreloadSkills = new ArrayList<>();
        for (String slug : configuredPreloads) {
            if (contextSkills.contains(slug)) {
                contextPreloadSkills.add(slug);
            }
        }
        List<String> preloadedSkills = expandSkillClosure(contextPreloadSkills, runtimeSkills);

        Map<String, ResolvedSkill> itemsBySlug = new LinkedHashMap<>();
        for (ResolvedSkill item : skillItems) {
            itemsBySlug.put(item.slug(), item);
        }
        Map<String, String> preloadedContents = preloadedSkills.isEmpty()
                ? new LinkedHashMap<>()
                : readPreloadedSkillContents(preloadedSkills, itemsBySlug);

        Map<String, Object> skillMetadata = new LinkedHashMap<>();
        for (String slug : effectiveSkills) {
            ResolvedSkill item = itemsBySlug.get(slug);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_scope", item == null ? null : item.sourceScope());
            metadata.put("version", item == null ? null : item.version());
            metadata.put("content_hash", item == null ? null : item.contentHash());
            skillMetadata.put(slug, metadata);
        }

        Map<String, Object> runtimeSkillsView = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeSkill> entry : runtimeSkills.entrySet()) {
            runtimeSkillsView.put(entry.getKey(), entry.getValue().toMap());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("context_skills", contextSkills);
        result.put("context_preload_skills", contextPreloadSkills);
        result.put("effective_skills", effectiveSkills);
        result.put("runtime_skills", runtimeSkillsView);
        result.put("skill_metadata", skillMetadata);
        result.put("preloaded_skills", preloadedSkills);
        result.put("preloaded_skill_contents", preloadedContents);
        return result;
    }

    /** 从授权解析得到的真实来源读取根级 SKILL.md（对应 {@code _read_preloaded_skill_contents}）。 */
    private Map<String, String> readPreloadedSkillContents(
            List<String> slugs, Map<String, ResolvedSkill> skillItems) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (String slug : slugs) {
            try {
                ResolvedSkill item = skillItems.get(slug);
                if (item == null || item.sourceDir() == null) {
                    throw new IOException("Skill 来源目录缺失");
                }
                Path sourceDir = item.sourceDir();
                if (!sourceDir.isAbsolute() || containsParentPart(sourceDir)) {
                    throw new IOException("Skill 来源目录必须是规范化绝对路径");
                }
                // 参考实现 parts = (*source_dir.parts[1:], "SKILL.md")，root = source_dir.anchor
                Path root = sourceDir.getRoot();
                List<String> parts = new ArrayList<>();
                for (int index = 0; index < sourceDir.getNameCount(); index++) {
                    parts.add(sourceDir.getName(index).toString());
                }
                parts.add("SKILL.md");
                SafeFiles.OpenedRegularFile opened = SafeFiles.openRegularFile(root, parts, false);
                try (InputStream stream = opened.openRead()) {
                    contents.put(slug, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException | java.nio.file.InvalidPathException exception) {
                throw new IllegalStateException(
                        "预加载 Skill '" + slug + "' 失败：根级 SKILL.md 不可读", exception);
            }
        }
        return contents;
    }

    private static boolean containsParentPart(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析所有可见 Skill 依赖且需注册到 ToolNode 的本地工具
     * （对应 {@code resolve_skill_gated_tools}）。
     */
    public List<ToolkitsRegistry.ToolDefinition> resolveSkillGatedTools(BaseContext context) {
        Object snapshotRaw = context == null ? null : context.getDynamic(SKILL_RUNTIME_SNAPSHOT_ATTR, null);
        Map<String, Object> snapshot = asStringKeyedMap(snapshotRaw);
        Map<String, Object> runtimeSkills = asStringKeyedMap(snapshot.get("runtime_skills"));
        List<String> effectiveSkills = asStringList(snapshot.get("effective_skills"));

        Set<String> toolNames = new LinkedHashSet<>();
        for (String slug : effectiveSkills) {
            Map<String, Object> node = asStringKeyedMap(runtimeSkills.get(slug));
            Object tools = node.get("tools");
            if (tools instanceof List<?> list) {
                for (Object value : list) {
                    if (value != null) {
                        toolNames.add(String.valueOf(value));
                    }
                }
            }
        }
        List<ToolkitsRegistry.ToolDefinition> result = new ArrayList<>();
        if (toolNames.isEmpty()) {
            return result;
        }
        for (ToolkitsRegistry.ToolDefinition tool : ToolkitsRegistry.getAllToolInstances()) {
            if (toolNames.contains(tool.getName())) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * 汇总直接激活 Skill 的本地工具和 MCP 依赖（对应 {@code build_dependency_bundle}）。
     */
    public Map<String, List<String>> buildDependencyBundle(
            List<String> activatedSkills, Map<String, RuntimeSkill> runtimeSkills) {
        List<String> tools = new ArrayList<>();
        List<String> mcps = new ArrayList<>();
        Set<String> seenTools = new LinkedHashSet<>();
        Set<String> seenMcps = new LinkedHashSet<>();

        if (activatedSkills != null) {
            for (String slug : activatedSkills) {
                RuntimeSkill dependency = runtimeSkills == null ? null : runtimeSkills.get(slug);
                List<String> dependencyTools = dependency == null ? List.of() : dependency.tools();
                for (String toolName : dependencyTools) {
                    if (seenTools.add(toolName)) {
                        tools.add(toolName);
                    }
                }
                List<String> dependencyMcps = dependency == null ? List.of() : dependency.mcps();
                for (String mcpName : dependencyMcps) {
                    if (seenMcps.add(mcpName)) {
                        mcps.add(mcpName);
                    }
                }
            }
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("tools", tools);
        result.put("mcps", mcps);
        return result;
    }

    // ==================== 内部小工具 ====================

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text) {
                result.add(text);
            }
        }
        return result;
    }
}
