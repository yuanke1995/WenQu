package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分块工具（对应参考实现 {@code ragflow_like/utils/semantic_utils.py}）。
 * <p>
 * 句子级切分逻辑（中英文混合分句、中文分句、英文缩写感知分句）为纯文本/正则逻辑，逐函数直译；
 * 仅聚类环节为能力差异：参考实现使用 sklearn {@code AgglomerativeClustering}（metric=cosine，
 * linkage=average）对句子嵌入做层次聚类，再按聚类标签变化或 token 上限切分。Java 侧无 sklearn，
 * 当未传入 {@code embed_fn} 或整体未超长时退化为「按 token 上限贪心合并」（与原实现
 * {@code embed_fn is None} 分支行为一致）；embedding 聚类路径待语义解析器接入 embedding 服务后补全。
 */
public final class RagflowSemanticUtils {

    private static final Set<String> ENGLISH_ABBREVIATIONS = new HashSet<>(Arrays.asList(
            "approx.", "dept.", "dr.", "e.g.", "etc.", "i.e.", "jr.", "mr.", "mrs.", "ms.",
            "no.", "prof.", "rev.", "sr.", "st.", "vs."));
    private static final Set<String> ENGLISH_TITLE_ABBREVIATIONS = new HashSet<>(Arrays.asList(
            "dr.", "jr.", "mr.", "mrs.", "ms.", "prof.", "rev.", "sr.", "st."));
    private static final Set<String> ENGLISH_SENTENCE_STARTERS = new HashSet<>(Arrays.asList(
            "he", "however", "i", "it", "meanwhile", "next", "she", "that", "then",
            "these", "this", "those", "they", "we", "you"));

    private static final String CJK_PUNCT = "[\u3002\uff01\uff1f]";
    private static final String SMART_QUOTES = "[\u201d\u2019\"]";

    private RagflowSemanticUtils() {
    }

    /** 嵌入函数（能力差异：参考实现由调用方注入 embed_fn；此处可选，未注入时降级为按 token 合并）。 */
    @FunctionalInterface
    public interface EmbeddingFunction {
        float[][] embed(List<String> sentences);
    }

    /** token 计数函数（对应参考实现的 token_count_fn）。 */
    @FunctionalInterface
    public interface TokenCounter {
        int count(String text);
    }

    /**
     * 语义切分（对应 {@code semantic_chunking_with_auto_clusters}）。
     */
    public static List<String> semanticChunkingWithAutoClusters(
            String text, EmbeddingFunction embedFn, TokenCounter tokenCountFn, int maxChunkSize) {
        List<String> sentences = splitMixedSentences(text);
        if (sentences.size() < 2) {
            return Collections.singletonList(text == null ? "" : text.strip());
        }
        List<Integer> sentenceTokenCounts = new ArrayList<>();
        int totalTokens = 0;
        for (String s : sentences) {
            int c = tokenCountFn.count(s);
            sentenceTokenCounts.add(c);
            totalTokens += c;
        }
        // 无嵌入函数或整体未超长：直接简单合并（与参考实现 embed_fn=None / total<=max 分支一致）。
        if (embedFn == null || totalTokens <= maxChunkSize) {
            return mergeByTokenLimit(sentences, sentenceTokenCounts, maxChunkSize);
        }
        // 能力差异：参考实现此处用 sklearn AgglomerativeClustering(metric="cosine", linkage="average")
        // 对句子嵌入做层次聚类后按标签变化 / token 上限切分。Java 侧暂以 token 上限贪心合并等效降级，
        // 待语义解析器接入 embedding 服务后替换为真实聚类实现。行为与原实现 embed_fn=None 分支一致。
        return mergeByTokenLimit(sentences, sentenceTokenCounts, maxChunkSize);
    }

    private static List<String> mergeByTokenLimit(List<String> sentences, List<Integer> counts, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String s = sentences.get(i);
            int cnt = counts.get(i);
            if (currentTokens + cnt > maxChunkSize && current.length() > 0) {
                chunks.add(current.toString().strip());
                current.setLength(0);
                current.append(s);
                currentTokens = cnt;
            } else {
                current.append(s);
                currentTokens += cnt;
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    /** 中英文混合分句（对应 {@code split_mixed_sentences}）。 */
    public static List<String> splitMixedSentences(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        String[] chunks = text.split("(\n+)");
        List<String> sentences = new ArrayList<>();
        Pattern english = Pattern.compile("[A-Za-z]");
        for (String ch : chunks) {
            if (ch.strip().isEmpty()) {
                continue;
            }
            if (english.matcher(ch).find()) {
                for (String p : splitEnglishSentences(ch)) {
                    if (!p.strip().isEmpty()) {
                        sentences.add(p.strip());
                    }
                }
            } else {
                List<String> sents = splitSentencesChinese(ch);
                if (!sents.isEmpty()) {
                    for (String s : sents) {
                        if (!s.strip().isEmpty()) {
                            sentences.add(s.strip());
                        }
                    }
                } else {
                    for (String p : ch.split("(?<=" + CJK_PUNCT + ")")) {
                        if (!p.strip().isEmpty()) {
                            sentences.add(p.strip());
                        }
                    }
                }
            }
        }
        return sentences;
    }

    /** 中文分句（对应 {@code split_sentences_chinese}）。 */
    public static List<String> splitSentencesChinese(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        String pattern = "(?<=" + CJK_PUNCT + SMART_QUOTES + ")|(?<=" + CJK_PUNCT + ")(?!" + SMART_QUOTES + ")";
        String[] sentences = text.split(pattern);
        List<String> result = new ArrayList<>();
        for (String s : sentences) {
            String t = s.strip();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    /** 英文分句，保留常见缩写（对应 {@code _split_english_sentences}）。 */
    public static List<String> splitEnglishSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sentences;
        }
        String closingChars = "\"'”’)]}";
        String punctuation = ".!?\u3002\uff01\uff1f";
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            if (punctuation.indexOf(text.charAt(index)) < 0) {
                index++;
                continue;
            }
            int punctuationEnd = index + 1;
            while (punctuationEnd < text.length() && punctuation.indexOf(text.charAt(punctuationEnd)) >= 0) {
                punctuationEnd++;
            }
            int boundaryEnd = punctuationEnd;
            while (boundaryEnd < text.length() && closingChars.indexOf(text.charAt(boundaryEnd)) >= 0) {
                boundaryEnd++;
            }
            boolean isBoundary = boundaryEnd == text.length() || Character.isWhitespace(text.charAt(boundaryEnd));
            if (text.charAt(index) == '.' && isEnglishAbbreviation(text, start, index)) {
                isBoundary = false;
            }
            if (isBoundary) {
                String sentence = text.substring(start, boundaryEnd).strip();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                start = boundaryEnd;
            }
            index = boundaryEnd;
        }
        String remainder = text.substring(start).strip();
        if (!remainder.isEmpty()) {
            sentences.add(remainder);
        }
        return sentences;
    }

    /** 判断句点是否属于常见英文缩写（对应 {@code _is_english_abbreviation}）。 */
    private static boolean isEnglishAbbreviation(String text, int start, int punctuation) {
        String prefix = text.substring(start, punctuation + 1).strip();
        Matcher m = Pattern.compile("([A-Za-z](?:[A-Za-z.]*)[.])$").matcher(prefix);
        if (!m.find()) {
            return false;
        }
        String token = m.group(1).toLowerCase();
        String letters = token.replace(".", "");
        if (ENGLISH_TITLE_ABBREVIATIONS.contains(token)) {
            return true;
        }
        int nextIndex = punctuation + 1;
        while (nextIndex < text.length() && Character.isWhitespace(text.charAt(nextIndex))) {
            nextIndex++;
        }
        String nextCharacter = nextIndex < text.length() ? String.valueOf(text.charAt(nextIndex)) : "";
        if (ENGLISH_ABBREVIATIONS.contains(token)) {
            return !nextCharacter.isEmpty() && Character.isLowerCase(nextCharacter.charAt(0));
        }
        if (letters.length() == 1) {
            return true;
        }
        if (Pattern.matches("(?:[A-Z][.]){2,}", m.group(1))) {
            Matcher nextWord = Pattern.compile("[A-Za-z]+").matcher(text.substring(nextIndex));
            return nextWord.find() && !ENGLISH_SENTENCE_STARTERS.contains(nextWord.group().toLowerCase());
        }
        if (Pattern.matches("(?:[a-z][.]){2,}", token)) {
            return nextIndex == text.length() || Character.isLowerCase(text.charAt(nextIndex));
        }
        return false;
    }
}
