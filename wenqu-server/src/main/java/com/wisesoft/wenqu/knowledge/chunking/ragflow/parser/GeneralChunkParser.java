package com.wisesoft.wenqu.knowledge.chunking.ragflow.parser;

import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowNlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通用分块解析器（对应参考实现 {@code knowledge/chunking/ragflow_like/parsers/general.py}）。
 * <p>
 * 按分隔符切分 → naive_merge 合并 → 超长块 token 上限保护。逻辑与正则保持一致；
 * 仅语言由 Python 译为 Java。
 */
public final class GeneralChunkParser {

    /** 硬上限比例：默认 512 token 允许到 768 再硬切（对应 GENERAL_HARD_LIMIT_RATIO）。 */
    private static final double GENERAL_HARD_LIMIT_RATIO = 1.5;

    private static final Pattern BACKTICK_DELIM = Pattern.compile("`");

    private GeneralChunkParser() {
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
                && !BACKTICK_DELIM.matcher(delimiter).find()) {
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

    private static List<String> ensureChunkTokenLimit(List<String> chunks, int chunkTokenNum) {
        int maxTokens = chunkTokenNum;
        if (maxTokens <= 0) {
            List<String> r = new ArrayList<>();
            for (String c : chunks) {
                String cleaned = (c == null ? "" : c).strip();
                if (!cleaned.isEmpty()) {
                    r.add(cleaned);
                }
            }
            return r;
        }
        int hardLimit = Math.max(maxTokens, (int) (maxTokens * GENERAL_HARD_LIMIT_RATIO));
        List<String> protectedChunks = new ArrayList<>();
        for (String chunk : chunks) {
            String cleaned = (chunk == null ? "" : chunk).strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (RagflowNlp.countTokens(cleaned) <= maxTokens) {
                protectedChunks.add(cleaned);
            } else {
                protectedChunks.addAll(RagflowNlp.hardSplitByTokenLimit(cleaned, maxTokens, hardLimit));
            }
        }
        return protectedChunks;
    }

    /** 通用分块主流程（对应 chunk_markdown）。 */
    public static List<String> chunkMarkdown(String markdownContent, Map<String, Object> parserConfig) {
        Map<String, Object> config = parserConfig == null ? new java.util.LinkedHashMap<>() : parserConfig;
        String delimiter = unescapeDelimiter(str(config.get("delimiter"), "\n"));
        int chunkTokenNum = (int) num(config.get("chunk_token_num"), 512);
        int overlappedPercent = (int) num(config.get("overlapped_percent"), 0);

        List<String> sections = iterSections(markdownContent, delimiter);
        List<String> chunks = RagflowNlp.naiveMerge(sections, chunkTokenNum, delimiter, overlappedPercent);
        return ensureChunkTokenLimit(chunks, chunkTokenNum);
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
