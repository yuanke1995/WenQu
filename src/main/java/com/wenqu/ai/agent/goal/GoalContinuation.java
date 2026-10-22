package com.wenqu.ai.agent.goal;

import com.alibaba.fastjson2.JSON;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 续跑上下文：跨轮、跨进程、跨上下文窗口的权威任务视图。
 *
 * <p>这是 2.4.1「Continuation 应包含目标、当前 Plan、已确认事实、失败尝试、Artifact、
 * 等待项、剩余预算和权限模式」在问渠这一侧的落点。每轮 prompt 由它生成，而不是靠聊天历史
 * 或压缩摘要让模型猜进度。
 *
 * <p>写入方只有两个：模型通过 {@code goal_note} 显式记录（计划/事实/产物/阶段），
 * Verifier 在失败时写回缺口。系统在每轮收尾时自动补产物清单。列表都有上限，
 * 避免 40 轮后把 prompt 撑爆——保留最近的内容，旧内容由计划/摘要承载。
 *
 * @author yuanke
 */
@Data
public class GoalContinuation {

    /** 目标原文（与 Goal.objective 同步；edit 时一起改） */
    private String objective;

    /** 目标版本（edit 递增） */
    private int objectiveVersion;

    /** 当前阶段：EXPLORE / PLAN / EXECUTE / VERIFY / DELIVER */
    private String stage = "EXECUTE";

    /** 当前计划（有序步骤，模型可改写） */
    private List<String> plan = new ArrayList<>();

    /** 已确认事实（模型显式记录 + 系统补充） */
    private List<String> facts = new ArrayList<>();

    /** 已交付产物（文件名/产物ID/路径） */
    private List<String> artifacts = new ArrayList<>();

    /** 失败尝试与原因（保留最近若干条，避免重复踩坑） */
    private List<String> failedAttempts = new ArrayList<>();

    /** Verifier 给出的未通过缺口（下一轮必须处理） */
    private List<String> gaps = new ArrayList<>();

    /** 正在等待什么（WAITING_* 用） */
    private String waiting;

    /** 上一轮交付说明（给下一轮一个继续点） */
    private String lastSummary;

    /** 连续无进展轮数（与 Goal 同步，便于 prompt/诊断） */
    private int noProgressRounds;

    /** 是否要求下一轮先重规划（无进展/计划被推翻时置位） */
    private boolean forceReplan;

    /**
     * 下一轮是不是「完成检查轮」：模型已经交付产物并声称完成，但没有调用 goal_submit。
     * 检查轮禁止做新工作，只允许 goal_submit 或 goal_note(remaining)，用一次便宜的模型调用
     * 把「完成/未完成」问清楚，而不是再跑一整轮。
     */
    private boolean completionCheck;

    /** 连续完成检查次数：超过上限仍不提交就转 PAUSED，避免无限检查。 */
    private int completionCheckCount;

    /**
     * 上一轮开始时的进展指纹（计划/事实/产物/缺口的摘要）。
     * 收尾时与当前指纹比较：一样且没有新产物 = 这一轮没有实质进展。
     */
    private String progressHash;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /** 列表上限：太多行会把 prompt 变成流水账，保留最近的部分即可。 */
    public static final int MAX_LIST = 24;
    public static final int MAX_TEXT = 400;

    public static GoalContinuation fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            GoalContinuation c = JSON.parseObject(json, GoalContinuation.class);
            return c == null ? null : c.normalized();
        } catch (Exception e) {
            return null;
        }
    }

    public String toJson() {
        return JSON.toJSONString(normalized());
    }

    /** 截断/去重，保证 prompt 与存储都有界。 */
    public GoalContinuation normalized() {
        objective = clip(objective, 4000);
        stage = stage == null || stage.isBlank() ? "EXECUTE" : stage;
        plan = normalizeList(plan);
        facts = normalizeList(facts);
        artifacts = normalizeList(artifacts);
        failedAttempts = normalizeList(failedAttempts);
        gaps = normalizeList(gaps);
        waiting = clip(waiting, 500);
        lastSummary = clip(lastSummary, 2000);
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        return this;
    }

    /** 合并模型这一轮记录的事实/计划/产物；保留旧内容并去重。 */
    public void merge(List<String> newPlan, List<String> newFacts, List<String> newArtifacts,
                      String newStage, String summary) {
        if (newPlan != null && !newPlan.isEmpty()) {
            plan = mergeList(plan, newPlan);
        }
        if (newFacts != null && !newFacts.isEmpty()) {
            facts = mergeList(facts, newFacts);
        }
        if (newArtifacts != null && !newArtifacts.isEmpty()) {
            artifacts = mergeList(artifacts, newArtifacts);
        }
        if (newStage != null && !newStage.isBlank()) {
            stage = newStage.trim();
        }
        if (summary != null && !summary.isBlank()) {
            lastSummary = clip(summary, 2000);
        }
        updatedAt = LocalDateTime.now();
        normalized();
    }

    private static List<String> normalizeList(List<String> in) {
        if (in == null) return new ArrayList<>();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            String v = clip(s, MAX_TEXT);
            if (v != null && !v.isBlank()) out.add(v);
        }
        List<String> list = new ArrayList<>(out);
        if (list.size() > MAX_LIST) list = new ArrayList<>(list.subList(list.size() - MAX_LIST, list.size()));
        return list;
    }

    private static List<String> mergeList(List<String> old, List<String> add) {
        LinkedHashSet<String> set = new LinkedHashSet<>(old == null ? List.of() : old);
        for (String s : add) {
            String v = clip(s, MAX_TEXT);
            if (v != null && !v.isBlank()) set.add(v);
        }
        List<String> list = new ArrayList<>(set);
        if (list.size() > MAX_LIST) list = new ArrayList<>(list.subList(list.size() - MAX_LIST, list.size()));
        return list;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String v = s.strip();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    /** 给 prompt 的一段紧凑视图；没有内容的段直接省略。 */
    public String render() {
        normalized();
        StringBuilder b = new StringBuilder();
        b.append("阶段：").append(stage).append('\n');
        appendList(b, "计划", plan);
        appendList(b, "已完成/已确认事实", facts);
        appendList(b, "产物", artifacts);
        appendList(b, "失败尝试（不要重复）", failedAttempts);
        appendList(b, "待补缺口", gaps);
        if (waiting != null && !waiting.isBlank()) b.append("等待：").append(waiting).append('\n');
        if (lastSummary != null && !lastSummary.isBlank()) {
            b.append("上一轮交付：").append(lastSummary).append('\n');
        }
        if (forceReplan) b.append("注意：上一轮没有实质进展，本轮先重新规划再动手。\n");
        return b.toString();
    }

    private static void appendList(StringBuilder b, String title, List<String> list) {
        if (list == null || list.isEmpty()) return;
        b.append(title).append("：\n");
        int i = 1;
        for (String s : list) b.append("  ").append(i++).append(". ").append(s).append('\n');
    }

    /** 空上下文（新目标）。 */
    public static GoalContinuation of(String objective) {
        GoalContinuation c = new GoalContinuation();
        c.setObjective(objective);
        c.setStage("EXECUTE");
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    /** 去重后的产物集（供进展检测）。 */
    public Set<String> artifactSet() {
        return new LinkedHashSet<>(artifacts == null ? List.of() : artifacts);
    }
}
