package com.wisesoft.wenqu.agents.engine;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.wisesoft.wenqu.agents.AIMessage;
import com.wisesoft.wenqu.agents.HumanMessage;
import com.wisesoft.wenqu.agents.ToolMessage;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

/**
 * 图引擎桥接编解码：本工程承载类 ↔ Spring AI 消息/配置/状态。
 *
 * <h3>为什么需要这一层</h3>
 * <p>参考实现的 agents 运行时直接消费 LangChain 的 {@code HumanMessage} /
 * {@code AIMessage} / {@code ToolMessage} 与 LangGraph 的 {@code config} dict
 * （{@code {"configurable": {"thread_id":…}}, "recursion_limit":…}）；
 * java 侧底座换成 <b>Spring AI + spring-ai-alibaba</b> 后，对应类型变为
 * {@code org.springframework.ai.chat.messages.*} 与
 * {@code com.alibaba.cloud.ai.graph.RunnableConfig}。本类把两侧逐字段对上，
 * 使 {@code BaseAgent} 及后续 middlewares 的照搬代码不必关心底座差异。
 *
 * <h3>必要替换（平台差异，显式标注）</h3>
 * <ul>
 *   <li>{@code config["configurable"]["thread_id"]} → {@link RunnableConfig.Builder#threadId};
 *       {@code config["configurable"]["checkpoint_id"]} → {@code checkPointId};
 *       {@code config["metadata"]} → {@code addMetadata}（逐键）。</li>
 *   <li>{@code config["recursion_limit"]} <b>在本层被丢弃</b>：spring-ai-alibaba 的递归上限是
 *       <b>编译期</b>设置（{@code CompileConfig.recursionLimit()} → {@code CompiledGraph.maxIterations}），
 *       不支持逐次调用传入。故 {@link #recursionLimit(Map, int)} 只供构图方在建图时取值，
 *       见 {@link GraphFactory}。</li>
 *   <li>多模态 {@code {"type":"image_url","image_url":{"url":…}}} → Spring AI
 *       {@link Media}({@link MimeType}, {@link URI})；无 mime 提示时按 url 后缀推断，
 *       推断不出用 {@link MimeTypeUtils#APPLICATION_OCTET_STREAM}。</li>
 *   <li>一条 {@link ToolMessage}（LangChain 的单条工具结果）→ 一个
 *       {@link ToolResponseMessage}（Spring AI 把工具结果按调用轮次分组成一条消息，
 *       故单条结果自成一组）。反向 {@link ToolResponseMessage} → 多条 {@link ToolMessage}。</li>
 * </ul>
 */
public final class GraphCodec {

    private GraphCodec() {
    }

    // =========================================================================
    // === 本工程承载类 → Spring AI Message ===
    // =========================================================================

    /** 把参考实现形态的消息列表（承载类 / Map / 字符串）统一转成 Spring AI 消息。 */
    public static List<Message> toSpringAiMessages(List<?> messages) {
        List<Message> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (Object message : messages) {
            Message converted = toSpringAiMessage(message);
            if (converted != null) {
                result.add(converted);
            }
        }
        return result;
    }

    /** 单条消息转换；无法识别时返回 null（调用方决定是否视为错误）。 */
    public static Message toSpringAiMessage(Object message) {
        if (message == null) {
            return null;
        }
        if (message instanceof Message springAiMessage) {
            return springAiMessage;
        }
        if (message instanceof HumanMessage human) {
            return toUserMessage(human.getContent());
        }
        if (message instanceof ToolMessage tool) {
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            nullToEmpty(tool.getToolCallId()),
                            nullToEmpty(tool.getName()),
                            stringify(tool.getContent()))))
                    .build();
        }
        if (message instanceof AIMessage ai) {
            return toAssistantMessage(ai);
        }
        if (message instanceof String text) {
            return new UserMessage(text);
        }
        if (message instanceof Map<?, ?> map) {
            return toSpringAiMessage(map);
        }
        return new UserMessage(String.valueOf(message));
    }

    /** 从序列化字典（{@code model_dump()} 形态）恢复 Spring AI 消息。 */
    public static Message toSpringAiMessage(Map<?, ?> raw) {
        Object type = raw.get("type");
        String kind = type == null ? "" : String.valueOf(type);
        if ("human".equals(kind) || "user".equals(kind)) {
            return toUserMessage(raw.get("content"));
        }
        if ("tool".equals(kind)) {
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            nullToEmpty(stringOrNull(raw.get("tool_call_id"))),
                            nullToEmpty(stringOrNull(raw.get("name"))),
                            stringify(raw.get("content")))))
                    .build();
        }
        if ("ai".equals(kind) || "AIMessageChunk".equals(kind)) {
            AssistantMessage.Builder builder = AssistantMessage.builder()
                    .content(stringify(raw.get("content")));
            List<AssistantMessage.ToolCall> toolCalls = toSpringAiToolCalls(raw.get("tool_calls"));
            if (!toolCalls.isEmpty()) {
                builder.toolCalls(toolCalls);
            }
            return builder.build();
        }
        return new UserMessage(stringify(raw.get("content")));
    }

    /** 多模态 content（字符串或多模态块列表）→ {@link UserMessage}。 */
    public static UserMessage toUserMessage(Object content) {
        if (content instanceof String text) {
            return new UserMessage(text);
        }
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            List<Media> media = new ArrayList<>();
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> block)) {
                    continue;
                }
                String type = stringOrNull(block.get("type"));
                if ("text".equals(type) && block.get("text") != null) {
                    text.append(block.get("text"));
                    continue;
                }
                String url = imageUrlOf(block);
                if (url != null) {
                    media.add(new Media(mimeTypeOf(url), URI.create(url)));
                }
            }
            UserMessage.Builder builder = UserMessage.builder().text(text.toString());
            if (!media.isEmpty()) {
                builder.media(media);
            }
            return builder.build();
        }
        return new UserMessage(stringify(content));
    }

    /** 承载类 {@link AIMessage} → Spring AI {@link AssistantMessage}。 */
    public static AssistantMessage toAssistantMessage(AIMessage ai) {
        AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(ai.text());
        List<AssistantMessage.ToolCall> toolCalls = toSpringAiToolCalls(ai.getToolCalls());
        if (!toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    /** LangChain 形态的 tool_calls（{@code [{name, args, id, type}]}）→ Spring AI {@link AssistantMessage.ToolCall}。 */
    public static List<AssistantMessage.ToolCall> toSpringAiToolCalls(Object rawToolCalls) {
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        if (!(rawToolCalls instanceof List<?> toolCalls)) {
            return result;
        }
        for (Object raw : toolCalls) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            String arguments = map.get("args") == null ? "{}" : stringify(map.get("args"));
            result.add(new AssistantMessage.ToolCall(
                    nullToEmpty(stringOrNull(map.get("id"))),
                    map.get("type") == null ? "function" : String.valueOf(map.get("type")),
                    nullToEmpty(stringOrNull(map.get("name"))),
                    arguments));
        }
        return result;
    }

    // =========================================================================
    // === Spring AI Message → 本工程承载类 ===
    // =========================================================================

    /** Spring AI 消息 → 承载类（供事件适配层构造 {@code messages} 事件载荷）。 */
    public static Object toCarrier(Message message) {
        if (message instanceof AssistantMessage assistant) {
            return toAiMessage(assistant);
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            return toToolMessage(toolResponse);
        }
        if (message instanceof UserMessage user) {
            return HumanMessage.of(user.getText());
        }
        return message;
    }

    /** Spring AI {@link AssistantMessage} → 承载类 {@link AIMessage}（含 tool_calls 投影）。 */
    public static AIMessage toAiMessage(AssistantMessage assistant) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
            toolCalls.add(toolCallToDict(toolCall));
        }
        return AIMessage.of(assistant.getText(), toolCalls);
    }

    /** {@link AssistantMessage.ToolCall} → {@code {"name":…, "args":{…}, "id":…, "type":"tool_call"}}。 */
    public static Map<String, Object> toolCallToDict(AssistantMessage.ToolCall toolCall) {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("name", toolCall.name());
        dict.put("args", parseJsonObject(toolCall.arguments()));
        dict.put("id", toolCall.id());
        dict.put("type", "tool_call");
        return dict;
    }

    /**
     * {@link ToolResponseMessage} → 承载类 {@link ToolMessage}（取第一条响应）。
     *
     * <p>参考实现的工具事件按单条结果产出，故多响应时取首条；需要全部时用
     * {@link #toToolMessages(ToolResponseMessage)}。
     */
    public static ToolMessage toToolMessage(ToolResponseMessage response) {
        List<ToolMessage> all = toToolMessages(response);
        return all.isEmpty() ? null : all.get(0);
    }

    /** {@link ToolResponseMessage} → 多条承载类 {@link ToolMessage}（顺序保持一致）。 */
    public static List<ToolMessage> toToolMessages(ToolResponseMessage response) {
        List<ToolMessage> result = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse item : response.getResponses()) {
            result.add(ToolMessage.of(item.responseData(), item.id(), item.name()));
        }
        return result;
    }

    // =========================================================================
    // === 配置：参考实现的 config dict → RunnableConfig ===
    // =========================================================================

    /** 取 {@code config["configurable"]}（缺失返回空表）。 */
    public static Map<String, Object> configurable(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        Object value = config.get("configurable");
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /** 取 {@code config["configurable"]["thread_id"]}。 */
    public static String threadId(Map<String, Object> config) {
        return stringOrNull(configurable(config).get("thread_id"));
    }

    /** 取 {@code config["configurable"]["uid"]}。 */
    public static String uid(Map<String, Object> config) {
        return stringOrNull(configurable(config).get("uid"));
    }

    /**
     * 取 {@code config["recursion_limit"]}，缺失或非正数时回落 default。
     *
     * <p>只供<b>构图</b>使用（spring-ai-alibaba 递归上限是编译期设置）。见类注释。
     */
    public static int recursionLimit(Map<String, Object> config, int defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        Object value = config.get("recursion_limit");
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return defaultValue;
    }

    /**
     * 参考实现的 {@code config} dict → {@link RunnableConfig}。
     *
     * <p>映射：{@code configurable.thread_id} → {@code threadId()}、
     * {@code configurable.checkpoint_id} → {@code checkPointId()}、
     * {@code metadata} → 逐键 {@code addMetadata}。
     * {@code recursion_limit} 不映射（编译期设置，见类注释）。
     */
    public static RunnableConfig toRunnableConfig(Map<String, Object> config) {
        RunnableConfig.Builder builder = RunnableConfig.builder();
        Map<String, Object> configurable = configurable(config);
        String threadId = stringOrNull(configurable.get("thread_id"));
        if (threadId != null) {
            builder.threadId(threadId);
        }
        String checkpointId = stringOrNull(configurable.get("checkpoint_id"));
        if (checkpointId != null) {
            builder.checkPointId(checkpointId);
        }
        if (config != null) {
            Object metadata = config.get("metadata");
            if (metadata instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() != null) {
                        builder.addMetadata(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
        }
        return builder.build();
    }

    /** 由 uid / threadId 直接构造 {@link RunnableConfig}（{@code AgentStateRepository} 端口用）。 */
    public static RunnableConfig toRunnableConfig(String uid, String threadId) {
        RunnableConfig.Builder builder = RunnableConfig.builder();
        if (threadId != null) {
            builder.threadId(String.valueOf(threadId));
        }
        if (uid != null) {
            builder.addMetadata("uid", String.valueOf(uid));
        }
        return builder.build();
    }

    // =========================================================================
    // === 状态：OverAllState → 普通 Map ===
    // =========================================================================

    /** {@link OverAllState} → {@code state.values} 形态的普通 Map（深拷贝外层）。 */
    public static Map<String, Object> stateToMap(OverAllState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (state == null) {
            return result;
        }
        Map<String, Object> data = state.data();
        if (data != null) {
            result.putAll(data);
        }
        return result;
    }

    /** 从 state 取 {@code messages}（对应 {@code state.values.get("messages", [])}）。 */
    public static List<Object> messagesOf(OverAllState state) {
        List<Object> result = new ArrayList<>();
        if (state == null) {
            return result;
        }
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            result.addAll(list);
        }
        return result;
    }

    // =========================================================================
    // === 小工具 ===
    // =========================================================================

    /** 宽松 JSON 对象解析（非法或空串回落空表）。 */
    public static Map<String, Object> parseJsonObject(String raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(raw)) {
            return result;
        }
        try {
            Object parsed = com.alibaba.fastjson2.JSON.parse(raw);
            if (parsed instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 参数（如纯文本 arguments）→ 空表，与参考实现宽松解析一致
        }
        return result;
    }

    private static String imageUrlOf(Map<?, ?> block) {
        Object imageUrl = block.get("image_url");
        if (imageUrl instanceof Map<?, ?> map) {
            return stringOrNull(map.get("url"));
        }
        if (imageUrl instanceof String url) {
            return url;
        }
        return null;
    }

    private static MimeType mimeTypeOf(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MimeTypeUtils.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return new MimeType("image", "webp");
        }
        return MimeTypeUtils.APPLICATION_OCTET_STREAM;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return com.alibaba.fastjson2.JSON.toJSONString(value);
        }
        return String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 去重保持顺序（供事件适配层收集节点名）。 */
    static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}
