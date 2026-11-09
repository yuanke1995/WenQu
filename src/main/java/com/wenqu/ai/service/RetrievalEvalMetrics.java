package com.wenqu.ai.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 检索评估指标计算（纯函数，便于单测）。
 * <p>除均值外提供 bootstrap 置信区间：小评估集上的 ±0.02 可能只是噪声，
 * 界面用区间提示「变化是否显著」，避免把样本波动当成检索质量涨跌。
 */
public final class RetrievalEvalMetrics {

    private RetrievalEvalMetrics() {
    }

    /** 聚合 recall@k / MRR / 命中率（与既有口径一致）。 */
    public static Map<String, Double> aggregate(List<RetrievalEvaluationService.CaseResult> cases, List<Integer> ks) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (cases == null || cases.isEmpty()) return out;
        for (int k : ks) {
            double avg = cases.stream()
                    .mapToDouble(c -> c.recallAtK().getOrDefault("recall@" + k, 0.0))
                    .average().orElse(0);
            out.put("recall@" + k, round3(avg));
        }
        double avgMrr = cases.stream().mapToDouble(RetrievalEvaluationService.CaseResult::mrr).average().orElse(0);
        out.put("MRR", round3(avgMrr));
        long hit = cases.stream().filter(c -> c.mrr() > 0).count();
        out.put("hitRate", round3((double) hit / cases.size()));
        return out;
    }

    /**
     * 每个指标 95% bootstrap 置信区间（下界/上界）。
     * iterations 取 400：25 条样本下足够稳定，又不至于拖慢一次 8 组实验。
     */
    public static Map<String, List<Double>> bootstrapCi(List<RetrievalEvaluationService.CaseResult> cases,
                                                       List<Integer> ks, int iterations) {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        if (cases == null || cases.size() < 2) return out;
        int n = cases.size();
        int rounds = Math.max(80, iterations);
        double[][] recall = new double[ks.size()][n];
        double[] mrr = new double[n];
        double[] hit = new double[n];
        for (int i = 0; i < n; i++) {
            RetrievalEvaluationService.CaseResult c = cases.get(i);
            for (int j = 0; j < ks.size(); j++) {
                recall[j][i] = c.recallAtK().getOrDefault("recall@" + ks.get(j), 0.0);
            }
            mrr[i] = c.mrr();
            hit[i] = c.mrr() > 0 ? 1 : 0;
        }
        for (int j = 0; j < ks.size(); j++) {
            out.put("recall@" + ks.get(j), ci(recall[j], rounds));
        }
        out.put("MRR", ci(mrr, rounds));
        out.put("hitRate", ci(hit, rounds));
        return out;
    }

    private static List<Double> ci(double[] values, int rounds) {
        int n = values.length;
        double[] means = new double[rounds];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int r = 0; r < rounds; r++) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += values[rnd.nextInt(n)];
            means[r] = sum / n;
        }
        java.util.Arrays.sort(means);
        int lo = (int) Math.floor((rounds - 1) * 0.025);
        int hi = (int) Math.ceil((rounds - 1) * 0.975);
        return List.of(round3(means[lo]), round3(means[hi]));
    }

    /**
     * 按分群聚合：caseToSegment 为空时返回空表。
     * 返回 [{segment, caseCount, metrics}]，按段名排序。
     */
    public static List<Map<String, Object>> segments(List<RetrievalEvaluationService.CaseResult> cases,
                                                     Map<String, String> caseToSegment, List<Integer> ks) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (cases == null || cases.isEmpty() || caseToSegment == null || caseToSegment.isEmpty()) return out;
        Map<String, List<RetrievalEvaluationService.CaseResult>> by = new LinkedHashMap<>();
        for (RetrievalEvaluationService.CaseResult c : cases) {
            String seg = caseToSegment.getOrDefault(c.id(), "未分类");
            if (seg == null || seg.isBlank()) seg = "未分类";
            by.computeIfAbsent(seg, k -> new ArrayList<>()).add(c);
        }
        by.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("segment", e.getKey());
                    row.put("caseCount", e.getValue().size());
                    row.put("metrics", aggregate(e.getValue(), ks));
                    out.add(row);
                });
        return out;
    }

    /** 两次运行的排名对比：0=未命中。 */
    public static String verdict(int aRank, int bRank) {
        if (aRank <= 0 && bRank <= 0) return "flat";
        if (aRank <= 0) return "better";
        if (bRank <= 0) return "worse";
        if (bRank < aRank) return "better";
        if (bRank > aRank) return "worse";
        return "flat";
    }

    /**
     * 评估集质量分（0-100）：人工确认 45% + 有期望标签 35% + 失效标签惩罚 20%。
     * 这不是「检索质量」，而是「这把尺子有多可信」，界面分开显示。
     */
    public static int qualityScore(int total, int verified, int withExpected, int stale) {
        if (total <= 0) return 0;
        double verifiedRatio = (double) verified / total;
        double expectedRatio = (double) withExpected / total;
        double staleRatio = Math.min(1.0, (double) stale / total);
        double score = 100 * (0.45 * verifiedRatio + 0.35 * expectedRatio + 0.20 * (1 - staleRatio));
        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    public static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
