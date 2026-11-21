package com.wenqu.ai.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性多问切分：连接词或问号计数判定多问，不调 LLM，零延迟。
 * <p>生产分解只在明确多问时触发；评测口径保留「原词 + 关键词组合」近似，供 normal vs multi 对照。
 */
@Component
public class QuerySplitter {

    public record SplitResult(List<String> queries, boolean multiQuestion) {
    }

    private final KeywordExtractor keywordExtractor;

    public QuerySplitter(KeywordExtractor keywordExtractor) {
        this.keywordExtractor = keywordExtractor;
    }

    /** 生产判定：必须有连接词或至少两个问号才拆，单问严格短路。 */
    public SplitResult analyze(String question) {
        for (String sep : List.of("和", "与", "、", "及", "，", ",")) {
            if (question.contains(sep)) {
                List<String> parts = splitOn(question, List.of(sep));
                if (parts.size() > 1) return new SplitResult(parts, true);
            }
        }
        long marks = question.chars().filter(c -> c == '?' || c == '？').count();
        if (marks >= 2) {
            List<String> parts = splitOn(question, List.of("?", "？"));
            if (parts.size() > 1) return new SplitResult(parts, true);
        }
        return new SplitResult(List.of(question), false);
    }

    /** 评测近似：多问直接拆；单问补一个关键词组合路（原 RetrievalEvaluationService 口径）。 */
    public List<String> split(String question) {
        SplitResult analyzed = analyze(question);
        if (analyzed.multiQuestion()) return analyzed.queries();
        List<String> parts = new ArrayList<>();
        parts.add(question);
        List<String> terms = keywordExtractor.extract(question);
        if (terms.size() >= 2) {
            parts.add(String.join(" ", terms.subList(0, Math.min(2, terms.size()))));
        }
        return parts.stream().distinct().toList();
    }

    private static List<String> splitOn(String text, List<String> separators) {
        String regex = String.join("|", separators.stream().map(java.util.regex.Pattern::quote).toList());
        List<String> parts = new ArrayList<>();
        for (String p : text.split(regex)) {
            if (!p.isBlank()) parts.add(p.trim());
        }
        return parts.stream().distinct().toList();
    }
}
