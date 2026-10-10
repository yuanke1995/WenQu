package com.wenqu.ai.agent.tool.guard;

import com.wenqu.ai.agent.tool.ToolGuard;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 叫停闸（守卫链第一条）：用户已经喊停本轮，就一步工具都不再执行。
 * <p>
 * 放在最前是有意的：副作用工具（沙盒命令、写文件、外部计费搜索）在叫停后仍被放行，是「停止」这个
 * 用户动作最严重的失效形态——正文停了、账也停了，命令还在跑。
 * <p>
 * 回给模型的文本同时是处置指令（「不再执行任何工具」），不只是错误码：模型据此收尾，而不是换个
 * 工具接着试、把终止后的这一步也算进预算。
 */
@Component
@Order(10)
public class UserStoppedGuard implements ToolGuard {

    @Override
    public Denial deny(ToolInvocation inv, ToolTurnContext turn) {
        if (!turn.userStopped()) return null;
        return new Denial("{\"error\":\"本轮已被用户停止，不再执行任何工具。\"}", "本轮已被用户停止");
    }
}
