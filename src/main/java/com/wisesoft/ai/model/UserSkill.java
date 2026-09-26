package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人技能（Skills）
 * <p>
 * 技能 = 一段可复用的「做法说明」：系统提示里只放名称与一行描述，正文由模型按需 readSkill 取回。
 * 归属某个用户（uid），原「服务器目录 + SKILL.md」的形态改为入库；随版本分发的内置技能仍在
 * classpath 的 skills 目录下，其个人停用标记记录在 {@link SkillDisabled}。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_skill")
public class UserSkill {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户 uid */
    private String uid;

    /** 技能标识（详情/停用/删除的主键；取值口径同原技能目录名） */
    private String dirName;

    /** 技能显示名（frontmatter name） */
    private String name;

    /** 一句话描述（注入系统提示、模型据此判断是否读取） */
    private String description;

    /** 版本（frontmatter version） */
    private String version;

    /** SKILL.md 全文（含 YAML frontmatter）；只作纯文本读取，绝不执行 */
    private String content;

    /** 来源：user=自建 / url=URL 安装 */
    private String source;

    /** 停用：0=生效 1=停用 */
    private Integer disabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
