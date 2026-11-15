package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话进程两路合成的口径：离线/旧版/读不到不装死、归属与停止路由、侧栏计数合并。
 */
class SessionProcServiceTest {

    private SessionService sessionService;
    private ProjectService projectService;
    private SandboxProcService sandboxProcService;
    private ConfigService configService;
    private ExecutorRegistry registry;
    private com.wenqu.ai.executor.ExecutorService executorService;
    private SessionProcService service;

    private static final String UID = "u1";
    private static final String SID = "sessA";

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        projectService = mock(ProjectService.class);
        sandboxProcService = mock(SandboxProcService.class);
        configService = mock(ConfigService.class);
        registry = mock(ExecutorRegistry.class);
        executorService = mock(com.wenqu.ai.executor.ExecutorService.class);
        service = new SessionProcService(sessionService, projectService, sandboxProcService,
                configService, registry, executorService);

        Session session = new Session();
        session.setId(SID);
        session.setUserId(UID);
        session.setWsBackend("local");
        session.setWsExecutorId("ex1");
        when(sessionService.assertOwned(eq(SID), eq(UID))).thenReturn(session);
        when(projectService.projectOfSession(SID)).thenReturn(null);
        when(executorService.belongsTo(UID, "ex1")).thenReturn(true);
        when(configService.getBoolean("tool.sandbox.enabled")).thenReturn(false);
        when(sandboxProcService.list(SID, UID)).thenReturn(Map.of("available", false, "procs", List.of()));
    }

    private static Map<String, Object> proc(String tag, String sessionId, long elapsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        m.put("sessionId", sessionId);
        m.put("command", "npm run dev");
        m.put("roots", 1);
        m.put("pids", List.of("123"));
        m.put("ports", List.of("5173"));
        m.put("elapsedSec", elapsed);
        m.put("startedAt", System.currentTimeMillis() - elapsed * 1000);
        m.put("killable", true);
        return m;
    }

    @Test
    @DisplayName("本机在线：条目标 side=local、own 按会话比对，沙盒没跑时不报错也不出条目")
    void localOnlineMerges() {
        when(registry.online("ex1")).thenReturn(true);
        when(registry.call(eq("ex1"), eq("procs"), anyMap(), anyLong()))
                .thenReturn(new ExecutorRegistry.Result(true, Map.of("procs", List.of(proc("wq1.sessA.abcdef12", SID, 42))), null, null));

        Map<String, Object> out = service.list(SID, UID);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) out.get("procs");
        assertThat(procs).hasSize(1);
        assertThat(procs.get(0).get("side")).isEqualTo("local");
        assertThat(procs.get(0).get("own")).isEqualTo(true);
        assertThat(out.get("available")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> sides = (Map<String, Object>) out.get("sides");
        assertThat(((Map<?, ?>) sides.get("local")).get("available")).isEqualTo(true);
    }

    @Test
    @DisplayName("执行器离线：local.available=false + 原因，不伪装成「没有进程」")
    void offlineIsNotSilent() {
        when(registry.online("ex1")).thenReturn(false);
        Map<String, Object> out = service.list(SID, UID);
        @SuppressWarnings("unchecked")
        Map<String, Object> sides = (Map<String, Object>) out.get("sides");
        Map<?, ?> local = (Map<?, ?>) sides.get("local");
        assertThat(local.get("available")).isEqualTo(false);
        assertThat(local.get("reason")).isEqualTo("executor-offline");
        assertThat((String) local.get("message")).contains("不在线");
        verify(registry, never()).call(anyString(), eq("procs"), anyMap(), anyLong());
    }

    @Test
    @DisplayName("执行器版本旧：E_UNSUPPORTED 单独报 too-old，与读取失败区分")
    void tooOldIsExplicit() {
        when(registry.online("ex1")).thenReturn(true);
        when(registry.call(eq("ex1"), eq("procs"), anyMap(), anyLong()))
                .thenReturn(ExecutorRegistry.Result.fail("E_UNSUPPORTED", "不支持的调用：procs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> local = (Map<String, Object>) ((Map<String, Object>) service.list(SID, UID).get("sides")).get("local");
        assertThat(local.get("available")).isEqualTo(false);
        assertThat(local.get("reason")).isEqualTo("executor-too-old");
        assertThat((String) local.get("message")).contains("升级");
    }

    @Test
    @DisplayName("沙盒读失败：reason=error 并带原文，不被空列表吞掉")
    void sandboxErrorSurfaces() {
        when(configService.getBoolean("tool.sandbox.enabled")).thenReturn(true);
        when(registry.online("ex1")).thenReturn(false);
        when(sandboxProcService.list(SID, UID)).thenThrow(new IllegalStateException("沙盒此刻读不到进程表"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sb = (Map<String, Object>) ((Map<String, Object>) service.list(SID, UID).get("sides")).get("sandbox");
        assertThat(sb.get("reason")).isEqualTo("error");
        assertThat((String) sb.get("message")).contains("读不到进程表");
    }

    @Test
    @DisplayName("停止本机进程：路由到本机执行器的 killProc，tag 里的会话归属先过一道")
    void killLocalRoutes() {
        when(registry.online("ex1")).thenReturn(true);
        when(registry.call(eq("ex1"), eq("killProc"), anyMap(), anyLong()))
                .thenReturn(new ExecutorRegistry.Result(true, Map.of("found", true, "killed", 2, "remaining", 0), null, null));

        Map<String, Object> out = service.kill(SID, UID, "local", "wq1.sessA.abcdef12");
        assertThat(out.get("killed")).isEqualTo(2);
        verify(sessionService).assertOwned("sessA", UID);
    }

    @Test
    @DisplayName("执行器离线时停止：明确报「命令没发出去」，不冒充「进程已自己退出」")
    void killOfflineIsExplicit() {
        when(registry.online("ex1")).thenReturn(false);
        assertThatThrownBy(() -> service.kill(SID, UID, "local", "wq1.sessA.abcdef12"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在线");
    }

    @Test
    @DisplayName("停止沙盒进程：转交沙盒服务，不碰本机执行器")
    void killSandboxRoutes() {
        when(sandboxProcService.kill(SID, UID, "wq1.sessA.abcdef12")).thenReturn(Map.of("killed", 1, "remaining", 0));
        Map<String, Object> out = service.kill(SID, UID, "sandbox", "wq1.sessA.abcdef12");
        assertThat(out.get("killed")).isEqualTo(1);
        verify(registry, never()).call(anyString(), eq("killProc"), anyMap(), anyLong());
    }

    @Test
    @DisplayName("全部停止（沙盒）：只停本会话 own=true 的，别人的进程不动")
    void killAllSandboxOwnOnly() {
        when(configService.getBoolean("tool.sandbox.enabled")).thenReturn(true);
        Map<String, Object> mine = proc("wq1.sessA.aaaaaaaa", SID, 10);
        mine.put("own", true);
        Map<String, Object> other = proc("wq1.sessB.bbbbbbbb", "sessB", 20);
        other.put("own", false);
        when(sandboxProcService.list(SID, UID)).thenReturn(Map.of("available", true, "procs", List.of(mine, other)));
        when(sandboxProcService.kill(eq(SID), eq(UID), eq("wq1.sessA.aaaaaaaa")))
                .thenReturn(Map.of("killed", 1, "remaining", 0));

        Map<String, Object> out = service.killAll(SID, UID, "sandbox");
        assertThat(out.get("killed")).isEqualTo(1);
        verify(sandboxProcService, never()).kill(eq(SID), eq(UID), eq("wq1.sessB.bbbbbbbb"));
    }

    @Test
    @DisplayName("侧栏计数：沙盒与本机两路相加，partial 传递")
    void liveMergesCounts() {
        when(sandboxProcService.liveBySession(UID)).thenReturn(Map.of("sessions", Map.of(SID, 1), "partial", false));
        when(executorService.list(UID)).thenReturn(List.of(Map.of("id", "ex1")));
        when(registry.online("ex1")).thenReturn(true);
        when(registry.call(eq("ex1"), eq("liveProcs"), anyMap(), anyLong()))
                .thenReturn(new ExecutorRegistry.Result(true, Map.of("sessions", Map.of(SID, 2), "partial", false), null, null));

        Map<String, Object> out = service.live(UID);
        @SuppressWarnings("unchecked")
        Map<String, Integer> sessions = (Map<String, Integer>) out.get("sessions");
        assertThat(sessions.get(SID)).isEqualTo(3);
        assertThat(out.get("partial")).isEqualTo(false);
    }
}
