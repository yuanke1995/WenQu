package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内置技能的全局改写（管理员维护）。
 * <p>
 * 内置技能随版本分发在 {@code classpath:skills/{dirName}/SKILL.md}，包内只读——但运维/实施常有
 * 「把组织自己的口径写进内置技能」的需求（示例话术、术语表、引用规范）。本表就是这层**平台级覆盖**：
 * <ul>
 *   <li>有行 → 读取（列表/详情/readSkill/注入清单）一律用本表的 name/description/content；</li>
 *   <li>无行 → 用 classpath 原文（随版本升级自动带上新内容）。</li>
 * </ul>
 * 因此不存在"漏同步"：管理员没改过的内置技能照旧跟随版本，改过的以本表为准（界面标「已改写」并提供恢复默认）。
 * <p>
 * 与 {@link UserSkill}（个人技能）、{@link SkillDisabled}（个人停用）分工：那两个按 uid 隔离，
 * 本表是全局唯一一行 per dirName，所有人看到同一份内容。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_builtin_skill_override")
public class BuiltinSkillOverride {

    /** 内置技能标识（classpath 目录名，主键） */
    @TableId(type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String dirName;

    /** 技能显示名（frontmatter name） */
    private String name;

    /** 一句话描述（注入系统提示、模型据此判断是否读取） */
    private String description;

    /** 版本（frontmatter version） */
    private String version;

    /** 改写后的 SKILL.md 全文（含 YAML frontmatter）；只作纯文本读取，绝不执行 */
    private String content;

    /** 最后修改人 uid（审计） */
    private String updatedBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}