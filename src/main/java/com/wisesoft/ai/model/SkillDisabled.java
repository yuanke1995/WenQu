package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内置技能的个人停用标记。
 * <p>
 * 内置技能随 classpath 分发、对所有人可见且不可删除，因此「某人不用某个内置技能」需要单独记录，
 * 不能挂在 {@link UserSkill}（那是各人自建的技能）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_skill_disabled")
public class SkillDisabled {

    /** 归属用户 uid（联合主键之一） */
    private String uid;

    /** 内置技能标识（classpath 里的目录名，联合主键之一） */
    private String dirName;

    private LocalDateTime createTime;
}
