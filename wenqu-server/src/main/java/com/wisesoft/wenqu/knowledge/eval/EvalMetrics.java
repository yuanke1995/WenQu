package com.wisesoft.wenqu.knowledge.eval;

import com.wisesoft.wenqu.common.LooseJson;
import com.wisesoft.wenqu.service.ModelSelectors;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 评估指标计算工具，对齐参考实现 {@code knowledge/eval/metrics.py}。
 *
 * <p>简化版：只保留 Recall/F1（检索）和 LLM Judge（答案准确性）。
 *
 * <p>逐字对齐要点：{@code precision_at_k} 的分母固定为 {@code k}（不是命中条数）；
 * 空集合的短路顺序与参考实现一致；{@code calculate_overall_score} 有答案指标时用答案均分，
 * 否则回退 recall@10 均分。
 */
@Slf4j
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /** 检索评估指标计算（对应参考实现 {@code RetrievalMetrics}）。 */
    public static final class RetrievalMetrics {

        private RetrievalMetrics() {
        }

        /** 计算 Precision@K。 */
        public static double precisionAtK(List<String> retrievedIds, Collection<String> relevantIds, int k) {
            List<String> topK = head(retrievedIds, k);
            if (topK.isEmpty()) {
                return 0.0;
            }
            Set<String> retrievedSet = new LinkedHashSet<>(topK);
            Set<String> relevantSet = new LinkedHashSet<>(relevantIds);
            retrievedSet.retainAll(relevantSet);
            return (double) retrievedSet.size() / k;
        }

        /** 计算 Recall@K。 */
        public static double recallAtK(List<String> retrievedIds, Collection<String> relevantIds, int k) {
            if (relevantIds == null || relevantIds.isEmpty()) {
                return 0.0;
            }
            Set<String> retrievedSet = new LinkedHashSet<>(head(retrievedIds, k));
            Set<String> relevantSet = new LinkedHashSet<>(relevantIds);
            retrievedSet.retainAll(relevantSet);
            return (double) retrievedSet.size() / relevantSet.size();
        }

        /** 计算 F1@K。 */
        public static double f1ScoreAtK(List<String> retrievedIds, Collection<String> relevantIds, int k) {
            double precision = precisionAtK(retrievedIds, relevantIds, k);
            double recall = recallAtK(retrievedIds, relevantIds, k);
            if (precision + recall == 0) {
                return 0.0;
            }
            return 2 * precision * recall / (precision + recall);
        }

        private static List<String> head(List<String> values, int k) {
            if (values == null || values.isEmpty() || k <= 0) {
                return List.of();
            }
            return values.subList(0, Math.min(k, values.size()));
        }
    }

    /** 答案评估指标计算（对应参考实现 {@code AnswerMetrics}）。 */
    public static final class AnswerMetrics {

        private AnswerMetrics() {
        }

        /**
         * 使用 LLM 判断生成的答案是否正确。
         *
         * <p>返回 {@code {"score": Double, "reasoning": String}}；任何异常都收敛为
         * {@code score=0.0} + {@code 评判出错: {原因}}，与参考实现一致。
         */
        public static Map<String, Object> judgeCorrectness(
                String query, String generatedAnswer, String goldAnswer, ModelSelectors.ChatAdapter judgeLlm) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (generatedAnswer == null || generatedAnswer.isEmpty()) {
                result.put("score", 0.0);
                result.put("reasoning", "未生成答案");
                return result;
            }
            if (goldAnswer == null || goldAnswer.isEmpty()) {
                result.put("score", 0.0);
                result.put("reasoning", "无参考答案");
                return result;
            }

            String prompt = """
                    你是一个公正的评判者，请评估AI生成的答案相对于标准答案的准确性。

                        问题：%s

                        标准答案：
                        %s

                        AI生成的答案：
                        %s

                        请判断AI生成的答案是否在事实层面与标准答案一致。
                        忽略措辞、标点符号或格式上的细微差异。
                        只关注核心事实是否准确包含。

                        请返回以下JSON格式的结果（不要包含其他文本、Markdown 或注释）：
                        {
                            "score": 1.0,
                            "reasoning": "简要说明判定理由"
                        }
                        score 只能是 1.0 或 0.0。
                    """.formatted(query, goldAnswer, generatedAnswer);
            try {
                ModelSelectors.GeneralResponse response = judgeLlm.call(prompt, false);
                String content = response == null || response.content == null ? "" : response.content.strip();

                // 尝试清理可能的 markdown 代码块
                if (content.startsWith("```json")) {
                    content = content.substring(7);
                }
                if (content.endsWith("```")) {
                    content = content.substring(0, content.length() - 3);
                }
                content = content.strip();

                Map<String, Object> parsed = LooseJson.parseObject(content);
                // 对应参考实现的 result.get("score", 0.0) + float(...)：键缺失取默认 0.0，值非法则抛错走统一兜底
                result.put("score", parsed.containsKey("score") ? pythonFloat(parsed.get("score")) : 0.0);
                Object reasoning = parsed.get("reasoning");
                result.put("reasoning", reasoning == null ? "" : String.valueOf(reasoning));
                return result;
            } catch (Exception exc) {
                log.error("LLM 评判失败: {}", exc.getMessage());
                result.put("score", 0.0);
                result.put("reasoning", "评判出错: " + exc);
                return result;
            }
        }
    }

    /** 综合评估指标计算器（对应参考实现 {@code EvaluationMetricsCalculator}）。 */
    public static final class EvaluationMetricsCalculator {

        private static final List<Integer> DEFAULT_K_VALUES = List.of(1, 3, 5, 10);

        private EvaluationMetricsCalculator() {
        }

        /** 计算检索指标 (Recall, F1)。 */
        public static Map<String, Object> calculateRetrievalMetrics(
                List<Map<String, Object>> retrievedChunks, List<String> goldChunkIds) {
            return calculateRetrievalMetrics(retrievedChunks, goldChunkIds, DEFAULT_K_VALUES);
        }

        /** 计算检索指标 (Recall, F1)，可指定 K 列表。 */
        public static Map<String, Object> calculateRetrievalMetrics(
                List<Map<String, Object>> retrievedChunks, List<String> goldChunkIds, List<Integer> kValues) {
            if (retrievedChunks == null || retrievedChunks.isEmpty() || goldChunkIds == null || goldChunkIds.isEmpty()) {
                return new LinkedHashMap<>();
            }

            List<String> retrievedIds = new ArrayList<>();
            for (Map<String, Object> chunk : retrievedChunks) {
                Object chunkId = chunk.get("chunk_id");
                if (chunkId == null && chunk.get("metadata") instanceof Map<?, ?> metadata) {
                    chunkId = metadata.get("chunk_id");
                }
                retrievedIds.add(chunkId == null ? "" : String.valueOf(chunkId));
            }

            Map<String, Object> metrics = new LinkedHashMap<>();
            for (Integer k : kValues) {
                metrics.put("recall@" + k, RetrievalMetrics.recallAtK(retrievedIds, goldChunkIds, k));
                metrics.put("f1@" + k, RetrievalMetrics.f1ScoreAtK(retrievedIds, goldChunkIds, k));
            }
            return metrics;
        }

        /** 计算答案指标 (LLM Judge)；未提供 judge_llm 时返回空。 */
        public static Map<String, Object> calculateAnswerMetrics(
                String query, String generatedAnswer, String goldAnswer, ModelSelectors.ChatAdapter judgeLlm) {
            if (judgeLlm == null) {
                return new LinkedHashMap<>();
            }
            return AnswerMetrics.judgeCorrectness(query, generatedAnswer, goldAnswer, judgeLlm);
        }

        /**
         * 综合得分：有答案准确率则用准确率，否则用 recall@10。
         *
         * <p>返回 null 表示两侧都没有可用样本（对应参考实现的 {@code None}）。
         */
        public static Double calculateOverallScore(
                List<Map<String, Object>> retrievalMetricsList, List<Map<String, Object>> answerMetricsList) {
            if (answerMetricsList != null && !answerMetricsList.isEmpty()) {
                List<Double> scores = new ArrayList<>();
                for (Map<String, Object> metrics : answerMetricsList) {
                    scores.add(toDouble(metrics.get("score")));
                }
                if (scores.isEmpty()) {
                    return null;
                }
                double sum = 0;
                for (Double score : scores) {
                    sum += score;
                }
                return sum / scores.size();
            }

            List<Double> recalls = new ArrayList<>();
            if (retrievalMetricsList != null) {
                for (Map<String, Object> metrics : retrievalMetricsList) {
                    if (metrics != null && metrics.containsKey("recall@10")) {
                        recalls.add(toDouble(metrics.get("recall@10")));
                    }
                }
            }
            if (recalls.isEmpty()) {
                return null;
            }
            double sum = 0;
            for (Double recall : recalls) {
                sum += recall;
            }
            return sum / recalls.size();
        }
    }

    /** 对应参考实现的 {@code float(value)}：无法转换时按 0.0 处理（仅用于指标聚合的兜底）。 */
    static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    /**
     * 严格对齐 Python {@code float(value)}：布尔按 1.0/0.0，数字原样，
     * 字符串解析失败 / null 抛错（而不是静默回退 0.0）。
     */
    static double pythonFloat(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? 1.0 : 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            throw new IllegalArgumentException("float() argument must be a string or a number, not 'NoneType'");
        }
        String text = String.valueOf(value).strip();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("could not convert string to float: '" + value + "'");
        }
    }
}
