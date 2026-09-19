package com.wisesoft.wenqu.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主智能体上下文（对应参考实现 {@code agents/buildin/chatbot/context.py} 的
 * {@code ChatBotContext}）。
 *
 * <p>参考实现以 dataclass 继承在 {@code BaseContext} 上叠加唯一字段
 * {@code subagents}（{@code list[str] | None}，默认 {@code None}）。本类逐字对齐其
 * 字段名、默认值与 metadata（name / options / description / type / kind）。
 *
 * <p>平台差异（必要替换）：Java 无法用继承叠加 {@link BaseContext} 的静态字段声明表，
 * 故在构造期经 {@link BaseContext#declareExtraField} 登记 —— 只影响本实例，
 * 不污染基类声明表（基类 {@code fieldNames()} 仍不含 {@code subagents}）。
 *
 * <p>语义备注：{@code subagents} 属于「空集合语义」的资源字段
 * （见 {@link AgentContextFields#EMPTY_ALL_CONTEXT_FIELDS}）—— {@code null} 表示
 * 启用当前用户可见的全部子智能体，空列表表示一个都不启用。
 */
public class ChatBotContext extends BaseContext {

    /** 参考实现 {@code ChatBotContext.subagents} 字段名（逐字）。 */
    public static final String SUBAGENTS = "subagents";

    public ChatBotContext() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "子智能体");
        metadata.put("options", List.of());
        metadata.put("description", "可选子智能体列表，为空表示启用当前用户可见的全部子智能体。");
        metadata.put("type", "list");
        metadata.put("kind", "subagents");
        declareExtraField(SUBAGENTS, null, metadata);
    }

    /** 当前配置的子智能体 slug（{@code null} 表示启用全部可见子智能体）。 */
    public List<String> getSubagents() {
        return getList(SUBAGENTS);
    }

    public void setSubagents(List<String> subagents) {
        set(SUBAGENTS, subagents);
    }
}
