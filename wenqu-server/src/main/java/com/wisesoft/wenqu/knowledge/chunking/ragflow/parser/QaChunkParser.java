package com.wisesoft.wenqu.knowledge.chunking.ragflow.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA 分块解析器（对应参考实现 {@code knowledge/chunking/ragflow_like/parsers/qa.py}）。
 * <p>
 * 按文件后缀选择提取器子集（行首 Q/A 前缀 / Markdown 标题 / Markdown 表格 / 分隔符），
 * 去重后统一渲染为「问题：xxx\t回答：yyy」；超长 chunk 保留问题、切分答案，避免超过 embedding 上下文上限。
 * 纯字符串/正则逻辑，不依赖 markdown_it；逻辑与正则保持一致。
 */
public final class QaChunkParser {

    /** QA chunk 字符数硬上限（对应 _QA_CHUNK_MAX_CHARS）。 */
    private static final int QA_CHUNK_MAX_CHARS = 4000;
    private static final String[] QA_QUESTION_PREFIXES = {"问题：", "Question: "};
    private static final String[] QA_ANSWER_PREFIXES = {"回答：", "Answer: "};

    private static final Pattern PREFIX_REMOVE = Pattern.compile(
            "^(问题|答案|回答|user|assistant|Q|A|Question|Answer|问|答)[\\t:： ]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_TABLE_ROW = Pattern.compile("\\|");
    private static final Pattern MD_SEP_CELL = Pattern.compile(":?-{3,}:?");
    private static final Pattern MD_QUESTION_HEADING = Pattern.compile("^(#{1,6})(?:[ \\t]+|$)");
    private static final Pattern Q_RE = Pattern.compile(
            "^(?:Q|Question|问|问题)\\s*[:：]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern A_RE = Pattern.compile(
            "^(?:A|Answer|答|回答)\\s*[:：]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_RE = Pattern.compile("```|~~~");

    private QaChunkParser() {
    }

    private static String rmPrefix(String text) {
        if (text == null) {
            return "";
        }
        return PREFIX_REMOVE.matcher(text.strip()).replaceFirst("");
    }

    private static String toQaChunk(String question, String answer, boolean eng) {
        String qprefix = eng ? "Question: " : "问题：";
        String aprefix = eng ? "Answer: " : "回答：";
        return qprefix + rmPrefix(question) + "\t" + aprefix + rmPrefix(answer);
    }

    private static String guessDelimiter(List<String> lines) {
        int comma = 0;
        int tab = 0;
        for (String line : lines) {
            if (line.split(",").length == 2) {
                comma++;
            }
            if (line.split("\t", -1).length == 2) {
                tab++;
            }
        }
        return tab >= comma ? "\t" : ",";
    }

    private static List<String[]> extractPairsWithDelimiter(List<String> lines, String delimiter) {
        List<String[]> pairs = new ArrayList<>();
        String question = "";
        String answer = "";
        for (String line : lines) {
            String[] arr = line.split(Pattern.quote(delimiter), -1);
            if (arr.length != 2) {
                if (!question.isEmpty()) {
                    answer += "\n" + line;
                }
                continue;
            }
            if (!question.isEmpty() && !answer.isEmpty()) {
                pairs.add(new String[]{question, answer});
            }
            question = arr[0];
            answer = arr[1];
        }
        if (!question.isEmpty()) {
            pairs.add(new String[]{question, answer});
        }
        List<String[]> out = new ArrayList<>();
        for (String[] p : pairs) {
            if (!p[0].strip().isEmpty()) {
                out.add(new String[]{p[0].strip(), p[1].strip()});
            }
        }
        return out;
    }

    private static List<String[]> extractPairsFromCsv(List<String> lines, String delimiter) {
        List<String[]> pairs = new ArrayList<>();
        String question = "";
        String answer = "";
        for (String rawLine : lines) {
            String[] row = splitCsvLine(rawLine, delimiter);
            if (row.length != 2) {
                if (!question.isEmpty()) {
                    answer += "\n" + rawLine;
                }
                continue;
            }
            if (!question.isEmpty() && !answer.isEmpty()) {
                pairs.add(new String[]{question, answer});
            }
            question = row[0];
            answer = row[1];
        }
        if (!question.isEmpty()) {
            pairs.add(new String[]{question, answer});
        }
        List<String[]> out = new ArrayList<>();
        for (String[] p : pairs) {
            if (!p[0].strip().isEmpty()) {
                out.add(new String[]{p[0].strip(), p[1].strip()});
            }
        }
        return out;
    }

    /** 极简 CSV 行解析：支持双引号包裹（对应 csv.reader 的两列场景）。 */
    private static String[] splitCsvLine(String line, String delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (line.startsWith(delimiter, i)) {
                    fields.add(cur.toString());
                    cur = new StringBuilder();
                    i += delimiter.length();
                } else {
                    cur.append(c);
                    i++;
                }
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    private static String[] parseMarkdownTableRow(String line) {
        if (line == null || !line.contains("|")) {
            return null;
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("|")) {
            text = text.substring(1);
        }
        if (text.endsWith("|")) {
            text = text.substring(0, text.length() - 1);
        }
        String[] cells = text.split("\\|", -1);
        List<String> cleaned = new ArrayList<>();
        for (String cell : cells) {
            cleaned.add(cell.strip());
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        for (String cell : cleaned) {
            if (!cell.isEmpty() && MD_SEP_CELL.matcher(cell.replace(" ", "")).matches()) {
                return null;
            }
        }
        return cleaned.toArray(new String[0]);
    }

    private static List<String[]> extractPairsFromMarkdownTables(String markdownContent) {
        List<String[]> pairs = new ArrayList<>();
        for (String line : (markdownContent == null ? "" : markdownContent).split("\n", -1)) {
            String[] cells = parseMarkdownTableRow(line);
            if (cells == null || cells.length < 2) {
                continue;
            }
            String question = cells[0];
            String answer = cells[1];
            if (!question.isEmpty() && !answer.isEmpty()) {
                pairs.add(new String[]{question, answer});
            }
        }
        return pairs;
    }

    private static int mdQuestionLevel(String line) {
        Matcher m = MD_QUESTION_HEADING.matcher(line);
        if (!m.find()) {
            return 0;
        }
        return m.group(1).length();
    }

    private static String updateFenceState(String line, String fence) {
        String stripped = line.strip();
        if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
            String marker = stripped.length() >= 3 ? stripped.substring(0, 3) : stripped;
            if (fence.isEmpty()) {
                return marker;
            }
            if (marker.equals(fence)) {
                return "";
            }
        }
        return fence;
    }

    private static List<String[]> extractPairsFromMarkdownHeadings(String markdownContent) {
        List<String[]> pairs = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String l : (markdownContent == null ? "" : markdownContent).split("\n", -1)) {
            lines.add(l);
        }
        if (lines.isEmpty()) {
            return pairs;
        }
        String lastAnswer = "";
        List<String> questionStack = new ArrayList<>();
        List<Integer> levelStack = new ArrayList<>();
        String fence = "";
        for (String line : lines) {
            fence = updateFenceState(line, fence);
            int questionLevel = 0;
            String question = "";
            if (fence.isEmpty()) {
                questionLevel = mdQuestionLevel(line);
                if (questionLevel > 0 && questionLevel <= 6) {
                    Matcher m = MD_QUESTION_HEADING.matcher(line);
                    m.find();
                    question = line.substring(m.end()).strip();
                }
            }
            if (questionLevel == 0 || questionLevel > 6) {
                lastAnswer = lastAnswer + "\n" + line;
                continue;
            }
            if (!lastAnswer.strip().isEmpty()) {
                String sumQuestion = String.join("\n", questionStack);
                if (!sumQuestion.isEmpty()) {
                    pairs.add(new String[]{sumQuestion, lastAnswer.strip()});
                }
                lastAnswer = "";
            }
            while (!questionStack.isEmpty() && questionLevel <= levelStack.get(levelStack.size() - 1)) {
                questionStack.remove(questionStack.size() - 1);
                levelStack.remove(levelStack.size() - 1);
            }
            questionStack.add(question);
            levelStack.add(questionLevel);
        }
        if (!lastAnswer.strip().isEmpty()) {
            String sumQuestion = String.join("\n", questionStack);
            if (!sumQuestion.isEmpty()) {
                pairs.add(new String[]{sumQuestion, lastAnswer.strip()});
            }
        }
        return pairs;
    }

    private static List<String[]> extractPairsByPrefix(String markdownContent) {
        List<String[]> pairs = new ArrayList<>();
        String question = "";
        List<String> answerLines = new ArrayList<>();
        Pattern headingRe = Pattern.compile("^#{1,6}(?:[ \\t]+|$)");
        String fence = "";

        // 注：参考实现的 flush_pair 用 nonlocal 修改外层变量；Java 无法在 lambda 内修改外层
        // String/List，故改为下方循环内显式 flush（见各处 pairs.add(...) 后重置 question/answerLines）。
        for (String line : (markdownContent == null ? "" : markdownContent).split("\n", -1)) {
            String stripped = line.strip();
            boolean isFenceLine = stripped.startsWith("```") || stripped.startsWith("~~~");
            fence = updateFenceState(line, fence);
            if (isFenceLine || !fence.isEmpty()) {
                if (!question.isEmpty()) {
                    answerLines.add(line);
                }
                continue;
            }
            boolean headingMatch = headingRe.matcher(line).find();
            String text = headingMatch ? line.substring(line.indexOf(' ') + 1) : line;
            Matcher qm = Q_RE.matcher(text);
            if (qm.find()) {
                if (!question.isEmpty()) {
                    pairs.add(new String[]{question, String.join("\n", answerLines)});
                    question = "";
                    answerLines = new ArrayList<>();
                }
                question = qm.group(1).strip();
                continue;
            }
            Matcher am = A_RE.matcher(text);
            if (am.find()) {
                if (!question.isEmpty()) {
                    answerLines.add(am.group(1).strip());
                }
                continue;
            }
            if (headingMatch) {
                if (!question.isEmpty()) {
                    pairs.add(new String[]{question, String.join("\n", answerLines)});
                    question = "";
                    answerLines = new ArrayList<>();
                }
                continue;
            }
            if (!question.isEmpty()) {
                answerLines.add(line);
            }
        }
        if (!question.isEmpty()) {
            pairs.add(new String[]{question, String.join("\n", answerLines)});
        }
        List<String[]> out = new ArrayList<>();
        for (String[] p : pairs) {
            if (!p[0].strip().isEmpty() && !p[1].strip().isEmpty()) {
                out.add(new String[]{p[0].strip(), p[1].strip()});
            }
        }
        return out;
    }

    private static List<String[]> dedupePairs(List<String[]> pairs) {
        List<String[]> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] p : pairs) {
            String q = p[0].strip();
            String a = p[1].strip();
            if (q.isEmpty() || a.isEmpty()) {
                continue;
            }
            String key = q + "\u0000" + a;
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            res.add(new String[]{q, a});
        }
        return res;
    }

    private static String[] splitQaPrefix(String text, String[] prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return new String[]{prefix, text.substring(prefix.length()).strip()};
            }
        }
        return new String[]{"", text.strip()};
    }

    private static List<String> hardSplitText(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxChars) {
            String part = text.substring(i, Math.min(i + maxChars, text.length()));
            if (!part.strip().isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private static List<String> splitAnswerByParagraphs(String answer, int maxChars) {
        List<String> paragraphs = new ArrayList<>();
        for (String p : answer.split("\n\n", -1)) {
            String t = p.strip();
            if (!t.isEmpty()) {
                paragraphs.add(t);
            }
        }
        if (paragraphs.isEmpty()) {
            String t = answer.strip();
            return t.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(answer));
        }
        List<String> result = new ArrayList<>();
        String current = "";
        for (String p : paragraphs) {
            if (p.length() > maxChars) {
                if (!current.isEmpty()) {
                    result.add(current);
                    current = "";
                }
                result.addAll(splitAnswerByLines(p, maxChars));
                continue;
            }
            if (!current.isEmpty() && current.length() + 2 + p.length() > maxChars) {
                result.add(current);
                current = p;
            } else {
                current = current.isEmpty() ? p : current + "\n\n" + p;
            }
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private static List<String> splitAnswerByLines(String answer, int maxChars) {
        List<String> lines = new ArrayList<>();
        for (String line : answer.split("\n", -1)) {
            if (!line.strip().isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            String t = answer.strip();
            return t.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(answer));
        }
        List<String> result = new ArrayList<>();
        String current = "";
        for (String line : lines) {
            if (line.length() > maxChars) {
                if (!current.isEmpty()) {
                    result.add(current);
                    current = "";
                }
                result.addAll(hardSplitText(line, maxChars));
                continue;
            }
            if (!current.isEmpty() && current.length() + 1 + line.length() > maxChars) {
                result.add(current);
                current = line;
            } else {
                current = current.isEmpty() ? line : current + "\n" + line;
            }
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private static List<String> splitLongQaChunks(List<String> chunks, int maxChars) {
        if (maxChars <= 0) {
            List<String> r = new ArrayList<>();
            for (String c : chunks) {
                String t = (c == null ? "" : c).strip();
                if (!t.isEmpty()) {
                    r.add(t);
                }
            }
            return r;
        }
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            String text = (chunk == null ? "" : chunk).strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() <= maxChars) {
                result.add(text);
                continue;
            }
            String marker;
            if (text.startsWith("问题：")) {
                marker = "\t回答：";
            } else if (text.startsWith("Question: ")) {
                marker = "\tAnswer: ";
            } else {
                marker = "";
            }
            int sepPos = marker.isEmpty() ? -1 : text.indexOf(marker);
            if (sepPos == -1) {
                result.addAll(hardSplitText(text, maxChars));
                continue;
            }
            String qPart = text.substring(0, sepPos);
            String aPart = text.substring(sepPos + 1);
            String[] q = splitQaPrefix(qPart, QA_QUESTION_PREFIXES);
            String[] a = splitQaPrefix(aPart, QA_ANSWER_PREFIXES);
            if (q[1].isEmpty() || a[1].isEmpty()) {
                result.addAll(hardSplitText(text, maxChars));
                continue;
            }
            if (q[0].length() + q[1].length() + a[0].length() + 1 >= maxChars) {
                result.addAll(hardSplitText(text, maxChars));
                continue;
            }
            int maxAnswerChars = maxChars - q[0].length() - q[1].length() - a[0].length() - 1;
            for (String subAnswer : splitAnswerByParagraphs(a[1], maxAnswerChars)) {
                result.add(q[0] + q[1] + "\t" + a[0] + subAnswer);
            }
        }
        return result;
    }

    /** QA 分块主流程（对应 chunk_markdown）。 */
    public static List<String> chunkMarkdown(
            String filename, String markdownContent, Map<String, Object> parserConfig) {
        Map<String, Object> config = parserConfig == null ? new java.util.LinkedHashMap<>() : parserConfig;
        boolean eng = "english".equalsIgnoreCase(str(config.get("language"), "Chinese"));

        String suffix = "";
        if (filename != null && filename.contains(".")) {
            suffix = "." + filename.toLowerCase().split("\\.")[filename.split("\\.").length - 1];
        }

        List<String> lines = new ArrayList<>();
        for (String l : (markdownContent == null ? "" : markdownContent).split("\n", -1)) {
            if (!l.strip().isEmpty()) {
                lines.add(l);
            }
        }
        List<String[]> pairs = new ArrayList<>();

        if (suffix.equals(".xlsx") || suffix.equals(".xls")) {
            pairs.addAll(extractPairsFromMarkdownTables(markdownContent));
            if (pairs.isEmpty()) {
                String delimiter = guessDelimiter(lines);
                pairs.addAll(extractPairsWithDelimiter(lines, delimiter));
            }
        } else if (suffix.equals(".csv")) {
            pairs.addAll(extractPairsFromMarkdownTables(markdownContent));
            String delimiter = lines.stream().anyMatch(l -> l.contains("\t")) ? "\t" : ",";
            pairs.addAll(extractPairsFromCsv(lines, delimiter));
        } else if (suffix.equals(".txt")) {
            String delimiter = guessDelimiter(lines);
            pairs.addAll(extractPairsWithDelimiter(lines, delimiter));
            if (pairs.isEmpty()) {
                pairs.addAll(extractPairsByPrefix(markdownContent));
            }
        } else if (suffix.equals(".md") || suffix.equals(".markdown") || suffix.equals(".mdx")
                || suffix.equals(".docx")) {
            pairs.addAll(extractPairsByPrefix(markdownContent));
            if (pairs.isEmpty()) {
                pairs.addAll(extractPairsFromMarkdownHeadings(markdownContent));
            }
            pairs.addAll(extractPairsFromMarkdownTables(markdownContent));
        } else {
            pairs.addAll(extractPairsByPrefix(markdownContent));
            if (pairs.isEmpty()) {
                pairs.addAll(extractPairsFromMarkdownHeadings(markdownContent));
            }
            pairs.addAll(extractPairsFromMarkdownTables(markdownContent));
            if (pairs.isEmpty()) {
                String delimiter = guessDelimiter(lines);
                pairs.addAll(extractPairsWithDelimiter(lines, delimiter));
            }
        }

        pairs = dedupePairs(pairs);

        if (pairs.isEmpty() && !lines.isEmpty()) {
            for (int i = 0; i < lines.size(); i += 2) {
                String q = lines.get(i);
                String a = (i + 1 < lines.size()) ? lines.get(i + 1) : "";
                if (!q.strip().isEmpty() && !a.strip().isEmpty()) {
                    pairs.add(new String[]{q, a});
                }
            }
        }

        List<String> chunks = new ArrayList<>();
        for (String[] p : pairs) {
            chunks.add(toQaChunk(p[0], p[1], eng));
        }
        return splitLongQaChunks(chunks, QA_CHUNK_MAX_CHARS);
    }

    private static String str(Object o, String fallback) {
        if (o == null) {
            return fallback;
        }
        String s = String.valueOf(o);
        return s.isEmpty() ? fallback : s;
    }
}
