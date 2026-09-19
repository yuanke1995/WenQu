package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析辅助（对应参考实现 {@code ragflow_like/utils/md_parser_utils.py}）。
 * <p>
 * 仅被语义解析器（semantic parser，后续批次移植）使用。其中：
 * <ul>
 *   <li>{@link #inferHeadingLevel(String)} / {@link #getTitlePath(List)} 为纯逻辑；</li>
 *   <li>{@link #extractTableBlock(List, int, List)} 依赖 token 流的 {@code map}（markdown-it 概念），
 *       由语义解析器产出 {@link MdToken} 序列后调用；</li>
 *   <li>{@link #splitTextByLengthAndNewline(String, int, RagflowSemanticUtils.EmbeddingFunction, RagflowSemanticUtils.TokenCounter)}
 *       对超长行委托 {@link RagflowSemanticUtils#semanticChunkingWithAutoClusters} 做语义切分。</li>
 * </ul>
 */
public final class RagflowMdParserUtils {

    private RagflowMdParserUtils() {
    }

    /** 从 {@code extract_table_block} 返回的结果：表格结束 token 索引 + 拼接出的表格原文。 */
    public record TableBlock(int closeIndex, String content) {
    }

    /** 推断标题层级 1-6（对应 {@code infer_heading_level}）。 */
    public static int inferHeadingLevel(String title) {
        String t = title == null ? "" : title;
        Matcher m = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)[.)、]?\\s*").matcher(t);
        if (m.find()) {
            String[] parts = m.group(1).split("\\.");
            return Math.max(1, Math.min(parts.length, 6));
        }
        Matcher mZh = Pattern.compile("^\\s*[一二三四五六七八九十百千]+[、.]\\s*").matcher(t);
        if (mZh.find()) {
            return 1;
        }
        return 1;
    }

    /** 根据标题栈生成标题路径（用「|」分隔，对应 {@code get_title_path}）。 */
    public static String getTitlePath(List<String> stack) {
        List<String> filtered = new ArrayList<>();
        if (stack != null) {
            for (String s : stack) {
                if (s != null && !s.isEmpty()) {
                    filtered.add(s);
                }
            }
        }
        return String.join("|", filtered);
    }

    /**
     * 从 token 流和原始文本中提取完整的表格块（对应 {@code extract_table_block}）。
     *
     * @param tokens        由语义解析器产出的 {@link MdToken} 序列
     * @param i             当前表格起始 token 索引
     * @param originalLines 原始按行拆分的文本
     * @return 包含「表格结束 token 索引」与「拼接后的表格原文」的结果
     */
    public static TableBlock extractTableBlock(List<MdToken> tokens, int i, List<String> originalLines) {
        if (tokens == null || i < 0 || i >= tokens.size() || originalLines == null) {
            return new TableBlock(i, "");
        }
        MdToken token = tokens.get(i);
        int tableStart = (token.map() != null && token.map().length >= 1) ? token.map()[0] : 0;
        int j = i + 1;
        while (j < tokens.size() && !"table_close".equals(tokens.get(j).type())) {
            j++;
        }
        int tableEnd;
        if (j < tokens.size()) {
            MdToken endToken = tokens.get(j);
            if (endToken.map() != null && endToken.map().length >= 2) {
                tableEnd = endToken.map()[1];
            } else {
                tableEnd = -1;
                for (int k = j + 1; k < tokens.size(); k++) {
                    if (tokens.get(k).map() != null && tokens.get(k).map().length >= 1
                            && tokens.get(k).map()[0] != 0) {
                        tableEnd = tokens.get(k).map()[0];
                        break;
                    }
                }
                if (tableEnd < 0) {
                    tableEnd = scanTableEndByHeuristic(originalLines, tableStart);
                }
            }
        } else {
            tableEnd = scanTableEndByHeuristic(originalLines, tableStart);
        }

        int safeStart = Math.max(0, Math.min(tableStart, originalLines.size()));
        int safeEnd = Math.max(safeStart, Math.min(tableEnd, originalLines.size()));
        String content = String.join("\n", originalLines.subList(safeStart, safeEnd));
        return new TableBlock(j, content);
    }

    private static int scanTableEndByHeuristic(List<String> originalLines, int tableStart) {
        int tableEnd = tableStart + 1;
        for (int lineIdx = tableStart; lineIdx < originalLines.size(); lineIdx++) {
            String line = originalLines.get(lineIdx).strip();
            if (line.isEmpty() || !(line.startsWith("|") || line.contains("|"))) {
                tableEnd = lineIdx;
                break;
            }
        }
        return tableEnd;
    }

    /**
     * 层次化文本切分（对应 {@code split_text_by_length_and_newline}）。
     * 段落 / 行优先合并；当单行 token 数超过上限时，委托
     * {@link RagflowSemanticUtils#semanticChunkingWithAutoClusters} 做语义切分。
     */
    public static List<String> splitTextByLengthAndNewline(
            String text, int maxLength,
            RagflowSemanticUtils.EmbeddingFunction embedFn,
            RagflowSemanticUtils.TokenCounter tokenCountFn) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String[] paragraphs = text.split("\n\n");
        for (String paragraph : paragraphs) {
            paragraph = paragraph.strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            int paragraphTokenCount = tokenCountFn.count(paragraph);
            if (paragraphTokenCount <= maxLength) {
                chunks.add(paragraph);
                continue;
            }
            String[] lines = paragraph.split("\n");
            List<String> currentChunkLines = new ArrayList<>();
            int currentChunkTokens = 0;
            for (String line : lines) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                int lineTokenCount = tokenCountFn.count(line);
                int addedTokens = lineTokenCount + (currentChunkLines.isEmpty() ? 0 : 1);
                if (lineTokenCount > maxLength) {
                    if (!currentChunkLines.isEmpty()) {
                        chunks.add(String.join("\n", currentChunkLines));
                        currentChunkLines.clear();
                        currentChunkTokens = 0;
                    }
                    List<String> subChunks = RagflowSemanticUtils.semanticChunkingWithAutoClusters(
                            line, embedFn, tokenCountFn, maxLength);
                    chunks.addAll(subChunks);
                } else if (currentChunkTokens + addedTokens > maxLength) {
                    chunks.add(String.join("\n", currentChunkLines));
                    currentChunkLines = new ArrayList<>(List.of(line));
                    currentChunkTokens = lineTokenCount;
                } else {
                    currentChunkLines.add(line);
                    currentChunkTokens += addedTokens;
                }
            }
            if (!currentChunkLines.isEmpty()) {
                chunks.add(String.join("\n", currentChunkLines));
            }
        }
        return chunks;
    }
}
