package com.wenqu.ai.agentx;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具适配器的协议搬运断言（九族工具能不能平移，取决于这一层对不对，而不是取决于跑没跑起来）。
 * 盯住四件事：名字与说明取自 Spring AI 定义（不另写一份）、inputSchema 原样变成框架的参数表、
 * 参数以 JSON 传回 {@code call}、异常必须收敛成工具失败而不是一条红栈打断整轮。
 */
@DisplayName("工具适配：Spring AI ToolCallback → AgentScope AgentTool")
class SpringAiToolAdapterTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";

    /** 记录被传进来的实参，用来证明参数搬运没有走样。 */
    private static class RecordingCallback implements ToolCallback {
        final AtomicReference<String> seenInput = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> seenContext = new AtomicReference<>();
        private final RuntimeException failure;

        RecordingCallback(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("search_web").description("联网搜索").inputSchema(SCHEMA).build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            seenInput.set(toolInput);
            seenContext.set(toolContext == null ? Map.of() : toolContext.getContext());
            if (failure != null) throw failure;
            return "命中 3 条";
        }
    }

    @Test
    @DisplayName("名字、说明与 JSON Schema 都取自原工具定义")
    void carriesDefinition() {
        var adapter = new SpringAiToolAdapter(new RecordingCallback(null), Map::of, standard(), false, true);

        assertEquals("search_web", adapter.getName());
        assertEquals("联网搜索", adapter.getDescription());
        // schema 必须原样解析成框架的参数表：改写过参数=模型看到另一套工具，黑名单与审批就失配了
        Map<String, Object> params = adapter.getParameters();
        assertEquals("object", params.get("type"));
        assertTrue(params.containsKey("properties"));
        assertFalse(adapter.isReadOnly(), "适配器一律按「可能有副作用」上报，放行与否归权限档位");
    }

    @Test
    @DisplayName("参数以 JSON 传回 call，per-call 上下文一并带到")
    void passesInputAndContext() {
        var callback = new RecordingCallback(null);
        var adapter = new SpringAiToolAdapter(callback, () -> Map.of("wenqu.knowledge.scope", "kb-1"), standard(), false, true);

        ToolResultBlock result = adapter.callAsync(
                ToolCallParam.builder().input(Map.of("query", "青竹 熔断")).build()).block();

        assertNotNull(result);
        assertEquals("{\"query\":\"青竹 熔断\"}", callback.seenInput.get());
        assertEquals("kb-1", callback.seenContext.get().get("wenqu.knowledge.scope"));
        assertFalse(result.getOutput().isEmpty(), "正常结果要落成可读的工具输出");
    }

    @Test
    @DisplayName("工具抛错收敛成工具失败，不打断这一轮")
    void failureBecomesToolError() {
        var adapter = new SpringAiToolAdapter(
                new RecordingCallback(new IllegalStateException("网关 502")), Map::of, standard(), false, true);

        ToolResultBlock result = adapter.callAsync(
                ToolCallParam.builder().input(Map.of("query", "任意")).build()).block();

        assertNotNull(result, "异常不许往上传：模型要看到失败原因才能改道，整轮也不该因此崩");
        assertFalse(result.getOutput().isEmpty());
        assertTrue(String.valueOf(result.getOutput()).contains("502"), "根因文案要留在工具结果里");
    }

    @Test
    @DisplayName("inputSchema 非法时 fail-loud 指明是哪个工具")
    void brokenSchemaNamesTheTool() {
        ToolCallback broken = new RecordingCallback(null) {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("broken_tool").description("说明").inputSchema("{不是 JSON").build();
            }
        };

        var error = assertThrows(IllegalStateException.class,
                () -> new SpringAiToolAdapter(broken, Map::of, standard(), false, true).getParameters());
        assertTrue(error.getMessage().contains("broken_tool"), "报错必须点出工具名");
    }

    private static com.wenqu.ai.agent.permission.ExecutionPolicy standard() {
        return com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                com.wenqu.ai.agent.permission.PermissionPreset.STANDARD, "auto", "/sandbox", "");
    }

    private static com.wenqu.ai.agent.permission.ExecutionPolicy researchWithDeny() {
        return com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                com.wenqu.ai.agent.permission.PermissionPreset.RESEARCH, "ask", "/sandbox", "run_code");
    }

    @Test
    @DisplayName("档位翻译：只读调研/常规/全自主，且全自主不等于绕过检查")
    void tierMapsToFrameworkMode() {
        assertEquals(PermissionMode.EXPLORE, AgentxPermissions.modeOf(
                com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                        com.wenqu.ai.agent.permission.PermissionPreset.RESEARCH, null, "/sandbox", "")));
        assertEquals(PermissionMode.DEFAULT, AgentxPermissions.modeOf(standard()));
        // 全自主=不再问人（DONT_ASK），不是连 checkPermissions 一起跳过（BYPASS）
        assertEquals(PermissionMode.DONT_ASK, AgentxPermissions.modeOf(
                com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                        com.wenqu.ai.agent.permission.PermissionPreset.AUTONOMOUS, null, "/sandbox", "")));
        assertEquals(PermissionMode.EXPLORE, AgentxPermissions.modeOf(null), "没解析出档位时按最保守的只读档");
    }

    @Test
    @DisplayName("按名黑名单落成框架 deny 规则")
    void denyBecomesRule() {
        var context = AgentxPermissions.contextOf(researchWithDeny());
        assertEquals(PermissionMode.EXPLORE, context.getMode());
        assertTrue(context.getDenyRules().containsKey("run_code"), "黑名单工具要进框架的 deny 规则");
    }

    @Test
    @DisplayName("工具自检：无档位拒、黑名单拒、登记要审批时问人")
    void checkPermissionsFailClosed() {
        var callback = new RecordingCallback(null);

        var noPolicy = new SpringAiToolAdapter(callback, Map::of, null, false, true);
        assertEquals(PermissionBehavior.DENY, noPolicy.checkPermissions(Map.of(), PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT).build()).block().getBehavior());

        var denied = new SpringAiToolAdapter(callback, Map::of, researchWithDeny(), false, true);
        var deniedContext = AgentxPermissions.contextOf(researchWithDeny());
        assertEquals(PermissionBehavior.DENY,
                denied.checkPermissions(Map.of(), deniedContext).block().getBehavior(), "MCP 那族只有按名这一层粒度");

        var asking = new SpringAiToolAdapter(callback, Map::of, researchWithDeny(), true, true);
        // 只读调研档先拦下副作用工具；问人这条分支要在允许执行的前提下才走得到
        assertEquals(PermissionBehavior.DENY,
                asking.checkPermissions(Map.of(), deniedContext).block().getBehavior());
        var standardAskContext = AgentxPermissions.contextOf(
                com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                        com.wenqu.ai.agent.permission.PermissionPreset.STANDARD, "ask", "/sandbox", ""));
        assertEquals(PermissionBehavior.ASK, new SpringAiToolAdapter(callback, Map::of,
                com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                        com.wenqu.ai.agent.permission.PermissionPreset.STANDARD, "ask", "/sandbox", ""), true, true)
                .checkPermissions(Map.of(), standardAskContext).block().getBehavior());
        // 没人在场时不许挂卡：问人改成 fail-closed 拒绝，否则整轮悬到超时（真机实测过这个形状）
        assertEquals(PermissionBehavior.DENY, new SpringAiToolAdapter(callback, Map::of,
                com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                        com.wenqu.ai.agent.permission.PermissionPreset.STANDARD, "ask", "/sandbox", ""), true, false)
                .checkPermissions(Map.of(), standardAskContext).block().getBehavior(), "无人可答时按拒绝，不挂悬卡");
    }

    private static <T extends Throwable> T assertThrows(Class<T> type, Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(type, action::run);
    }
}
