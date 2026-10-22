package com.wenqu.ai.agent.goal;

import com.alibaba.fastjson2.JSON;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化 Outcome：目标结束（完成/失败/受阻）时写进 {@code c_ai_goal.outcome_json}。
 *
 * <p>对照 2.6.3 的形状：不是一句“已完成”，而是一组目标相关的事实——
 * {@code state / outcome / evidence / remaining_actions}。UI 的完成卡直接读它，
 * 不再从聊天正文里猜“它到底交付了什么”。
 *
 * @author yuanke
 */
@Data
public class GoalOutcome {

    /** COMPLETED / FAILED / BLOCKED / CANCELLED */
    private String state;

    /** 细分原因：achieved / verified / budget / no-progress / orphaned / blocked / failed / cancelled */
    private String code;

    /** 交付说明 */
    private String summary;

    /** 证据引用（已核验通过的） */
    private List<GoalProposal.Item> evidence = new ArrayList<>();

    /** 未完成动作/缺口 */
    private List<String> remaining = new ArrayList<>();

    /** 谁提交的（verifier 名称；人工审批时为 human:{uid}） */
    private String verifier;

    private LocalDateTime at;

    public static GoalOutcome of(String state, String code, String summary) {
        GoalOutcome o = new GoalOutcome();
        o.setState(state);
        o.setCode(code);
        o.setSummary(summary);
        o.setAt(LocalDateTime.now());
        return o;
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    public static GoalOutcome fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseObject(json, GoalOutcome.class);
        } catch (Exception e) {
            return null;
        }
    }
}
