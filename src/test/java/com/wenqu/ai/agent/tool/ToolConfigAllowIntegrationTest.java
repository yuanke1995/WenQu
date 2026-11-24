package com.wenqu.ai.agent.tool;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成级验证：{@code ToolsConfig.allow} 在真实 Toolkit 上只裁问渠业务工具，
 * 不误伤框架后挂的文件/记忆/平台工具。
 * <p>这里用真实 {@link Toolkit} + 真实 {@link ToolFilter#apply}（不是 mock）：
 * allow 名单一旦漏掉框架工具，模型工具表就会静默少能力，而单测只看配置对象是发现不了的。
 */
class ToolConfigAllowIntegrationTest {

    /** 最小可注册工具：只实现框架需要的名字与调用签名。 */
    private record StubTool(String getName) implements io.agentscope.core.tool.AgentTool {
        @Override
        public String getDescription() {
            return "stub";
        }

        @Override
        public java.util.Map<String, Object> getParameters() {
            return java.util.Map.of("type", "object", "properties", java.util.Map.of());
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                io.agentscope.core.tool.ToolCallParam param) {
            return reactor.core.publisher.Mono.just(io.agentscope.core.message.ToolResultBlock.text("ok"));
        }
    }

    private static Toolkit toolkitWith(String... names) {
        Toolkit toolkit = new Toolkit();
        for (String n : names) toolkit.registerAgentTool(new StubTool(n));
        return toolkit;
    }

    @Test
    @DisplayName("allow 只保留名单内业务工具，平台工具按框架规则放行")
    void allowKeepsPlatformTools() {
        Toolkit toolkit = toolkitWith("searchKnowledge", "officeConvert", "read_file", "memory_save", "agent_spawn", "skill_manage");
        ToolsConfig config = ToolConfigFactory.fromPolicyAndAllow(null, List.of("searchKnowledge"));
        ToolFilter.apply(toolkit, config);

        assertThat(toolkit.getToolNames())
                .contains("searchKnowledge")
                .contains("agent_spawn", "skill_manage")   // HarnessPlatformTools 白名单
                .doesNotContain("officeConvert");          // 名单外业务工具被裁
    }

    @Test
    @DisplayName("deny 优先级高于 allow：同名同时在两名单时被移除")
    void denyWinsOverAllow() {
        Toolkit toolkit = toolkitWith("searchKnowledge", "webSearch");
        ToolsConfig config = ToolConfigFactory.fromPolicyAndAllow(
                com.wenqu.ai.agent.permission.ExecutionPolicy.of(
                        com.wenqu.ai.agent.permission.PermissionPreset.STANDARD, "auto", "/workspace", "webSearch"),
                List.of("searchKnowledge", "webSearch"));
        ToolFilter.apply(toolkit, config);

        assertThat(toolkit.getToolNames()).contains("searchKnowledge").doesNotContain("webSearch");
    }

    @Test
    @DisplayName("allow 为空时只保留平台工具，业务工具全裁")
    void emptyAllowKeepsOnlyPlatformTools() {
        Toolkit toolkit = toolkitWith("searchKnowledge", "officeConvert", "agent_spawn");
        ToolsConfig config = ToolConfigFactory.fromPolicyAndAllow(null, List.of());
        ToolFilter.apply(toolkit, config);

        assertThat(toolkit.getToolNames()).doesNotContain("searchKnowledge", "officeConvert");
    }
}
