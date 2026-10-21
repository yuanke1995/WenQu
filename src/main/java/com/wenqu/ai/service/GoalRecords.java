package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.wenqu.ai.model.Goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标的标准/验证记录的读写（<b>纯静态</b>）。
 *
 * <p>为什么抽成静态工具而不是挂在某个 bean 上：验收注册表要读「用户勾没勾这条」，
 * 目标服务又要写它——两者互相依赖就成了 Bean 循环（Spring 直接启动失败）。
 * 静态方法不需要 bean 引用，环自然断掉；顺带也让「记录怎么存」只有一份实现。
 *
 * <p>形状说明：{@code verifications_json} 存的是「每条标准最新一份验证」，
 * 元素形如 {@code {criterionKey, outcome, reason, verifierId, actionId, recordedAt, manual, binding:{contractVersion,subjectVersion}}}。
 */
final class GoalRecords {

    private GoalRecords() {}

    /** 读整份验证记录（解析失败按空处理：坏数据不许让整张卡消失） */
    static List<Map<String, Object>> readVerifications(Goal goal) {
        if (goal == null || goal.getVerificationsJson() == null || goal.getVerificationsJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> rows = JSON.parseObject(goal.getVerificationsJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            return rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 某条标准上的最新一份验证记录（null=还没验过） */
    static Map<String, Object> latestVerification(Goal goal, String criterionKey) {
        if (criterionKey == null) return null;
        for (Map<String, Object> r : readVerifications(goal)) {
            if (criterionKey.equals(String.valueOf(r.get("criterionKey")))) return r;
        }
        return null;
    }

    /** 人工勾选的那一份（null=用户还没点） */
    static Map<String, Object> manualVerdict(Goal goal, String criterionKey) {
        Map<String, Object> v = latestVerification(goal, criterionKey);
        return v != null && Boolean.TRUE.equals(v.get("manual")) ? v : null;
    }

    /** 写一条验证记录（同 key 覆盖：每条标准只留最新一份） */
    static void upsertVerification(Goal goal, String criterionKey, Map<String, Object> result) {
        List<Map<String, Object>> rows = readVerifications(goal);
        rows.removeIf(r -> criterionKey.equals(String.valueOf(r.get("criterionKey"))));
        Map<String, Object> row = new LinkedHashMap<>(result);
        row.put("criterionKey", criterionKey);
        rows.add(row);
        goal.setVerificationsJson(JSON.toJSONString(rows));
    }

    /**
     * 结论是否过期：绑定水位与当前目标态不一致（工件改过/要求改过都算）。
     * <p>框架那边的判据是「验证记录的 binding ≠ 当前 getEvidenceBinding()」——
     * 问渠这边用库里存的 contractVersion/subjectVersion 对账（同一份口径的镜像）。
     */
    static boolean isStale(Goal goal, Map<String, Object> v) {
        if (goal == null || v == null) return false;
        Object binding = v.get("binding");
        if (!(binding instanceof Map<?, ?> b)) return false;
        Object cv = b.get("contractVersion");
        Object sv = b.get("subjectVersion");
        if (cv instanceof Number n && goal.getContractVersion() != null
                && n.longValue() != goal.getContractVersion()) {
            return true;
        }
        return sv != null && goal.getSubjectVersion() != null
                && !String.valueOf(sv).equals(goal.getSubjectVersion());
    }
}
