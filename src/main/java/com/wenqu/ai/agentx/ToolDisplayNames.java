package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.tool.ToolRegistry;
import com.wenqu.ai.service.McpToolLabelService;

import java.util.Map;

/**
 * 工具卡的展示名与悬停说明的解析入口（框架引擎这一侧）。
 *
 * <p><b>为什么要有它</b>：同一张工具卡要显示中文名，而工具来源不同：
 * 手写侧的工具全部由 {@link ToolRegistry} 声明，框架引擎除了那批问渠工具，还挂着框架自己
 * registerTool 进来的 {@code plan_*} 三把（{@code HarnessAgent#enablePlanMode}），以及
 * 用户自己登记的 MCP 动态工具。引擎原先把裸名当 label 下发，前端只好靠手抄映射兜底。
 *
 * <p><b>取值顺序（顺序本身也是契约）</b>：
 * <ol>
 *   <li>{@link ToolRegistry}——问渠工具的唯一真源；</li>
 *   <li>{@link FrameworkToolLabels}——框架原生工具（注册中心管不着它们，原因见该类注释）；</li>
 *   <li>{@link McpToolLabelService}——MCP 动态工具（用户在 MCP 页手工起的中文名，
 *       按用户归属隔离；没起名返回 null 走下一档）；</li>
 *   <li>都没有 ⇒ 返回 {@code null}，<b>调用方据此不写 label 字段</b>。</li>
 * </ol>
 * 最后一档刻意不回落成工具裸名：前端 {@code toolLabel(n, label)} 把「label 等于名字」判定为
 * 「后端没给展示名」而走它自己的映射，所以留空与填裸名在界面上等价，但留空让
 * 「这里真的没有中文名」这件事在载荷里<b>看得见</b>——填裸名则会被误读成「后端认真取过这个名字」。
 *
 * <p>MCP 这一档是<b>按 uid 查</b>的（MCP 是个人资产，翻译缓存跟着归属走）：调用方必须把
 * 本轮的 uid 传进来；uid 为 null 时跳过这一档（不抛、不造翻译）。
 *
 * @author yuanke
 */
final class ToolDisplayNames {

    private final ToolRegistry registry;
    private final McpToolLabelService mcpLabels;

    ToolDisplayNames(ToolRegistry registry, McpToolLabelService mcpLabels) {
        this.registry = registry;
        this.mcpLabels = mcpLabels;
    }

    /**
     * 卡片展示名；解析不到返回 null。
     *
     * @param uid      本轮归属用户（MCP 中文名按用户查；可为 null = 跳过 MCP 档）
     * @param toolName 模型可见工具名（可为 null）
     */
    String label(String uid, String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        String fromRegistry = registry == null ? null : registry.displayLabel(toolName);
        if (fromRegistry != null) return fromRegistry;
        String framework = FrameworkToolLabels.label(toolName);
        if (framework != null) return framework;
        return mcpLabels == null ? null : mcpLabels.label(uid, toolName);
    }

    /** 悬停一句话说明；未收录返回 null（前端据此不显示 title，好过显示一句编出来的解释） */
    String hint(String uid, String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        String fromRegistry = registry == null ? null : registry.cardHint(toolName);
        if (fromRegistry != null) return fromRegistry;
        // MCP 工具没有说明源：名字是用户手工起的，悬停不编解释
        return FrameworkToolLabels.hint(toolName);
    }

    /**
     * 展示字段写入（{@code label} / {@code hint}），取不到的字段<b>不写</b>。
     *
     * <p>与 {@code ToolRegistry#putDisplayFields} 同名同语义。刻意收在这里而不是让各调用处
     * 各写一遍 {@code if (label != null) put(...)}：手抄的失败方式正是「同一张卡刷新前后换了个名字」。
     *
     * @param payload  待写入的载荷（一般是工具卡或审批卡）
     * @param uid      本轮归属用户（MCP 档按它查；可为 null）
     * @param toolName 模型可见工具名
     */
    void putDisplayFields(Map<String, Object> payload, String uid, String toolName) {
        String label = label(uid, toolName);
        if (label != null) payload.put("label", label);
        String hint = hint(uid, toolName);
        if (hint != null) payload.put("hint", hint);
    }
}
