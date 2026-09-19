package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.HumanMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户输入在数据库与消息对象之间的规范化工具。
 *
 * <p>由参考实现的 services/input_message_service.py 逐函数翻译：AgentRunInputMessage
 * 载荷、chat 文本/多模态输入构造、OpenAI content 数组归一化、resume 消息、
 * 从持久化 metadata 恢复。
 *
 * <p>必要替换：langchain HumanMessage → {@link HumanMessage} 数据承载
 * （引擎照搬前的能力差异端口）；{@code dataclasses.replace} → 以同字段新建；
 * {@code json.dumps(ensure_ascii=False)} → fastjson2（非 ASCII 不转义）。
 */
public final class InputMessageService {

    private InputMessageService() {}

    /** AgentRunInputMessage 载荷（不可变，withMetadata 对应 dataclasses.replace）。 */
    public record AgentRunInputMessage(
            String content,
            String messageType,
            String imageContent,
            HumanMessage langchainMessage,
            Map<String, Object> extraMetadata) {

        public Map<String, Object> rawMessage() {
            return langchainMessage != null ? langchainMessage.rawMessage() : null;
        }

        public HumanMessage requireLangchainMessage() {
            if (langchainMessage == null) {
                throw new IllegalArgumentException("chat input message must include a LangChain HumanMessage");
            }
            return langchainMessage;
        }

        public AgentRunInputMessage withMetadata(Map<String, Object> metadata) {
            return new AgentRunInputMessage(
                    content, messageType, imageContent, langchainMessage,
                    metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
        }
    }

    /** 构造文本或多模态图片输入消息。 */
    public static AgentRunInputMessage buildChatInputMessage(String query, String imageContent) {
        HumanMessage langchainMessage;
        String messageType;
        if (imageContent != null && !imageContent.isEmpty()) {
            Map<String, Object> imageUrl = new LinkedHashMap<>();
            imageUrl.put("url", "data:image/jpeg;base64," + imageContent);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "image_url");
            part.put("image_url", imageUrl);
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", query);

            List<Object> content = new ArrayList<>();
            content.add(textPart);
            content.add(part);
            langchainMessage = HumanMessage.of(content);
            messageType = "multimodal_image";
        } else {
            langchainMessage = HumanMessage.of(query);
            messageType = "text";
        }

        return new AgentRunInputMessage(
                query, messageType, imageContent, langchainMessage, new LinkedHashMap<>());
    }

    /** 从 OpenAI content（字符串或多模态数组）构造输入消息。 */
    public static AgentRunInputMessage buildChatInputMessageFromOpenaiContent(Object content) {
        if (content instanceof String text) {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("user message content 必须是非空字符串或多模态数组");
            }
            return buildChatInputMessage(text, null);
        }

        if (!(content instanceof List) || ((List<?>) content).isEmpty()) {
            throw new IllegalArgumentException("user message content 必须是非空字符串或多模态数组");
        }

        List<Object> parts = new ArrayList<>();
        List<String> textSegments = new ArrayList<>();
        String firstImageContent = null;
        boolean hasImage = false;

        for (Object item : (List<?>) content) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("user message content 多模态数组元素必须是对象");
            }
            Map<?, ?> part = (Map<?, ?>) item;

            Object partType = part.get("type");
            if ("text".equals(partType)) {
                Object textObj = part.get("text");
                if (!(textObj instanceof String text)) {
                    throw new IllegalArgumentException("text content part 必须包含字符串 text");
                }
                if (!text.isEmpty()) {
                    textSegments.add(text);
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", text);
                    parts.add(textPart);
                }
                continue;
            }

            if ("image_url".equals(partType)) {
                Map<String, Object> imageUrl = normalizeOpenaiImageUrlPart(part.get("image_url"));
                hasImage = true;
                if (firstImageContent == null) {
                    firstImageContent = extractDataUrlBase64(String.valueOf(imageUrl.get("url")));
                }
                Map<String, Object> imagePart = new LinkedHashMap<>();
                imagePart.put("type", "image_url");
                imagePart.put("image_url", imageUrl);
                parts.add(imagePart);
                continue;
            }

            throw new IllegalArgumentException("不支持的多模态 content part 类型: " + partType);
        }

        if (textSegments.isEmpty() && !hasImage) {
            throw new IllegalArgumentException("user message content 必须包含非空文本或图片");
        }

        String query = String.join("\n", textSegments);
        if (!hasImage) {
            return buildChatInputMessage(query, null);
        }

        return new AgentRunInputMessage(
                query, "multimodal_image", firstImageContent, HumanMessage.of(parts), new LinkedHashMap<>());
    }

    private static Map<String, Object> normalizeOpenaiImageUrlPart(Object imageUrl) {
        String url;
        Map<String, Object> normalized;
        if (imageUrl instanceof String text) {
            url = text;
            normalized = new LinkedHashMap<>();
            normalized.put("url", url);
        } else if (imageUrl instanceof Map) {
            url = (String) ((Map<?, ?>) imageUrl).get("url");
            normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) imageUrl).entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            throw new IllegalArgumentException("image_url content part 必须包含 image_url.url");
        }

        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("image_url content part 必须包含 image_url.url");
        }
        normalized.put("url", url);
        return normalized;
    }

    private static String extractDataUrlBase64(String url) {
        String marker = ";base64,";
        if (url == null || !url.startsWith("data:image/") || !url.contains(marker)) {
            return null;
        }
        return url.substring(url.indexOf(marker) + marker.length());
    }

    /** 构造 resume 输入消息（resume 对象以 JSON 承载）。 */
    public static AgentRunInputMessage buildResumeInputMessage(Object resume) {
        return new AgentRunInputMessage(pythonJsonDumps(resume), "resume", null, null, new LinkedHashMap<>());
    }

    /**
     * Python {@code json.dumps(obj, ensure_ascii=False)} 的默认形态：
     * 分隔符为 {@code ", "}/{@code ": "}、保留 null、键序为插入序、非 ASCII 原样。
     */
    static String pythonJsonDumps(Object value) {
        StringBuilder builder = new StringBuilder();
        dumps(builder, value);
        return builder.toString();
    }

    private static void dumps(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            dumpsString(builder, text);
        } else if (value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            builder.append(value);
        } else if (value instanceof Number) {
            // 整数不带小数点（与 Python int 一致）
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                dumpsString(builder, String.valueOf(entry.getKey()));
                builder.append(": ");
                dumps(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                dumps(builder, item);
            }
            builder.append(']');
        } else {
            dumpsString(builder, String.valueOf(value));
        }
    }

    /** json.dumps 的字符串转义（ensure_ascii=False）：仅控制字符与引号/反斜杠。 */
    private static void dumpsString(StringBuilder builder, String text) {
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    /** 从持久化 metadata 恢复输入消息。 */
    public static AgentRunInputMessage restoreChatInputMessage(
            String content, String imageContent, Map<String, Object> metadata) {
        Object rawMessage = metadata == null ? null : metadata.get("raw_message");
        if (rawMessage instanceof Map) {
            HumanMessage langchainMessage;
            try {
                langchainMessage = HumanMessage.validate(rawMessage);
            } catch (RuntimeException exc) {
                throw new IllegalArgumentException("invalid raw_message for chat input message");
            }
            Object rawContent = ((Map<?, ?>) rawMessage).get("content");
            String messageType =
                    (imageContent != null && !imageContent.isEmpty()) || hasImageUrlContentPart(rawContent)
                            ? "multimodal_image"
                            : "text";
            return new AgentRunInputMessage(
                    content,
                    messageType,
                    imageContent,
                    langchainMessage,
                    metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
        }

        return buildChatInputMessage(content, imageContent);
    }

    private static boolean hasImageUrlContentPart(Object content) {
        if (!(content instanceof List)) {
            return false;
        }
        for (Object part : (List<?>) content) {
            if (part instanceof Map && "image_url".equals(((Map<?, ?>) part).get("type"))) {
                return true;
            }
        }
        return false;
    }
}
