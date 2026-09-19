package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain {@code AIMessage} / {@code AIMessageChunk} 的最小承载（能力差异，显式标注）。
 *
 * <p>参考实现直接使用 {@code langchain.messages.AIMessage} 与 {@code AIMessageChunk}
 * （pydantic 模型）；本工程无 LangChain，由本类承载其数据面 —— 与既有
 * {@link HumanMessage} / {@link ToolMessage} 构成完整的三件套。
 *
 * <h3>字段来源（照搬的实际消费面，非推测）</h3>
 * <p>参考实现 {@code chat_service.py} 对 AI 消息只做两件事：
 * <ol>
 *   <li>{@code msg_dict = msg.model_dump()}（判定条件 {@code isinstance(msg, AIMessageChunk)
 *       or hasattr(msg, "model_dump")}）；</li>
 *   <li>{@code _ai_message_content_and_tool_calls(msg_dict)} 读 {@code msg_dict["content"]}
 *       与 {@code msg_dict["tool_calls"]}。</li>
 * </ol>
 * 故本类的 {@link #rawMessage()} 承载 {@code content} / {@code type} / {@code tool_calls}
 * 三个消费键；{@code id} 与 {@code usage_metadata} 仅在调用方（图引擎事件适配层）
 * 确实持有该信息时输出，避免凭空造键。
 *
 * <p>{@code type} 固定为 {@code "ai"}（对应 {@code AIMessage}）。参考实现的流式分片是
 * {@code AIMessageChunk}（dump 的 type 为 {@code "AIMessageChunk"}）；本工程图引擎按节点
 * 粒度产出完整 {@code AIMessage}，无分片类型，故统一以 {@code "ai"} 承载。
 */
public final class AIMessage implements ModelDumpable {

    private final Object content;
    private final List<Map<String, Object>> toolCalls;
    private final String id;
    private final Map<String, Object> usageMetadata;

    private AIMessage(
            Object content,
            List<Map<String, Object>> toolCalls,
            String id,
            Map<String, Object> usageMetadata) {
        this.content = content;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : new ArrayList<>(toolCalls);
        this.id = id;
        this.usageMetadata = usageMetadata;
    }

    /** {@code AIMessage(content=...)}。 */
    public static AIMessage of(Object content) {
        return new AIMessage(content, null, null, null);
    }

    /** {@code AIMessage(content=..., tool_calls=[...])}。 */
    public static AIMessage of(Object content, List<Map<String, Object>> toolCalls) {
        return new AIMessage(content, toolCalls, null, null);
    }

    /** 完整构造（含 id / usage_metadata；两者为 null 时不在 dump 中出现）。 */
    public static AIMessage of(
            Object content,
            List<Map<String, Object>> toolCalls,
            String id,
            Map<String, Object> usageMetadata) {
        return new AIMessage(content, toolCalls, id, usageMetadata);
    }

    public Object getContent() {
        return content;
    }

    public List<Map<String, Object>> getToolCalls() {
        return new ArrayList<>(toolCalls);
    }

    /** 是否存在工具调用（对应参考实现 {@code bool(msg_dict.get("tool_calls"))}）。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public String getId() {
        return id;
    }

    public Map<String, Object> getUsageMetadata() {
        return usageMetadata == null ? null : new LinkedHashMap<>(usageMetadata);
    }

    /**
     * {@code model_dump()}：键序 content / type / tool_calls（+ 可选 id / usage_metadata）。
     *
     * <p>与 {@link HumanMessage#rawMessage()} / {@link ToolMessage#rawMessage()} 同风格：
     * 只承载实际被消费的键，且未设置的可选键不出现。
     */
    public Map<String, Object> rawMessage() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("content", content);
        dump.put("type", "ai");
        dump.put("tool_calls", new ArrayList<>(toolCalls));
        if (id != null) {
            dump.put("id", id);
        }
        if (usageMetadata != null) {
            dump.put("usage_metadata", new LinkedHashMap<>(usageMetadata));
        }
        return dump;
    }

    /** model_dump()：与 {@link #rawMessage()} 同形状（消息类上两者返回一致）。 */
    @Override
    public Map<String, Object> modelDump() {
        return rawMessage();
    }

    /**
     * 文本正文（{@code content} 为字符串时返回自身，为多模态块列表时拼接其中的 {@code text} 块）。
     *
     * <p>对应参考实现 {@code isinstance(message.content, str)} 的分支判定
     * （见 {@code chat_service.py} 的上下文拼接逻辑）。
     */
    public String text() {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && map.get("text") != null) {
                    builder.append(map.get("text"));
                }
            }
            return builder.toString();
        }
        return String.valueOf(content);
    }
}
