package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 本机 OpenSSH 别名执行器：只做协议转发，连接发生在用户电脑上。 */
class SshAliasVirtualExecutorTest {

    @Test
    @SuppressWarnings("unchecked")
    void forwardsStandardOpToAgentSshOp() {
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        when(registry.online("ex-1")).thenReturn(true);
        when(registry.call(eq("ex-1"), eq("ssh"), any(Map.class), anyLong()))
                .thenReturn(ExecutorRegistry.Result.ok(Map.of("entries", List.of())));

        SshAliasVirtualExecutor exec = new SshAliasVirtualExecutor("ex-1", "wenqu", registry, null);
        assertThat(exec.online()).isTrue();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "/home/ubuntu");
        args.put("roots", List.of(Map.of("alias", "home", "path", "/home/ubuntu", "primary", true)));
        args.put("policy", "workspace-write");
        ExecutorRegistry.Result r = exec.execute("list", args);
        assertThat(r.ok()).isTrue();

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(registry).call(eq("ex-1"), eq("ssh"), cap.capture(), anyLong());
        Map<String, Object> wrapper = cap.getValue();
        assertThat(wrapper.get("alias")).isEqualTo("wenqu");
        assertThat(wrapper.get("op")).isEqualTo("list");
        assertThat(wrapper.get("policy")).isEqualTo("workspace-write");
        assertThat(wrapper.get("args")).isSameAs(args);
    }

    @Test
    void offlineDeviceFailsWithoutCallingAgent() {
        ExecutorRegistry registry = mock(ExecutorRegistry.class);
        when(registry.online("ex-1")).thenReturn(false);
        SshAliasVirtualExecutor exec = new SshAliasVirtualExecutor("ex-1", "wenqu", registry, null);
        ExecutorRegistry.Result r = exec.execute("list", Map.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isEqualTo("E_EXECUTOR_OFFLINE");
    }
}
