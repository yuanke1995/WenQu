package com.wenqu.ai.service;

import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.sandbox.ProvisionerSandboxProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实例键必须是工作目录的<b>完整函数</b>：显式工作区换项目/换 root、隐式云端切 userScope
 * 都必须让键变化，否则 build 期的文件系统/prompt/guard roots 不会重建（见方案 §P0-1/P0-5）。
 */
class WorkspaceKeyContractTest {

    private static final String UID = "u-1";
    private static final String SID = "s-1";

    private final ProjectService projects = mock(ProjectService.class);
    private final ConfigService config = mock(ConfigService.class);
    private final SessionWorkspaceService sessionWorkspace = mock(SessionWorkspaceService.class);
    private final SandboxService service = new SandboxService(
            mock(ProvisionerSandboxProvider.class), projects, config,
            mock(com.wenqu.ai.executor.ExecutorRegistry.class),
            mock(ProjectTurnChangesService.class), sessionWorkspace);

    private Project explicitProject(String id) {
        Project p = new Project();
        p.setId(id);
        p.setSelectionStatus(Project.SELECTION_SELECTABLE);
        return p;
    }

    private ProjectRoot root(String path, boolean primary) {
        ProjectRoot r = new ProjectRoot();
        r.setPath(path);
        r.setPrimaryRoot(primary ? ProjectRoot.PRIMARY : 0);
        return r;
    }

    @Test
    void explicitWorkspaceKeyCoversProjectIdAndRoots() {
        Project a = explicitProject("proj-a");
        when(projects.projectOfSession(SID)).thenReturn(a);
        when(projects.executorIdOf(a)).thenReturn("exec-1");
        when(projects.rootsOf(a)).thenReturn(List.of(root("/Users/me/a", true)));

        String keyA = service.sessionWorkspaceKey(SID, UID);

        // 同项目、换 root → 键必变（bindRoots 场景）
        when(projects.rootsOf(a)).thenReturn(List.of(root("/Users/me/a-new", true)));
        String keyAChangedRoot = service.sessionWorkspaceKey(SID, UID);
        assertThat(keyAChangedRoot).isNotEqualTo(keyA);

        // 不同项目、相同 root → 键必变（moveSessionProject 场景）
        when(projects.projectOfSession(SID)).thenReturn(explicitProject("proj-b"));
        String keyB = service.sessionWorkspaceKey(SID, UID);
        assertThat(keyB).isNotEqualTo(keyA);
        assertThat(keyB).doesNotContain("proj-a");
    }

    @Test
    void explicitWorkspaceKeyIsStableUnderRootReordering() {
        Project p = explicitProject("proj-x");
        when(projects.projectOfSession(SID)).thenReturn(p);
        when(projects.executorIdOf(p)).thenReturn("exec-1");
        when(projects.rootsOf(p)).thenReturn(List.of(
                root("/Users/me/second", false), root("/Users/me/primary", true)));

        String key1 = service.sessionWorkspaceKey(SID, UID);
        when(projects.rootsOf(p)).thenReturn(List.of(
                root("/Users/me/primary", true), root("/Users/me/second", false)));
        String key2 = service.sessionWorkspaceKey(SID, UID);

        assertThat(key2).isEqualTo(key1).as("仅展示顺序变化不该触发重建");
    }

    @Test
    void implicitCloudKeyCoversScopeSwitch() {
        when(projects.projectOfSession(SID)).thenReturn(null);
        when(sessionWorkspace.resolve(SID, UID)).thenReturn(null); // 云端
        when(config.getBoolean(anyString(), anyBoolean())).thenReturn(false);

        String sessionScoped = service.sessionWorkspaceKey(SID, UID);

        when(config.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        String userScoped = service.sessionWorkspaceKey(SID, UID);

        assertThat(sessionScoped).isNotEqualTo(userScoped)
                .as("sandbox.userScope false→true 必须让已建实例失效");
        assertThat(sessionScoped).contains(SID);
        assertThat(userScoped).contains(UID);
    }

    @Test
    void implicitLocalKeyCoversExecutorAndRoot() {
        when(projects.projectOfSession(SID)).thenReturn(null);
        when(sessionWorkspace.resolve(SID, UID)).thenReturn(
                new SessionWorkspaceService.Workspace(
                        SessionWorkspaceService.BACKEND_LOCAL, "exec-9", "/Users/me/ws", null));

        String key = service.sessionWorkspaceKey(SID, UID);
        assertThat(key).contains("exec-9").contains("/Users/me/ws");
    }
}
