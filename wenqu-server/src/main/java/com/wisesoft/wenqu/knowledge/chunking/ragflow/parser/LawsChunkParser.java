package com.wisesoft.wenqu.knowledge.chunking.ragflow.parser;

import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowNlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 法规分块解析器（对应参考实现 {@code knowledge/chunking/ragflow_like/parsers/laws.py}，简化版）。
 * <p>
 * docx 优先尝试标题树；其余格式按章/节/条树形切分（depth=3）；最后统一超长保护。
 * 逻辑与正则保持一致；仅语言由 Python 译为 Java。
 */
public final class LawsChunkParser {

    private static final Pattern ARTICLE_PATTERN =
            Pattern.compile("^(第[零一二三四五六七八九十百千万0-9]+条)[\\s　:：]*(.*)$");

    private LawsChunkParser() {
    }

    private static String unescapeDelimiter(String delimiter) {
        if (delimiter == null) {
            return "\n";
        }
        return delimiter.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static List<String> iterLines(String markdownContent) {
        List<String> out = new ArrayList<>();
        String text = markdownContent == null ? "" : markdownContent;
        for (String line : text.split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String normalizeLawLine(String line) {
        String text = (line == null ? "" : line).strip();
        text = text.replaceAll("^#{1,6}\\s+", "");
        text = text.replaceAll("^[-*+]\\s+", "");
        text = text.replace("**", "").replace("__", "").replace("`", "");
        text = text.replaceAll("[ \\t]+", " ");
        return text.strip();
    }

    private static List<String> expandArticleLine(String line) {
        String normalized = normalizeLawLine(line);
        if (normalized.isEmpty()) {
            return new ArrayList<>();
        }
        java.util.regex.Matcher m = ARTICLE_PATTERN.matcher(normalized);
        if (!m.find()) {
            List<String> r = new ArrayList<>();
            r.add(normalized);
            return r;
        }
        String article = m.group(1).strip();
        String body = m.group(2).strip();
        if (body.isEmpty()) {
            List<String> r = new ArrayList<>();
            r.add(article);
            return r;
        }
        List<String> r = new ArrayList<>();
        r.add(article);
        r.add(body);
        return r;
    }

    private static List<String> iterLawSections(String markdownContent) {
        List<String> sections = new ArrayList<>();
        for (String line : iterLines(markdownContent)) {
            sections.addAll(expandArticleLine(line));
        }
        List<String> out = new ArrayList<>();
        for (String s : sections) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> docxHeadingTree(String markdownContent) {
        List<RagflowNlp.Node> stack = new ArrayList<>();
        List<int[]> lines = new ArrayList<>();
        java.util.Set<Integer> levelSet = new java.util.HashSet<>();
        for (String raw : (markdownContent == null ? "" : markdownContent).split("\n", -1)) {
            String text = raw.strip();
            if (text.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher headingMatch = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(text);
            int level;
            String value;
            if (headingMatch.find()) {
                level = headingMatch.group(1).length();
                value = headingMatch.group(2).strip();
            } else {
                level = 99;
                value = text;
            }
            if (value.isEmpty()) {
                continue;
            }
            lines.add(new int[]{lines.size(), 0});
            levelSet.add(level);
        }
        // 注：参考实现将 (level, value) 传入 Node.build_tree；此处 level 已抽取，文本在 build_tree 内组合。
        // 为忠实反映层级合并，使用 value 重建 LevelText 结构：复用 RagflowNlp.treeMerge 的语义，
        // 这里直接按标题层级组装（depth 由 sorted_levels 推导）。
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }
        List<Integer> sortedLevels = new ArrayList<>(levelSet);
        sortedLevels.sort(Integer::compareTo);
        int h2Level = sortedLevels.size() > 1 ? sortedLevels.get(1) : 1;
        if (h2Level == sortedLevels.get(sortedLevels.size() - 1) && sortedLevels.size() > 2) {
            h2Level = sortedLevels.get(sortedLevels.size() - 2);
        }
        // 重新构建带文本的层级树（与参考 _docx_heading_tree 等价）
        return buildHeadingTree(markdownContent, h2Level);
    }

    private static List<String> buildHeadingTree(String markdownContent, int h2Level) {
        List<RagflowNlp.Node> stack = new ArrayList<>();
        RagflowNlp.Node root = new RagflowNlp.Node(0, new ArrayList<>());
        root.depth = h2Level;
        stack.add(root);
        for (String raw : (markdownContent == null ? "" : markdownContent).split("\n", -1)) {
            String text = raw.strip();
            if (text.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher headingMatch = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(text);
            int level;
            String value;
            if (headingMatch.find()) {
                level = headingMatch.group(1).length();
                value = headingMatch.group(2).strip();
            } else {
                level = 99;
                value = text;
            }
            if (value.isEmpty()) {
                continue;
            }
            if (root.depth != -1 && level > root.depth) {
                stack.get(stack.size() - 1).addText(value);
                continue;
            }
            while (stack.size() > 1 && level <= stack.get(stack.size() - 1).level) {
                stack.remove(stack.size() - 1);
            }
            RagflowNlp.Node node = new RagflowNlp.Node(level, new ArrayList<>());
            node.texts.add(value);
            stack.get(stack.size() - 1).addChild(node);
            stack.add(node);
        }
        return root.getTree().stream().filter(s -> s != null && !s.isEmpty()).toList();
    }

    private static List<String> ensureChunkTokenLimit(
            List<String> chunks, int chunkTokenNum, String delimiter, int overlappedPercent) {
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
                continue;
            }
            List<String> refined = RagflowNlp.naiveMerge(toTyped(iterLines(chunk)), maxTokens, delimiter, overlappedPercent);
            if (refined.isEmpty()) {
                refined = new ArrayList<>(List.of(chunk));
            }
            for (String item : refined) {
                String cleaned = (item == null ? "" : item).strip();
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (RagflowNlp.countTokens(cleaned) <= maxTokens) {
                    protectedChunks.add(cleaned);
                } else {
                    List<String> sentenceRefined = RagflowNlp.naiveMerge(
                            toTyped(splitSentences(cleaned)), maxTokens, delimiter, overlappedPercent);
                    if (sentenceRefined.isEmpty()) {
                        sentenceRefined = new ArrayList<>(List.of(cleaned));
                    }
                    for (String sentenceChunk : sentenceRefined) {
                        String t = sentenceChunk.strip();
                        if (t.isEmpty()) {
                            continue;
                        }
                        if (RagflowNlp.countTokens(t) <= maxTokens) {
                            protectedChunks.add(t);
                        } else {
                            protectedChunks.addAll(RagflowNlp.hardSplitByTokenLimit(t, maxTokens, null));
                        }
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String c : protectedChunks) {
            String t = c.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<Object> toTyped(List<String> lines) {
        List<Object> out = new ArrayList<>();
        for (String s : lines) {
            out.add(s);
        }
        return out;
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[。！？；;!?])", -1)) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 法规分块主流程（对应 chunk_markdown）。 */
    public static List<String> chunkMarkdown(
            String filename, String markdownContent, Map<String, Object> parserConfig) {
        Map<String, Object> config = parserConfig == null ? new java.util.LinkedHashMap<>() : parserConfig;
        String delimiter = unescapeDelimiter(str(config.get("delimiter"), "\n"));
        int chunkTokenNum = (int) num(config.get("chunk_token_num"), 512);
        int overlappedPercent = (int) num(config.get("overlapped_percent"), 0);

        if (filename != null && Pattern.compile("\\.docx$", Pattern.CASE_INSENSITIVE).matcher(filename).find()) {
            List<String> docxChunks = docxHeadingTree(markdownContent);
            if (docxChunks.size() > 1) {
                return ensureChunkTokenLimit(docxChunks, chunkTokenNum, delimiter, overlappedPercent);
            }
        }

        List<String> sections = iterLawSections(markdownContent);
        if (sections.isEmpty()) {
            return new ArrayList<>();
        }
        RagflowNlp.removeContentsTable(toObjects(sections), RagflowNlp.isEnglish(sections));
        List<Object> typedSections = toObjects(sections);
        RagflowNlp.makeColonAsTitle(typedSections);

        int bull = RagflowNlp.bulletsCategory(sections);
        if (bull == RagflowNlp.MARKDOWN_BULLET_GROUP_INDEX) {
            bull = 0;
        }

        List<String> merged;
        if (bull >= 0) {
            List<String> tm = RagflowNlp.treeMerge(bull, typedSections, 3);
            merged = new ArrayList<>(tm);
        } else {
            merged = RagflowNlp.naiveMerge(typedSections, chunkTokenNum, delimiter, overlappedPercent);
        }

        return ensureChunkTokenLimit(merged, chunkTokenNum, delimiter, overlappedPercent);
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
