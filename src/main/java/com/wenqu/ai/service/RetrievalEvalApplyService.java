package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.RetrievalEvalRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数组灰度应用与自动回滚。
 * <p>「应用此组」不是裸写配置：先快照应用前参数，再写入观察窗口（默认 24h）；
 * 下一次体检/运行若核心指标相对应用时基线明显下滑，自动回滚并通知管理员。
 * 这样评估页推荐的最佳组不会因为一次误判把线上参数带偏。
 */
@Slf4j
@Service
public class RetrievalEvalApplyService {

    private static final String APPLY_KEY = "eval.lastApply";

    /** 参与灰度应用/回滚的参数清单（每组参数与线上配置同源）。
     * （rerank.enabled 不在此列：全局开关已退役，apply 不得再写全局键留下强制关的死配置行。） */
    private static final List<String> KEYS = List.of(
            "retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.vecThreshold",
            "retrieval.keywordLimit", "retrieval.vectorTopK",
            "retrieval.lowConfidencePolicy", "retrieval.top1Floor");

    private final ConfigService configService;
    private final NotificationService notificationService;

    public RetrievalEvalApplyService(ConfigService configService, NotificationService notificationService) {
        this.configService = configService;
        this.notificationService = notificationService;
    }

    /**
     * 应用某组参数并进入观察期。
     *
     * @param run           产生该组的运行
     * @param groupName     组名
     * @param params        组参数（字段名同 EvalParams，null=不覆盖）
     * @param baselineMetrics 该组运行时指标（用于观察期对比）
     */
    public Map<String, Object> apply(RetrievalEvalRun run, String groupName, Map<String, Object> params,
                                     Map<String, Object> baselineMetrics) {
        if (run == null) throw new BizException(404, "运行不存在");
        if (params == null || params.isEmpty()) throw new BizException("该组没有可应用的参数");
        Map<String, String> before = currentParams();
        Map<String, String> after = new LinkedHashMap<>(before);
        Map<String, Map<String, String>> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = mapKey(e.getKey());
            if (key == null || e.getValue() == null) continue;
            after.put(key, String.valueOf(e.getValue()));
            String[] parts = key.split("\\.", 2);
            payload.computeIfAbsent(parts[0], k -> new LinkedHashMap<>()).put(parts[1], String.valueOf(e.getValue()));
        }
        if (payload.isEmpty()) throw new BizException("该组没有可应用的参数");
        try {
            configService.update(payload);
        } catch (Exception e) {
            throw new BizException("应用参数失败：" + e.getMessage());
        }

        int hours = Math.max(1, configService.getInt("eval.applyObserveHours", 24));
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("runId", run.getId());
        record.put("groupName", groupName);
        record.put("before", before);
        record.put("after", after);
        record.put("changed", changedKeys(before, after));
        record.put("baselineMetrics", baselineMetrics == null ? Map.of() : baselineMetrics);
        record.put("appliedAt", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        record.put("deadline", now.plusHours(hours).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        record.put("observeHours", hours);
        record.put("status", "observing");
        configService.putInternal(APPLY_KEY, JSON.toJSONString(record));
        log.info("[Eval] 参数组「{}」已灰度应用（观察 {}h），changed={}", groupName, hours, record.get("changed"));
        return record;
    }

    /** 当前灰度应用状态；过期未复测时标记 expired（不改变配置）。 */
    public Map<String, Object> status() {
        Map<String, Object> record = parse(configService.get(APPLY_KEY));
        if (record == null) return null;
        Object deadline = record.get("deadline");
        if ("observing".equals(record.get("status")) && deadline != null) {
            try {
                LocalDateTime d = LocalDateTime.parse(String.valueOf(deadline),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                if (LocalDateTime.now().isAfter(d)) record.put("expired", true);
            } catch (Exception ignored) {
            }
        }
        return record;
    }

    /** 手动回滚（管理员从总览发起）。 */
    public Map<String, Object> rollback() {
        Map<String, Object> record = parse(configService.get(APPLY_KEY));
        if (record == null) throw new BizException("没有可回滚的灰度应用");
        rollbackConfig(record, "手动回滚");
        record.put("status", "rolledBack");
        record.put("message", "管理员手动回滚");
        configService.putInternal(APPLY_KEY, JSON.toJSONString(record));
        return record;
    }

    /**
     * 体检完成后观察：核心指标相对应用时基线跌幅超过阈值 → 自动回滚。
     * 没有基线指标/本期无指标的场合只确认观察期是否结束，不猜。
     */
    public void observe(Map<String, Object> report) {
        Map<String, Object> record = parse(configService.get(APPLY_KEY));
        if (record == null || !"observing".equals(record.get("status"))) return;
        if (report == null) return;
        double threshold = Math.max(1, configService.getDouble("eval.autoThresholdPct", 10.0));
        Map<String, Object> base = asMap(record.get("baselineMetrics"));
        Map<String, Object> now = asMap(report.get("metrics"));
        String declined = declineItem(base, now, threshold);
        if (declined != null) {
            rollbackConfig(record, "指标下滑 " + declined);
            record.put("status", "rolledBack");
            record.put("message", "自动回滚：" + declined);
            configService.putInternal(APPLY_KEY, JSON.toJSONString(record));
            try {
                notificationService.createForAdmins(Notification.TYPE_EVAL_DECLINE,
                        "检索参数已自动回滚",
                        "灰度应用「" + record.get("groupName") + "」后检测到 " + declined
                                + "，已恢复应用前参数。请前往「检索评估」复核。",
                        null, null);
            } catch (Exception e) {
                log.debug("[Eval] 回滚通知发送失败（不影响回滚）: {}", e.getMessage());
            }
            log.warn("[Eval] 灰度应用自动回滚：{}", declined);
            return;
        }
        if (observationEnded(record)) {
            record.put("status", "confirmed");
            record.put("message", "观察期结束，指标未显著下滑，参数保留");
            configService.putInternal(APPLY_KEY, JSON.toJSONString(record));
        }
    }

    private boolean observationEnded(Map<String, Object> record) {
        Object deadline = record.get("deadline");
        if (deadline == null) return false;
        try {
            LocalDateTime d = LocalDateTime.parse(String.valueOf(deadline),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return LocalDateTime.now().isAfter(d);
        } catch (Exception e) {
            return false;
        }
    }

    private String declineItem(Map<String, Object> base, Map<String, Object> now, double threshold) {
        if (base == null || base.isEmpty() || now == null || now.isEmpty()) return null;
        for (String key : List.of("recall@5", "MRR", "hitRate")) {
            Double b = num(base.get(key));
            Double n = num(now.get(key));
            if (b == null || n == null || b <= 0) continue;
            double rel = (n - b) / b * 100.0;
            if (rel < -threshold) {
                return key + " 较应用时 -" + Math.round(Math.abs(rel)) + "%（阈值 " + (long) threshold + "%）";
            }
        }
        return null;
    }

    private void rollbackConfig(Map<String, Object> record, String reason) {
        Map<String, Object> before = asMap(record.get("before"));
        if (before == null || before.isEmpty()) throw new BizException("缺少应用前参数快照，无法回滚");
        Map<String, Map<String, String>> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : before.entrySet()) {
            if (e.getValue() == null) continue;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length != 2) continue;
            payload.computeIfAbsent(parts[0], k -> new LinkedHashMap<>()).put(parts[1], String.valueOf(e.getValue()));
        }
        try {
            configService.update(payload);
            log.info("[Eval] 参数已回滚（{}）", reason);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 参数回滚失败: {}", e.getMessage());
            throw new BizException("回滚失败：" + e.getMessage());
        }
    }

    private Map<String, String> currentParams() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String key : KEYS) {
            String v = configService.get(key);
            m.put(key, v == null ? "" : v);
        }
        return m;
    }

    private static List<String> changedKeys(Map<String, String> before, Map<String, String> after) {
        return KEYS.stream()
                .filter(k -> !String.valueOf(before.get(k)).equals(String.valueOf(after.get(k))))
                .map(k -> k + "：" + zero(before.get(k)) + " → " + zero(after.get(k)))
                .toList();
    }

    private static String zero(String v) {
        return v == null || v.isBlank() ? "（空）" : v;
    }

    private static String mapKey(String field) {
        return switch (field) {
            case "vectorWeight" -> "retrieval.vectorWeight";
            case "keywordWeight" -> "retrieval.keywordWeight";
            case "vecThreshold" -> "retrieval.vecThreshold";
            case "keywordLimit" -> "retrieval.keywordLimit";
            case "topK" -> "retrieval.vectorTopK";
            case "lowConfidencePolicy" -> "retrieval.lowConfidencePolicy";
            case "top1Floor" -> "retrieval.top1Floor";
            default -> null;
        };
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static Double num(Object o) {
        if (o == null) return null;
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
