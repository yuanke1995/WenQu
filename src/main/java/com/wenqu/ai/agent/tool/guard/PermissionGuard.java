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
 * 拦的是「本轮不该存在的工具却要被执行」。正常路径下这类工具在装配阶段就没进模型的工具表
 * （{@code SandboxToolProvider.exposes} 与 {@code McpToolProvider} 已经问过同一份策略），所以本闸今天拦到的
 * 只有两种情况：绕过装配的裸调（工作流节点那一类），以及装配与执行之间策略发生变化的轮次。
 * 判据与装配同源这一点是全部的要点：两处各写一份，就会漂出「挂着的被拦、没挂的反能跑」，
 * 那种错配从回答里完全看不出来，只能靠逐字比对工具表。
 * <p>
 * 排在步数闸<b>之后</b>是有意的：被拒的这一步照样计入本轮预算。与审批闸同一条口径
 * （迁移前就是「过预算闸记账、再进审批等待」），否则模型反复触发同一个被拒调用就能无限白跑。
 * <p>
 * 两条判据的覆盖面不同：按名黑名单对<b>全部</b>工具生效（含 MCP 动态工具），档位裁剪只对有静态声明的
 * 工具生效——外部工具的性质平台无从知道，档位在它身上的表达是审批而不是隐藏。
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
        // ① 按名黑名单：静态描述符与 MCP 动态工具都走这条（后者压根没有描述符，档位裁剪不到它）
        if (policy.denies(inv.toolName())) {
            log.info("[PERM] 工具被本轮黑名单禁用: tool={} session={}", inv.toolName(), turn.sessionId());
            return denial();
        }
        ToolDescriptor d = inv.descriptor();
        if (d == null) return null;
        // ② 档位裁剪（只对有声明的工具做）
        if (policy.allowsTool(d)) return null;
        log.info("[PERM] 档位禁用的工具被调用，已拒绝: tool={} {} session={}",
                inv.toolName(), policy.describe(), turn.sessionId());
        return denial();
    }

    /**
     * 两份文本合一处。文案按「本轮没有提供这一能力」写，而不是「权限不足」：前者让模型收尾，后者会让它
     * 掉头去问用户开权限、或换个工具试探同一件事——两种都在烧本轮预算。
     */
    private static Denial denial() {
        return new Denial("{\"error\":\"该能力本轮未提供（不是临时故障，重试无效，也无法通过其它工具绕行）。"
                + "请基于已有信息直接给出回答。\"}",
                "本轮未提供这一能力，未执行");
    }
}
