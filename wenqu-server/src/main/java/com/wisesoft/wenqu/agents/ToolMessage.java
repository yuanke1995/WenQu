package com.wisesoft.wenqu.agents;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LangChain {@code ToolMessage} 的最小承载（能力差异，显式标注）。
 *
 * <p>参考实现直接使用 {@code langchain_core.messages.ToolMessage}（pydantic 模型）；
 * 本工程无 LangChain，由本类承载其数据面。用途见
 * {@link BaseAgent#normalizeToolEventData(java.util.Map)}：{@code write_todos} / {@code task}
 * 这类"返回 {@code Command} 的工具"，其 tool-finished 事件的 output 是 {@code Command}，
 * 需要从 {@code Command.update["messages"]} 里按 {@code tool_call_id} 取出真正的
 * {@code ToolMessage}，前端才能关联到工具调用结果。
 *
 * <p>{@link #rawMessage()} 对应 {@code model_dump()}（键序与 langchain 一致：
 * content / type / tool_call_id / name，未设置的可选键不出现）。
 */
public final class ToolMessage implements ModelDumpable {

    private final Object content;
    private final String toolCallId;
    private final String name;

    private ToolMessage(Object content, String toolCallId, String name) {
        this.content = content;
        this.toolCallId = toolCallId;
        this.name = name;
    }

    /** {@code ToolMessage(content=..., tool_call_id=..., name=...)}。 */
    public static ToolMessage of(Object content, String toolCallId) {
        return new ToolMessage(content, toolCallId, null);
    }

    public static ToolMessage of(Object content, String toolCallId, String name) {
        return new ToolMessage(content, toolCallId, name);
    }

    public Object getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getName() {
        return name;
    }

    /** model_dump()：{"content":..., "type":"tool", "tool_call_id":..., "name":...}。 */
    public Map<String, Object> rawMessage() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("content", content);
        dump.put("type", "tool");
        dump.put("tool_call_id", toolCallId);
        if (name != null) {
            dump.put("name", name);
        }
        return dump;
    }

    @Override
    public Map<String, Object> modelDump() {
        return rawMessage();
    }
}
