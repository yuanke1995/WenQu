package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.EffectiveWorkspace;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.service.SandboxService;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 本轮框架文件系统的解析（从 AgentscopeChatEngine 抽出）：按工作区类型与沙盒装配状态
 * 决定用「工作区目录 / 会话临时目录 / 沙盒」哪一套。
 *
 * <p>聊天档用宿主会话临时目录（文件工具可用、shell 不注册、不建容器）；工作区档若沙盒没装配成功
 * （超时/失败）也退到同一份临时目录，避免回落到宿主共享目录。隐式会话的本机后端没有 SandboxContext，
 * 用「会话目录是否就绪」来判。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxFilesystemResolver {

    private final FrameworkSessions sessions;
    private final SandboxService sandboxService;
    private final ProjectService projectService;
    private final AgentxPromptContext promptCtx;

    public io.agentscope.harness.agent.filesystem.AbstractFilesystem resolve(
            ChatCommand cmd, boolean unrestrictedWrites, boolean sandboxEnabled,
            com.wenqu.ai.service.EffectiveWorkspace ew) {
        if (sandboxService == null) return null;
        String uid = cmd.getUserId();
        String sessionId = cmd.getSessionId();
        if (uid == null || uid.isBlank() || sessionId == null || sessionId.isBlank()) return null;
        String memoryAgentId = promptCtx.memoryAgentIdOf(cmd);
        return ew != null
                ? byWorkspace(cmd, ew, unrestrictedWrites, sandboxEnabled, sessionId, uid, memoryAgentId)
                : byState(cmd, unrestrictedWrites, sandboxEnabled, sessionId, uid, memoryAgentId);
    }

    /** 建实例那轮已经解析过工作区：按 EW 的 kind 与沙盒装配状态直接定。 */
    private io.agentscope.harness.agent.filesystem.AbstractFilesystem byWorkspace(
            ChatCommand cmd, EffectiveWorkspace ew, boolean unrestrictedWrites, boolean sandboxEnabled,
            String sessionId, String uid, String memoryAgentId) {
        if (!sandboxEnabled || ew.kind() == EffectiveWorkspace.Kind.CHAT_SCRATCH) {
            return sandboxService.chatScratchFilesystem(sessionId, uid, memoryAgentId);
        }
        boolean attached = sessions.sandboxAttached(sessions.key(cmd));
        if (ew.kind() == EffectiveWorkspace.Kind.IMPLICIT_CLOUD && !attached) {
            return sandboxService.chatScratchFilesystem(sessionId, uid, memoryAgentId);
        }
        return sandboxService.frameworkFilesystem(ew, unrestrictedWrites, attached, memoryAgentId);
    }

    /**
     * 运行期现查（没有 EW）：聊天档用宿主会话临时目录；工作区档若沙盒没装配成功也退到同一份，
     * 避免回落到宿主共享目录。隐式会话的本机后端没有 SandboxContext，用「会话目录是否就绪」来判。
     */
    private io.agentscope.harness.agent.filesystem.AbstractFilesystem byState(
            ChatCommand cmd, boolean unrestrictedWrites, boolean sandboxEnabled,
            String sessionId, String uid, String memoryAgentId) {
        boolean localProject = isLocalProject(sessionId);
        boolean localSession = sandboxEnabled && !localProject && hasLocalSession(sessionId, uid);
        boolean attached = sessions.sandboxAttached(sessions.key(cmd));
        if (!sandboxEnabled || (!localProject && !localSession && !attached)) {
            return sandboxService.chatScratchFilesystem(sessionId, uid, memoryAgentId);
        }
        return sandboxService.frameworkFilesystem(sessionId, uid, unrestrictedWrites, attached, memoryAgentId);
    }

    /** 直连工作区（文件就在用户电脑上）。 */
    private boolean isLocalProject(String sessionId) {
        try {
            com.wenqu.ai.model.Project p = projectService.projectOfSession(sessionId);
            return p != null && projectService.isDirect(p);
        } catch (RuntimeException e) {
            log.debug("[PROFILE] 工作区类型解析失败，按沙盒状态判文件系统 session={}: {}",
                    sessionId, e.toString());
            return false;
        }
    }

    /** 本机会话目录是否就绪（隐式会话的本机后端没有 SandboxContext）。 */
    private boolean hasLocalSession(String sessionId, String uid) {
        try {
            return sandboxService.localSessionWorkspace(sessionId, uid);
        } catch (RuntimeException e) {
            log.warn("[PROFILE] 本机会话目录解析失败，本轮按沙盒状态判文件系统 session={}: {}",
                    sessionId, e.toString());
            return false;
        }
    }
}
