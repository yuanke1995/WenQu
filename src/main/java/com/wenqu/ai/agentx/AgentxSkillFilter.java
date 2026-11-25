package com.wenqu.ai.agentx;

import com.wenqu.ai.model.Agent;
import com.wenqu.ai.skill.SkillRef;
import io.agentscope.core.skill.SkillFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 智能体的技能过滤口径（从 AgentscopeChatEngine 抽出）。
 *
 * <p>框架的 SkillFilter 只按 AgentSkill.getName() 比较，因此规范引用（builtin/user/workspace）
 * 与历史 {uid}/{name} 都必须先解析成裸名再交给它——把 fullRef 塞进去等于一个技能都匹配不上。
 * 非法引用记 warn 后忽略，不为一条坏数据把整轮打挂。
 *
 * @author yuanke
 */
@Slf4j
public final class AgentxSkillFilter {

    private AgentxSkillFilter() {
    }

    /** 智能体声明的技能范围 → 框架 SkillFilter（all / none / only）。 */
    public static SkillFilter of(Agent agent) {
        if (agent == null || agent.getToolSkill() == null) {
            return SkillFilter.all();
        }
        if (agent.getToolSkill() == 0) {
            return SkillFilter.none();
        }
        Set<String> names = names(agent.getSkills());
        return SkillFilter.only(names.toArray(new String[0]));
    }

    /** 技能引用串列表 → 裸名集合（与只读清单、点名注入同一套剥法）。 */
    public static Set<String> names(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("[\r\n,，;；]+")) {
            String ref = line.trim();
            if (ref.isEmpty()) continue;
            try {
                out.add(SkillRef.parse(ref).filterName());
            } catch (RuntimeException e) {
                log.warn("[SKILL] 智能体技能引用非法，已忽略: {}", ref);
            }
        }
        return out;
    }
}
