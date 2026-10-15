package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具卡「不显示英文裸名」的回归账。
 * <p>真机症状：框架引擎这一路上的工具卡标题写着 {@code consultAgent}、{@code plan_write}。
 * 根因不在渲染层而在取值：{@code AgentscopeChatEngine#toolCard} 把工具<b>裸名</b>直接塞进
 * {@code label}，前端 {@code toolLabel} 的「裸名不算展示名」判断把它挡回本地映射，
 * 而那份映射里没有 {@code consultAgent}、{@code writeTodo}、{@code plan_*}——于是现出英文名。
 * <p>本测试钉住三件事：①注册中心有的走注册中心；②框架原生工具走框架表；
 * ③两边都没有时<b>返回 null 而不是回落成裸名</b>（回落成裸名会被前端误读成「后端认真取过这个名字」）。
 */
@DisplayName("框架引擎：工具卡展示名解析（注册中心 → 框架工具表 → 留空）")
class AgentxToolDisplayNamesTest {

    /**
     * 只登记给定描述符的注册中心。
     *
     * <p>{@code ToolRegistry#init()} 是包级可见（它标着 {@code @PostConstruct}，由 Spring 调），
     * 所以这里反射调而不是把它放开成 public——为了一个测试去改生产代码的可见性是笔亏本买卖。
     */
    private static ToolRegistry registryWith(ToolDescriptor... ds) {
        ToolFamilyProvider provider = new ToolFamilyProvider() {
            @Override
            public ToolFamily family() {
                return ToolFamily.BUILTIN;
            }

            @Override
            public List<ToolDescriptor> descriptors() {
                return List.of(ds);
            }
        };
        ToolRegistry reg = new ToolRegistry(List.of(provider));
        try {
            // 同一个 Method 实例：getDeclaredMethod 每次返回新对象，setAccessible 只作用于拿到的那个
            Method init = ToolRegistry.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(reg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调 ToolRegistry#init 失败——它的可见性变了？", e);
        }
        return reg;
    }

    private static ToolDescriptor desc(String name, String cardLabel, String cardHint) {
        return new ToolDescriptor(name, ToolFamily.BUILTIN, name + "（术语名）", cardLabel, cardHint,
                SideEffect.PURE, "tool.builtin.enabled", true, false, null);
    }

    @Test
    @DisplayName("注册中心登记过的工具：取口语名 cardLabel，不取术语名也不取工具名")
    void 注册中心优先() {
        ToolDisplayNames names = new ToolDisplayNames(registryWith(
                desc("consultAgent", "问另一位助手", "把这一小问交给职责对口的助手去查"),
                desc("writeTodo", "整理任务清单", "把要做的事列成清单")));
        assertEquals("问另一位助手", names.label("consultAgent"));
        assertEquals("把这一小问交给职责对口的助手去查", names.hint("consultAgent"));
        assertEquals("整理任务清单", names.label("writeTodo"));
    }

    @Test
    @DisplayName("框架原生工具（plan_*）不在注册中心里，由框架工具表接住")
    void 框架原生工具走框架表() {
        ToolDisplayNames names = new ToolDisplayNames(registryWith());
        for (String tool : List.of("plan_enter", "plan_write", "plan_exit")) {
            String label = names.label(tool);
            assertTrue(label != null && !label.isBlank(), tool + " 没有中文名");
            assertFalse(label.equals(tool), tool + " 的展示名回落成了工具裸名");
            assertTrue(names.hint(tool) != null && !names.hint(tool).isBlank(),
                    tool + " 缺悬停说明");
        }
    }

    @Test
    @DisplayName("两边都没有时返回 null，绝不拿工具名冒充展示名")
    void 未知工具留空() {
        ToolDisplayNames names = new ToolDisplayNames(registryWith());
        // MCP 动态工具（名字由用户自己起的 server 决定）走的就是这一档
        assertNull(names.label("wen_qu_query_docs_v2"));
        assertNull(names.hint("wen_qu_query_docs_v2"));
        Map<String, Object> payload = new LinkedHashMap<>();
        names.putDisplayFields(payload, "wen_qu_query_docs_v2");
        assertFalse(payload.containsKey("label"), "取不到就不该有这个字段：填裸名会被前端当成展示名");
        assertFalse(payload.containsKey("hint"));
    }

    @Test
    @DisplayName("注册中心为 null（装配未注入）时框架表仍能兜住，不 NPE")
    void 注册中心缺失不崩() {
        ToolDisplayNames names = new ToolDisplayNames(null);
        assertEquals("把计划写下来", names.label("plan_write"));
        assertNull(names.label("consultAgent"), "没有注册中心就只能退到框架表，问渠工具交前端映射");
        assertNull(names.label(null));
        assertNull(names.label("  "));
    }

    @Test
    @DisplayName("putDisplayFields 只写取到的字段，label 与 hint 各自独立")
    void 写入字段独立() {
        ToolDisplayNames names = new ToolDisplayNames(registryWith(desc("onlyLabel", "只有名字", null)));
        Map<String, Object> payload = new LinkedHashMap<>();
        names.putDisplayFields(payload, "onlyLabel");
        assertEquals("只有名字", payload.get("label"));
        assertFalse(payload.containsKey("hint"), "没有说明就不该写空串：前端会拿它当 title 显示");
    }
}