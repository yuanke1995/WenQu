package com.wenqu.ai.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvalMetricsTest {

    private static RetrievalEvaluationService.CaseResult caseResult(String id, List<String> expected,
                                                                   List<String> hitIds) {
        List<RetrievalEvaluationService.HitInfo> hits = hitIds.stream()
                .map(h -> new RetrievalEvaluationService.HitInfo(h, "d", h, 0.9))
                .toList();
        Map<String, Double> recall = new LinkedHashMap<>();
        int first = 0;
        double mrr = 0;
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            if (expected.contains(hits.get(i).knowledgeId())) {
                ranks.putIfAbsent(hits.get(i).knowledgeId(), i + 1);
                if (first == 0) {
                    first = i + 1;
                    mrr = 1.0 / first;
                }
            }
        }
        for (int k : List.of(5, 10)) {
            int hit = 0;
            for (int i = 0; i < Math.min(k, hits.size()); i++) {
                if (expected.contains(hits.get(i).knowledgeId())) hit++;
            }
            recall.put("recall@" + k, expected.isEmpty() ? 1.0 : (double) hit / expected.size());
        }
        return new RetrievalEvaluationService.CaseResult(id, "q" + id, expected, hits, recall, mrr, first, ranks);
    }

    @Test
    void aggregateComputesRecallMrrAndHitRate() {
        List<RetrievalEvaluationService.CaseResult> cases = List.of(
                caseResult("c1", List.of("a", "b"), List.of("x", "a")),
                caseResult("c2", List.of("c"), List.of("c")));
        Map<String, Double> m = RetrievalEvalMetrics.aggregate(cases, List.of(5, 10));
        assertEquals(0.75, m.get("recall@5"), 1e-9);
        assertEquals(0.75, m.get("MRR"), 1e-9);
        assertEquals(1.0, m.get("hitRate"), 1e-9);
    }

    @Test
    void verdictComparesFirstHitRank() {
        assertEquals("better", RetrievalEvalMetrics.verdict(0, 3));
        assertEquals("worse", RetrievalEvalMetrics.verdict(2, 0));
        assertEquals("better", RetrievalEvalMetrics.verdict(5, 2));
        assertEquals("worse", RetrievalEvalMetrics.verdict(2, 7));
        assertEquals("flat", RetrievalEvalMetrics.verdict(0, 0));
        assertEquals("flat", RetrievalEvalMetrics.verdict(3, 3));
    }

    @Test
    void qualityScoreRewardsVerifiedAndExpectedLabels() {
        int perfect = RetrievalEvalMetrics.qualityScore(10, 10, 10, 0);
        int weak = RetrievalEvalMetrics.qualityScore(10, 0, 10, 0);
        int stale = RetrievalEvalMetrics.qualityScore(10, 10, 10, 5);
        assertEquals(100, perfect);
        assertTrue(weak < perfect);
        assertTrue(stale < perfect);
        assertEquals(0, RetrievalEvalMetrics.qualityScore(0, 0, 0, 0));
    }

    @Test
    void segmentsGroupByProvidedLabel() {
        List<RetrievalEvaluationService.CaseResult> cases = List.of(
                caseResult("c1", List.of("a"), List.of("a")),
                caseResult("c2", List.of("b"), List.of("x")));
        Map<String, String> seg = Map.of("c1", "Java", "c2", "Java");
        List<Map<String, Object>> rows = RetrievalEvalMetrics.segments(cases, seg, List.of(5));
        assertEquals(1, rows.size());
        assertEquals("Java", rows.get(0).get("segment"));
        assertEquals(2, rows.get(0).get("caseCount"));
    }

    @Test
    void bootstrapCiReturnsOrderedBounds() {
        List<RetrievalEvaluationService.CaseResult> cases = List.of(
                caseResult("c1", List.of("a"), List.of("a")),
                caseResult("c2", List.of("a"), List.of("x")),
                caseResult("c3", List.of("a"), List.of("x", "a")),
                caseResult("c4", List.of("a"), List.of("a")),
                caseResult("c5", List.of("a"), List.of("x")));
        Map<String, List<Double>> ci = RetrievalEvalMetrics.bootstrapCi(cases, List.of(5), 300);
        assertFalse(ci.isEmpty());
        List<Double> bounds = ci.get("recall@5");
        assertEquals(2, bounds.size());
        assertTrue(bounds.get(0) <= bounds.get(1));
        assertTrue(bounds.get(0) >= 0 && bounds.get(1) <= 1);
    }
}
