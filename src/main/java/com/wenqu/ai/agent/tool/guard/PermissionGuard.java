package com.wenqu.ai.agent.tool.guard;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolGuard;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 权限档位闸（守卫链第三条：叫停闸 → 步数闸 → <b>本闸</b> → 审批闸）。
 * <p>
 * 拦的是「档位不允许、却仍然被调到」的工具。正常路径下这类工具在装配阶段就没进模型的工具表
 * （{@code SandboxToolProvider.exposes} 已经问过同一份策略），所以本闸今天拦到的只有两种情况：
 * 绕过装配的裸调（工作流节点那一类），以及装配与执行之间策略发生变化的轮次。
 * 判据与装配同源这一点是全部的要点：两处各写一份，就会漂出「挂着的被拦、没挂的反能跑」，
 * 那种错配从回答里完全看不出来，只能靠逐字比对工具表。
 * <p>
 * 排在步数闸<b>之后</b>是有意的：被档位拒掉的这一步照样计入本轮预算。与审批闸同一条口径
 * （迁移前就是「过预算闸记账、再进审批等待」），否则模型反复触发同一个被拒调用就能无限白跑。
 * <p>
 * 只收束有静态声明的工具。MCP 外部工具没有描述符（名字由外部 server 自定），平台无从知道哪一次
 * 调用会写东西——它在档位上的表达是审批（只读档建议 ask），不是隐藏。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(25)
public class PermissionGuard implements ToolGuard {

    @Override
    public Denial deny(ToolInvocation inv, ToolTurnContext turn) {
        ExecutionPolicy policy = turn.permissionPolicy();
        if (policy == null) return null;
        ToolDescriptor d = inv.descriptor();
        if (d == null) return null;
        if (policy.allowsTool(d)) return null;
        log.info("[PERM] 档位禁用的工具被调用，已拒绝: tool={} {} session={}",
                inv.toolName(), policy.describe(), turn.sessionId());
        // 文案按「本轮没有提供这一能力」写，而不是「权限不足」：前者让模型收尾，后者会让它
        // 掉头去问用户开权限、或换个工具试探同一件事——两种都在烧本轮预算。
        return new Denial("{\"error\":\"该能力本轮未提供（不是临时故障，重试无效，也无法通过其它工具绕行）。"
                + "请基于已有信息直接给出回答。\"}",
                "本轮权限档位「" + policy.preset().label() + "」未提供这一能力，未执行");
    }
}
