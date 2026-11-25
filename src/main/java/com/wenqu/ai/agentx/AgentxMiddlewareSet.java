package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;

/**
 * 建实例期的中间件与权限配置（从 AgentxAgentBuilder 抽出）。
 *
 * <p>中间件顺序是语义而不是风格：档位闸必须排在步数闸之后（先判「该不该跑」，再判「还剩几步」），
 * 否则被档位拒绝的那次会白占一步预算；重复调用闸排在步数闸外侧，空转的重复调用不挤掉真正要做的事。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxMiddlewareSet {

    public void apply(HarnessAgent.Builder agentBuilder, ChatCommand cmd, ExecutionPolicy policy,
                      AgentxTurnSlot slot, AgentxStepBudget stepBudget, AgentxRepeatGuard repeatGuard,
                      AgentxFrameworkToolGate toolGate, boolean hasSubagents) {
        agentBuilder
                // 权限由框架持有：档位→模式、按名黑名单→deny 规则；游客 MCP 授权名单一并传入
                .permissionContext(AgentxPermissions.contextOf(policy, cmd.getGuestMcpServers()))
                // 待办持久化 + 答复后自动接续原任务，是这套执行形态的立身之处
                .enablePendingToolRecovery(true)
                // 重复调用闸：同一工具同一参数连续第 3 次起拒掉。order=2 排在步数闸外侧
                .middleware(repeatGuard)
                // 步数预算闸：maxIters 限的是推理轮数而不是工具调用次数
                .middleware(stepBudget)
                // 档位闸必须排在步数闸之后：先判「该不该跑」，再判「还剩几步」
                .middleware(toolGate)
                // 流式可见性闸：把一次模型调用结束才落盘的 chunk 拆成 200ms 一片
                .middleware(new AgentxStreamFlushMiddleware())
                // 工具面闸：无子智能体/无活跃目标时隐藏对应的编排工具，省 schema 降误选
                .middleware(new AgentxToolSurfaceFilter(slot, hasSubagents));
    }
}
