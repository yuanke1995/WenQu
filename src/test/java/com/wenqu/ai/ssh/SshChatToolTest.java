package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.executor.ExecutorService;
import com.wenqu.ai.util.RequestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SshChatToolTest {

    private final ExecutorService executors = mock(ExecutorService.class);
    private final ExecutorRegistry registry = mock(ExecutorRegistry.class);
    private final SshChatTool tool = new SshChatTool(executors, registry);

    @BeforeEach
    void setUp() {
        RequestUser.set("u1", null, "user");
    }

    @AfterEach
    void tearDown() {
        RequestUser.clear();
    }

    @Test
    void picksExecutorWhoseConfigHasAliasAndRunsCommand() {
        when(executors.list("u1")).thenReturn(List.of(Map.of("id", "ex-1", "name", "mac")));
        when(registry.online("ex-1")).thenReturn(true);
        when(registry.call("ex-1", "sshAliases", Map.of(), 15_000L))
                .thenReturn(ExecutorRegistry.Result.ok(Map.of("aliases", List.of("wenqu", "other"))));
        String virtualId = SshAliasIds.virtualId("ex-1", "wenqu");
        when(registry.call(eq(virtualId), eq("exec"), anyMap(), anyLong()))
                .thenReturn(ExecutorRegistry.Result.ok(Map.of(
                        "output", "hostname\nuptime", "exitCode", 0, "truncated", false)));

        Map<String, Object> out = tool.sshExec("wenqu", "hostname; uptime", null, null);

        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("output")).isEqualTo("hostname\nuptime");
        assertThat(out.get("exitCode")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(registry).call(eq(virtualId), eq("exec"), args.capture(), anyLong());
        assertThat(args.getValue()).containsEntry("command", "hostname; uptime")
                .containsEntry("timeoutSeconds", 60)
                .containsEntry("policy", "workspace-write");
    }

    @Test
    void reportsAvailableAliasesWhenNotFound() {
        when(executors.list("u1")).thenReturn(List.of(Map.of("id", "ex-1", "name", "mac")));
        when(registry.online("ex-1")).thenReturn(true);
        when(registry.call("ex-1", "sshAliases", Map.of(), 15_000L))
                .thenReturn(ExecutorRegistry.Result.ok(Map.of("aliases", List.of("alpha", "beta"))));

        Map<String, Object> out = tool.sshExec("wenqu", "hostname", null, null);

        assertThat(out.get("ok")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) out.get("error");
        assertThat(err.get("code")).isEqualTo("E_ALIAS_NOT_FOUND");
        assertThat(String.valueOf(err.get("message"))).contains("alpha").contains("beta");
        verify(registry, never()).call(eq(SshAliasIds.virtualId("ex-1", "wenqu")), eq("exec"), anyMap(), anyLong());
    }

    @Test
    void failsWhenNoExecutorOnline() {
        when(executors.list("u1")).thenReturn(List.of(Map.of("id", "ex-1", "name", "mac")));
        when(registry.online("ex-1")).thenReturn(false);

        Map<String, Object> out = tool.sshExec("wenqu", "hostname", null, null);

        assertThat(out.get("ok")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) out.get("error");
        assertThat(err.get("code")).isEqualTo("E_EXECUTOR_OFFLINE");
    }

    @Test
    void readsUserFromToolContextWhenThreadLocalIsSystem() {
        // 框架在弹性线程上跑工具：ThreadLocal 已回落成 SYSTEM，只有 ToolContext 里的快照可用
        RequestUser.clear();
        when(executors.list("u1")).thenReturn(List.of(Map.of("id", "ex-1", "name", "mac")));
        when(registry.online("ex-1")).thenReturn(true);
        when(registry.call("ex-1", "sshAliases", Map.of(), 15_000L))
                .thenReturn(ExecutorRegistry.Result.ok(Map.of("aliases", List.of("wenqu"))));
        when(registry.call(eq(SshAliasIds.virtualId("ex-1", "wenqu")), eq("exec"), anyMap(), anyLong()))
                .thenReturn(ExecutorRegistry.Result.ok(Map.of("output", "ok", "exitCode", 0)));

        Map<String, Object> out = tool.sshExec("wenqu", "hostname", null,
                new org.springframework.ai.chat.model.ToolContext(Map.of("userId", "u1")));

        assertThat(out.get("ok")).isEqualTo(true);
    }
}
