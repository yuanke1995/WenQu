package com.wisesoft.wenqu.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型标准块投影展示正文；旧持久消息只在这里尽力恢复。
 *
 * <p>由参考实现的 models/utils.py 逐函数翻译：parse_assistant_message_body ——
 * 标准块（text/reasoning）投影、历史 metadata 恢复（reasoning_content 字段与
 * {@code <think>} 前缀）。
 */
public final class ModelUtils {

    private static final Pattern THINK_PREFIX = Pattern.compile("^\\s*<think>(.*?)(?:</think>|$)", Pattern.DOTALL);

    private ModelUtils() {}

    /**
     * 投影标准块；显式传入历史 metadata 时才尝试恢复旧格式。
     *
     * @param content 字符串或块列表（Map 形式，来自 JSON）
     * @param metadata 可为 null（表示无历史恢复路径）
     */
    public static Map<String, String> parseAssistantMessageBody(Object content, Map<String, Object> metadata) {
        Map<String, Object> history = metadata instanceof Map ? metadata : Map.of();
        Object blocks = content instanceof List ? content : history.get("content");
        List<String> textParts = new ArrayList<>();
        List<String> reasoningParts = new ArrayList<>();
        if (blocks instanceof List) {
            for (Object item : (List<?>) blocks) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> block = (Map<?, ?>) item;
                if ("text".equals(block.get("type")) && block.get("text") instanceof String value) {
                    textParts.add(value);
                } else if ("reasoning".equals(block.get("type")) && block.get("reasoning") instanceof String value) {
                    reasoningParts.add(value);
                }
            }
        }

        String text = content instanceof String ? (String) content : String.join("", textParts);
        String reasoning = String.join("", reasoningParts);
        if (metadata == null) {
            return result(text, reasoning);
        }
        if (reasoning.isEmpty()) {
            Object[] sources = {history, history.get("additional_kwargs")};
            for (Object source : sources) {
                if (!(source instanceof Map)) {
                    continue;
                }
                Object value = ((Map<?, ?>) source).get("reasoning_content");
                if (value instanceof String text1 && !text1.isEmpty()) {
                    reasoning = text1;
                    break;
                }
            }
        }
        if (reasoning.isEmpty()) {
            Matcher matcher = THINK_PREFIX.matcher(text);
            if (matcher.find()) {
                reasoning = matcher.group(1);
                text = text.substring(matcher.end());
            }
        }
        return result(text, reasoning);
    }

    private static Map<String, String> result(String content, String reasoningContent) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("reasoning_content", reasoningContent);
        return result;
    }
}
