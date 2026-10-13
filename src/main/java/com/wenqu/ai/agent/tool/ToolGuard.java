package com.wenqu.ai.agent.tool;

/**
 * 工具守卫（单调：只否决，不放行）。
 * <p>
 * 「单调」是这条链的全部要点：实现返回一个 {@link Denial} 即拒绝执行，返回 null 只是「本条没意见」，
 * 永远不可能出现「前面的守卫拒了、后面的守卫给批了」。这跟人在回路的审批是两回事——
 * 审批是**问人**（{@link ToolApprover}），守卫是**平台自己的不可协商闸**（本轮已被叫停、
 * 步数预算用尽）。把两者混进一个返回 boolean 的接口，迟早有人写出一条「审批通过所以放行」的守卫，
 * 那等于让一个监听器的顺序翻掉另一个的否决。
 * <p>
 * 链的顺序由 {@code @Order} 决定，靠前的先判。迁移前这段逻辑内联在
 * {@code ChatService.instrumentTools} 的匿名类里（叫停闸 → 步数闸 → 记账 → 审批），
 * 抽出来之后工作流节点与技能那两条现在完全绕过守卫的旁路（{@code WorkflowEngine.mcpBody}/
 * {@code skillBody}）才可能复用同一批闸。
 *
 * @author yuanke
 */
public interface ToolGuard {

    /**
     * @return 非 null = 拒绝执行；null = 放行（本条无意见）
     */
    Denial deny(ToolInvocation inv, ToolTurnContext turn);

    /**
     * 一次拒绝的两份文本。
     * <p>
     * 为什么必须分开：{@code modelPayload} 是回给模型的**工具结果**，要写成可执行的处置指令
     * （「请基于已有信息直接给出最终回答」），一句裸的「forbidden」只会让模型换个工具接着试；
     * {@code userSummary} 是工具卡片上给用户看的一句话，写成 JSON 载荷用户读不懂。
     * 迁移前这两份文本手抄在匿名类里两处，改一处忘另一处就会出现「卡片说成功、模型收到错误」。
     */
    record Denial(String modelPayload, String userSummary) {
    }
}
