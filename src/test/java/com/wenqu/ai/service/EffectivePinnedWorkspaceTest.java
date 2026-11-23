package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorFiles;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Session;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提示词装配期用的本轮后端必须是<b>只读库 + 在线探测</b>的结果：
 * 已钉本机且在线→本机根；已钉但离线→cloud 带提示；未钉住→null（不建目录）。
 * 见方案 §P0-2 / T2——离线时不能拿库里的本机路径骗模型。
 */
class EffectivePinnedWorkspaceTest {

    private static final String SID = "s-1";

    private final SessionMapper sessions = mock(SessionMapper.class);
    private final ExecutorRegistry registry = mock(ExecutorRegistry.class);
    private final SessionWorkspaceService service = new SessionWorkspaceService(
            sessions, mock(ProjectService.class), registry,
            mock(com.wenqu.ai.executor.ExecutorService.class),
            mock(ExecutorFiles.class), mock(ConfigService.class));

    private Session pinnedLocal() {
        Session s = new Session();
        s.setId(SID);
        s.setWsBackend(SessionWorkspaceService.BACKEND_LOCAL);
        s.setWsExecutorId("exec-1");
        s.setWsRoot("/Users/me/ws");
        return s;
    }

    @Test
    void pinnedAndOnlineReportsLocalRoot() {
        when(sessions.selectById(SID)).thenReturn(pinnedLocal());
        when(registry.online("exec-1")).thenReturn(true);

        SessionWorkspaceService.Workspace ws = service.effectivePinned(SID);

        assertThat(ws).isNotNull();
        assertThat(ws.local()).isTrue();
        assertThat(ws.rootPath()).isEqualTo("/Users/me/ws");
        assertThat(ws.fallbackNotice()).isNull();
    }

    @Test
    void pinnedButOfflineReportsCloudWithNotice() {
        when(sessions.selectById(SID)).thenReturn(pinnedLocal());
        when(registry.online("exec-1")).thenReturn(false);

        SessionWorkspaceService.Workspace ws = service.effectivePinned(SID);

        assertThat(ws).isNotNull();
        assertThat(ws.local()).isFalse();
        assertThat(ws.backend()).isEqualTo(SessionWorkspaceService.BACKEND_CLOUD);
        assertThat(ws.fallbackNotice()).isNotBlank()
                .as("离线必须带提示，让提示词不写本机路径");
    }

    @Test
    void unpinnedReportsNullWithoutCreatingDirectory() {
        when(sessions.selectById(SID)).thenReturn(null);

        assertThat(service.effectivePinned(SID)).isNull();
    }
}
