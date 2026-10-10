package com.wenqu.ai.agent.tool.guard;

import com.wenqu.ai.agent.tool.ToolApprover;
import com.wenqu.ai.agent.tool.ToolGuard;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 审批闸（守卫链第三条，排在预算闸之后：与迁移前「过预算闸记账、再进审批等待」的时序一致，
 * 被拒的这一步照样计入预算，否则模型反复触发同一个被拒调用可以无限白跑）。
 * <p>
 * 它名义上挂在守卫链里、实际是**问人**而不是平台裁决，之所以不单独开一条流程：调用点的判断
 * 「这次要不要问人」需要的信息（是否审批模式、该工具是否算有副作用）与守卫完全同源，
 * 分开放就会两处各判一次、迟早不一致。真正的阻塞与通知、落库都在 {@link ToolApprover} 里，
 * 本类只做「要不要问、问完怎么定性」。
 * <p>
 * 三种问不到的处理（都不代用户批准）：
 * <ul>
 *   <li>{@code approver()==null}：本轮压根没有可应答的通道（后台续跑、工作流节点）→ 直接拒，
 *       理由写明「无人可确认」，而不是让模型以为工具坏了反复重试；</li>
 *   <li>超时：按拒绝（默认 120s，见 {@code chat.approvalTimeoutMs}）；</li>
 *   <li>叫停：本轮已停，等待中止。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(30)
public class ApprovalGate implements ToolGuard {

    private static final String DENY_PAYLOAD =
            "{\"error\":\"该工具调用未被用户批准（拒绝或确认超时），未执行。"
                    + "请基于已有信息继续回答，不要重复尝试调用该工具。\"}";

    @Override
    public ToolGuard.Denial deny(ToolInvocation inv, ToolTurnContext turn) {
        String mode = turn.approvalMode();
        if (mode == null || !"ask".equalsIgnoreCase(mode.trim())) return null;
        if (!turn.requiresApproval(inv.toolName())) return null;

        ToolApprover approver = turn.approver();
        if (approver == null) {
            log.info("[TOOL] 本轮无可应答通道，有副作用工具按未批准处理: tool={} session={}",
                    inv.toolName(), turn.sessionId());
            return new Denial("{\"error\":\"本轮没有可确认工具执行的用户界面（后台执行），"
                    + "该调用未执行。请改用无副作用的方式继续回答。\"}", "后台执行无人确认，未执行");
        }
        ToolApprover.Outcome outcome = approver.approve(inv, turn.approvalTimeoutMs());
        if (outcome == ToolApprover.Outcome.ALLOWED) return null;
        String summary = switch (outcome) {
            case REJECTED -> "用户拒绝执行";
            case TIMED_OUT -> "确认超时，未执行";
            case CANCELLED -> "本轮已被用户停止";
            case UNAVAILABLE -> "无人可确认，未执行";
            case ALLOWED -> "";
        };
        log.info("[TOOL] 审批未通过: tool={} outcome={} session={}", inv.toolName(), outcome, turn.sessionId());
        return new Denial(DENY_PAYLOAD, summary.isEmpty() ? "用户拒绝或确认超时，未执行" : summary);
    }
}
