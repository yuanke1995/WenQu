package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住一条关键前提：{@code SpringAiToolAdapter} 里删掉重复的 deny 判断，
 * 前提是框架 {@code PermissionEngine} 在适配器之前就按 DENY 规则拒绝。
 * <p>这条一旦不成立，被禁用的工具会重新可执行——是安全问题，不是样式问题。
 */
class PermissionEngineDenyOrderTest {

    /** 极简 ToolBase：权限判定只需要名字与只读位。 */
    private static ToolBase tool(String name, boolean readOnly) {
        return new ToolBase(ToolBase.builder().name(name).description("test")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(readOnly)) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
    }

    @Test
    @DisplayName("DENY 规则先于工具自身 checkPermissions 生效")
    void denyRuleWinsBeforeToolDecision() {
        ExecutionPolicy policy = ExecutionPolicy.of(PermissionPreset.STANDARD, "auto", "/workspace", "webSearch");
        PermissionContextState ctx = AgentxPermissions.contextOf(policy);
        PermissionEngine engine = new PermissionEngine(ctx);

        PermissionDecision denied = engine.checkPermission(tool("webSearch", true), Map.of()).block();
        assertThat(denied).isNotNull();
        assertThat(denied.getBehavior()).isEqualTo(PermissionBehavior.DENY);

        PermissionDecision allowed = engine.checkPermission(tool("searchKnowledge", true), Map.of()).block();
        assertThat(allowed).isNotNull();
        assertThat(allowed.getBehavior()).isNotEqualTo(PermissionBehavior.DENY);
    }

    @Test
    @DisplayName("MCP 动态工具名同样被 DENY 规则拦住")
    void denyRuleCoversDynamicNames() {
        ExecutionPolicy policy = ExecutionPolicy.of(PermissionPreset.STANDARD, "auto", "/workspace", "wen_qugithub_search");
        PermissionEngine engine = new PermissionEngine(AgentxPermissions.contextOf(policy));

        assertThat(engine.checkPermission(tool("wen_qugithub_search", true), Map.of()).block().getBehavior())
                .isEqualTo(PermissionBehavior.DENY);
    }
}
