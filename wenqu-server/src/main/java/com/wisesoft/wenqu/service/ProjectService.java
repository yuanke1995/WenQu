package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.Project;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.workspace.Workdir;
import com.wisesoft.wenqu.workspace.Workspace;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Project 创建、选择与历史目录复用用例。
 *
 * <p>由参考实现的 services/project_service.py 逐函数翻译：名称规范化与校验、
 * 幂等创建意图匹配、managed/linked 目录分配与打开、selectable Project 的
 * 幂等创建/列举/重命名/软删除、历史目录候选。
 *
 * <p>必要替换：{@code pg_advisory_xact_lock(hashtextextended("project-workdir:{uid}", 0))} →
 * MySQL 命名锁 {@code GET_LOCK("wenqu:project-workdir:{uid}", 10)}（会话级，成对释放）；
 * {@code HTTPException(422/409/404/400)} → {@link BizException}(同状态码)；
 * {@code IntegrityError} → {@link DataIntegrityViolationException}（重放分支）；
 * 显式 commit/rollback → 各写入点自动提交（参考实现的提交边界见各方法注解）。
 */
@Service
public class ProjectService {

    public static final int MAX_PROJECT_NAME_LENGTH = 255;

    private final ProjectRepository projectRepository;
    private final JdbcTemplate jdbc;

    public ProjectService(ProjectRepository projectRepository, JdbcTemplate jdbc) {
        this.projectRepository = projectRepository;
        this.jdbc = jdbc;
    }

    /** 串行化同一用户的 linked Project 绑定与测试目录删除。 */
    private void lockProjectWorkdirChanges(String uid) {
        String lockName = "wenqu:project-workdir:" + uid;
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, lockName);
        // 参考实现为事务级建议锁，随事务释放；此处以 ThreadLocal 延迟到事务结束不适用，
        // 改为调用方事务提交前由本类 releaseProjectWorkdirChanges 释放（成对调用）
        ThreadLocalLocks.hold(lockName, acquired != null && acquired == 1);
    }

    /** 释放 {@link #lockProjectWorkdirChanges} 获取的命名锁。 */
    private void releaseProjectWorkdirChanges() {
        String lockName = ThreadLocalLocks.release();
        if (lockName != null) {
            jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
        }
    }

    /** 命名锁的调用线程内持有记录（对应事务级锁随事务释放的语义）。 */
    private static final class ThreadLocalLocks {
        private static final ThreadLocal<String> HELD = new ThreadLocal<>();

        static void hold(String name, boolean acquired) {
            if (!acquired) {
                throw new IllegalStateException("获取 Project Workdir 串行锁超时: " + name);
            }
            HELD.set(name);
        }

        static String release() {
            String name = HELD.get();
            HELD.remove();
            return name;
        }
    }

    /** 规范化并校验 Project 名称。 */
    private String normalizeProjectName(String name, boolean required) {
        String normalizedName = name == null ? "" : name.strip();
        if (required && normalizedName.isEmpty()) {
            throw new BizException(422, "项目名称不能为空");
        }
        if (normalizedName.length() > MAX_PROJECT_NAME_LENGTH) {
            throw new BizException(422, "项目名称过长");
        }
        return normalizedName.isEmpty() ? null : normalizedName;
    }

    /** 要求已有 Project 仍有效且匹配当前幂等创建意图。 */
    private void requireMatchingCreationIntent(Project project, String name, String workdirPath) {
        if ("deleted".equals(project.getStatus())) {
            throw new BizException(409, "request_id 已用于已删除的 Project");
        }
        if (!java.util.Objects.equals(project.getName(), name)
                || !"linked".equals(project.getDirectoryMode())
                || !java.util.Objects.equals(project.getWorkdirPath(), workdirPath)) {
            throw new BizException(409, "request_id 已用于其他 Project 创建意图");
        }
    }

    /** 在当前事务内创建 Project，但不提交或物化 managed 目录。 */
    public Project createProjectRecord(
            String uid,
            String name,
            String directoryMode,
            String selectionStatus,
            String workdirPath,
            String idempotencyKey) {
        String normalizedName = normalizeProjectName(name, "selectable".equals(selectionStatus));
        if (!"managed".equals(directoryMode) && !"linked".equals(directoryMode)) {
            throw new BizException(422, "directory_mode 必须是 managed 或 linked");
        }
        if (!"implicit".equals(selectionStatus) && !"selectable".equals(selectionStatus)) {
            throw new BizException(422, "selection_status 非法");
        }

        String projectId = UUID.randomUUID().toString();
        String normalizedPath;
        if ("managed".equals(directoryMode)) {
            if (workdirPath != null) {
                throw new BizException(422, "managed Project 不接受 workdir_path");
            }
            normalizedPath = WorkspacePaths.allocateDefaultUserWorkdirPath(str(uid), projectId, null);
        } else {
            if (workdirPath == null) {
                throw new BizException(422, "linked Project 必须指定 workdir_path");
            }
            normalizedPath = WorkspacePaths.normalizeWorkdirPath(workdirPath);
        }

        Project project = new Project();
        project.setId(projectId);
        project.setUid(str(uid));
        project.setName(normalizedName);
        project.setSelectionStatus(selectionStatus);
        project.setWorkdirPath(normalizedPath);
        project.setDirectoryMode(directoryMode);
        project.setIdempotencyKey(idempotencyKey == null || idempotencyKey.strip().isEmpty()
                ? null
                : idempotencyKey.strip());

        if ("linked".equals(directoryMode)) {
            // 锁窗口覆盖「打开校验 + 插入」，贴近参考实现“事务提交才释放建议锁”
            lockProjectWorkdirChanges(str(uid));
            try {
                openExistingOrThrow(str(uid), normalizedPath);
                return projectRepository.add(project);
            } finally {
                releaseProjectWorkdirChanges();
            }
        }
        return projectRepository.add(project);
    }

    /** linked 模式的目录存在性校验（错误码翻译见 except 元组）。 */
    private void openExistingOrThrow(String uid, String normalizedPath) {
        try {
            Workdir.openExisting(uid, normalizedPath);
        } catch (Workspace.NoSuchFileRuntime exc) {
            throw new BizException(404, "目录不存在");
        } catch (RuntimeException exc) {
            // 对应参考实现 except (NotADirectoryError, PermissionError, OSError, ValueError)
            throw new BizException(400, String.valueOf(exc.getMessage()));
        }
    }

    /** 为新 Conversation 创建 implicit managed Project。 */
    public Project createImplicitProject(String uid, String idempotencyKey) {
        return createProjectRecord(uid, null, "managed", "implicit", null, idempotencyKey);
    }

    /** 幂等创建 selectable Project。 */
    public Map<String, Object> createProjectView(
            String uid, String requestId, String name, String directoryMode, String workdirPath) {
        String normalizedRequestId = requestId == null ? "" : requestId.strip();
        if (normalizedRequestId.isEmpty()) {
            throw new BizException(422, "request_id 不能为空");
        }
        if (!"linked".equals(directoryMode) || (workdirPath == null || workdirPath.strip().isEmpty())) {
            throw new BizException(422, "手动创建项目必须选择目录");
        }
        Project existing = projectRepository.getByIdempotencyKey(normalizedRequestId, str(uid));
        String normalizedName = normalizeProjectName(name, true);
        String normalizedPath;
        try {
            normalizedPath = WorkspacePaths.normalizeWorkdirPath(workdirPath);
        } catch (IllegalArgumentException exc) {
            throw new BizException(400, String.valueOf(exc.getMessage()));
        }
        if (existing != null) {
            requireMatchingCreationIntent(existing, normalizedName, normalizedPath);
            return ProjectRepository.toDict(existing);
        }

        Project project;
        try {
            project = createProjectRecord(
                    uid, name, directoryMode, "selectable", workdirPath, normalizedRequestId);
        } catch (DataIntegrityViolationException exc) {
            // 参考实现 rollback 后按幂等键重放
            Project replay = projectRepository.getByIdempotencyKey(normalizedRequestId, str(uid));
            if (replay == null) {
                throw new BizException(409, "Project 创建冲突");
            }
            requireMatchingCreationIntent(replay, normalizedName, normalizedPath);
            project = replay;
        }
        return ProjectRepository.toDict(project);
    }

    /** 列出当前用户可选择的 Project。 */
    public List<Map<String, Object>> listProjectsView(String uid) {
        List<Project> projects = projectRepository.listSelectableForUser(str(uid));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Project project : projects) {
            result.add(ProjectRepository.toDict(project));
        }
        return result;
    }

    /** 重命名当前用户的 selectable Project。 */
    public Map<String, Object> renameProjectView(String uid, String projectId, String name) {
        String normalizedName = normalizeProjectName(name, true);
        Project project = projectRepository.lockActiveSelectableForUser(projectId, str(uid));
        if (project == null) {
            throw new BizException(404, "Project 不存在");
        }

        project.setName(normalizedName);
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        project.setUpdatedAt(now);
        projectRepository.updateProject(project);
        return ProjectRepository.toDict(project);
    }

    /** 软删除 Project 及其 Conversation，保留 Workdir 字节。 */
    public Map<String, Object> deleteProjectView(String uid, String projectId) {
        Project project = projectRepository.lockActiveSelectableForUser(projectId, str(uid));
        if (project == null) {
            throw new BizException(404, "Project 不存在");
        }

        int deletedConversations =
                projectRepository.softDeleteWithConversations(project, DateTimeUtils.utcNowNaive());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "删除成功");
        result.put("deleted_conversations", deletedConversations);
        return result;
    }

    /** 列出可作为新建 Project 目录快捷入口的历史 Conversation。 */
    public Map<String, Object> listHistoryCandidatesView(String uid, String query, int limit, int offset) {
        List<Map<String, Object>> conversations = projectRepository.listHistoryCandidates(str(uid));
        return listHistoryCandidatesFromRows(conversations, query, limit, offset);
    }

    /** 历史候选的筛选与分页核心（rows 为联表行：conversation.* + workdir_path）。 */
    static Map<String, Object> listHistoryCandidatesFromRows(
            List<Map<String, Object>> conversations, String query, int limit, int offset) {
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seenWorkdirs = new HashSet<>();
        for (Map<String, Object> row : conversations) {
            String workdirPath = str(row.get("workdir_path"));
            if (seenWorkdirs.contains(workdirPath)) {
                continue;
            }
            String title = str(row.get("title"));
            String agentId = str(row.get("agent_id"));
            if (!normalizedQuery.isEmpty()
                    && !title.toLowerCase().contains(normalizedQuery)
                    && !agentId.toLowerCase().contains(normalizedQuery)) {
                continue;
            }
            seenWorkdirs.add(workdirPath);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("thread_id", str(row.get("thread_id")));
            item.put("title", row.get("title"));
            item.put("agent_id", row.get("agent_id"));
            item.put("workdir_path", workdirPath);
            item.put("updated_at", formatIso(row.get("updated_at")));
            items.add(item);
        }
        int from = Math.min(offset, items.size());
        int to = Math.min(offset + limit, items.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items.subList(from, to));
        result.put("has_more", items.size() > offset + limit);
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String formatIso(Object value) {
        // 对应 item.updated_at.isoformat()（naive datetime 的 ISO 串）
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        return value == null ? null : String.valueOf(value);
    }
}
