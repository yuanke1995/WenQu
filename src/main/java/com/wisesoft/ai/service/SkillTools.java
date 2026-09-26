package com.wisesoft.ai.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 技能读取工具（Skills 渐进披露的取回端）。
 *
 * <p>system prompt 里只放「技能名 + 一行描述」，正文由模型主动调用本工具取回——
 * 这样技能装得多也不会每次都吃掉上下文预算。技能按用户隔离，因此本工具**每次问答按当前用户
 * 实例化**（技能是个人的资产，读技能正文时不能跨账户），而不是全局单例。
 *
 * @author yuanke
 */
public class SkillTools {

    private final SkillService skillService;
    /** 本次问答所属用户：读技能正文时只在这个人的范围内找 */
    private final String uid;

    public SkillTools(SkillService skillService, String uid) {
        this.skillService = skillService;
        this.uid = uid;
    }

    /**
     * 读取指定技能的完整说明。
     *
     * @param name 技能名（见系统提示中的可用技能清单）
     * @return 技能全文（过长会截断）或错误提示
     */
    @Tool(description = "读取某个技能的完整说明。当用户问题涉及系统提示中列出的某个技能领域时，"
            + "先调用本工具取回该技能的详细做法（步骤、格式要求、注意事项），再按它作答；不要臆测技能内容。")
    public String readSkill(@ToolParam(description = "技能名，如「报表字段命名规范」") String name) {
        return skillService.readContent(uid, name);
    }
}
