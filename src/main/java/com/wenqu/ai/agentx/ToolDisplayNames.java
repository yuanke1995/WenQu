package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.tool.ToolRegistry;

import java.util.Map;

/**
 * 工具卡的展示名与悬停说明的解析入口（框架引擎这一侧）。
 *
 * <p><b>为什么要有它</b>：同一张工具卡在两条运行时上要显示同一个中文名，而两个运行时的工具来源不同：
 * 手写侧的工具全部由 {@link ToolRegistry} 声明，框架引擎除了那批问渠工具，还挂着框架自己
 * registerTool 进来的 {@code plan_*} 三把（{@code HarnessAgent#enablePlanMode}）。原先框架引擎
 * 压根不问注册中心，直接把裸名当 label 下发，前端只好靠那份手抄映射兜底——而那份表里
 * 没有 {@code consultAgent}、{@code writeTodo}、{@code plan_*}，于是这些卡片现出英文裸名。
 *
 * <p><b>取值顺序（与手写侧同源，顺序本身也是契约）</b>：
 * <ol>
 *   <li>{@link ToolRegistry}——问渠工具的唯一真源；</li>
 *   <li>{@link FrameworkToolLabels}——框架原生工具（注册中心管不着它们，原因见该类注释）；</li>
 *   <li>都没有 ⇒ 返回 {@code null}，<b>调用方据此不写 label 字段</b>。</li>
 * </ol>
 * 第三档刻意不回落成工具裸名：前端 {@code toolLabel(n, label)} 把「label 等于名字」判定为
 * 「后端没给展示名」而走它自己的映射，所以留空与填裸名在界面上等价，但留空让
 * 「这里真的没有中文名」这件事在载荷里<b>看得见</b>——填裸名则会被误读成「后端认真取过这个名字」。
 *
 * <p>取不到时也不抛、不造翻译：MCP 动态工具名由用户自己起的 server 决定，本就没有可译的名字，
 * 前端那份映射（含 MCP 工具的收录）才是它们的来源。
 *
 * @author yuanke
 */
final class ToolDisplayNames {

    private final ToolRegistry registry;

    ToolDisplayNames(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 卡片展示名；解析不到返回 null。
     *
     * @param toolName 模型可见工具名（可为 null）
     */
    String label(String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        String fromRegistry = registry == null ? null : registry.displayLabel(toolName);
        // 注册中心对未登记工具返回 null（不硬造翻译），所以这里能安全地往下退
        return fromRegistry != null ? fromRegistry : FrameworkToolLabels.label(toolName);
    }

    /** 悬停一句话说明；未收录返回 null（前端据此不显示 title，好过显示一句编出来的解释） */
    String hint(String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        String fromRegistry = registry == null ? null : registry.cardHint(toolName);
        return fromRegistry != null ? fromRegistry : FrameworkToolLabels.hint(toolName);
    }

    /**
     * 展示字段写入（{@code label} / {@code hint}），取不到的字段<b>不写</b>。
     *
     * <p>与 {@code ToolRegistry#putDisplayFields} 同名同语义——那段是手写侧工具卡、实时审批卡、
     * 刷新后重建待批列表三处的共同入口，这段是框架侧那几处（实时工具卡、落库工具卡、
     * 审批卡）的共同入口。刻意收在这里而不是让三处各写一遍 {@code if (label != null) put(...)}：
     * 手抄的失败方式正是「同一张卡刷新前后换了个名字」。
     *
     * @param payload  待写入的载荷（一般是工具卡或审批卡）
     * @param toolName 模型可见工具名
     */
    void putDisplayFields(Map<String, Object> payload, String toolName) {
        String label = label(toolName);
        if (label != null) payload.put("label", label);
        String hint = hint(toolName);
        if (hint != null) payload.put("hint", hint);
    }
}