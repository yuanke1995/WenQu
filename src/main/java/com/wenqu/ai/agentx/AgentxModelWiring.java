package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;

/**
 * 建实例期的模型与执行配置（从 AgentxAgentBuilder 抽出）：主链/辅助模型、工具超时、推理轮上限、
 * 计划模式、上下文压缩、工作区目录。
 *
 * <p>工具超时必须显式设：框架对「一把工具」自带 5 分钟硬超时，咨询子轮与沙盒长命令都会撞上去，
 * 而手写运行时没有这一层——不设就等于框架比问答侧多一道没人知道的天花板。
 *
 * <p>压缩只按「窗口推导出来的那道 token 线」触发（按条数那道已在 compactionConfig 里关掉）；
 * 用户选的窗口已落到 model.contextWindowSize，阈值由它推导，这里不再单独传 maxContextTokens。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxModelWiring {

    private final AgentxModelConfig modelConfig;
    private final java.util.function.IntSupplier turnSeconds;
    private final java.util.function.IntSupplier maxIters;

    public void apply(HarnessAgent.Builder b, Model chatModel, Model auxModel,
                      WenquAgentxTools.TurnHooks hooks, ChatCommand cmd, Toolkit toolkit,
                      AgentBinding binding, java.nio.file.Path stateDir) {
        b                .model(chatModel)
                .toolkit(toolkit)
                // 框架对「一把工具」有自带的 5 分钟硬超时（TOOL_DEFAULTS）。咨询子轮、沙盒长命令都会撞上去，
                // 而手写运行时没有这一层——不显式设，就等于框架比问答侧多一道没人知道的天花板。
                .toolExecutionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofSeconds(turnSeconds.getAsInt()))
                        .build())
                .maxIters(maxIters.getAsInt())
                // 计划模式工具面常驻开启：是否进入只由用户显式开计划开关（sessions.setPlanMode）或
                // 模型自己在推理中调用 plan_enter 决定。问渠不做「按消息意图预判」，
                // 计划怎么执行全交框架的 plan_enter/write/exit + 只读拦截。
                // 历史计划卡的多版本落库仍由 AgentxTurnRecording.planVersions 负责，与意图判定无关。
                .enablePlanMode(!cmd.branchTurn())
                // 目标模式（Goal Mode）：不借框架的任务上下文投影（Scope/要求/验证）——
                // 目标由问渠自己带进模型输入（交互轮拼目标上下文，续跑轮用 GoalPromptBuilder 的续跑提示词），
                // task_requirement_propose 也不注册（模型没有地方、也没有必要「提议验收标准」）。
                // 任务清单的投影不受影响：TaskContextProjection 仍照常渲染 todo 状态，它只看自己的
                // tasksContext 存储，与这里的开关无关。
                // 任务清单：**不开**框架的 enableTaskList，只借它的 state 做存储。
                // 原因：开它会同时注册 todo_write 工具并由 TaskReminderMiddleware 每轮教模型用
                // 「in_progress/completed」这套词，而问渠挂的是 writeTodo（教的是 doing/done，
                // 且带护栏与中文展示名）。两套工具 + 两套状态词同时进模型上下文，它会挑错工具、
                // 或者把 doing 写进 todo_write 被框架判非法。storage 用框架的、tool 面用问渠的，
                // 是这里刻意的分工——框架 AgentState 才是清单的权威真源（见 TodoBridge）。
                // 真要换成框架工具面，得连护栏、中文名、前端契约一起换，不能只开这个开关。
                // 上下文压缩：只按「窗口推导出来的那道 token 线」触发（按条数触发那道已在
                // compactionConfig 里关掉，逐字保留最近 20 条仍走框架默认）。
                // 用户选的窗口已落到 model.contextWindowSize（见 .model(...) 段），压缩阈值由它推导，
                // 这里不再单独传 maxContextTokens（那是 WorkspaceContextMiddleware 的注入预算，非压缩阈值）。
                // 压缩摘要用独立的 MeteredModel（kind=compress），成本才进得了台账。
                .compaction(modelConfig.compactionConfig(cmd, auxModel, hooks.modelRef()))

                .workspace(stateDir);
    }
}
