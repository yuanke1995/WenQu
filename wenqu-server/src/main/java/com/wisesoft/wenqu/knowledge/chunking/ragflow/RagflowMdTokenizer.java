package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown tokenizer（能力差异：替换参考实现的 markdown-it + dollarmath_plugin）。
 * <p>
 * 逐行扫描 markdown，产出与 markdown-it 结构兼容的 token 序列，供
 * {@link SemanticChunkParser} 消费。产出保证以下分组/索引语义与参考一致：
 * <ul>
 *   <li>标题：{@code heading_open(tag=h1..h6)} + {@code inline(content)} + {@code heading_close}（3 个）</li>
 *   <li>段落：{@code paragraph_open} + {@code inline(content)} + {@code paragraph_close}（3 个）</li>
 *   <li>围栏代码：单个 {@code fence(content)}</li>
 *   <li>表格：{@code table_open(map=[start,end])} … {@code table_close}（map 供 extract_table_block 用）</li>
 *   <li>有序/无序列表：{@code *list_open} … {@code *list_close}，内嵌
 *       {@code list_item_open} + {@code paragraph_open} + {@code inline} + {@code list_item_close}</li>
 *   <li>HTML 块：单个 {@code html_block(content)}</li>
 *   <li>数学块：单个 {@code math_block(content)}（对应 dollarmath_plugin 的 $$ 与行内 $…$）</li>
 * </ul>
 * 能力差异说明：dollarmath_plugin 对 `$...$` 的判定依赖 markdown-it 的 token 上下文；Java 侧以
 * 简单规则近似（独占行或连续行内的 `$$...$$` / `$...$`），仅影响数学块识别，不影响其余结构。
 */
public final class RagflowMdTokenizer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final Pattern HTML_BLOCK_START = Pattern.compile("(?i)^\\s*<(table|div|pre|blockquote|script|style)\\b");
    private static final Pattern IMG_LINE = Pattern.compile("(?is)^\\s*!\\[.*?\\]\\(.*?\\)\\s*$");

    private RagflowMdTokenizer() {
    }

    /**
     * 将 markdown 文本解析为 token 序列。
     */
    public static List<MdToken> parse(String markdown) {
        List<MdToken> tokens = new ArrayList<>();
        if (markdown == null) {
            return tokens;
        }
        String[] lines = markdown.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // 数学块（dollarmath 近似）：独占行的 $$...$$ 或多行 $$ 块
            MathBlock math = tryMathBlock(lines, i);
            if (math != null) {
                tokens.add(new MdToken("math_block").content(math.content()).map(math.map()));
                i = math.endLine();
                continue;
            }

            // 围栏代码块
            Matcher fm = FENCE.matcher(line);
            if (fm.find()) {
                StringBuilder sb = new StringBuilder();
                String fenceChar = fm.group(1);
                int j = i + 1;
                boolean closed = false;
                while (j < lines.length) {
                    if (lines[j].strip().startsWith(fenceChar)) {
                        closed = true;
                        break;
                    }
                    sb.append(lines[j]);
                    if (j < lines.length - 1) {
                        sb.append("\n");
                    }
                    j++;
                }
                tokens.add(new MdToken("fence").content(sb.toString()));
                i = closed ? j + 1 : lines.length;
                continue;
            }

            // 标题
            Matcher hm = HEADING.matcher(line);
            if (hm.find()) {
                int level = hm.group(1).length();
                String title = hm.group(2).strip();
                MdToken open = new MdToken("heading_open").tag("h" + level).map(new int[]{i, i + 1});
                MdToken inline = new MdToken("inline").content(title);
                MdToken close = new MdToken("heading_close");
                tokens.add(open);
                tokens.add(inline);
                tokens.add(close);
                i++;
                continue;
            }

            // HTML 块（仅 table/div 等块级标签起头的整块）
            if (HTML_BLOCK_START.matcher(line).find()) {
                StringBuilder sb = new StringBuilder();
                int j = i;
                while (j < lines.length) {
                    sb.append(lines[j]);
                    if (j < lines.length - 1) {
                        sb.append("\n");
                    }
                    if (hasClosingTag(lines[j])) {
                        j++;
                        break;
                    }
                    j++;
                }
                tokens.add(new MdToken("html_block").content(sb.toString()).map(new int[]{i, j}));
                i = j;
                continue;
            }

            // 表格：表头行 + 分隔行 + 数据行（commonmark 的 table 扩展）
            if (isTableRow(line) && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                int startLine = i;
                int j = i;
                while (j < lines.length && isTableRow(lines[j])) {
                    j++;
                }
                tokens.add(new MdToken("table_open").map(new int[]{startLine, j}));
                // 逐行产出 paragraph_open + inline(content=原始行) + paragraph_close
                for (int k = startLine; k < j; k++) {
                    tokens.add(new MdToken("paragraph_open"));
                    tokens.add(new MdToken("inline").content(lines[k]));
                    tokens.add(new MdToken("paragraph_close"));
                }
                tokens.add(new MdToken("table_close").map(new int[]{startLine, j}));
                i = j;
                continue;
            }

            // 无序列表
            if (isBulletItem(line)) {
                int j = i;
                while (j < lines.length && (isBulletItem(lines[j])
                        || (lines[j].strip().isEmpty() && j + 1 < lines.length && isBulletItem(lines[j + 1])))) {
                    j++;
                }
                tokens.add(new MdToken("bullet_list_open").map(new int[]{i, j}));
                for (int k = i; k < j; k++) {
                    String l = lines[k];
                    if (l.strip().isEmpty()) {
                        continue;
                    }
                    tokens.add(new MdToken("list_item_open"));
                    tokens.add(new MdToken("paragraph_open"));
                    tokens.add(new MdToken("inline").content(stripBulletMarker(l)));
                    tokens.add(new MdToken("list_item_close"));
                }
                tokens.add(new MdToken("bullet_list_close"));
                i = j;
                continue;
            }

            // 有序列表
            if (isOrderedItem(line)) {
                int j = i;
                while (j < lines.length && isOrderedItem(lines[j])) {
                    j++;
                }
                tokens.add(new MdToken("ordered_list_open").map(new int[]{i, j}));
                int counter = 1;
                for (int k = i; k < j; k++) {
                    String l = lines[k];
                    if (l.strip().isEmpty()) {
                        continue;
                    }
                    String content = stripOrderedMarker(l);
                    // 参考实现：数字序号由 list_item_counter 生成，与原文编号无关
                    tokens.add(new MdToken("list_item_open"));
                    tokens.add(new MdToken("paragraph_open"));
                    tokens.add(new MdToken("inline").content(counter + ". " + content));
                    tokens.add(new MdToken("list_item_close"));
                    counter++;
                }
                tokens.add(new MdToken("ordered_list_close"));
                i = j;
                continue;
            }

            // 空行跳过
            if (line.strip().isEmpty()) {
                i++;
                continue;
            }

            // 普通段落
            MdToken pOpen = new MdToken("paragraph_open").map(new int[]{i, i + 1});
            MdToken inline = new MdToken("inline").content(line.strip());
            MdToken pClose = new MdToken("paragraph_close");
            tokens.add(pOpen);
            tokens.add(inline);
            tokens.add(pClose);
            i++;
        }
        return tokens;
    }

    // ==================== 行判定辅助 ====================

    private static boolean isTableRow(String line) {
        String t = line.strip();
        return t.contains("|") && !IMG_LINE.matcher(t).matches();
    }

    private static boolean isBulletItem(String line) {
        return Pattern.compile("^\\s*[-*+](?:\\s+|$)").matcher(line).find();
    }

    private static boolean isOrderedItem(String line) {
        return Pattern.compile("^\\s*\\d+[.、)](?:\\s+|$)").matcher(line).find();
    }

    private static String stripBulletMarker(String line) {
        return Pattern.compile("^\\s*[-*+](?:\\s+|$)").matcher(line).replaceFirst("").strip();
    }

    private static String stripOrderedMarker(String line) {
        return Pattern.compile("^\\s*\\d+[.、)](?:\\s+|$)").matcher(line).replaceFirst("").strip();
    }

    private static boolean hasClosingTag(String line) {
        // 粗略：该行内闭合了已开启的块级标签
        return Pattern.compile("(?i)</(table|div|pre|blockquote|script|style)\\s*>").matcher(line).find();
    }

    private record MathBlock(String content, int[] map, int endLine) {
    }

    private static MathBlock tryMathBlock(String[] lines, int i) {
        // 独占行 $$...$$（单行）
        String line = lines[i].strip();
        Matcher single = Pattern.compile("^\\$\\$(.+?)\\$\\$$").matcher(line);
        if (single.find()) {
            return new MathBlock(single.group(1).strip(), new int[]{i, i + 1}, i + 1);
        }
        // 独占行 $...$（单行）
        Matcher singleDollar = Pattern.compile("^\\$(.+?)\\$$").matcher(line);
        if (singleDollar.find() && !line.startsWith("$$") && !line.contains("$$")) {
            return new MathBlock(singleDollar.group(1).strip(), new int[]{i, i + 1}, i + 1);
        }
        // 多行 $$ 块
        if (line.startsWith("$$")) {
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            boolean closed = false;
            while (j < lines.length) {
                if (lines[j].strip().endsWith("$$")) {
                    sb.append(lines[j].strip().replace("$$", ""));
                    closed = true;
                    break;
                }
                sb.append(lines[j]);
                if (j < lines.length - 1) {
                    sb.append("\n");
                }
                j++;
            }
            if (closed) {
                return new MathBlock(sb.toString().strip(), new int[]{i, j + 1}, j + 1);
            }
        }
        return null;
    }
}
