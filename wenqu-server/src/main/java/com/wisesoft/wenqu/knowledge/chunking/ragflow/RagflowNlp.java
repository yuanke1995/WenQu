package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAGFlow 分块 NLP 工具层。
 * <p>
 * 逐函数对应参考实现 {@code knowledge/chunking/ragflow_like/nlp.py}：
 * 近似 token 计数、按 token 硬切、层级合并（tree_merge / hierarchical_merge）、
 * 标题/项目符号识别、目录表清理、冒号标题化、naive_merge 等，逻辑与正则保持一致；
 * 仅语言由 Python 译为 Java。
 * <p>必要替换：参考 BULLET_PATTERN 用 Python 正则 `{,2}`（即 {0,2} 的简写），Java
 * Pattern 不识别裸 `{,N}`，已统一改写为显式 `{0,2}`（语义一致，非逻辑改动）。
 */
public final class RagflowNlp {

    /** 标题层级/项目符号识别的若干组正则（对应参考实现 BULLET_PATTERN）。 */
    private static final List<List<String>> BULLET_PATTERN = Arrays.asList(
            Arrays.asList(
                    "第[零一二三四五六七八九十百0-9]+(分?编|部分)",
                    "第[零一二三四五六七八九十百0-9]+章",
                    "第[零一二三四五六七八九十百0-9]+节",
                    "第[零一二三四五六七八九十百0-9]+条",
                    "[\\(（][零一二三四五六七八九十百]+[\\)）]"),
            Arrays.asList(
                    "第[0-9]+章",
                    "第[0-9]+节",
                    "[0-9]{0,2}[\\. 、]",
                    "[0-9]{0,2}\\.[0-9]{0,2}[^a-zA-Z/%~-]",
                    "[0-9]{0,2}\\.[0-9]{0,2}\\.[0-9]{0,2}",
                    "[0-9]{0,2}\\.[0-9]{0,2}\\.[0-9]{0,2}\\.[0-9]{0,2}"),
            Arrays.asList(
                    "第[零一二三四五六七八九十百0-9]+章",
                    "第[零一二三四五六七八九十百0-9]+节",
                    "[零一二三四五六七八九十百]+[ 、]",
                    "[\\(（][零一二三四五六七八九十百]+[\\)）]",
                    "[\\(（][0-9]{0,2}[\\)）]"),
            Arrays.asList(
                    "PART (ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN)",
                    "Chapter (I+V?|VI*|XI|IX|X)",
                    "Section [0-9]+",
                    "Article [0-9]+"),
            Arrays.asList(
                    "^#[^#]",
                    "^##[^#]",
                    "^###.*",
                    "^####.*",
                    "^#####.*",
                    "^######.*"));

    /** Markdown 标题组在 BULLET_PATTERN 中的索引（0-based）。 */
    public static final int MARKDOWN_BULLET_GROUP_INDEX = 4;

    /** 近似值 token 计数正则：英文单词/数字/下划线 + 单个 CJK 字。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_]+|[一-鿿]");

    /** 英文行判定正则（对应参考实现 is_english 的 patt）。 */
    private static final Pattern ENGLISH_LINE_PATTERN =
            Pattern.compile("`[a-zA-Z0-9\\s.,':;/\"?<>!()\\-]+");

    private static final Random RANDOM = new Random();

    private RagflowNlp() {
    }

    // ==================== token 计数与硬切 ====================

    /** 近似 token 计数，避免引入额外依赖（对应 count_tokens）。 */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (text.strip().isEmpty()) {
            return 0;
        }
        Matcher m = TOKEN_PATTERN.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return Math.max(1, n);
    }

    private static List<int[]> tokenSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        if (text == null) {
            return spans;
        }
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
        }
        return spans;
    }

    /**
     * 将文本按 token 上限硬切，用于 naive_merge 之后的兜底保护（对应 hard_split_by_token_limit）。
     *
     * @param hardLimitTokenNum 仅当调用方显式传入时生效，允许略超目标长度的块保持完整；
     *                          为 null 时保持严格不超过 chunkTokenNum 的历史行为。
     */
    public static List<String> hardSplitByTokenLimit(String text, int chunkTokenNum, Integer hardLimitTokenNum) {
        List<int[]> spans = tokenSpans(text);
        if (spans.isEmpty()) {
            String cleaned = (text == null ? "" : text).strip();
            return cleaned.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(cleaned));
        }
        int maxTokens = Math.max(chunkTokenNum, 1);
        Integer hardLimit = null;
        if (hardLimitTokenNum != null) {
            hardLimit = Math.max(hardLimitTokenNum, maxTokens);
            if (spans.size() <= hardLimit) {
                String cleaned = (text == null ? "" : text).strip();
                return cleaned.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(cleaned));
            }
        }

        List<int[]> out = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < spans.size()) {
            int nextIndex = Math.min(index + maxTokens, spans.size());
            int end;
            if (nextIndex < spans.size()) {
                end = spans.get(nextIndex)[0];
            } else {
                end = text.length();
            }
            if (!text.substring(start, end).strip().isEmpty()) {
                out.add(new int[]{start, end});
            }
            start = end;
            index = nextIndex;
        }

        String tail = text.substring(start).strip();
        if (!tail.isEmpty()) {
            out.add(new int[]{start, text.length()});
        }

        if (hardLimit != null && out.size() >= 2) {
            int prevStart = out.get(out.size() - 2)[0];
            int tailEnd = out.get(out.size() - 1)[1];
            String candidate = text.substring(prevStart, tailEnd).strip();
            if (countTokens(candidate) <= hardLimit) {
                out.get(out.size() - 2)[1] = tailEnd;
                out.remove(out.size() - 1);
            }
        }

        List<String> result = new ArrayList<>();
        for (int[] s : out) {
            String chunk = text.substring(s[0], s[1]).strip();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 无放回抽样（对应 random_choices）。 */
    public static List<String> randomChoices(List<String> arr, int k) {
        if (arr == null || arr.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> copy = new ArrayList<>(arr);
        int take = Math.min(copy.size(), k);
        for (int i = 0; i < take; i++) {
            int j = i + RANDOM.nextInt(copy.size() - i);
            String tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
        }
        return new ArrayList<>(copy.subList(0, take));
    }

    /** 判断文本（或文本列表）是否以英文为主（对应 is_english）。 */
    public static boolean isEnglish(Object texts) {
        if (texts == null) {
            return false;
        }
        List<String> seq;
        if (texts instanceof String s) {
            seq = List.of(s);
        } else if (texts instanceof List<?> l) {
            seq = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof String str && !str.strip().isEmpty()) {
                    seq.add(str);
                }
            }
        } else {
            return false;
        }
        if (seq.isEmpty()) {
            return false;
        }
        int hits = 0;
        for (String t : seq) {
            if (ENGLISH_LINE_PATTERN.matcher(t.strip()).matches()) {
                hits++;
            }
        }
        return ((double) hits / seq.size()) > 0.8;
    }

    /** 是否不是项目符号行（对应 not_bullet）。 */
    public static boolean notBullet(String line) {
        String[] pats = {"0", "[0-9]+ +[0-9~个只-]", "[0-9]+\\.{2,}"};
        for (String p : pats) {
            if (Pattern.compile(p).matcher(line == null ? "" : line).find()) {
                return true;
            }
        }
        return false;
    }

    /** 是否可能是标题行（对应 is_probable_heading_line）。 */
    public static boolean isProbableHeadingLine(String line) {
        String text = (line == null ? "" : line).strip();
        if (text.isEmpty()) {
            return false;
        }
        if (Pattern.matches("^#{1,6}\\s+\\S", text)) {
            return true;
        }
        if (Pattern.compile("</?(table|tr|td|th|caption|tbody|thead)[^>]*>", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            return false;
        }
        if (text.length() > 96) {
            return false;
        }
        if (countTokens(text) > 72) {
            return false;
        }
        if (Pattern.compile("[，。；！？!?:：]").matcher(text.substring(0, Math.min(24, text.length()))).find()) {
            return false;
        }
        if ((text.endsWith("。") || text.endsWith("；") || text.endsWith("！") || text.endsWith("!")
                || text.endsWith("？") || text.endsWith("?")) && text.length() > 20) {
            return false;
        }
        return true;
    }

    private static boolean isMidSentenceBullet(String line) {
        String text = (line == null ? "" : line).strip();
        if (text.isEmpty()) {
            return false;
        }
        if (Pattern.matches("^#{1,6}\\s+\\S", text)) {
            return false;
        }
        Matcher marker = Pattern.compile("([一二三四五六七八九十百]+、|[\\(（][一二三四五六七八九十百]+[\\)）]|[0-9]{1,2}[\\.、])")
                .matcher(text);
        if (!marker.find()) {
            return false;
        }
        if (marker.start() == 0) {
            return false;
        }
        char prev = text.charAt(marker.start() - 1);
        return prev != '#' && prev != '\n';
    }

    /** 项目符号类别判定，返回命中组索引（对应 bullets_category）。 */
    public static int bulletsCategory(List<String> sections) {
        double[] hits = new double[BULLET_PATTERN.size()];
        for (int i = 0; i < BULLET_PATTERN.size(); i++) {
            List<String> pro = BULLET_PATTERN.get(i);
            for (String sec : sections) {
                sec = sec.strip();
                for (String p : pro) {
                    if (Pattern.compile(p).matcher(sec).find() && !notBullet(sec)) {
                        double w = bulletWeight(i, sec);
                        if (isMidSentenceBullet(sec)) {
                            w *= 0.1;
                        }
                        if (i != MARKDOWN_BULLET_GROUP_INDEX && !isProbableHeadingLine(sec)) {
                            w *= 0.2;
                        }
                        hits[i] += w;
                        break;
                    }
                }
            }
        }
        double maximum = 0;
        int res = -1;
        for (int i = 0; i < hits.length; i++) {
            if (hits[i] <= maximum) {
                continue;
            }
            res = i;
            maximum = hits[i];
        }
        return res;
    }

    private static double bulletWeight(int groupIdx, String line) {
        if (groupIdx != MARKDOWN_BULLET_GROUP_INDEX) {
            return 1.0;
        }
        String heading = line.strip();
        if (!Pattern.matches("^#{1,6}\\s+\\S", heading)) {
            return 1.0;
        }
        int level = heading.length() - heading.stripLeading().length();
        if (level <= 2) {
            return 4.0;
        }
        if (level <= 4) {
            return 3.0;
        }
        return 2.0;
    }

    private static String getText(Object section) {
        if (section instanceof String s) {
            return s.strip();
        }
        if (section instanceof Object[] arr && arr.length >= 1 && arr[0] instanceof String s) {
            return (s == null ? "" : s).strip();
        }
        return "";
    }

    /**
     * 清理目录表（对应 remove_contents_table）。sections 为 String 或 [text, layout] 二元组。
     */
    @SuppressWarnings("unchecked")
    public static void removeContentsTable(List<Object> sections, boolean eng) {
        int i = 0;
        while (i < sections.size()) {
            String line = getText(sections.get(i)).split("@@")[0];
            line = line.replaceAll("( |　|\\u3000)+", "");
            if (!Pattern.compile("(contents|目录|目次|tableofcontents|致谢|acknowledge)$", Pattern.CASE_INSENSITIVE)
                    .matcher(line).matches()) {
                i++;
                continue;
            }
            sections.remove(i);
            if (i >= sections.size()) {
                break;
            }
            String prefix = prefixOf(sections.get(i), eng);
            while (prefix.isEmpty() && i < sections.size()) {
                sections.remove(i);
                if (i >= sections.size()) {
                    break;
                }
                prefix = prefixOf(sections.get(i), eng);
            }
            if (i >= sections.size() || prefix.isEmpty()) {
                break;
            }
            sections.remove(i);
            if (i >= sections.size()) {
                break;
            }
            for (int j = i; j < Math.min(i + 128, sections.size()); j++) {
                if (!Pattern.compile(Pattern.quote(prefix)).matcher(getText(sections.get(j))).find()) {
                    continue;
                }
                for (int k = i; k < j; k++) {
                    sections.remove(i);
                }
                break;
            }
        }
    }

    private static String prefixOf(Object section, boolean eng) {
        String t = getText(section);
        if (eng) {
            String[] parts = t.split("\\s+");
            return parts.length >= 2 ? (parts[0] + " " + parts[1]) : "";
        }
        return t.length() >= 3 ? t.substring(0, 3) : "";
    }

    /**
     * 冒号标题化（对应 make_colon_as_title）。仅当 sections 元素为二元组（[text, layout]）时生效。
     */
    @SuppressWarnings("unchecked")
    public static List<Object> makeColonAsTitle(List<Object> sections) {
        if (sections == null || sections.isEmpty()) {
            return sections;
        }
        if (sections.get(0) instanceof String) {
            return sections;
        }
        int i = 0;
        while (i < sections.size()) {
            Object[] entry = (Object[]) sections.get(i);
            i++;
            String text = ((String) entry[0]).split("@")[0].strip();
            if (text.isEmpty() || !(text.endsWith(":") || text.endsWith("："))) {
                continue;
            }
            String rev = new StringBuilder(text).reverse().toString();
            String[] arr = rev.split("(。？！!?;；]| \\.)");
            if (arr.length < 2 || arr[1].length() < 32) {
                continue;
            }
            Object[] titleEntry = new Object[]{arr[0], "title"};
            sections.add(i - 1, titleEntry);
            i++;
        }
        return sections;
    }

    /** 是否不是标题（对应 not_title）。 */
    public static boolean notTitle(String text) {
        if (Pattern.matches("第[零一二三四五六七八九十百0-9]+条", text == null ? "" : text)) {
            return false;
        }
        if ((text.split(" ").length > 12) || (!text.contains(" ") && text.length() >= 32)) {
            return true;
        }
        return Pattern.compile("[,;，。；！!]").matcher(text).find();
    }

    private record LevelText(int level, String text) {
    }

    @SuppressWarnings("unchecked")
    private static LevelText getLevel(Object[] section, int bull) {
        String text = ((String) section[0]).replace("\u3000", " ").strip();
        for (int i = 0; i < BULLET_PATTERN.get(bull).size(); i++) {
            String patt = BULLET_PATTERN.get(bull).get(i);
            if (Pattern.compile(patt).matcher(text).find() && isProbableHeadingLine(text)) {
                return new LevelText(i + 1, text);
            }
        }
        String layout = (String) section[1];
        if ((layout.contains("title") || layout.contains("head")) && !notTitle(text)) {
            return new LevelText(BULLET_PATTERN.get(bull).size() + 1, text);
        }
        return new LevelText(BULLET_PATTERN.get(bull).size() + 2, text);
    }

    /**
     * 树形合并（对应 tree_merge）。返回合并后的文本块列表。
     */
    @SuppressWarnings("unchecked")
    public static List<String> treeMerge(int bull, List<Object> sections, int depth) {
        if (sections == null || sections.isEmpty() || bull < 0) {
            List<String> r = new ArrayList<>();
            if (sections != null) {
                for (Object s : sections) {
                    r.add(getText(s));
                }
            }
            return r;
        }
        List<Object[]> typed;
        if (sections.get(0) instanceof String) {
            typed = new ArrayList<>();
            for (Object s : sections) {
                typed.add(new Object[]{s, ""});
            }
        } else {
            typed = new ArrayList<>();
            for (Object s : sections) {
                typed.add((Object[]) s);
            }
        }
        typed.removeIf(e -> {
            String t = ((String) e[0]).split("@")[0].strip();
            return t.isEmpty() || t.length() <= 1 || Pattern.matches("[0-9]+$", t);
        });

        List<LevelText> lines = new ArrayList<>();
        Set<Integer> levelSet = new HashSet<>();
        for (Object[] section : typed) {
            LevelText lt = getLevel(section, bull);
            if (lt.text().strip().isEmpty()) {
                continue;
            }
            lines.add(lt);
            levelSet.add(lt.level());
        }
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }
        List<Integer> sortedLevels = new ArrayList<>(levelSet);
        sortedLevels.sort(Integer::compareTo);
        int targetLevel = depth <= sortedLevels.size() ? sortedLevels.get(depth - 1)
                : sortedLevels.get(sortedLevels.size() - 1);
        int maxBodyLevel = BULLET_PATTERN.get(bull).size() + 2;
        if (targetLevel == maxBodyLevel) {
            targetLevel = sortedLevels.size() > 1 ? sortedLevels.get(sortedLevels.size() - 2) : sortedLevels.get(0);
        }
        Node root = new Node(0, new ArrayList<>());
        root.depth = targetLevel;
        root.buildTree(lines);
        List<String> tree = root.getTree();
        return tree.stream().filter(s -> s != null && !s.isEmpty()).toList();
    }

    /**
     * 层级合并为二维块（对应 hierarchical_merge）。返回块列表，每块是若干文本段落的集合。
     */
    @SuppressWarnings("unchecked")
    public static List<List<String>> hierarchicalMerge(int bull, List<Object> sections, int depth) {
        if (sections == null || sections.isEmpty() || bull < 0) {
            return new ArrayList<>();
        }
        List<Object[]> typed;
        if (sections.get(0) instanceof String) {
            typed = new ArrayList<>();
            for (Object s : sections) {
                typed.add(new Object[]{s, ""});
            }
        } else {
            typed = new ArrayList<>();
            for (Object s : sections) {
                typed.add((Object[]) s);
            }
        }
        typed.removeIf(e -> {
            String t = ((String) e[0]).split("@")[0].strip();
            return t.isEmpty() || t.length() <= 1 || Pattern.matches("[0-9]+$", t);
        });

        int bulletsSize = BULLET_PATTERN.get(bull).size();
        List<List<Integer>> levels = new ArrayList<>();
        for (int i = 0; i < bulletsSize + 2; i++) {
            levels.add(new ArrayList<>());
        }
        List<String> pureSections = new ArrayList<>();
        for (int i = 0; i < typed.size(); i++) {
            String text = (String) typed.get(i)[0];
            String layout = (String) typed.get(i)[1];
            boolean placed = false;
            for (int j = 0; j < BULLET_PATTERN.get(bull).size(); j++) {
                String patt = BULLET_PATTERN.get(bull).get(j);
                if (Pattern.compile(patt).matcher(text.strip()).find() && isProbableHeadingLine(text)) {
                    levels.get(j).add(i);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                if ((layout.contains("title") || layout.contains("head")) && !notTitle(text)) {
                    levels.get(bulletsSize).add(i);
                } else {
                    levels.get(bulletsSize + 1).add(i);
                }
            }
            pureSections.add(text);
        }

        List<List<Integer>> cks = new ArrayList<>();
        boolean[] readed = new boolean[pureSections.size()];
        List<List<Integer>> reversed = new ArrayList<>(levels);
        reversed.sort((a, b) -> Integer.compare(levels.indexOf(b), levels.indexOf(a)));
        int limit = Math.min(depth, reversed.size());
        for (int i = 0; i < limit; i++) {
            for (int j : reversed.get(i)) {
                if (readed[j]) {
                    continue;
                }
                readed[j] = true;
                List<Integer> ck = new ArrayList<>();
                ck.add(j);
                cks.add(ck);
                if (i + 1 == reversed.size() - 1) {
                    continue;
                }
                for (int ii = i + 1; ii < reversed.size(); ii++) {
                    int jj = binarySearch(reversed.get(ii), j);
                    if (jj < 0) {
                        continue;
                    }
                    List<Integer> last = cks.get(cks.size() - 1);
                    if (reversed.get(ii).get(jj) > last.get(last.size() - 1)) {
                        last.remove(last.size() - 1);
                    }
                    last.add(reversed.get(ii).get(jj));
                }
                for (int ii : cks.get(cks.size() - 1)) {
                    readed[ii] = true;
                }
            }
        }
        if (cks.isEmpty()) {
            return new ArrayList<>();
        }
        for (int i = 0; i < cks.size(); i++) {
            List<Integer> rev = new ArrayList<>();
            for (int k = cks.get(i).size() - 1; k >= 0; k--) {
                rev.add(cks.get(i).get(k));
            }
            cks.set(i, rev);
        }

        // 纯校验：ck 中的索引需能在 pureSections 中定位（防御性）
        List<List<String>> res = new ArrayList<>();
        res.add(new ArrayList<>());
        List<Integer> num = new ArrayList<>();
        num.add(0);
        for (List<Integer> ck : cks) {
            if (ck.size() == 1) {
                String txt = pureSections.get(ck.get(0));
                int n = countTokens(txt.replaceAll("@@[0-9]+.*", ""));
                if (n + num.get(num.size() - 1) < 218) {
                    res.get(res.size() - 1).add(txt);
                    num.set(num.size() - 1, num.get(num.size() - 1) + n);
                    continue;
                }
                List<String> single = new ArrayList<>();
                single.add(txt);
                res.add(single);
                num.add(n);
                continue;
            }
            List<String> multi = new ArrayList<>();
            for (int k : ck) {
                multi.add(pureSections.get(k));
            }
            res.add(multi);
            num.add(218);
        }
        return res.stream().filter(chunk -> !chunk.isEmpty()).toList();
    }

    private static int binarySearch(List<Integer> arr, int target) {
        if (arr == null || arr.isEmpty()) {
            return -1;
        }
        if (target > arr.get(arr.size() - 1)) {
            return arr.size() - 1;
        }
        if (target < arr.get(0)) {
            return -1;
        }
        int s = 0;
        int e = arr.size();
        while (e - s > 1) {
            int mid = (e + s) / 2;
            if (target > arr.get(mid)) {
                s = mid;
            } else if (target < arr.get(mid)) {
                e = mid;
            } else {
                return mid;
            }
        }
        return s;
    }

    private static String removePdfTags(String text) {
        return (text == null ? "" : text).replaceAll("@@[0-9-\\t]+\\t[0-9.\\t]+##", "");
    }

    private static List<String> extractCustomDelimiters(String delimiter) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(delimiter == null ? "" : delimiter);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 按 token 上限合并分块（对应 naive_merge）。
     * sections 可为 String、或 [text, layout] 二元组；返回合并后的文本块列表。
     */
    @SuppressWarnings("unchecked")
    public static List<String> naiveMerge(Object sections, int chunkTokenNum, String delimiter, int overlappedPercent) {
        if (sections == null) {
            return new ArrayList<>();
        }
        List<Object[]> typed;
        if (sections instanceof String s) {
            typed = new ArrayList<>();
            typed.add(new Object[]{s, ""});
        } else if (sections instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof String) {
                typed = new ArrayList<>();
                for (Object o : list) {
                    typed.add(new Object[]{o, ""});
                }
            } else {
                typed = new ArrayList<>();
                for (Object o : list) {
                    typed.add((Object[]) o);
                }
            }
        } else {
            return new ArrayList<>();
        }

        int maxTokens = Math.max(chunkTokenNum, 0);
        int overlap = Math.max(0, Math.min(overlappedPercent, 99));

        List<String> customDelimiters = extractCustomDelimiters(delimiter);
        if (!customDelimiters.isEmpty()) {
            String pattern = String.join("|", customDelimiters.stream()
                    .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                    .map(Pattern::quote)
                    .toList());
            Pattern compiled = Pattern.compile("(" + pattern + ")", Pattern.DOTALL);
            List<String> chunks = new ArrayList<>();
            for (Object[] sec : typed) {
                List<String> splitSec = splitKeepDelimiters((String) sec[0], compiled);
                for (String sub : splitSec) {
                    if (Pattern.matches("(" + pattern + ")", sub == null ? "" : sub)) {
                        continue;
                    }
                    String text = "\n" + sub;
                    String localPos = (String) sec[1];
                    if (countTokens(text) >= 8) {
                        localPos = "";
                    }
                    if (localPos != null && !localPos.isEmpty() && !text.contains(localPos)) {
                        text += localPos;
                    }
                    if (!text.strip().isEmpty()) {
                        chunks.add(text);
                    }
                }
            }
            return chunks;
        }

        if (maxTokens <= 0) {
            StringBuilder merged = new StringBuilder();
            for (Object[] sec : typed) {
                String secText = (String) sec[0];
                if (secText != null && !secText.strip().isEmpty()) {
                    if (merged.length() > 0) {
                        merged.append("\n");
                    }
                    merged.append(secText.strip());
                }
            }
            String m = merged.toString();
            return m.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(m));
        }

        List<String> chunks = new ArrayList<>();
        chunks.add("");
        List<Integer> tokenNums = new ArrayList<>();
        tokenNums.add(0);

        for (Object[] sec : typed) {
            if (sec[0] == null) {
                continue;
            }
            addChunk(chunks, tokenNums, "\n" + sec[0], (String) sec[1], maxTokens, overlap);
        }

        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (!chunk.strip().isEmpty()) {
                result.add(chunk);
            }
        }
        return result;
    }

    private static void addChunk(List<String> chunks, List<Integer> tokenNums, String text, String pos,
                                int chunkTokenNum, int overlap) {
        int tnum = countTokens(text);
        String localPos = (pos == null) ? "" : pos;
        if (tnum < 8) {
            localPos = "";
        }
        double threshold = chunkTokenNum * (100.0 - overlap) / 100.0;
        if (chunks.get(chunks.size() - 1).isEmpty() || tokenNums.get(tokenNums.size() - 1) > threshold) {
            if (!chunks.get(chunks.size() - 1).isEmpty()) {
                String prev = removePdfTags(chunks.get(chunks.size() - 1));
                int start = (int) (prev.length() * (100.0 - overlap) / 100.0);
                text = prev.substring(start) + text;
            }
            if (localPos != null && !localPos.isEmpty() && !text.contains(localPos)) {
                text += localPos;
            }
            chunks.add(text);
            tokenNums.add(tnum);
        } else {
            if (localPos != null && !localPos.isEmpty() && !chunks.get(chunks.size() - 1).contains(localPos)) {
                text += localPos;
            }
            chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + text);
            tokenNums.set(tokenNums.size() - 1, tokenNums.get(tokenNums.size() - 1) + tnum);
        }
    }

    /** 按分隔正则切分并保留分隔符（Python re.split 带捕获组语义）。 */
    private static List<String> splitKeepDelimiters(String text, Pattern delim) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = delim.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(text.substring(last, m.start()));
            }
            out.add(m.group());
            last = m.end();
        }
        if (last < text.length()) {
            out.add(text.substring(last));
        }
        return out;
    }

    // ==================== 树节点（对应 nlp.Node） ====================

    /** 层级树节点（对应参考实现 Node）。 */
    public static class Node {
        public int level;
        public int depth;
        public List<String> texts = new ArrayList<>();
        public List<Node> children = new ArrayList<>();

        /** 构造节点（对应 Python dataclass：depth 默认 -1，texts 默认空）。 */
        public Node(int level, List<String> texts) {
            this.level = level;
            this.depth = -1;
            this.texts = texts == null ? new ArrayList<>() : texts;
        }

        public void addChild(Node childNode) {
            this.children.add(childNode);
        }

        public void addText(String text) {
            this.texts.add(text);
        }

        public Node buildTree(List<LevelText> lines) {
            List<Node> stack = new ArrayList<>();
            stack.add(this);
            for (LevelText line : lines) {
                int lvl = line.level();
                String text = line.text();
                if (this.depth != -1 && lvl > this.depth) {
                    stack.get(stack.size() - 1).addText(text);
                    continue;
                }
                while (stack.size() > 1 && lvl <= stack.get(stack.size() - 1).level) {
                    stack.remove(stack.size() - 1);
                }
                Node node = new Node(lvl, new ArrayList<>());
                node.texts.add(text);
                stack.get(stack.size() - 1).addChild(node);
                stack.add(node);
            }
            return this;
        }

        public List<String> getTree() {
            List<String> treeList = new ArrayList<>();
            dfs(this, treeList, new ArrayList<>());
            return treeList;
        }

        private void dfs(Node node, List<String> treeList, List<String> titles) {
            int level = node.level;
            List<String> texts = node.texts;
            List<Node> child = node.children;
            if (level == 0 && !texts.isEmpty()) {
                List<String> combined = new ArrayList<>(titles);
                combined.addAll(texts);
                treeList.add(String.join("\n", combined));
            }
            List<String> pathTitles;
            if (1 <= level && level <= this.depth) {
                pathTitles = new ArrayList<>(titles);
                pathTitles.addAll(texts);
            } else {
                pathTitles = new ArrayList<>(titles);
            }
            if (level > this.depth && !texts.isEmpty()) {
                List<String> combined = new ArrayList<>(pathTitles);
                combined.addAll(texts);
                treeList.add(String.join("\n", combined));
            } else if (child.isEmpty() && (1 <= level && level <= this.depth)) {
                if (!pathTitles.isEmpty()) {
                    treeList.add(String.join("\n", pathTitles));
                }
            }
            for (Node c : child) {
                dfs(c, treeList, pathTitles);
            }
        }
    }
}
