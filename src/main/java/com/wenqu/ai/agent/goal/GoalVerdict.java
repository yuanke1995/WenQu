package com.wenqu.ai.agent.goal;

import java.util.List;

/**
 * Verifier 的判定：通过则允许提交 COMPLETED；不通过则把 {@link #gaps} 写回 Continuation，
 * 成为下一轮的 Observation（2.6.2：「验证失败不是简单结束，而是新的 Observation」）。
 *
 * @param passed   是否所有强制检查通过
 * @param gaps     未通过的具体缺口（面向下一轮的可执行描述，不是“质量不够”这种空话）
 * @param summary  判定说明（写给审计/排障，不直接给用户）
 * @param verifier 判定器标识
 */
public record GoalVerdict(boolean passed, List<String> gaps, String summary, String verifier) {

    public static GoalVerdict pass(String summary, String verifier) {
        return new GoalVerdict(true, List.of(), summary, verifier);
    }

    public static GoalVerdict fail(List<String> gaps, String summary, String verifier) {
        return new GoalVerdict(false, gaps == null ? List.of() : List.copyOf(gaps), summary, verifier);
    }
}
