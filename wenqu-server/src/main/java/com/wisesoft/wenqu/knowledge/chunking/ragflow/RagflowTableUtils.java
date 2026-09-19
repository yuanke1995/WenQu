package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 表格 → 键值对（对应参考实现 {@code ragflow_like/utils/table_utils.py}）。
 * <p>
 * 能力差异：参考实现用 BeautifulSoup（bs4）解析 HTML；Java 侧不引入 bs4，改用纯字符串/正则
 * 轻量解析器 {@link #htmlTableToKeyValue(String)}，仅支持语义解析所需的 {@code <table>} /
 * {@code <tr>} / {@code <td>}/{@code <th>} 结构及其 {@code rowspan}/{@code colspan} 属性，
 * 产出结果与参考实现的「网格重建 → 键值对转换」逻辑一致。
 */
public final class RagflowTableUtils {

    private static final Pattern TABLE_TAG = Pattern.compile(
            "(?is)<table\\b[^>]*>(.*?)</table\\s*>");
    private static final Pattern TR_TAG = Pattern.compile(
            "(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern CELL_TAG = Pattern.compile(
            "(?is)<(td|th)\\b([^>]*)>(.*?)</\\1\\s*>");
    private static final Pattern TAG_ATTR = Pattern.compile(
            "(?i)(rowspan|colspan)\\s*=\\s*[\"']?(\\d+)");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");

    private RagflowTableUtils() {
    }

    /**
     * 将 HTML 表格转换为键值对格式的列表（对应 {@code html_table_to_key_value}）。
     * <ul>
     *   <li>网格重建：支持 rowspan/colspan 合并单元格展开为完整二维网格；</li>
     *   <li>首行视为表头 Key，其后每行生成 "键：值；键：值；" 字符串。</li>
     * </ul>
     */
    public static List<String> htmlTableToKeyValue(String html) {
        if (html == null || html.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher tableMatcher = TABLE_TAG.matcher(html);
        if (!tableMatcher.find()) {
            return new ArrayList<>();
        }
        String tableBody = tableMatcher.group(1);

        List<Tr> rows = new ArrayList<>();
        Matcher trMatcher = TR_TAG.matcher(tableBody);
        while (trMatcher.find()) {
            List<Cell> cells = new ArrayList<>();
            Matcher cellMatcher = CELL_TAG.matcher(trMatcher.group(1));
            while (cellMatcher.find()) {
                String text = stripTags(cellMatcher.group(3)).strip();
                int rowspan = attrInt(cellMatcher.group(2), "rowspan");
                int colspan = attrInt(cellMatcher.group(2), "colspan");
                cells.add(new Cell(text, rowspan, colspan));
            }
            rows.add(new Tr(cells));
        }
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }

        // 网格重建：遇到合并单元格，将内容填充到受影响的所有坐标点。
        List<List<String>> grid = new ArrayList<>();
        for (int rIdx = 0; rIdx < rows.size(); rIdx++) {
            ensureGridRow(grid, rIdx);
            int cIdx = 0;
            for (Cell cell : rows.get(rIdx).cells()) {
                while (cIdx < grid.get(rIdx).size() && grid.get(rIdx).get(cIdx) != null) {
                    cIdx++;
                }
                for (int r = 0; r < cell.rowspan(); r++) {
                    int targetR = rIdx + r;
                    ensureGridRow(grid, targetR);
                    for (int c = 0; c < cell.colspan(); c++) {
                        int targetC = cIdx + c;
                        while (grid.get(targetR).size() <= targetC) {
                            grid.get(targetR).add(null);
                        }
                        grid.get(targetR).set(targetC, cell.text());
                    }
                }
                cIdx += cell.colspan();
            }
        }
        if (grid.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> headers = new ArrayList<>();
        for (String h : grid.get(0)) {
            headers.add(h == null ? "" : h);
        }

        List<String> kvLines = new ArrayList<>();
        for (int rowIdx = 1; rowIdx < grid.size(); rowIdx++) {
            List<String> rowValues = grid.get(rowIdx);
            int minLen = Math.min(headers.size(), rowValues.size());
            List<String> rowParts = new ArrayList<>();
            for (int i = 0; i < minLen; i++) {
                String key = headers.get(i);
                String val = rowValues.get(i) != null ? rowValues.get(i) : "";
                if (key != null && !key.isEmpty()) {
                    rowParts.add(key + "：" + val);
                }
            }
            if (!rowParts.isEmpty()) {
                kvLines.add(String.join("；", rowParts) + "；");
            }
        }
        return kvLines;
    }

    private static void ensureGridRow(List<List<String>> grid, int index) {
        while (grid.size() <= index) {
            grid.add(new ArrayList<>());
        }
    }

    private static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        return ANY_TAG.matcher(html).replaceAll("").replace("\u00a0", " ").strip();
    }

    private static int attrInt(String attrs, String name) {
        if (attrs == null) {
            return 1;
        }
        Matcher m = TAG_ATTR.matcher(attrs);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) {
                try {
                    return Math.max(1, Integer.parseInt(m.group(2)));
                } catch (NumberFormatException ignored) {
                    return 1;
                }
            }
        }
        return 1;
    }

    private record Cell(String text, int rowspan, int colspan) {
    }

    private record Tr(List<Cell> cells) {
    }
}
