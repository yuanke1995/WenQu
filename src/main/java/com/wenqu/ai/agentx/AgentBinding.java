package com.wenqu.ai.agentx;

import com.wenqu.ai.model.Agent;
import io.agentscope.core.skill.SkillFilter;

/**
 * 本轮智能体绑定解析的产物（从 AgentscopeChatEngine 的私有嵌套记录提为顶层，供
 * AgentxAgentBinding 与本类共享）：解析到的智能体行、实例指纹 key、技能滤镜、上下文窗口与最大输出。
 *
 * @author yuanke
 */
record AgentBinding(Agent row, String key, SkillFilter skillFilter, int contextWindow, int maxOutput) {
}
