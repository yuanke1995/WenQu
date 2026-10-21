package com.wenqu.ai.service;

import com.wenqu.ai.model.Goal;
import io.agentscope.core.state.TaskVerification;
import io.agentscope.harness.agent.observation.StoredActionObservation;
import io.agentscope.harness.agent.verification.DeterministicVerifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工核验器：观感、语气、口径这类无法机器判定的标准（目标模式分岔 3 取 A）。
 *
 * <p>读的是用户在目标卡上点的勾选（{@code c_ai_goal.verifications_json} 里那条 manual 记录）——
 * <b>点之前 outcome 一律 UNKNOWN</b>，不替用户判断「看起来差不多了」。
 *
 * <p>为什么它也走 {@code recordVerification}：只要走这条路径，人工结论就有凭据、有绑定、
 * 有 STALE 语义（工件改了一样作废），不破坏「完成由证据决定」这条总纪律——
 * 人工勾选只是一种 verifier，不是绕过验收的后门。
 *
 * <p>它仍然是 {@link DeterministicVerifier}（同步、有界、无副作用），不是 LLM 判定：
 * 本类不做任何语义判断，只搬运用户已经做出的决定。
 */
public final class ManualCheckVerifier implements DeterministicVerifier {

    /** 验证器ID（落进 TaskVerification.verifierId 与界面） */
    public static final String ID = "manual-check-v1";

    private final Goal goal;
    private final String criterionKey;

    public ManualCheckVerifier(Goal goal, String criterionKey) {
        this.goal = goal;
        this.criterionKey = criterionKey;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Verdict verify(StoredActionObservation evidence) {
        Map<String, Object> verdict = GoalRecords.manualVerdict(goal, criterionKey);
        if (verdict == null) {
            return new Verdict(TaskVerification.Outcome.UNKNOWN, "等待人工核验：用户还没有勾选这条标准");
        }
        String outcome = String.valueOf(verdict.getOrDefault("outcome", "UNKNOWN"));
        String reason = String.valueOf(verdict.getOrDefault("reason", "用户勾选"));
        TaskVerification.Outcome o = switch (outcome) {
            case "PASSED" -> TaskVerification.Outcome.PASSED;
            case "FAILED" -> TaskVerification.Outcome.FAILED;
            case "ERROR" -> TaskVerification.Outcome.ERROR;
            default -> TaskVerification.Outcome.UNKNOWN;
        };
        return new Verdict(o, "人工核验：" + reason);
    }

    /** 人工勾选落库时的形状（GoalService.markManual 用） */
    public static Map<String, Object> verdict(String outcome, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome);
        m.put("reason", reason == null || reason.isBlank() ? "用户勾选" : reason);
        m.put("manual", true);
        m.put("recordedAt", System.currentTimeMillis());
        return m;
    }
}
