package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.service.ComputerUseTools;
import com.wenqu.ai.service.ConversationSearchTools;
import com.wenqu.ai.service.McpResourceTools;
import com.wenqu.ai.service.OfficeTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新增工具族的「声明名 = @Tool 名」核对：ToolRegistry 启动期会做同一件事，
 * 这里提前在单测里卡住，避免改一个名字要让平台启动日志刷 ERROR 才发现。
 *
 * <p>holder 用 null 依赖构造：只反射 {@code @Tool} 注解，不调用方法。
 *
 * @author yuanke
 */
class NewToolFamilyDeclarationTest {

    private static void assertDeclaredMatchesActual(ToolFamilyProvider provider) {
        List<ToolDescriptor> ds = provider.descriptors();
        Set<String> declared = ds.stream().map(ToolDescriptor::name).collect(Collectors.toSet());
        Set<String> actual = java.util.Arrays.stream(ToolCallbacks.from(provider.holder()))
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertThat(actual)
                .as("族 %s 的 @Tool 名必须与描述符逐字一致", provider.family().code())
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(ds).allSatisfy(d -> assertThat(d.family()).isEqualTo(provider.family()));
        assertThat(ds).allSatisfy(d -> assertThat(d.displayLabel()).isNotBlank());
    }

    @Test
    @DisplayName("Office 族声明与实现一致")
    void office() {
        assertDeclaredMatchesActual(new OfficeToolProvider(new OfficeTools(null, null, null)));
    }

    @Test
    @DisplayName("Computer Use 族声明与实现一致")
    void computerUse() {
        assertDeclaredMatchesActual(new ComputerUseProvider(new ComputerUseTools(null, null, null)));
    }

    @Test
    @DisplayName("历史对话检索族声明与实现一致")
    void conversation() {
        assertDeclaredMatchesActual(new ConversationSearchProvider(new ConversationSearchTools(null, null)));
    }

    @Test
    @DisplayName("MCP 资源族声明与实现一致")
    void mcpResource() {
        assertDeclaredMatchesActual(new McpResourceProvider(new McpResourceTools(null), null));
    }
}
