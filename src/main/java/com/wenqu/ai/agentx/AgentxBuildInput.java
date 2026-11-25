package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;

import java.nio.file.Path;
import java.util.List;

/**
 * 建实例所需的全部输入（从 AgentxAgentBuilder 的内嵌 record 提出为顶层类型）。
 *
 * <p>参数曾有 17 个、类引用 26 个类型，都超 Checkstyle 阈值；单独成类后
 * {@link AgentxAgentBuilder} 只依赖本类型，不再逐个持有这些框架类型。
 *
 * @param cmd                 本轮命令
 * @param policy              执行档位
 * @param route               模型路由
 * @param hooks               本轮钩子（引用池、游标、用量）
 * @param binding             智能体绑定（提示词行、技能范围、上下文窗口）
 * @param slot                本轮槽位（档位、工具面）
 * @param stateDir            实例状态目录
 * @param toolkit             工具集
 * @param chatModel           主链模型
 * @param auxModel            辅助链模型（记忆提炼／压缩）
 * @param subagentTargets     可派遣的子智能体清单
 * @param stepBudget          工具步数闸
 * @param repeatGuard         重复调用闸
 * @param toolGate            框架工具闸
 * @param frameworkFs         本轮的沙盒/工作区文件系统
 * @param workspaceMemory     工作区约定（注入 system prompt）
 * @param userMemoryReadonly  用户记忆（只读段）
 * @param localEnv            本机执行环境说明
 * @param teamGuidance        团队指引
 * @author yuanke
 */
public record AgentxBuildInput(ChatCommand cmd, ExecutionPolicy policy, ModelRegistryService.ModelRoute route,
                               WenquAgentxTools.TurnHooks hooks, AgentBinding binding, AgentxTurnSlot slot,
                               Path stateDir, Toolkit toolkit, Model chatModel, Model auxModel,
                               List<WenquAgentxTools.SubagentTarget> subagentTargets, AgentxStepBudget stepBudget,
                               AgentxRepeatGuard repeatGuard, AgentxFrameworkToolGate toolGate,
                               AbstractFilesystem frameworkFs, String workspaceMemory,
                               String userMemoryReadonly, String localEnv, String teamGuidance) {
}
