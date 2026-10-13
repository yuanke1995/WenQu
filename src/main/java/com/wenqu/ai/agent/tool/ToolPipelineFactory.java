package com.wenqu.ai.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用管线：把模型侧的一次工具调用过一遍「守卫链 → 上下文注入 → 执行 → 观测 → 结果加工」。
 * <p>
 * 迁移前这一整段是 {@code RagService.instrumentTools} 里的匿名内部类（两百行、直读
 * TurnState 的字段）。搬出来的直接收益有三条，都不是抽象洁癖：
 * <ol>
 *   <li><b>旁路可收口</b>：工作流的 mcp/skill 节点当前是 {@code target.call(json)} 裸调，
 *       绕开审批、绕开预算、绕开工具卡与事件账本。管线独立成 bean 之后它才可能被复用；</li>
 *   <li><b>按轮状态不再挂在静态字段上</b>：知识检索的引用注册器、联网搜索的落点、沙盒的输出回调
 *       全部改为本次调用独占（{@link ToolEnrichment} + {@link ToolInvocation#attributes()}）；</li>
 *   <li><b>元数据有单一来源</b>：要不要审批、卡片叫什么，都查 {@link ToolRegistry}，
 *       不再在装配处维护一份、清单页维护另一份。</li>
 * </ol>
 * 绞杀式迁移：本管线目前只服务问答流水线那一条装配路径（{@code instrumentTools} 的唯一调用点），
 * 工作流/技能/MCP server 侧的裸调路径**本期不动**，只留下 {@link #wrapOne} 这个接入口。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ToolPipelineFactory {

    private final ToolRegistry registry;
    /** 守卫链：Spring 按 @Order 注入有序列表（叫停闸 → 预算闸 → 审批闸） */
    private final List<ToolGuard> guards;

    public ToolPipelineFactory(ToolRegistry registry, List<ToolGuard> guards) {
        this.registry = registry;
        this.guards = guards == null ? List.of() : guards;
    }

    public ToolCallback[] wrap(List<ToolCallback> rawTools, ToolTurnContext turn) {
        if (rawTools == null || rawTools.isEmpty()) return new ToolCallback[0];
        ToolCallback[] out = new ToolCallback[rawTools.size()];
        for (int i = 0; i < rawTools.size(); i++) {
            out[i] = wrapOne(rawTools.get(i), turn);
        }
        return out;
    }

    public ToolCallback wrapOne(ToolCallback raw, ToolTurnContext turn) {
        return new GuardedToolCallback(raw, turn, registry, guards);
    }

    /**
     * 单次调用的执行体（两个 {@code call} 重载都走这里，无上下文的调用同样过守卫——
     * 迁移前 {@code call(String)} 是直接透传裸回调的，等于给「没带上下文的那条路」开了个免检口）。
     */
    static final class GuardedToolCallback implements ToolCallback {

        private final ToolCallback raw;
        private final ToolTurnContext turn;
        private final ToolRegistry registry;
        private final List<ToolGuard> guards;
        private final String toolName;
        private final ToolDescriptor descriptor;

        GuardedToolCallback(ToolCallback raw, ToolTurnContext turn, ToolRegistry registry,
                            List<ToolGuard> guards) {
            this.raw = raw;
            this.turn = turn;
            this.registry = registry;
            this.guards = guards;
            this.toolName = raw.getToolDefinition().name();
            this.descriptor = registry.find(toolName).orElse(null);
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return raw.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return invoke(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return invoke(toolInput, toolContext);
        }

        private String invoke(String toolInput, ToolContext springContext) {
            ToolInvocation inv = new ToolInvocation(descriptor, toolName, toolInput);

            // 1) 守卫链：任何一条否决都立即返回，后续守卫不再看（单调）
            for (ToolGuard guard : guards) {
                ToolGuard.Denial denial = guard.deny(inv, turn);
                if (denial != null) {
                    inv.denied(true);   // 工具体一次没跑：与「跑了但失败」在账本上分开定性
                    turn.observe(inv, "start", null);
                    turn.observe(inv, "error", denial.userSummary());
                    return denial.modelPayload();
                }
            }

            // 2) 本次调用的上下文：本轮基础条目 ← 框架传入的上下文 ← 按族注入（后者覆盖，因为它才是本轮新鲜的）
            Map<String, Object> ctx = new HashMap<>();
            if (turn.baseToolContext() != null) ctx.putAll(turn.baseToolContext());
            if (springContext != null && springContext.getContext() != null) ctx.putAll(springContext.getContext());
            ToolFamily family = descriptor != null ? descriptor.family() : registry.familyOf(toolName);
            ToolEnrichment enrichment = family == null ? null : turn.enrichments().get(family);
            if (enrichment != null) {
                Map<String, Object> extra = enrichment.entriesFor(inv);
                if (extra != null) ctx.putAll(extra);
            }

            turn.observe(inv, "start", null);

            int[] attempts = {0};
            String result;
            try {
                result = turn.execute(raw, toolInput, ctx, inv, attempts);
            } catch (RuntimeException e) {
                inv.attempts(attempts[0]);
                turn.observe(inv, "error", e.getMessage());
                throw e;
            }
            inv.attempts(attempts[0]);
            // 引用注册要在观测之前：卡片显示的是注册后的文本（与迁移前一致）
            String result2;
            try {
                result2 = turn.decorateResult(inv, result);
            } catch (Exception e) {
                log.warn("[TOOL] 引用注册失败（不阻塞返回）: tool={} {}", toolName, e.getMessage());
                result2 = result;
            }

            // 3) 工具自己上报的失败（基础设施层：沙盒压根没通）优先定性为 error，
            //    否则「provisioner 连不上」会显示成绿色对勾，与「命令退出码非 0」混为一谈
            String note = inv.failureNote();
            boolean failed = note != null && !note.isBlank();
            turn.observe(inv, failed ? "error" : "done", failed ? note : result2);

            // 4) 用户中途插话随这一步的结果一起送达模型（观测之后才附）
            try {
                return turn.appendSteers(inv, result2);
            } catch (Exception e) {
                log.warn("[TOOL] 插话附送失败（不阻塞返回，本条插话留到下一步再送）: tool={} {}",
                        toolName, e.getMessage());
                return result2;
            }
        }
    }
}
