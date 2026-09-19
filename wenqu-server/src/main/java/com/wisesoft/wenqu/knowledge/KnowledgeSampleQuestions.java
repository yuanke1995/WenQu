package com.wisesoft.wenqu.knowledge;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库示例问题生成的纯逻辑部分（对应参考实现 knowledge/utils/sample_question_utils.py）。
 *
 * <p>本类只承载无外部依赖的部分：系统提示词、文件清单构建、用户消息拼装、AI 返回解析。
 * 需要模型调用与持久化的部分（{@code generate_database_sample_questions} /
 * {@code get_database_sample_questions}）在 {@link KnowledgeContentService} 中，
 * 与参考实现的模块级函数划分一一对应。
 *
 * <h3>能力差异（已显式标注）</h3>
 * {@code textwrap.dedent} 的缩进归一在此以显式拼接等价实现（输出字符串逐字一致）；
 * {@code json.loads} → fastjson2 {@code JSON.parse}，解析失败同样收敛为
 * {@link IllegalArgumentException}（对应 Python 的 {@code ValueError}/{@code JSONDecodeError}）。
 */
public final class KnowledgeSampleQuestions {

    /** 示例问题系统提示词（逐字对齐参考实现）。 */
    public static final String SAMPLE_QUESTIONS_SYSTEM_PROMPT = """
            你是一个专业的知识库问答测试专家。

            你的任务是根据知识库中的文件列表，生成有价值的测试问题。

            要求：
            1. 问题要具体、有针对性，基于文件名称和类型推测可能的内容
            2. 问题要涵盖不同方面和难度
            3. 问题要简洁明了，适合用于检索测试
            4. 问题要多样化，包括事实查询、概念解释、操作指导等
            5. 问题长度控制在10-30字之间
            6. 直接返回JSON数组格式，不要其他说明

            返回格式：
            ```json
            {
              "questions": [
                "问题1？",
                "问题2？",
                "问题3？"
              ]
            }
            ```
            """;

    private KnowledgeSampleQuestions() {}

    /** 由知识库文件映射构建提示词用的文件清单（对应 build_sample_question_file_list）。 */
    public static List<Map<String, String>> buildSampleQuestionFileList(Map<String, Map<String, Object>> files) {
        List<Map<String, String>> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        for (Map<String, Object> info : files.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("filename", strOf(info.get("filename")));
            // type 为空时回退 file_type（与参考实现的 `or` 语义一致）
            String type = strOf(info.get("type"));
            if (type.isEmpty()) {
                type = strOf(info.get("file_type"));
            }
            item.put("type", type);
            result.add(item);
        }
        return result;
    }

    /** 拼装示例问题生成的用户消息（对应 build_sample_questions_user_message）。 */
    public static String buildSampleQuestionsUserMessage(String dbName, List<Map<String, String>> filesInfo, int count) {
        List<Map<String, String>> limited = filesInfo.size() > 20 ? filesInfo.subList(0, 20) : filesInfo;
        String filesText = limited.stream()
                .map(info -> "- " + nullToEmpty(info.get("filename")) + " (" + nullToEmpty(info.get("type")) + ")")
                .collect(Collectors.joining("\n"));
        // 只在文件超过 20 个时提示总数（与参考实现一致）
        String fileCountText = filesInfo.size() > 20 ? "（共" + filesInfo.size() + "个文件）" : "";

        return "请为知识库\"" + dbName + "\"生成" + count + "个测试问题。\n"
                + "\n"
                + "知识库文件列表" + fileCountText + "：\n"
                + filesText + "\n"
                + "\n"
                + "请根据这些文件的名称和类型，生成" + count + "个有价值的测试问题。";
    }

    /**
     * 解析 AI 返回的示例问题（对应 parse_sample_questions_content）。
     * 兼容 ```json 围栏与裸 JSON，格式不正确时抛 {@link IllegalArgumentException}。
     */
    public static List<String> parseSampleQuestionsContent(String content) {
        String text = content == null ? "" : content;

        if (text.contains("```json")) {
            int jsonStart = text.indexOf("```json") + 7;
            int jsonEnd = text.indexOf("```", jsonStart);
            if (jsonEnd == -1) {
                throw new IllegalArgumentException("AI返回的JSON代码块不完整");
            }
            text = text.substring(jsonStart, jsonEnd).strip();
        } else if (text.contains("```")) {
            int jsonStart = text.indexOf("```") + 3;
            int jsonEnd = text.indexOf("```", jsonStart);
            if (jsonEnd == -1) {
                throw new IllegalArgumentException("AI返回的代码块不完整");
            }
            text = text.substring(jsonStart, jsonEnd).strip();
        }

        Object parsed;
        try {
            parsed = JSON.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }

        List<String> questions = new ArrayList<>();
        if (parsed instanceof Map<?, ?> map) {
            Object value = map.get("questions");
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    questions.add(item == null ? "" : String.valueOf(item));
                }
            }
        }
        // 非字典、缺 questions、空数组、非列表 —— 统一视为格式不正确
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("AI返回的问题格式不正确");
        }
        return questions;
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
