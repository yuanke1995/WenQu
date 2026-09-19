package com.wisesoft.wenqu.knowledge;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.models.KnowledgeFile;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 知识库思维导图的纯逻辑部分（对应参考实现 knowledge/utils/mindmap_utils.py）。
 *
 * <p>本类承载无外部依赖的部分：两套系统提示词与页面常量、文件清单构建、
 * 用户消息拼装、AI 返回解析、变更检测、树修剪。需要模型调用与持久化的部分
 * （生成/增量更新/概览/数据读取/删除联动）在 {@link KnowledgeContentService}，
 * 与参考实现的模块级函数划分一一对应。
 *
 * <h3>能力差异（已显式标注）</h3>
 * <ul>
 *   <li>{@code json.dumps(mindmap_data, ensure_ascii=False, indent=2)} → 本类的
 *       {@link #toJsonPretty} 手写序列化：fastjson2 的 {@code PrettyFormat} 固定 4 空格缩进，
 *       与参考实现的 {@code indent=2} 不一致，故自实现以保证提示词逐字对齐
 *       （非 ASCII 不转义，转义规则与 Python 一致）。</li>
 *   <li>{@code copy.deepcopy} → fastjson2 往返拷贝（对纯 JSON 树结构语义等价）。</li>
 *   <li>{@code textwrap.dedent} → 显式拼接（输出字符串逐字一致）。</li>
 * </ul>
 */
public final class KnowledgeMindmap {

    /** 思维导图文件分页大小。 */
    public static final int MINDMAP_FILE_PAGE_SIZE = 500;

    /** 思维导图生成的文件数量上限。 */
    public static final int MINDMAP_GENERATION_FILE_LIMIT = 200;

    /** 全量生成思维导图的系统提示词（逐字对齐参考实现）。 */
    public static final String MINDMAP_SYSTEM_PROMPT = """
            你是一个专业的知识整理助手。

            你的任务是分析用户提供的文件列表，生成一个层次分明的思维导图结构。

            **核心规则：每个文件名只能出现一次！不允许重复！**

            要求：
            1. 思维导图要有清晰的层级结构（2-4层）
            2. 根节点是知识库名称
            3. 第一层是主要分类（如：技术文档、规章制度、数据资源等）
            4. 第二层是子分类
            5. **叶子节点必须是具体的文件名称**
            6. **每个文件名在整个思维导图中只能出现一次，不得重复！**
            7. 如果一个文件可能属于多个分类，只选择最合适的一个分类放置
            8. 使用合适的emoji图标增强可读性
            9. 返回JSON格式，遵循以下结构：

            ```json
            {
              "content": "知识库名称",
              "children": [
                {
                  "content": "🎯 主分类1",
                  "children": [
                    {
                      "content": "子分类1.1",
                      "children": [
                        {"content": "文件名1.txt", "children": []},
                        {"content": "文件名2.pdf", "children": []}
                      ]
                    }
                  ]
                },
                {
                  "content": "💻 主分类2",
                  "children": [
                    {"content": "文件名3.docx", "children": []},
                    {"content": "文件名4.md", "children": []}
                  ]
                }
              ]
            }
            ```

            **重要约束：**
            - 每个文件名在整个JSON中只能出现一次
            - 不要按多个维度分类导致文件重复
            - 选择最主要、最合适的分类维度
            - 每个叶子节点的children必须是空数组[]
            - 分类名称要简洁明了
            - 使用emoji增强视觉效果
            """;

    /** 增量更新思维导图的系统提示词（逐字对齐参考实现）。 */
    public static final String MINDMAP_INCREMENTAL_SYSTEM_PROMPT = """
            你是一个专业的知识整理助手。

            你的任务是将新文件整合到已有的思维导图结构中。

            **核心规则：**
            1. 保留现有思维导图的分类结构不变
            2. 将新文件添加到最合适的已有分类下
            3. 如果新文件不属于任何现有分类，可以创建新的分类节点
            4. 每个文件名只能出现一次，不允许重复
            5. 如果已有分类名称需要微调以容纳新文件，可以适当调整
            6. 返回完整的思维导图JSON（包含原有结构 + 新文件）

            返回JSON格式同标准思维导图结构。
            """;

    private static final DateTimeFormatter UTC_ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private KnowledgeMindmap() {}

    /** 由知识库文件映射构建导图文件清单（对应 build_database_file_list）。 */
    public static List<Map<String, Object>> buildDatabaseFileList(Map<String, Map<String, Object>> files) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        for (Map.Entry<String, Map<String, Object>> entry : files.entrySet()) {
            Map<String, Object> info = entry.getValue() == null ? Map.of() : entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_id", entry.getKey());
            item.put("filename", info.getOrDefault("filename", ""));
            item.put("type", info.getOrDefault("type", ""));
            item.put("status", info.getOrDefault("status", ""));
            item.put("created_at", info.getOrDefault("created_at", ""));
            result.add(item);
        }
        return result;
    }

    /** 由文件记录构建导图文件信息（对应 _file_record_to_mindmap_file）。 */
    public static Map<String, Object> fileRecordToMindmapFile(KnowledgeFile record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("file_id", record.getFileId());
        item.put("filename", record.getFilename() == null ? "" : record.getFilename());
        item.put("type", record.getFileType() == null ? "" : record.getFileType());
        item.put("status", record.getStatus() == null ? "" : record.getStatus());
        item.put("created_at", formatUtcIso(record.getCreatedAt()));
        return item;
    }

    /** 按指定 file_id 顺序收集文件信息（对应 collect_mindmap_files）。 */
    public static List<Map<String, String>> collectMindmapFiles(
            Map<String, Map<String, Object>> allFiles, List<String> fileIds) {
        List<Map<String, String>> result = new ArrayList<>();
        if (allFiles == null || fileIds == null) {
            return result;
        }
        for (String fileId : fileIds) {
            Map<String, Object> info = allFiles.get(fileId);
            if (info == null) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("filename", strOf(info.get("filename")));
            item.put("type", strOf(info.get("type")));
            result.add(item);
        }
        return result;
    }

    /** 拼装全量生成的用户消息（对应 build_mindmap_user_message）。 */
    public static String buildMindmapUserMessage(
            String dbName, List<Map<String, String>> filesInfo, String userPrompt) {
        String filesText = filesInfo.stream()
                .map(info -> "- " + strOf(info.get("filename")) + " (" + strOf(info.get("type")) + ")")
                .collect(Collectors.joining("\n"));
        String promptLine = (userPrompt == null || userPrompt.isEmpty()) ? "" : "用户补充说明：" + userPrompt;
        int count = filesInfo.size();

        return "请为知识库\"" + dbName + "\"生成思维导图结构。\n"
                + "\n"
                + "文件列表（共" + count + "个文件）：\n"
                + filesText + "\n"
                + "\n"
                + promptLine + "\n"
                + "\n"
                + "**重要提醒：**\n"
                + "1. 这个知识库共有" + count + "个文件\n"
                + "2. 每个文件名只能在思维导图中出现一次\n"
                + "3. 不要让同一个文件出现在多个分类下\n"
                + "4. 为每个文件选择最合适的唯一分类\n"
                + "\n"
                + "请生成合理的思维导图结构。";
    }

    /** 拼装增量更新的用户消息（对应 build_mindmap_incremental_user_message）。 */
    public static String buildMindmapIncrementalUserMessage(
            String dbName, Object mindmapData, List<Map<String, String>> addedFiles, String userPrompt) {
        String existingStructure = toJsonPretty(mindmapData, 0);
        String filesText = addedFiles.stream()
                .map(info -> "- " + strOf(info.get("filename")) + " (" + strOf(info.get("type")) + ")")
                .collect(Collectors.joining("\n"));
        String promptLine = (userPrompt == null || userPrompt.isEmpty()) ? "" : "用户补充说明：" + userPrompt;
        int count = addedFiles.size();

        return "请将以下新文件整合到知识库\"" + dbName + "\"的现有思维导图中。\n"
                + "\n"
                + "现有思维导图结构：\n"
                + existingStructure + "\n"
                + "\n"
                + "新增文件列表（共" + count + "个文件）：\n"
                + filesText + "\n"
                + "\n"
                + promptLine + "\n"
                + "\n"
                + "**重要提醒：**\n"
                + "1. 保留现有分类结构，将新文件添加到最合适的已有分类下\n"
                + "2. 如果新文件不适合任何现有分类，创建新的分类节点\n"
                + "3. 每个文件名只能出现一次\n"
                + "4. 返回完整的思维导图JSON（包含原有结构 + 新文件）\n"
                + "\n"
                + "请整合新文件到现有结构中。";
    }

    /**
     * 解析 AI 返回的思维导图 JSON（对应 parse_mindmap_content）。
     * 结构不正确时抛 {@link IllegalArgumentException}。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMindmapContent(String content) {
        String text = content == null ? "" : content;

        if (text.contains("```json")) {
            int jsonStart = text.indexOf("```json") + 7;
            int jsonEnd = text.indexOf("```", jsonStart);
            // 复刻参考实现行为：围栏未闭合时 Python 负索引会截掉末字符
            if (jsonEnd == -1) {
                jsonEnd = Math.max(jsonStart, text.length() - 1);
            }
            text = text.substring(jsonStart, jsonEnd).strip();
        } else if (text.contains("```")) {
            int jsonStart = text.indexOf("```") + 3;
            int jsonEnd = text.indexOf("```", jsonStart);
            if (jsonEnd == -1) {
                jsonEnd = Math.max(jsonStart, text.length() - 1);
            }
            text = text.substring(jsonStart, jsonEnd).strip();
        }

        Object parsed;
        try {
            parsed = JSON.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }

        if (!(parsed instanceof Map<?, ?> map) || !map.containsKey("content")) {
            throw new IllegalArgumentException("思维导图结构不正确");
        }
        return (Map<String, Object>) map;
    }

    /**
     * 对比导图追踪的文件与知识库当前文件，返回变更信息（对应 detect_mindmap_changes）。
     */
    public static Map<String, Object> detectMindmapChanges(
            Map<String, Object> mindmapData,
            Map<String, String> mindmapFileIds,
            Map<String, Map<String, Object>> currentFiles) {
        Map<String, String> tracked = mindmapFileIds;
        boolean hasMindmapData = mindmapData != null && !mindmapData.isEmpty();

        // 兼容旧数据：有导图但缺追踪映射时，用叶子节点反向重建
        if (hasMindmapData && (tracked == null || tracked.isEmpty())) {
            Set<String> leafFilenames = collectLeafFilenames(mindmapData);
            tracked = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : currentFiles.entrySet()) {
                Map<String, Object> info = entry.getValue() == null ? Map.of() : entry.getValue();
                if (leafFilenames.contains(strOf(info.get("filename")))) {
                    tracked.put(entry.getKey(), strOf(info.get("filename")));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        boolean trackedEmpty = tracked == null || tracked.isEmpty();
        if (!hasMindmapData || trackedEmpty) {
            List<Map<String, Object>> addedFiles = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : currentFiles.entrySet()) {
                Map<String, Object> info = entry.getValue() == null ? Map.of() : entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file_id", entry.getKey());
                item.put("filename", strOf(info.get("filename")));
                item.put("type", strOf(info.get("type")));
                addedFiles.add(item);
            }
            result.put("has_mindmap", mindmapData != null);
            result.put("tracked_files", trackedEmpty ? List.of() : new ArrayList<>(tracked.keySet()));
            result.put("current_files", new ArrayList<>(currentFiles.keySet()));
            result.put("added_files", addedFiles);
            result.put("removed_file_ids", List.of());
            result.put("unchanged_count", 0);
            result.put("needs_update", !addedFiles.isEmpty());
            return result;
        }

        Set<String> trackedIds = new LinkedHashSet<>(tracked.keySet());
        Set<String> currentIds = new LinkedHashSet<>(currentFiles.keySet());

        Set<String> removedFileIds = new LinkedHashSet<>(trackedIds);
        removedFileIds.removeAll(currentIds);
        Set<String> addedFileIds = new LinkedHashSet<>(currentIds);
        addedFileIds.removeAll(trackedIds);

        List<Map<String, Object>> addedFiles = new ArrayList<>();
        for (String fileId : new TreeSet<>(addedFileIds)) {
            Map<String, Object> info = currentFiles.get(fileId);
            if (info == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_id", fileId);
            item.put("filename", strOf(info.get("filename")));
            item.put("type", strOf(info.get("type")));
            addedFiles.add(item);
        }

        Set<String> unchanged = new LinkedHashSet<>(trackedIds);
        unchanged.retainAll(currentIds);

        result.put("has_mindmap", true);
        result.put("tracked_files", new ArrayList<>(trackedIds));
        result.put("current_files", new ArrayList<>(currentIds));
        result.put("added_files", addedFiles);
        result.put("removed_file_ids", new ArrayList<>(removedFileIds));
        result.put("unchanged_count", unchanged.size());
        result.put("needs_update", !addedFiles.isEmpty() || !removedFileIds.isEmpty());
        return result;
    }

    /** 递归收集所有叶子节点的文件名（对应 _collect_leaf_filenames）。 */
    @SuppressWarnings("unchecked")
    public static Set<String> collectLeafFilenames(Map<String, Object> node) {
        Set<String> result = new LinkedHashSet<>();
        if (node == null) {
            return result;
        }
        Object childrenValue = node.get("children");
        List<Object> children = childrenValue instanceof List<?> list ? (List<Object>) list : List.of();
        if (children.isEmpty()) {
            result.add(strOf(node.get("content")));
            return result;
        }
        for (Object child : children) {
            if (child instanceof Map<?, ?> childMap) {
                result.addAll(collectLeafFilenames((Map<String, Object>) childMap));
            }
        }
        return result;
    }

    /**
     * 从导图树中移除指定文件名的叶子节点（对应 remove_files_from_mindmap）。
     * 修改的是深拷贝，原数据不被改动。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> removeFilesFromMindmap(
            Map<String, Object> mindmapData, Set<String> removedFilenames) {
        if (removedFilenames == null || removedFilenames.isEmpty()) {
            return mindmapData;
        }
        // 深拷贝：参考实现用 copy.deepcopy，此处经 JSON 往返（纯树结构语义等价）
        Map<String, Object> copy = (Map<String, Object>) JSON.parse(JSON.toJSONString(mindmapData));
        String rootName = strOf(copy.get("content"));
        Map<String, Object> pruned = pruneMindmapNode(copy, removedFilenames, rootName);
        if (pruned != null) {
            return pruned;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("content", rootName);
        fallback.put("children", new ArrayList<>());
        return fallback;
    }

    /**
     * 递归修剪导图节点，移除指定文件名的叶子（对应 _prune_mindmap_node）。
     *
     * @return 修剪后的节点；返回 null 表示该节点应被整体移除
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> pruneMindmapNode(
            Map<String, Object> node, Set<String> removedFilenames, String rootName) {
        String content = strOf(node.get("content"));
        Object childrenValue = node.get("children");
        List<Object> children = childrenValue instanceof List<?> list ? (List<Object>) list : List.of();

        if (children.isEmpty()) {
            if (removedFilenames.contains(content)) {
                return null;
            }
            return node;
        }

        List<Object> prunedChildren = new ArrayList<>();
        for (Object child : children) {
            if (!(child instanceof Map<?, ?> childMap)) {
                continue;
            }
            Map<String, Object> result =
                    pruneMindmapNode((Map<String, Object>) childMap, removedFilenames, rootName);
            if (result != null) {
                prunedChildren.add(result);
            }
        }

        if (prunedChildren.isEmpty()) {
            if (content.equals(rootName)) {
                node.put("children", new ArrayList<>());
                return node;
            }
            return null;
        }

        node.put("children", prunedChildren);
        return node;
    }

    /**
     * 对齐 Python {@code json.dumps(x, ensure_ascii=False, indent=2)} 的序列化。
     * fastjson2 的 PrettyFormat 固定 4 空格缩进，无法满足提示词逐字对齐，故自实现。
     */
    public static String toJsonPretty(Object value, int level) {
        String indent = "  ".repeat(level);
        String childIndent = "  ".repeat(level + 1);

        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(childIndent)
                        .append(jsonString(String.valueOf(entry.getKey())))
                        .append(": ")
                        .append(toJsonPretty(entry.getValue(), level + 1));
                if (++index < map.size()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            return sb.append(indent).append("}").toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append(childIndent).append(toJsonPretty(list.get(i), level + 1));
                if (i < list.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            return sb.append(indent).append("]").toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return jsonString(String.valueOf(value));
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** UTC 时间的 ISO 文本（对应 Python {@code datetime.isoformat()} 的秒级精度）。 */
    private static String formatUtcIso(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return value.atOffset(ZoneOffset.UTC).toLocalDateTime().format(UTC_ISO_FORMATTER);
    }

    /** 保留给调用方判断集合差异时使用（与参考实现的 set 差集语义一致）。 */
    static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
