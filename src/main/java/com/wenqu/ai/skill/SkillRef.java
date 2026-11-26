package com.wenqu.ai.skill;

import com.wenqu.ai.common.BizException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 技能引用：让「谁的那把技能」可精确表达，同时兼容历史裸名与 {@code {uid}/{name}}。
 * <p>框架的技能过滤只认裸名，所以这里只负责把引用解析成「裸名 + 来源约束」；
 * 真正取正文时由 {@link WenquSkillRepository#resolveRef} 按来源/归属复核，不能跨人同名顶替。
 * <p>格式：
 * <ul>
 *   <li>{@code builtin:<name>}：内置技能；</li>
 *   <li>{@code user:<uid>:<name>}：某人的自装技能（uid 可含冒号时按最后一段切名字）；</li>
 *   <li>{@code workspace:<sessionId>:<name>}：会话工作区技能，不进智能体配置；</li>
 *   <li>{@code {uid}/{name}}：历史引用，等同 user；裸名=当前用户优先 + 内置兜底。</li>
 * </ul>
 */
public final class SkillRef {

    // 技能名允许中文：内置/示例技能的 SKILL.md frontmatter name 就是中文（如「多来源交叉核对」），
    // 与 UserSkillStore.NAME_OK（[\\p{IsHan}A-Za-z0-9_-]）保持同一口径；raw 已 toLowerCase，
    // 所以这里只需 a-z。空格/斜杠/冒号仍不允许——它们会破坏裸名与 {uid}/{name} 的解析。
    private static final Pattern NAME = Pattern.compile("[\\p{IsHan}a-z0-9][\\p{IsHan}a-z0-9._-]{0,99}");

    private final String source;
    private final String owner;
    private final String name;
    private final boolean canonical;

    private SkillRef(String source, String owner, String name, boolean canonical) {
        this.source = source;
        this.owner = owner;
        this.name = name;
        this.canonical = canonical;
    }

    public String source() {
        return source;
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public boolean canonical() {
        return canonical;
    }

    public static SkillRef parse(String raw) {
        if (raw == null || raw.isBlank()) throw new BizException("技能引用不能为空");
        String ref = raw.trim();
        if (ref.startsWith(WenquSkillRepository.SOURCE_BUILTIN + ":")) {
            return new SkillRef(WenquSkillRepository.SOURCE_BUILTIN, null, name(ref.substring("builtin:".length())), true);
        }
        if (ref.startsWith(WenquSkillRepository.SOURCE_USER + ":")) {
            return colonScoped(WenquSkillRepository.SOURCE_USER, ref.substring("user:".length()), true);
        }
        if (ref.startsWith(WenquSkillRepository.SOURCE_WORKSPACE + ":")) {
            return colonScoped(WenquSkillRepository.SOURCE_WORKSPACE, ref.substring("workspace:".length()), true);
        }
        int slash = ref.indexOf('/');
        if (slash > 0) {
            String uid = ref.substring(0, slash).trim();
            if (uid.isEmpty()) throw new BizException("技能引用缺少归属人: " + ref);
            return new SkillRef(WenquSkillRepository.SOURCE_USER, uid, name(ref.substring(slash + 1)), false);
        }
        return new SkillRef(null, null, name(ref), false);
    }

    private static SkillRef colonScoped(String source, String rest, boolean canonical) {
        int split = rest.lastIndexOf(':');
        if (split <= 0) throw new BizException("技能引用格式应为 " + source + ":<归属>:<名称>: " + rest);
        String owner = rest.substring(0, split).trim();
        if (owner.isEmpty()) throw new BizException("技能引用缺少归属: " + rest);
        return new SkillRef(source, owner, name(rest.substring(split + 1)), canonical);
    }

    private static String name(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) {
            throw new BizException("技能名须为 1–100 位字母（含中文）/数字/._-: " + raw);
        }
        return n;
    }

    /** 输出稳定引用：裸名保留为裸名（当前用户优先），其余写规范格式。 */
    public String canonicalText() {
        if (!canonical) {
            if (source == null) return name;
            return source + ":" + owner + ":" + name;   // legacy {uid}/{name} 归一
        }
        return source + ":" + (owner == null ? "" : owner + ":") + name;
    }

    /** 模型侧 SkillFilter 只认裸名：返回它能比较的名字。 */
    public String filterName() {
        return name;
    }
}
