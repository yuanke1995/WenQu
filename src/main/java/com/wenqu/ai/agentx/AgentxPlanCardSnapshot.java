package com.wenqu.ai.agentx;

import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.agent.chat.ChatCommand;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.str;

/**
 * 计划卡快照的构建（从 AgentscopeChatEngine 抽出）。
 *
 * <p>接流/快照端点要把一张「正在跑的计划卡」完整重建给新页签：草稿/待确认/退回重出/已批准
 * 各阶段的字段、版本链与血缘都在这里拼。原先埋在引擎 77 行的方法体里，只依赖 sessions 与
 * 配置两个外部对象，与一轮的控制流无关——抽出来后引擎只负责在需要快照时调它。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxPlanCardSnapshot {

    private final FrameworkSessions sessions;
    private final ConfigService configService;

    public Map<String, Object> build(ChatCommand cmd, AgentxTurnRecording recording) {
        if (recording == null || !recording.hasPlanActivity()) return null;
        String sessionKey = sessions.key(cmd);
        String outcome = sessions.peekPlanOutcome(sessionKey);
        String revisionFeedback = sessions.peekPlanFeedback(sessionKey);
        boolean revising = sessions.isPlanRevising(sessionKey);
        long timeoutMs = configService.getInt("chat.planTimeoutMs", 3600000);
        java.util.List<Map<String, Object>> all = recording.planVersionsCopy();
        String draft = recording.planDraftText();

        Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("id", recording.planApprovalIdValue() == null ? "" : recording.planApprovalIdValue());
        card.put("auto", false);
        card.put("busy", false);
        card.put("editText", "");
        card.put("revising", false);
        card.put("revisingStarted", false);
        card.put("pendingFeedback", "");
        card.put("timeoutMs", timeoutMs);
        card.put("remainingMs", 0L);

        boolean finalOutcome = "approve".equals(outcome) || "supersede".equals(outcome)
                || "reject".equals(outcome);

        java.util.List<Map<String, Object>> prior = new java.util.ArrayList<>();
        if (finalOutcome) {
            if (all.isEmpty()) return null;
            Map<String, Object> last = all.get(all.size() - 1);
            for (int i = 0; i < all.size() - 1; i++) prior.add(AgentxPlanSteerUtil.planVersionOut(all.get(i)));
            card.put("status", "approve".equals(outcome) ? "approved"
                    : "supersede".equals(outcome) ? "superseded" : "rejected");
            card.put("plan", str(last.get("plan")));
            card.put("feedback", str(last.get("feedback")));
            card.put("versions", prior);
        } else if (!draft.isEmpty()) {
            for (Map<String, Object> v : all) prior.add(AgentxPlanSteerUtil.planVersionOut(v));
            card.put("status", "drafting");
            card.put("plan", draft);
            card.put("feedback", revising ? (revisionFeedback == null ? "" : revisionFeedback) : "");
            card.put("versions", prior);
            if (revising) {
                card.put("revising", true);
                card.put("revisingStarted", true);
                card.put("pendingFeedback", revisionFeedback == null ? "" : revisionFeedback);
                card.put("busy", true);
            }
        } else if (recording.planPendingNow() && !all.isEmpty()) {
            Map<String, Object> last = all.get(all.size() - 1);
            for (int i = 0; i < all.size() - 1; i++) prior.add(AgentxPlanSteerUtil.planVersionOut(all.get(i)));
            card.put("status", "pending");
            card.put("plan", str(last.get("plan")));
            card.put("feedback", str(last.get("feedback")));
            card.put("versions", prior);
            if (revising) {
                card.put("revising", true);
                card.put("revisingStarted", false);
                card.put("pendingFeedback", revisionFeedback == null ? "" : revisionFeedback);
                card.put("busy", true);
            } else {
                long remaining = Math.max(0L, recording.planPendingAtMs() + timeoutMs - System.currentTimeMillis());
                card.put("remainingMs", remaining);
            }
        } else {
            return null;
        }

        if (cmd.getPlanBaseId() != null && !cmd.getPlanBaseId().isBlank()) {
            Map<String, Object> from = new java.util.LinkedHashMap<>();
            from.put("cardId", cmd.getPlanBaseId());
            from.put("version", cmd.getPlanBaseVersion());
            from.put("done", cmd.getPlanBaseDone());
            from.put("total", cmd.getPlanBaseTotal());
            from.put("failed", cmd.getPlanBaseFailed());
            card.put("adjustFrom", from);
        }
        return card;    }
}
