package com.wisesoft.wenqu.knowledge.chunking.ragflow.parser;

import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowNlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 书籍分块解析器（对应参考实现 {@code knowledge/chunking/ragflow_like/parsers/book.py}）。
 * <p>
 * 行切分 → 清理目录表 → 冒号标题化 → 项目符号分类 → 层级合并（depth=5）或 naive_merge → 超长保护。
 * 逻辑与正则保持一致；仅语言由 Python 译为 Java。
 */
public final class BookChunkParser {

    private BookChunkParser() {
    }

    private static String unescapeDelimiter(String delimiter) {
        if (delimiter == null) {
            return "\n";
        }
        return delimiter.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static List<String> iterSections(String markdownContent) {
        List<String> sections = new ArrayList<>();
        String text = markdownContent == null ? "" : markdownContent;
        for (String line : text.split("\n", -1)) {
            String block = line.strip();
            if (!block.isEmpty()) {
                sections.add(block);
            }
        }
        if (sections.isEmpty() && !text.strip().isEmpty()) {
            sections.add(text.strip());
        }
        return sections;
    }

    private static List<String> ensureChunkTokenLimit(List<String> chunks, int chunkTokenNum) {
        int maxTokens = chunkTokenNum;
        List<String> normalized = new ArrayList<>();
        for (String c : chunks) {
            String cleaned = (c == null ? "" : c).strip();
            if (!cleaned.isEmpty()) {
                normalized.add(cleaned);
            }
        }
        if (maxTokens <= 0) {
            return normalized;
        }
        List<String> protectedChunks = new ArrayList<>();
        for (String chunk : normalized) {
            if (RagflowNlp.countTokens(chunk) <= maxTokens) {
                protectedChunks.add(chunk);
            } else {
                protectedChunks.addAll(RagflowNlp.hardSplitByTokenLimit(chunk, maxTokens, null));
            }
        }
        return protectedChunks;
    }

    /** 书籍分块主流程（对应 chunk_markdown）。 */
    public static List<String> chunkMarkdown(String markdownContent, Map<String, Object> parserConfig) {
        Map<String, Object> config = parserConfig == null ? new java.util.LinkedHashMap<>() : parserConfig;
        String delimiter = unescapeDelimiter(str(config.get("delimiter"), "\n"));
        int chunkTokenNum = (int) num(config.get("chunk_token_num"), 512);
        int overlappedPercent = (int) num(config.get("overlapped_percent"), 0);

        List<String> sections = iterSections(markdownContent);
        if (sections.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> sectionTexts = new ArrayList<>(sections);
        RagflowNlp.removeContentsTable(toObjects(sections),
                RagflowNlp.isEnglish(RagflowNlp.randomChoices(sectionTexts, 200)));
        RagflowNlp.makeColonAsTitle(toObjects(sections));

        int bull = RagflowNlp.bulletsCategory(RagflowNlp.randomChoices(sectionTexts, 100));

        List<String> chunks;
        if (bull >= 0) {
            List<List<String>> merged = RagflowNlp.hierarchicalMerge(bull, toObjects(sections), 5);
            chunks = new ArrayList<>();
            for (List<String> ck : merged) {
                chunks.add(String.join("\n", ck));
            }
        } else {
            chunks = RagflowNlp.naiveMerge(sections, chunkTokenNum, delimiter, overlappedPercent);
        }

        if (!chunks.isEmpty()) {
            return ensureChunkTokenLimit(chunks, chunkTokenNum);
        }
        return ensureChunkTokenLimit(
                RagflowNlp.naiveMerge(sections, chunkTokenNum, delimiter, overlappedPercent), chunkTokenNum);
    }

    private static List<Object> toObjects(List<String> sections) {
        List<Object> out = new ArrayList<>();
        for (String s : sections) {
            out.add(s);
        }
        return out;
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
