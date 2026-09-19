package com.wisesoft.wenqu.knowledge.chunking.ragflow.parser;

import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowNlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 严格分隔分块解析器（对应参考实现 {@code knowledge/chunking/ragflow_like/parsers/separator.py}）。
 * <p>
 * 命中分隔符即切分，仅超长片段内部继续按 token 长度切分（可带重叠）。逻辑保持一致。
 */
public final class SeparatorChunkParser {

    private SeparatorChunkParser() {
    }

    private static String unescapeDelimiter(String delimiter) {
        if (delimiter == null) {
            return "\n";
        }
        return delimiter.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static List<String> iterSections(String markdownContent, String delimiter) {
        List<String> sections = new ArrayList<>();
        String text = markdownContent == null ? "" : markdownContent;
        if (delimiter != null && !delimiter.equals("\n") && !delimiter.equals("\r\n")
                && !delimiter.contains("`")) {
            for (String part : text.split(Pattern.quote(delimiter))) {
                String block = part.strip();
                if (!block.isEmpty()) {
                    sections.add(block);
                }
            }
        } else {
            for (String line : text.split("\n", -1)) {
                String block = line.strip();
                if (!block.isEmpty()) {
                    sections.add(block);
                }
            }
        }
        if (sections.isEmpty() && !text.strip().isEmpty()) {
            sections.add(text.strip());
        }
        return sections;
    }

    private static List<String> sliceTextByTokens(String text, int maxTokens, int overlapTokens) {
        if (maxTokens <= 0) {
            String cleaned = (text == null ? "" : text).strip();
            return cleaned.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(cleaned));
        }
        List<String> chars = new ArrayList<>();
        String src = text == null ? "" : text;
        for (int i = 0; i < src.length(); i++) {
            chars.add(String.valueOf(src.charAt(i)));
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < chars.size()) {
            StringBuilder current = new StringBuilder();
            int currentTokens = 0;
            int end = start;
            while (end < chars.size()) {
                String nextText = current + chars.get(end);
                int nextTokens = RagflowNlp.countTokens(nextText);
                if (!current.toString().isEmpty() && nextTokens > maxTokens) {
                    break;
                }
                current = new StringBuilder(nextText);
                currentTokens = nextTokens;
                end++;
                if (currentTokens >= maxTokens) {
                    break;
                }
            }
            String chunk = current.toString().strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= chars.size()) {
                break;
            }
            if (overlapTokens <= 0) {
                start = end;
                continue;
            }
            int backtrack = end;
            StringBuilder overlapText = new StringBuilder();
            while (backtrack > start) {
                String candidate = chars.get(backtrack - 1) + overlapText.toString();
                if (RagflowNlp.countTokens(candidate) > overlapTokens) {
                    break;
                }
                overlapText = new StringBuilder(candidate);
                backtrack--;
            }
            start = backtrack < end ? backtrack : end;
        }
        return chunks;
    }

    private static List<String> splitSectionWithOverlap(String section, int chunkTokenNum, int overlappedPercent) {
        int overlapTokens = 0;
        if (chunkTokenNum > 0 && overlappedPercent > 0) {
            overlapTokens = (int) (chunkTokenNum * Math.max(0, Math.min(overlappedPercent, 99)) / 100.0);
        }
        return sliceTextByTokens(section, chunkTokenNum, overlapTokens);
    }

    /** 严格分隔分块主流程（对应 chunk_markdown）。 */
    public static List<String> chunkMarkdown(String markdownContent, Map<String, Object> parserConfig) {
        Map<String, Object> config = parserConfig == null ? new java.util.LinkedHashMap<>() : parserConfig;
        String delimiter = unescapeDelimiter(str(config.get("delimiter"), "\n"));
        int chunkTokenNum = (int) num(config.get("chunk_token_num"), 512);
        int overlappedPercent = (int) num(config.get("overlapped_percent"), 0);

        List<String> sections = iterSections(markdownContent, delimiter);
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            String text = (section == null ? "" : section).strip();
            if (text.isEmpty()) {
                continue;
            }
            if (chunkTokenNum > 0 && RagflowNlp.countTokens(text) > chunkTokenNum) {
                chunks.addAll(splitSectionWithOverlap(text, chunkTokenNum, overlappedPercent));
                continue;
            }
            chunks.add(text);
        }
        return chunks;
    }

    private static String str(Object o, String fallback) {
        if (o == null) {
            return fallback;
        }
        String s = String.valueOf(o);
        return s.isEmpty() ? fallback : s;
    }

    private static double num(Object o, double fallback) {
        if (o == null) {
            return fallback;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
