package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行计划卡「正文必须是 Markdown 文本」的回归账。
 * <p>真机症状：计划卡标题栏写着「已批准」，正文却只有一个 {@code [object Object]}。
 * 根因不在渲染层而在取值：框架 {@code PlanExitTool} 的入参<b>只有一个可选 summary</b>
 * （{@code inputSchema.properties = {summary}}），整份计划正文在更早的
 * {@code PlanWriteTool} 的 {@code content} 里。引擎早前把 {@code plan_exit} 的<b>整个入参对象</b>
 * 塞进 {@code planCard.plan}，前端 {@code renderMd()} 拿到非字符串就 toString 成了那行占位符
 * ——用户点的是「批准这份计划」，看到的却不是计划。
 * <p>本测试钉住的是取值优先级与「绝不返回对象」这条底线。
 */
@DisplayName("框架引擎：计划卡正文取值（plan_write 正文优先，绝不返回对象）")
class AgentxPlanTextTest {

    /** 框架 {@code plan_exit} 的真实入参形状：只有 summary，没有正文。 */
    private static Map<String, Object> planExitInput(String summary) {
        return summary == null ? Map.of() : Map.of("summary", summary);
    }

    private static final String PLAN_MD = "# 问渠部署上线计划\n\n1. Docker Compose 单机部署\n2. Nginx 反向代理";

    @Test
    @DisplayName("正文取自 plan_write 的 content，不是 plan_exit 的 summary")
    void 正文优先用planWrite() {
        String text = AgentscopeChatEngine.planTextOf(
                planExitInput("问渠部署上线计划：采用 Docker Compose 单机部署 + Nginx 反向代理"), PLAN_MD);
        assertEquals(PLAN_MD, text);
        assertFalse(text.contains("summary"), "不能把 summary 当整份计划：它只有一句话");
    }

    @Test
    @DisplayName("没调 plan_write 时退回 summary 摘要，而不是变成空卡")
    void 无planWrite退回summary() {
        String text = AgentscopeChatEngine.planTextOf(planExitInput("已完成部署方案调研"), null);
        assertEquals("已完成部署方案调研", text);
    }

    @Test
    @DisplayName("入参直接是纯文本时按原文取（框架改 inputSchema 也不丢内容）")
    void 入参为纯文本() {
        assertEquals("整份计划正文", AgentscopeChatEngine.planTextOf("整份计划正文", null));
    }

    @Test
    @DisplayName("两档都空：给一句说明，绝不返回 null/空串让卡片空白，也绝不返回对象")
    void 两档皆空有兜底文案() {
        String text = AgentscopeChatEngine.planTextOf(planExitInput(null), "   ");
        assertTrue(text != null && !text.isBlank(), "兜底文案必须非空：空白卡和 [object Object] 一样是坏体验");
        // 用户口径：这句话印在「用户要点批准」的卡片上，内部机制（哪个工具没调）归日志，不归正文
        assertFalse(text.contains("plan_write"), "计划卡正文不出现工具名：用户不知道 plan_write 是什么");
        assertEquals(AgentscopeChatEngine.NO_PLAN_BODY, text, "兜底文案要能被调用方认出来（认出它才去记那条日志）");
    }

    @Test
    @DisplayName("任何入参形状都返回非空字符串（这条就是 [object Object] 的直接判据）")
    void 任何形状都返回字符串() {
        // 注意：不能用 List.of 装 null（它本身拒绝 null 元素），这里要的是「入参为 null 也得有兜底」
        java.util.List<Object> 入参形状 = java.util.Arrays.asList(
                planExitInput("摘要"),
                Map.of("summary", "摘要", "extra", Map.of("nested", List.of(1, 2))),
                Map.of(),
                "纯文本",
                List.of("一", "二"),
                null);
        for (Object in : 入参形状) {
            String text = AgentscopeChatEngine.planTextOf(in, PLAN_MD);
            assertTrue(text != null && !text.isBlank(),
                    "入参 " + in + " 产出了非字符串/空值：" + text);
        }
        // plan_write 正文缺失时同样不能空
        for (Object in : 入参形状) {
            assertTrue(AgentscopeChatEngine.planTextOf(in, null) != null);
        }
    }
}
