package com.wenqu.ai.agent.goal;

import com.alibaba.fastjson2.JSON;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型的一轮「完成申请」（{@code goal_submit} 的落点）。
 *
 * <p>它只是申请：{@code blocked=true} 表示自报受阻，{@code blocked=false} 表示自报完成；
 * 两者都不会直接改终态，要由 {@link GoalVerifier} 依据证据提交 Outcome。
 * 保留 {@code remaining} 让「只完成了一部分」也能被结构化记录，而不是一句“没做完”。
 *
 * @author yuanke
 */
@Data
public class GoalProposal {

    /** 交付说明（给人读的摘要，不是证据本身） */
    private String summary;

    /** 证据列表 */
    private List<Item> evidence = new ArrayList<>();

    /** 尚未完成的动作（部分交付/受阻时必填） */
    private List<String> remaining = new ArrayList<>();

    /** 自报受阻 */
    private boolean blocked;

    /** 受阻原因（blocked=true 时） */
    private String blockedReason;

    /** 提交轮次（由服务端补，模型不用填） */
    private Integer roundN;

    /** 一条证据。kind 见 {@link com.wenqu.ai.model.GoalEvidence} 常量。 */
    @Data
    public static class Item {
        private String kind;
        private String ref;
        private String note;
        /** command/test 的退出码；模型自报，Verifier 会按 kind 决定信不信 */
        private Integer exitCode;

        public Item() {
        }

        public Item(String kind, String ref, String note) {
            this.kind = kind;
            this.ref = ref;
            this.note = note;
        }
    }

    public static GoalProposal fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            GoalProposal p = JSON.parseObject(json, GoalProposal.class);
            if (p == null) return null;
            if (p.evidence == null) p.evidence = new ArrayList<>();
            if (p.remaining == null) p.remaining = new ArrayList<>();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    public boolean hasEvidence() {
        return evidence != null && !evidence.isEmpty();
    }
}
