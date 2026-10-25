package com.wenqu.ai.agent.tool.guard;

import com.wenqu.ai.agent.tool.ToolGuard;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 步数预算闸（守卫链第二条）：防模型陷入「调工具 → 不满意 → 再调」的失控循环烧 token。
 * <p>
 * 达上限时返回错误结果并要求模型直接作答，是 fail-safe 而不是无限放行——这条闸同时也是账单闸：
 * 没有它，一次失控循环能把整轮的 token 预算花光（成本记账在路由出口，拦不住就是真实损失）。
 * <p>
 * <b>放行即记账</b>：判定通过后由本守卫直接占掉一步（{@code turn.takeStep()}），与迁移前
 * 「过闸即计数、审批被拒的那一步同样计入预算」的时序保持一致。之所以不挪到管线里另一处计数：
 * 计数点与判定点一分家，「审批被拒算不算用掉一步」就变成两条代码各自的理解，实测表现是
 * 上限 15 步的会话实际跑了三十几次工具调用。
 */
@Slf4j
@Component
@Order(20)
public class StepBudgetGuard implements ToolGuard {

    @Override
    public Denial deny(ToolInvocation inv, ToolTurnContext turn) {
        int max = turn.maxToolSteps();
        if (max > 0 && turn.stepsTaken() >= max) {
            log.warn("[TOOL] 达到单轮步数上限({})，拒绝继续调用: tool={} session={}",
                    max, inv.toolName(), turn.sessionId());
            return new Denial("{\"error\":\"已达到本轮工具调用步数上限(" + max + ")，不再执行工具调用。"
                    + "请基于已有信息直接给出最终回答。\"}",
                    "已达单轮工具调用步数上限(" + max + ")，发一句「继续」可以接着做");
        }
        turn.takeStep();
        return null;
    }
}
