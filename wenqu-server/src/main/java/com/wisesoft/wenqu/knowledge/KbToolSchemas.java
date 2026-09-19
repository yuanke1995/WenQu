package com.wisesoft.wenqu.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库工具 IO 模式（Search / Find / Open）。
 *
 * <p>由参考实现的 knowledge/schemas.py 逐字段翻译。参考实现用 pydantic 做校验与序列化；
 * 本工程无校验框架依赖，改以 record + 静态 {@code toMap} 承载同一字段结构：
 * 字段名、默认值、取值范围（ge/le）与输出键完全对齐。
 *
 * <p>必要替换：pydantic 的 {@code Field(default=...)} 默认值在 Java record 上无法表达，
 * 由 {@code toMap} 的调用方在构造时显式给出（默认值语义与参考实现一致）。
 */
public final class KbToolSchemas {

    private KbToolSchemas() {}

    /** 检索输入。 */
    public record SearchInput(String kbId, String queryText, String fileName) {}

    /** 单条检索结果。 */
    public record SearchResult(String id, String kbId, String fileId, String content, Map<String, Object> metadata) {}

    /** 检索输出。 */
    public record SearchOutput(String kbId, List<SearchResult> results) {
        public Map<String, Object> toMap() {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            if (results != null) {
                for (SearchResult result : results) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", result.id());
                    item.put("kb_id", result.kbId());
                    item.put("file_id", result.fileId() == null ? "" : result.fileId());
                    item.put("content", result.content());
                    item.put("metadata", result.metadata() == null ? new LinkedHashMap<>() : result.metadata());
                    items.add(item);
                }
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("kb_id", kbId);
            output.put("results", items);
            return output;
        }
    }

    /** 查找输入。 */
    public record FindInput(
            String kbId,
            String fileId,
            List<String> patterns,
            boolean useRegex,
            boolean caseSensitive,
            int maxWindows,
            int windowSize) {}

    /** 查找窗口。 */
    public record FindWindow(int startLine, int endLine, List<Integer> matchedLines, String content) {
        Map<String, Object> toMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("start_line", startLine);
            item.put("end_line", endLine);
            item.put("matched_lines", matchedLines == null ? List.of() : matchedLines);
            item.put("content", content);
            return item;
        }
    }

    /** 查找输出。 */
    public record FindOutput(
            String kbId,
            String fileId,
            boolean semantic,
            String matchMode,
            int totalMatches,
            List<FindWindow> windows) {
        public Map<String, Object> toMap() {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            if (windows != null) {
                for (FindWindow window : windows) {
                    items.add(window.toMap());
                }
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("kb_id", kbId);
            output.put("file_id", fileId);
            output.put("semantic", semantic);
            output.put("match_mode", matchMode);
            output.put("total_matches", totalMatches);
            output.put("windows", items);
            return output;
        }

        /** 对应参考实现 model_dump(exclude={"kb_id","file_id"})：去掉 kb_id/file_id。 */
        public Map<String, Object> toMapExcludingIds() {
            Map<String, Object> output = toMap();
            output.remove("kb_id");
            output.remove("file_id");
            return output;
        }
    }

    /** 打开输入。 */
    public record OpenInput(String kbId, String fileId, Integer line, Integer offset, int windowSize) {}

    /** 打开输出。 */
    public record OpenOutput(
            String kbId,
            String fileId,
            int startLine,
            int endLine,
            int totalLines,
            int offset,
            int windowSize,
            boolean hasMoreBefore,
            boolean hasMoreAfter,
            Integer nextOffset,
            String content) {
        public Map<String, Object> toMap() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("kb_id", kbId);
            output.put("file_id", fileId);
            output.put("start_line", startLine);
            output.put("end_line", endLine);
            output.put("total_lines", totalLines);
            output.put("offset", offset);
            output.put("window_size", windowSize);
            output.put("has_more_before", hasMoreBefore);
            output.put("has_more_after", hasMoreAfter);
            output.put("next_offset", nextOffset);
            output.put("content", content);
            return output;
        }
    }
}
