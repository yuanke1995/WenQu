package com.wenqu.ai.agentx;

import com.wenqu.ai.service.GoalService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 目标模式的两把控制工具：{@code goal_note} 记录权威状态，{@code goal_submit} 提交完成/受阻申请。
 *
 * <p>为什么用工具而不是继续解析回答最后一行：marker 只能表达「我做完了」，留不下证据、计划、
 * 失败尝试与缺口；而跨轮续跑需要的恰恰是这些结构化事实。工具调用进框架的事件账本，也能被
 * Verifier 与审计复用。
 *
 * <p>两把工具常驻注册（框架实例按会话常驻，不能等目标建立后再加工具），执行时按
 * {@link AgentxTurnSlot} 的当前轮号判断是不是目标轮；非目标轮返回一句「没有进行中的目标」，
 * 不产生副作用。
 *
 * @author yuanke
 */
public class GoalTools {

    private final GoalService goals;
    private final AgentxTurnSlot slot;

    public GoalTools(GoalService goals, AgentxTurnSlot slot) {
        this.goals = goals;
        this.slot = slot;
    }

    @Tool(name = "goal_note",
            description = "记录目标推进的权威状态（仅目标模式使用）：计划、已确认事实、产物、阶段。"
                    + "跨轮续跑只认这份状态，不认聊天历史；每轮有实际进展就调用一次。"
                    + "参数都是 JSON 字符串数组或普通文本（数组元素是字符串）。")
    public String note(
            @ToolParam(name = "plan", required = false,
                    description = "当前计划（JSON 字符串数组，如 [\"确认范围\",\"实现\",\"验证\"]）") String plan,
            @ToolParam(name = "facts", required = false,
                    description = "本轮新确认的事实（JSON 字符串数组）") String facts,
            @ToolParam(name = "artifacts", required = false,
                    description = "本轮产生/更新的产物文件名或产物ID（JSON 字符串数组）") String artifacts,
            @ToolParam(name = "stage", required = false,
                    description = "阶段：EXPLORE/PLAN/EXECUTE/VERIFY/DELIVER，可选") String stage,
            @ToolParam(name = "summary", required = false,
                    description = "这一轮的交付说明（一句话）") String summary,
            @ToolParam(name = "remaining", required = false,
                    description = "尚未完成/还差的缺口（JSON 字符串数组）；完成检查轮里用它回答“未完成”") String remaining) {
        return goals.recordNote(turnId(), plan, facts, artifacts, stage, summary, remaining);
    }

    @Tool(name = "goal_submit",
            description = "提交目标完成/受阻申请（仅目标模式使用）。平台会逐条核验证据后才算完成，"
                    + "不会仅凭这句话标记完成。evidence 必须是 JSON 数组，元素形如 "
                    + "{\"kind\":\"artifact\",\"ref\":\"报告.pdf\",\"note\":\"最终交付物\"}；"
                    + "kind 可用 artifact/file/command/test/url/text。做不完不要调用完成分支。")
    public String submit(
            @ToolParam(name = "summary", description = "交付说明（给人读的摘要）") String summary,
            @ToolParam(name = "evidence", required = false,
                    description = "证据 JSON 数组字符串；artifact/file 优先，command/test 需带 exitCode") String evidence,
            @ToolParam(name = "remaining", required = false,
                    description = "尚未完成的动作/缺口（JSON 字符串数组）") String remaining,
            @ToolParam(name = "blocked", required = false,
                    description = "true=受阻，false/不传=申请完成") Boolean blocked,
            @ToolParam(name = "blockedReason", required = false,
                    description = "受阻原因（blocked=true 时）") String blockedReason) {
        return goals.recordProposal(turnId(), summary, evidence, remaining, blocked, blockedReason);
    }

    /** 当前轮的 {@code goal:{id}:{n}}；用户轮是普通 UUID，服务端会据此拒绝。 */
    private String turnId() {
        AgentxTurnSlot.Turn t = slot.current();
        return t == null ? null : t.turnId();
    }
}
