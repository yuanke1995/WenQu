package com.wisesoft.wenqu.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 知识库文件视图的纯逻辑：文件树条目、按行打开窗口、关键词/正则查找窗口、检索结果投影。
 *
 * <p>由参考实现的 knowledge/base.py 中对应纯函数逐方法翻译（这些方法不依赖 IO，
 * 是 manager/executor 的共用内核，故独立成类以免与 {@link KnowledgeBaseRuntime} 的 IO 混杂）：
 * <ul>
 *   <li>{@link #originalFilePath} ← _original_file_path</li>
 *   <li>{@link #knowledgeFileEntry} ← _knowledge_file_entry</li>
 *   <li>{@link #sortFileEntries} ← _sort_file_entries</li>
 *   <li>{@link #buildOpenFileWindow} ← _build_open_file_window</li>
 *   <li>{@link #buildSearchOutput} ← build_search_output</li>
 *   <li>{@link #buildFindFileWindows} ← _build_find_file_windows</li>
 *   <li>{@link #normalizeDatabaseStats} ← _normalize_database_stats（manager）</li>
 * </ul>
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>Python 的行窗口格式化 {@code f"{n:6d}\t{line}"} → Java 同宽右对齐（{@code %6d\t}）。
 *   <li>{@code re.compile(pattern, re.IGNORECASE)} → {@code Pattern.compile(..., CASE_INSENSITIVE)}
 *       （{@code .search} 语义 = Java {@code Matcher.find}）。
 *   <li>pydantic 的 {@code model_dump(exclude=...)} → 显式从结果 Map 移除键。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}。
 * </ul>
 */
public final class KnowledgeFileViews {

    private KnowledgeFileViews() {}

    /** 原始文件路径：优先 minio_url，其次 path（对应 _original_file_path）。 */
    public static String originalFilePath(Map<String, Object> fileMeta) {
        if (fileMeta == null) {
            return null;
        }
        Object minioUrl = fileMeta.get("minio_url");
        if (minioUrl != null && !String.valueOf(minioUrl).isEmpty()) {
            return String.valueOf(minioUrl);
        }
        Object path = fileMeta.get("path");
        return path == null || String.valueOf(path).isEmpty() ? null : String.valueOf(path);
    }

    /** 文件树条目（对应 _knowledge_file_entry）。 */
    public static Map<String, Object> knowledgeFileEntry(String kbId, String fileId, Map<String, Object> fileMeta) {
        boolean isDir = truthy(fileMeta == null ? null : fileMeta.get("is_folder"));
        String originalPath = originalFilePath(fileMeta);
        String path = "/" + fileId;
        if (isDir) {
            path = path + "/";
        }
        Object filename = fileMeta == null ? null : fileMeta.get("filename");
        Object originalFilename = fileMeta == null ? null : fileMeta.get("original_filename");
        Object markdownFile = fileMeta == null ? null : fileMeta.get("markdown_file");
        Object updatedAt = fileMeta == null ? null : fileMeta.get("updated_at");
        Object createdAt = fileMeta == null ? null : fileMeta.get("created_at");
        Object status = fileMeta == null ? null : fileMeta.get("status");

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", "knowledge");
        entry.put("kb_id", kbId);
        entry.put("file_id", fileId);
        entry.put("parent_id", fileMeta == null ? null : fileMeta.get("parent_id"));
        entry.put("path", path);
        entry.put("virtual_path", "/knowledge/" + kbId + "/" + fileId);
        entry.put("name", firstNonBlank(filename, originalFilename, fileId));
        entry.put("is_dir", isDir);
        entry.put("size", isDir ? 0 : (fileMeta == null || fileMeta.get("size") == null ? 0 : fileMeta.get("size")));
        entry.put("modified_at", firstNonBlank(updatedAt, createdAt, ""));
        entry.put("readonly", true);
        entry.put("status", status == null ? "done" : status);
        entry.put("has_original_file", originalPath != null);
        entry.put("has_parsed_markdown", markdownFile != null && !String.valueOf(markdownFile).isEmpty());
        return entry;
    }

    /** 文件树排序：文件夹在前，名称不区分大小写升序（对应 _sort_file_entries）。 */
    public static List<Map<String, Object>> sortFileEntries(List<Map<String, Object>> entries) {
        List<Map<String, Object>> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparing((Map<String, Object> item) -> !truthy(item.get("is_dir")))
                .thenComparing(item -> {
                    Object name = item.get("name");
                    return name == null ? "" : String.valueOf(name).toLowerCase();
                }));
        return sorted;
    }

    /** 按行窗口打开内容（对应 _build_open_file_window）。 */
    public static Map<String, Object> buildOpenFileWindow(String content, int offset, int limit) {
        List<String> lines = splitLines(content);
        int totalLines = lines.size();
        int start = Math.min(Math.max(offset, 0), totalLines);
        int windowSize = Math.min(Math.max(limit, 1), 2000);
        List<String> selected = lines.subList(start, Math.min(start + windowSize, totalLines));
        int end = start + selected.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start_line", selected.isEmpty() ? 0 : start + 1);
        result.put("end_line", end);
        result.put("total_lines", totalLines);
        result.put("offset", start);
        result.put("window_size", windowSize);
        result.put("has_more_before", start > 0);
        result.put("has_more_after", end < totalLines);
        result.put("next_offset", end < totalLines ? end : null);
        result.put("content", numberedContent(start, selected));
        return result;
    }

    /**
     * 检索结果投影（对应 build_search_output）。
     *
     * <p>入参非列表时原样返回（与参考实现 {@code return retrieval_results} 一致）。
     */
    public static Object buildSearchOutput(String kbId, Object retrievalResults) {
        if (!(retrievalResults instanceof List<?> list)) {
            return retrievalResults;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof Map<?, ?> rawChunk)) {
                continue;
            }
            Map<String, Object> chunk = castMap(rawChunk);
            Map<String, Object> metadata = chunk.get("metadata") instanceof Map<?, ?> rawMeta
                    ? castMap(rawMeta) : new LinkedHashMap<>();
            metadata.entrySet().removeIf(e -> switch (e.getKey()) {
                case "filepath", "parsed_path", "path", "markdown_file" -> true;
                default -> false;
            });

            Object fileIdValue = firstNonBlank(
                    metadata.get("file_id"), chunk.get("file_id"), chunk.get("full_doc_id"), "");
            String fileId = fileIdValue == null ? "" : String.valueOf(fileIdValue);
            Object chunkId = firstNonNull(metadata.get("chunk_id"), chunk.get("chunk_id"), chunk.get("id"));
            Object chunkIndex = firstNonNull(metadata.get("chunk_index"), chunk.get("chunk_index"));
            if (chunkIndex != null) {
                metadata.putIfAbsent("chunk_index", chunkIndex);
            }
            if (chunk.get("score") != null) {
                metadata.putIfAbsent("score", chunk.get("score"));
            }
            if (chunk.get("distance") != null) {
                metadata.putIfAbsent("distance", chunk.get("distance"));
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", chunkId == null ? fileId + ":" + (index + 1) : String.valueOf(chunkId));
            item.put("kb_id", String.valueOf(kbId));
            item.put("file_id", fileId);
            item.put("content", chunk.get("content") == null ? "" : String.valueOf(chunk.get("content")));
            item.put("metadata", metadata);
            results.add(item);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("kb_id", String.valueOf(kbId));
        output.put("results", results);
        return output;
    }

    /** 关键词/正则查找窗口（对应 _build_find_file_windows）。 */
    public static Map<String, Object> buildFindFileWindows(
            String content,
            List<String> patterns,
            boolean useRegex,
            boolean caseSensitive,
            int maxWindows,
            int windowSize) {
        List<String> effectivePatterns = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty()) {
                effectivePatterns.add(pattern);
            }
        }
        if (effectivePatterns.isEmpty()) {
            throw new IllegalArgumentException("请提供至少一个 pattern");
        }

        List<String> lines = splitLines(content);
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        final List<Pattern> matchers = new ArrayList<>();
        final List<String> normalizedPatterns = new ArrayList<>();
        if (useRegex) {
            for (String pattern : effectivePatterns) {
                matchers.add(Pattern.compile(pattern, flags));
            }
        } else {
            for (String pattern : effectivePatterns) {
                normalizedPatterns.add(caseSensitive ? pattern : pattern.toLowerCase());
            }
        }

        List<Integer> matchedIndexes = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            boolean matched;
            if (useRegex) {
                matched = false;
                for (Pattern matcher : matchers) {
                    if (matcher.matcher(line).find()) {
                        matched = true;
                        break;
                    }
                }
            } else {
                String haystack = caseSensitive ? line : line.toLowerCase();
                matched = false;
                for (String pattern : normalizedPatterns) {
                    if (haystack.contains(pattern)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) {
                matchedIndexes.add(index);
            }
        }

        List<Map<String, Object>> windows = new ArrayList<>();
        int coveredUntil = -1;
        int normalizedWindowSize = Math.min(Math.max(windowSize, 1), 200);
        int halfWindow = normalizedWindowSize / 2;

        for (int matchedIndex : matchedIndexes) {
            if (matchedIndex < coveredUntil) {
                continue;
            }
            int start = Math.max(matchedIndex - halfWindow, 0);
            int end = Math.min(start + normalizedWindowSize, lines.size());
            start = Math.max(end - normalizedWindowSize, 0);

            List<Integer> matchedLines = new ArrayList<>();
            for (int index : matchedIndexes) {
                if (index >= start && index < end) {
                    matchedLines.add(index + 1);
                }
            }
            List<String> selected = lines.subList(start, end);
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("start_line", selected.isEmpty() ? 0 : start + 1);
            window.put("end_line", end);
            window.put("matched_lines", matchedLines);
            window.put("content", numberedContent(start, selected));
            windows.add(window);

            coveredUntil = end;
            if (windows.size() >= maxWindows) {
                break;
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("semantic", false);
        output.put("match_mode", useRegex ? "regex" : "keyword");
        output.put("total_matches", matchedIndexes.size());
        output.put("windows", windows);
        return output;
    }

    /** 知识库聚合统计规范化（对应 manager._normalize_database_stats）。 */
    public static Map<String, Object> normalizeDatabaseStats(Map<String, Object> stats) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String key : List.of("file_count", "folder_count", "row_count", "total_size",
                "chunk_count", "token_count", "pending_parse_count", "pending_index_count",
                "processing_count")) {
            normalized.put(key, 0);
        }
        if (stats == null) {
            return normalized;
        }
        for (String key : new ArrayList<>(normalized.keySet())) {
            Object raw = stats.get(key);
            long value;
            try {
                value = raw == null ? 0L : Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException exception) {
                value = 0L;
            }
            normalized.put(key, Math.max(value, 0L));
        }
        return normalized;
    }

    // ==================== 工具 ====================

    /** Python str.splitlines() 的等价：按 \n 切分并丢弃末尾空串，\r 一并剥除。 */
    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return lines;
        }
        String[] parts = content.split("\n", -1);
        int count = parts.length;
        if (count > 0 && parts[count - 1].isEmpty()) {
            count--;
        }
        for (int index = 0; index < count; index++) {
            String line = parts[index];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return lines;
    }

    /** 行号前缀内容（对应 f"{start + idx + 1:6d}\t{line}"）。 */
    private static String numberedContent(int start, List<String> selected) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < selected.size(); index++) {
            if (index > 0) {
                builder.append("\n");
            }
            builder.append(String.format("%6d", start + index + 1)).append("\t").append(selected.get(index));
        }
        return builder.toString();
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isEmpty()) {
                return value;
            }
        }
        return values.length == 0 ? null : values[values.length - 1];
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return !String.valueOf(value).isEmpty() && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
