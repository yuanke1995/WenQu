package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorFiles;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Session;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 口径（方案 §P0-3 / T3）：{@code wsBackend/wsExecutorId/wsRoot} 只存钉住的本机绑定，
 * cloud 是每轮派生的瞬时态，绝不落库、绝不清理 root——否则会话改名后会在新目录里找不到旧文件。
 */
class WorkspaceBindingContractTest {

    private static final String SID = "s-1";
    private static final String UID = "u-1";

    private final SessionMapper sessions = mock(SessionMapper.class);
    private final ExecutorRegistry registry = mock(ExecutorRegistry.class);
    private final SessionWorkspaceService service = new SessionWorkspaceService(
            sessions, mock(ProjectService.class), registry,
            mock(com.wenqu.ai.executor.ExecutorService.class),
            mock(ExecutorFiles.class), mock(ConfigService.class));

    private Session pinnedLocal(String title) {
        Session s = new Session();
        s.setId(SID);
        s.setUserId(UID);
        s.setTitle(title);
        s.setWsBackend(SessionWorkspaceService.BACKEND_LOCAL);
        s.setWsExecutorId("exec-1");
        s.setWsRoot("/Users/me/20260101-old-title-a1b2c3d4");
        return s;
    }

    @Test
    void offlineResolveKeepsBindingUntouched() {
        Session pinned = pinnedLocal("标题A");
        when(sessions.selectById(SID)).thenReturn(pinned);
        when(registry.online("exec-1")).thenReturn(false);

        SessionWorkspaceService.Workspace derived = service.resolve(SID, UID);

        assertThat(derived.backend()).isEqualTo(SessionWorkspaceService.BACKEND_CLOUD);
        assertThat(pinned.getWsBackend()).isEqualTo(SessionWorkspaceService.BACKEND_LOCAL);
        assertThat(pinned.getWsRoot()).isEqualTo("/Users/me/20260101-old-title-a1b2c3d4");
        // 离线这一轮不许改写绑定
        verify(sessions, never()).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void renameDoesNotMovePinnedBinding() {
        Session pinned = pinnedLocal("标题A");
        pinned.setTitle("改名后的标题B");
        when(sessions.selectById(SID)).thenReturn(pinned);
        when(registry.online("exec-1")).thenReturn(true);

        SessionWorkspaceService.Workspace binding = service.pinnedLocalBinding(SID);

        assertThat(binding).isNotNull();
        assertThat(binding.rootPath()).isEqualTo("/Users/me/20260101-old-title-a1b2c3d4")
                .as("会话改名不得让绑定指向新目录——旧文件在旧目录里");
        assertThat(SessionWorkspaceService.dirNameOf(pinned)).doesNotContain("标题A")
                .as("dirNameOf 随标题变，正说明必须靠 wsRoot 钉住而不是重算");
    }
}
