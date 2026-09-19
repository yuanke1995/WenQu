package com.wisesoft.wenqu.knowledge.eval;

import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.service.ModelSelectors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单题评估与指标聚合，对齐参考实现 {@code knowledge/eval/evaluator.py}。
 *
 * <p>职责：对一道题执行「检索 → 必要时补生成答案 → 计算检索/答案指标」，以及把逐题指标聚合成总览。
 */
public final class EvalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EvalEvaluator.class);

    private EvalEvaluator() {
    }

    /** 查询结果归一化产物（对应参考实现的二元组 {@code (answer, retrieved_chunks)}）。 */
    public record NormalizedQueryResult(String answer, List<Map<String, Object>> retrievedChunks) {}

    /** 指标聚合产物（对应参考实现的二元组 {@code (overall_metrics, overall_score)}）。 */
    public record AggregateResult(Map<String, Object> metrics, Double overallScore) {}

    /**
     * 归一化查询结果（对应 {@code normalize_query_result}）。
     *
     * <p>字典输入取 {@code answer}/{@code retrieved_chunks}，列表输入视为纯片段列表，其余为空。
     */
    public static NormalizedQueryResult normalizeQueryResult(Object queryResult) {
        if (queryResult instanceof Map<?, ?> map) {
            Object answer = map.get("answer");
            Object chunks = map.get("retrieved_chunks");
            return new NormalizedQueryResult(
                    answer == null ? "" : String.valueOf(answer),
                    chunks instanceof List<?> list ? castList(list) : new ArrayList<>());
        }
        if (queryResult instanceof List<?> list) {
            return new NormalizedQueryResult("", castList(list));
        }
        return new NormalizedQueryResult("", new ArrayList<>());
    }

    /** 构造补生成答案用的提示词（对应 {@code build_answer_prompt}）。 */
    public static String buildAnswerPrompt(
            String query, List<Map<String, Object>> retrievedChunks, int maxDocs) {
        List<String> contextDocs = new ArrayList<>();
        int limit = Math.min(maxDocs, retrievedChunks.size());
        for (int index = 0; index < limit; index++) {
            Object content = retrievedChunks.get(index).get("content");
            String text = content == null ? "" : String.valueOf(content);
            if (!text.isEmpty()) {
                contextDocs.add("文档 " + (index + 1) + ":\n" + text);
            }
        }

        String contextText = String.join("\n\n", contextDocs);
        return "基于以下上下文信息，请回答用户的问题。\n\n"
                + "上下文信息：" + contextText + "\n\n"
                + "用户问题：" + query + "\n\n"
                + "请根据上下文信息准确回答问题。\n\n"
                + "如果上下文中缺少相关信息，请回答“信息不足，无法回答”。\n\n";
    }

    /**
     * 按需补生成答案（对应 {@code generate_answer_if_needed}）。
     *
     * <p>已有答案直接返回；无片段或未配置 {@code answer_llm} 时返回空串；调用失败同样返回空串。
     */
    public static String generateAnswerIfNeeded(
            String query,
            String generatedAnswer,
            List<Map<String, Object>> retrievedChunks,
            Map<String, Object> retrievalConfig,
            ModelSelectors modelSelectors) {
        if (generatedAnswer != null && !generatedAnswer.isEmpty()) {
            return generatedAnswer;
        }
        Object answerLlm = retrievalConfig == null ? null : retrievalConfig.get("answer_llm");
        if (retrievedChunks == null
                || retrievedChunks.isEmpty()
                || answerLlm == null
                || String.valueOf(answerLlm).isEmpty()) {
            return "";
        }

        String modelSpec = String.valueOf(answerLlm);
        log.debug("使用 LLM {} 生成答案...", modelSpec);
        try {
            ModelSelectors.ChatAdapter llm = modelSelectors.selectModel(modelSpec);
            ModelSelectors.GeneralResponse response =
                    llm.call(buildAnswerPrompt(query, retrievedChunks, 5), false);
            String answer = response == null || response.content == null ? "" : response.content;
            log.debug("LLM 生成的答案长度: {}", answer.length());
            return answer;
        } catch (Exception exc) {
            log.error("LLM 生成答案失败: {}", exc.getMessage());
            return "";
        }
    }

    /**
     * 评估一道题（对应 {@code evaluate_question}）。
     *
     * <p>返回结构为 {@code {detail, retrieval_scores, answer_scores}}，与参考实现一致。
     */
    public static Map<String, Object> evaluateQuestion(
            String kbId,
            Map<String, Object> questionData,
            Map<String, Object> retrievalConfig,
            boolean hasGoldChunks,
            boolean hasGoldAnswers,
            ModelSelectors.ChatAdapter judgeLlm,
            ModelSelectors modelSelectors,
            KnowledgeBaseManager kbManager) {
        String query = String.valueOf(questionData.get("query"));
        Map<String, Object> aqueryOptions = new LinkedHashMap<>();
        if (retrievalConfig != null) {
            aqueryOptions.putAll(retrievalConfig);
        }
        Object queryResult = kbManager.aquery(query, kbId, aqueryOptions);
        NormalizedQueryResult normalized = normalizeQueryResult(queryResult);
        String generatedAnswer = generateAnswerIfNeeded(
                query,
                normalized.answer(),
                normalized.retrievedChunks(),
                retrievalConfig,
                modelSelectors);

        Map<String, Object> currentMetrics = new LinkedHashMap<>();
        Map<String, Object> retrievalScores = new LinkedHashMap<>();
        Map<String, Object> answerScores = new LinkedHashMap<>();

        Object goldChunkIds = questionData.get("gold_chunk_ids");
        if (hasGoldChunks && isTruthy(goldChunkIds)) {
            retrievalScores = EvalMetrics.EvaluationMetricsCalculator.calculateRetrievalMetrics(
                    normalized.retrievedChunks(), toStringList(goldChunkIds));
            currentMetrics.putAll(retrievalScores);
        }

        Object goldAnswer = questionData.get("gold_answer");
        if (hasGoldAnswers && isTruthy(goldAnswer)) {
            if (judgeLlm != null) {
                answerScores = EvalMetrics.EvaluationMetricsCalculator.calculateAnswerMetrics(
                        query, generatedAnswer, String.valueOf(goldAnswer), judgeLlm);
                currentMetrics.putAll(answerScores);
            } else {
                log.warn("需要计算答案指标但未配置 Judge LLM");
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("query_text", query);
        detail.put("gold_chunk_ids", goldChunkIds);
        detail.put("gold_answer", goldAnswer);
        detail.put("generated_answer", generatedAnswer);
        detail.put("retrieved_chunks", normalized.retrievedChunks());
        detail.put("metrics", currentMetrics);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detail", detail);
        result.put("retrieval_scores", retrievalScores);
        result.put("answer_scores", answerScores);
        return result;
    }

    /**
     * 聚合逐题指标（对应 {@code aggregate_metrics}）。
     *
     * <p>检索指标按首个样本的键集合求均值；答案指标取 {@code score} 均值；
     * {@code include_overall_score} 为真时把总览分写入 {@code overall_score}。
     */
    public static AggregateResult aggregateMetrics(
            List<Map<String, Object>> retrievalMetricsList,
            List<Map<String, Object>> answerMetricsList,
            boolean includeOverallScore) {
        Map<String, Object> overallMetrics = new LinkedHashMap<>();

        if (retrievalMetricsList != null && !retrievalMetricsList.isEmpty()) {
            Map<String, Object> first = retrievalMetricsList.get(0);
            for (String key : first.keySet()) {
                double sum = 0;
                for (Map<String, Object> metrics : retrievalMetricsList) {
                    sum += EvalMetrics.toDouble(metrics.get(key));
                }
                overallMetrics.put(key, sum / retrievalMetricsList.size());
            }
        }

        if (answerMetricsList != null && !answerMetricsList.isEmpty()) {
            List<Double> scores = new ArrayList<>();
            for (Map<String, Object> metrics : answerMetricsList) {
                Object score = metrics == null ? null : metrics.get("score");
                scores.add(score == null ? 0.0 : EvalMetrics.toDouble(score));
            }
            double sum = 0;
            for (Double score : scores) {
                sum += score;
            }
            overallMetrics.put("answer_correctness", sum / scores.size());
        }

        Double overallScore = EvalMetrics.EvaluationMetricsCalculator.calculateOverallScore(
                retrievalMetricsList, answerMetricsList);
        if (includeOverallScore) {
            overallMetrics.put("overall_score", overallScore);
        }

        return new AggregateResult(overallMetrics, overallScore);
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(List<?> raw) {
        return (List<Map<String, Object>>) raw;
    }

    private static List<String> toStringList(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
        }
        return values;
    }

    /** Python 真值语义（空串/空列表/0 为假）。 */
    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence text) {
            return text.length() > 0;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }
}
