package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.permissions.ShareableResource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 描述当前用户最终可用的 Skill 及其真实来源。
 *
 * <p>由参考实现的 agents/skills/service.py 中 {@code @dataclass(frozen=True, slots=True)
 * class ResolvedSkill} 逐字段翻译：字段名、字段顺序、默认值（version/content_hash 可空、
 * overrides_shared/shadowed_by_personal 默认 false）与 {@code to_dict} 的键序逐字对齐。
 *
 * <p>必要替换（能力差异，已标注）：
 * <ul>
 *   <li>frozen dataclass 的 {@code dataclasses.replace(item, overrides_shared=...)} →
 *       {@link #withOverridesShared(boolean)} / {@link #withShadowedByPersonal(boolean)}
 *       两个拷贝方法（字段全部 final，语义等价）。
 *   <li>{@code source_dir: Path} 保留为 {@link Path}；{@code id} 在个人 Skill 上取
 *       {@code "personal:<slug>"} 字符串、在共享 Skill 上取整型主键，故用 {@link Object} 承载。
 *   <li>参考实现靠鸭子类型把 ResolvedSkill 与 ORM 的 Skill 都塞进
 *       {@code user_can_manage_skill}/{@code resolve_skill_permission}；Java 侧让本类实现
 *       {@link ShareableResource}（字段名与参考实现读取的属性一一对应），从而复用同一套权限解析。
 * </ul>
 */
public final class ResolvedSkill implements ShareableResource {

    private final Object id;
    private final String slug;
    private final String name;
    private final String description;
    private final String sourceType;
    private final String sourceScope;
    private final Path sourceDir;
    private final boolean enabled;
    private final String createdBy;
    private final JSONObject shareConfig;
    private final List<String> toolDependencies;
    private final List<String> mcpDependencies;
    private final List<String> skillDependencies;
    private final String version;
    private final String contentHash;
    private final boolean overridesShared;
    private final boolean shadowedByPersonal;

    public ResolvedSkill(
            Object id,
            String slug,
            String name,
            String description,
            String sourceType,
            String sourceScope,
            Path sourceDir,
            boolean enabled,
            String createdBy,
            JSONObject shareConfig,
            List<String> toolDependencies,
            List<String> mcpDependencies,
            List<String> skillDependencies,
            String version,
            String contentHash,
            boolean overridesShared,
            boolean shadowedByPersonal) {
        this.id = id;
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.sourceType = sourceType;
        this.sourceScope = sourceScope;
        this.sourceDir = sourceDir;
        this.enabled = enabled;
        this.createdBy = createdBy;
        this.shareConfig = shareConfig;
        this.toolDependencies = toolDependencies == null ? new ArrayList<>() : new ArrayList<>(toolDependencies);
        this.mcpDependencies = mcpDependencies == null ? new ArrayList<>() : new ArrayList<>(mcpDependencies);
        this.skillDependencies = skillDependencies == null ? new ArrayList<>() : new ArrayList<>(skillDependencies);
        this.version = version;
        this.contentHash = contentHash;
        this.overridesShared = overridesShared;
        this.shadowedByPersonal = shadowedByPersonal;
    }

    public Object id() {
        return id;
    }

    public String slug() {
        return slug;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String sourceType() {
        return sourceType;
    }

    public String sourceScope() {
        return sourceScope;
    }

    public Path sourceDir() {
        return sourceDir;
    }

    public boolean enabled() {
        return enabled;
    }

    public String createdBy() {
        return createdBy;
    }

    public JSONObject shareConfig() {
        return shareConfig;
    }

    public List<String> toolDependencies() {
        return new ArrayList<>(toolDependencies);
    }

    public List<String> mcpDependencies() {
        return new ArrayList<>(mcpDependencies);
    }

    public List<String> skillDependencies() {
        return new ArrayList<>(skillDependencies);
    }

    public String version() {
        return version;
    }

    public String contentHash() {
        return contentHash;
    }

    public boolean overridesShared() {
        return overridesShared;
    }

    public boolean shadowedByPersonal() {
        return shadowedByPersonal;
    }

    /** 对应参考实现的 {@code replace(item, overrides_shared=...)}。 */
    public ResolvedSkill withOverridesShared(boolean value) {
        return new ResolvedSkill(
                id,
                slug,
                name,
                description,
                sourceType,
                sourceScope,
                sourceDir,
                enabled,
                createdBy,
                shareConfig,
                toolDependencies,
                mcpDependencies,
                skillDependencies,
                version,
                contentHash,
                value,
                shadowedByPersonal);
    }

    /** 对应参考实现的 {@code replace(item, shadowed_by_personal=...)}。 */
    public ResolvedSkill withShadowedByPersonal(boolean value) {
        return new ResolvedSkill(
                id,
                slug,
                name,
                description,
                sourceType,
                sourceScope,
                sourceDir,
                enabled,
                createdBy,
                shareConfig,
                toolDependencies,
                mcpDependencies,
                skillDependencies,
                version,
                contentHash,
                overridesShared,
                value);
    }

    /** 返回可安全提供给前端的 Skill 元数据（键序与参考实现 to_dict 逐字一致）。 */
    public Map<String, Object> toDict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("slug", slug);
        data.put("name", name);
        data.put("description", description);
        data.put("source_type", sourceType);
        data.put("source_scope", sourceScope);
        data.put("enabled", enabled);
        data.put("created_by", createdBy);
        data.put("tool_dependencies", toolDependencies);
        data.put("mcp_dependencies", mcpDependencies);
        data.put("skill_dependencies", skillDependencies);
        data.put("overrides_shared", overridesShared);
        data.put("shadowed_by_personal", shadowedByPersonal);
        if (shareConfig != null) {
            data.put("share_config", shareConfig);
        }
        return data;
    }
}
