package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Project;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.workspace.Workdir;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 授权 Conversation 对持久化 Project Workdir 的访问。
 *
 * <p>由参考实现的 services/workdir_service.py 逐函数翻译：Workdir 绑定快照构造与校验、
 * 按 thread_id 授权打开 Workdir、持久 Workdir 路径解析、managed 模式目录物化。
 *
 * <p>必要替换：{@code fastapi HTTPException(404, ...)} → {@link BizException}(404, ...)；
 * {@code RuntimeError} → {@link IllegalStateException}；AsyncSession 传参 → Spring Bean 方法。
 */
@Service
public class WorkdirService {

    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;

    public WorkdirService(ConversationRepository conversationRepository, ProjectRepository projectRepository) {
        this.conversationRepository = conversationRepository;
        this.projectRepository = projectRepository;
    }

    /** Conversation 当前 Project 所拥有的已授权 Workdir 快照。 */
    public record WorkdirBinding(
            int conversationId,
            String threadId,
            String uid,
            String projectId,
            String workdirPath,
            String directoryMode) {

        /** 判断该绑定是否需要在事务提交后物化目录。 */
        public boolean materializeManaged() {
            return "managed".equals(directoryMode);
        }
    }

    /** Service 授权上下文与持久化 Workdir。 */
    public record AuthorizedWorkdir(
            int conversationId,
            String threadId,
            String uid,
            Workdir workdir,
            String projectId,
            String directoryMode) {

        public String workdirPath() {
            return workdir.getRelativePath();
        }
    }

    /** 从已加载的 Project 构造线程 Workdir 快照，避免再次查询 Project。 */
    public WorkdirBinding workdirBindingFromProject(Conversation conversation, String uid, Project project) {
        if (project == null) {
            throw new IllegalStateException("Conversation 绑定的 Project 不存在");
        }
        String conversationProjectId = conversation.getProjectId() == null ? "" : String.valueOf(conversation.getProjectId());
        String projectId = project.getId() == null ? "" : String.valueOf(project.getId());
        if (conversationProjectId.isEmpty() || projectId.isEmpty() || !projectId.equals(conversationProjectId)) {
            throw new IllegalStateException("Conversation 与 Project 绑定不一致");
        }
        if (!Objects.equals(str(project.getUid()), str(uid))) {
            throw new IllegalStateException("Project 不属于当前用户");
        }
        String threadId = conversation.getThreadId() == null ? "" : String.valueOf(conversation.getThreadId());
        if (threadId.isEmpty()) {
            throw new IllegalStateException("Conversation 缺少 thread_id");
        }
        String workdirPath = project.getWorkdirPath() == null ? "" : String.valueOf(project.getWorkdirPath());
        String directoryMode =
                project.getDirectoryMode() == null ? "" : String.valueOf(project.getDirectoryMode());
        if (workdirPath.isEmpty() || !("managed".equals(directoryMode) || "linked".equals(directoryMode))) {
            throw new IllegalStateException("Project Workdir 绑定无效");
        }
        return new WorkdirBinding(
                conversation.getId(),
                threadId,
                str(uid),
                projectId,
                workdirPath,
                directoryMode);
    }

    /** 校验传入快照仍属于当前用户和 Conversation。 */
    public void validateWorkdirBinding(WorkdirBinding binding, Conversation conversation, String uid) {
        if (!Objects.equals(binding.uid(), str(uid))
                || binding.conversationId() != conversation.getId()
                || !Objects.equals(binding.threadId(), str(conversation.getThreadId()))
                || !Objects.equals(binding.projectId(), str(conversation.getProjectId()))) {
            throw new IllegalStateException("传入的 Workdir 绑定与 Conversation 不一致");
        }
    }

    /** 按公共 Thread ID 授权并打开持久化 Workdir。 */
    public AuthorizedWorkdir resolveAuthorizedWorkdir(String threadId, String uid) {
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        return resolveAuthorizedConversationWorkdir(conversation, uid);
    }

    /** 复用已查询的 Conversation，重新校验归属后打开 Workdir。 */
    public AuthorizedWorkdir resolveAuthorizedConversationWorkdir(Conversation conversation, String uid) {
        if (conversation == null
                || !Objects.equals(conversation.getUid(), str(uid))
                || "deleted".equals(conversation.getStatus())) {
            throw new BizException(404, "对话线程不存在");
        }
        WorkdirBinding binding = resolveConversationWorkdirBinding(conversation, str(uid), null);
        return new AuthorizedWorkdir(
                conversation.getId(),
                conversation.getThreadId(),
                str(uid),
                Workdir.openExisting(str(uid), binding.workdirPath()),
                binding.projectId(),
                binding.directoryMode());
    }

    /** 解析 Conversation 的持久 Workdir 路径。 */
    public String resolveConversationWorkdirPath(Conversation conversation, String uid) {
        WorkdirBinding binding = resolveConversationWorkdirBinding(conversation, uid, null);
        return binding.workdirPath();
    }

    /** 确保 Conversation 的持久 Workdir 可用，并返回其相对路径。 */
    public String ensureConversationWorkdirAvailable(
            Conversation conversation, String uid, WorkdirBinding workdirBinding) {
        WorkdirBinding binding = workdirBinding;
        if (binding == null) {
            binding = resolveConversationWorkdirBinding(conversation, uid, null);
        } else {
            validateWorkdirBinding(binding, conversation, uid);
        }
        if (binding.materializeManaged()) {
            WorkspacePaths.ensureBoundUserWorkdir(binding.uid(), binding.workdirPath());
        } else {
            Workdir.openExisting(binding.uid(), binding.workdirPath());
        }
        return binding.workdirPath();
    }

    /** 解析 Conversation 唯一 Project 所拥有的持久 Workdir。 */
    public WorkdirBinding resolveConversationWorkdirBinding(
            Conversation conversation, String uid, Project project) {
        Project resolvedProject = project;
        if (resolvedProject == null) {
            resolvedProject = projectRepository.getForUser(str(conversation.getProjectId()), str(uid));
        }
        return workdirBindingFromProject(conversation, uid, resolvedProject);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
