package com.wisesoft.wenqu.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 问题和选项规范化工具。
 *
 * <p>由参考实现的 utils/question_utils.py 逐函数翻译：normalize_options /
 * normalize_questions 及其内部收集器与布尔解析。
 *
 * <p>说明：Python 的 {@code or} 链按真值取第一个成立项，本实现以 {@link #truthy}
 * 复刻同语义（0、空串、空集合均视为假）；JSON 解析用 fastjson2（保持键序）。
 */
public final class QuestionUtils {

    private static final String[] WRAPPER_OPTION_KEYS = {"item", "items", "options", "list", "choices", "data"};
    private static final String[] WRAPPER_QUESTION_KEYS = {"questions", "items", "item", "list", "data"};

    private QuestionUtils() {}

    /** Python 真值语义（用于复刻 or 链与真值判断）。 */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    /** 把 JSON、包装对象或单项对象统一成列表。 */
    private static List<Object> normalizeCollection(
            Object value,
            String[] wrapperKeys,
            java.util.function.Predicate<Map<Object, Object>> isItem,
            boolean mappingAsOptions) {
        if (value instanceof String text) {
            try {
                Object parsed = JSON.parse(text);
                if (!(parsed instanceof String)) {
                    value = parsed;
                }
            } catch (RuntimeException exception) {
                return new ArrayList<>();
            }
        }

        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        if (!(value instanceof Map)) {
            return new ArrayList<>();
        }
        Map<Object, Object> map = (Map<Object, Object>) value;

        for (String key : wrapperKeys) {
            if (!map.containsKey(key)) {
                continue;
            }
            Object wrapped = map.get(key);
            if (wrapped instanceof List) {
                return new ArrayList<>((List<?>) wrapped);
            }
            if (wrapped instanceof Map && isItem.test((Map<Object, Object>) wrapped)) {
                List<Object> single = new ArrayList<>();
                single.add(wrapped);
                return single;
            }
        }

        if (isItem.test(map)) {
            List<Object> single = new ArrayList<>();
            single.add(map);
            return single;
        }
        if (mappingAsOptions) {
            List<Object> options = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("label", String.valueOf(entry.getValue()));
                option.put("value", String.valueOf(entry.getKey()));
                options.add(option);
            }
            return options;
        }
        return new ArrayList<>();
    }

    /** 健壮地将各类值解析为布尔值。 */
    static boolean parseBool(Object val, boolean defaultValue) {
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Boolean bool) {
            return bool;
        }
        if (val instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (val instanceof String text) {
            String s = text.strip().toLowerCase();
            if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("t")) {
                return true;
            }
            if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n") || s.equals("f")
                    || s.isEmpty()) {
                return false;
            }
        }
        return defaultValue;
    }

    /** 规范化选项列表，支持列表、包装对象或字符串输入。 */
    public static List<Map<String, Object>> normalizeOptions(Object rawOptions) {
        List<Object> items = normalizeCollection(
                rawOptions,
                WRAPPER_OPTION_KEYS,
                item -> truthy(item.get("label")) || truthy(item.get("value")),
                true);

        List<Map<String, Object>> options = new ArrayList<>();
        for (Object rawItem : items) {
            if (rawItem instanceof Map) {
                Map<Object, Object> item = (Map<Object, Object>) rawItem;
                String label = str(or(item.get("label"), item.get("value"), item.get("title"), item.get("text")))
                        .strip();
                String value = str(or(item.get("value"), item.get("label"), item.get("id"), item.get("key")))
                        .strip();
                String description = str(or(item.get("description"), item.get("desc"))).strip();
                if (!label.isEmpty() && !value.isEmpty()) {
                    Map<String, Object> opt = new LinkedHashMap<>();
                    opt.put("label", label);
                    opt.put("value", value);
                    if (!description.isEmpty()) {
                        opt.put("description", description);
                    }
                    options.add(opt);
                }
            } else {
                String label = String.valueOf(rawItem).strip();
                if (!label.isEmpty()) {
                    Map<String, Object> opt = new LinkedHashMap<>();
                    opt.put("label", label);
                    opt.put("value", label);
                    options.add(opt);
                }
            }
        }
        return options;
    }

    /** 规范化问题列表，支持列表或包装对象输入。 */
    public static List<Map<String, Object>> normalizeQuestions(Object rawQuestions) {
        return normalizeQuestions(rawQuestions, "q");
    }

    public static List<Map<String, Object>> normalizeQuestions(Object rawQuestions, String defaultQuestionIdPrefix) {
        List<Object> items = normalizeCollection(
                rawQuestions,
                WRAPPER_QUESTION_KEYS,
                item -> truthy(item.get("question")) || truthy(item.get("title")) || truthy(item.get("text")),
                false);

        List<Map<String, Object>> questions = new ArrayList<>();
        for (int idx = 0; idx < items.size(); idx++) {
            Object rawItem = items.get(idx);
            if (!(rawItem instanceof Map)) {
                continue;
            }
            Map<Object, Object> item = (Map<Object, Object>) rawItem;

            String question = str(or(item.get("question"), item.get("title"), item.get("text"))).strip();
            if (question.isEmpty()) {
                continue;
            }

            // Python or 链：question_id / questionId / id / 缺省 "q-{idx+1}"（idx 为全列表下标）
            Object rawId = or(
                    item.get("question_id"),
                    item.get("questionId"),
                    item.get("id"),
                    defaultQuestionIdPrefix + "-" + (idx + 1));
            String questionId = String.valueOf(rawId).strip();
            if (questionId.isEmpty()) {
                questionId = UUID.randomUUID().toString();
            }

            // item.get("options") if item.get("options") is not None else item.get("choices")（按 is not None 判断）
            Object optionsVal = item.get("options") != null ? item.get("options") : item.get("choices");

            Map<String, Object> normalizedQuestion = new LinkedHashMap<>();
            normalizedQuestion.put("question_id", questionId);
            normalizedQuestion.put("question", question);
            normalizedQuestion.put("options", normalizeOptions(optionsVal));
            // dict.get(key, default)：键存在但值为假仍取原值，不参与 or 链
            normalizedQuestion.put(
                    "multi_select", parseBool(getWithDefault(item, "multi_select", "multiSelect", false), false));
            normalizedQuestion.put(
                    "allow_other", parseBool(getWithDefault(item, "allow_other", "allowOther", true), true));

            Object operation = item.get("operation");
            if (operation instanceof String text && !text.strip().isEmpty()) {
                normalizedQuestion.put("operation", text.strip());
            }

            questions.add(normalizedQuestion);
        }

        return questions;
    }

    /** 复刻 {@code item.get(key, item.get(fallbackKey, default))}：键存在但值为假仍取原值。 */
    private static Object getWithDefault(Map<Object, Object> item, String key, String fallbackKey, Object fallback) {
        if (item.containsKey(key)) {
            return item.get(key);
        }
        if (item.containsKey(fallbackKey)) {
            return item.get(fallbackKey);
        }
        return fallback;
    }

    /** Python or 链：返回第一个真值，否则最后一个参数。 */
    private static Object or(Object... values) {
        for (Object value : values) {
            if (truthy(value)) {
                return value;
            }
        }
        return values.length == 0 ? null : values[values.length - 1];
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
