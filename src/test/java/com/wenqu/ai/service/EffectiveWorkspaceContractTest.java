package com.wenqu.ai.service;

import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.sandbox.ProvisionerSandboxProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EffectiveWorkspace 是工作目录的唯一真相源：key 必须覆盖全部影响 build 期的字段，
 * 且引擎 build 期消费者必须从 ew 取（不许再各自查库），见方案 §P1 / T7/T8。
 */
class EffectiveWorkspaceContractTest {

    private static final String UID = "u-1";
    private static final String SID = "s-1";

    private final ProjectService projects = mock(ProjectService.class);
    private final ConfigService config = mock(ConfigService.class);
    private final SessionWorkspaceService sessionWorkspace = mock(SessionWorkspaceService.class);
    private final SandboxService service = new SandboxService(
            mock(ProvisionerSandboxProvider.class), projects, config,
            mock(com.wenqu.ai.executor.ExecutorRegistry.class),
            mock(ProjectTurnChangesService.class), sessionWorkspace);

    private Project explicit(String id) {
        Project p = new Project();
        p.setId(id);
        p.setSelectionStatus(Project.SELECTION_SELECTABLE);
        p.setName("项目" + id);
        return p;
    }

    private ProjectRoot root(String path, boolean primary) {
        ProjectRoot r = new ProjectRoot();
        r.setPath(path);
        r.setPrimaryRoot(primary ? ProjectRoot.PRIMARY : 0);
        return r;
    }

    @Test
    void explicitWorkspaceEwCarriesAllRootsAndPrimary() {
        Project p = explicit("p1");
        when(projects.projectOfSession(SID)).thenReturn(p);
        when(projects.executorIdOf(p)).thenReturn("exec-1");
        when(projects.rootsOf(p)).thenReturn(List.of(
                root("/w/second", false), root("/w/primary", true)));

        EffectiveWorkspace ew = service.resolveWorkspace(SID, UID, true);

        assertThat(ew.kind()).isEqualTo(EffectiveWorkspace.Kind.EXPLICIT_LOCAL);
        assertThat(ew.primaryRoot()).isEqualTo("/w/primary");
        assertThat(ew.roots()).containsExactly("/w/primary", "/w/second")
                .as("key 要稳定，roots 按路径排序；primary 由 primaryRoot 单独标记，guard 侧会重排到首位");
        assertThat(ew.label()).isEqualTo("项目p1");
        assertThat(ew.sessionId()).isEqualTo(SID);
        assertThat(ew.uid()).isEqualTo(UID);
        assertThat(ew.key()).contains("p1", "/w/second", "/w/primary");
    }

    @Test
    void implicitCloudEwCarriesScope() {
        when(projects.projectOfSession(SID)).thenReturn(null);
        when(sessionWorkspace.resolve(SID, UID)).thenReturn(null);
        when(config.getBoolean(anyString(), anyBoolean())).thenReturn(false);

        EffectiveWorkspace ew = service.resolveWorkspace(SID, UID, true);

        assertThat(ew.kind()).isEqualTo(EffectiveWorkspace.Kind.IMPLICIT_CLOUD);
        assertThat(ew.serverSandbox()).isTrue();
        assertThat(ew.scopeId()).isEqualTo(SID);
        assertThat(ew.key()).contains(SID);
    }

    @Test
    void chatProfileEwIsScratchWithoutShell() {
        EffectiveWorkspace ew = service.resolveWorkspace(SID, UID, false);

        assertThat(ew.kind()).isEqualTo(EffectiveWorkspace.Kind.CHAT_SCRATCH);
        assertThat(ew.key()).contains(SID, UID);
    }

    @Test
    void buildTimeConsumersReadFromEw() throws Exception {
        String engine = Files.readString(Path.of("src", "main", "java", "com", "wenqu", "ai",
                "agentx", "AgentscopeChatEngine.java"));
        // buildAgent 签名带 ew；report/nativeGuard/FS 三处 build 期调用都传了 ew
        assertThat(engine).contains("AgentxTurnSlot slot, boolean sandboxEnabled,", "EffectiveWorkspace ew)")
                .contains("localExecutorEnvironment(cmd, ew)")
                .contains("workspaceRootsOf(cmd, ew)")
                .contains("frameworkFilesystem(cmd,", "sandboxEnabled, ew)");
    }
}
