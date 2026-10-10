package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.SkillService;
import com.wenqu.ai.service.SkillTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能族（Skills 渐进披露的取回端）。
 * <p>
 * {@link #holder()} 用占位 uid 现造一个实例：技能是个人资产、工具实例按本轮用户创建，
 * 但注册中心只需要反射出**工具名与 schema**，不碰任何人的技能内容（读内容要等真调用）。
 * <p>
 * gateKey 挂 {@code tool.enabled} 而不是族开关：技能没有独立全局开关（谁装谁用、停用由本人控），
 * 这是现状如实记录，不是笔误。
 */
@Component
public class SkillToolProvider implements ToolFamilyProvider {

    private final SkillService skillService;

    public SkillToolProvider(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.SKILL;
    }

    @Override
    public Object holder() {
        return new SkillTools(skillService, "__tool_registry__");
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor("readSkill", ToolFamily.SKILL,
                "读取技能", "读取技能说明", "按需加载某个技能的详细说明",
                SideEffect.READ, "tool.enabled", false, false, null));
    }

    /**
     * 技能是**个人资产**：工具实例按本轮用户现造，读正文时只在这个人的范围里找。
     * <p>刻意不用 {@link #holder()} 那个实例——那是启动核对用的占位 uid，拿它执行会把
     * 「谁的技能」读成「占位账号的技能」，属于跨账户读取。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, new SkillTools(skillService, mc.userId()), d -> exposes(d, mc));
    }
}
