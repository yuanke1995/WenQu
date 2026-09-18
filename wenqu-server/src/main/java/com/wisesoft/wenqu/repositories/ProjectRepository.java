package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Project;
import com.wisesoft.wenqu.repository.port.ConversationMapper;
import com.wisesoft.wenqu.repository.port.ProjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目仓储。
 *
 * <p>由参考实现的 repositories/project_repository.py 逐方法翻译：可管理条件
 * （selection_status=selectable 且 status=active）、按幂等键查找、可选项目排序
 * （更新时间倒序、主键倒序）、可解析实际工作目录的历史对话筛选、软删除项目时
 * 连带软删除其全部对话。
 *
 * <p>必要替换：
 * <ul>
 *   <li>JSON 路径条件 {@code extra_metadata["source"].as_string()} → MySQL 的
 *       {@code JSON_UNQUOTE(JSON_EXTRACT(extra_metadata, '$.source'))}。
 *   <li>多表联查（对话 + 项目工作目录）用原生 SQL，查询仍在本仓储内。
 * </ul>
 */
@Repository
public class ProjectRepository {

    /** 属于调用类对话的来源（历史候选需排除这些）。 */
    public static final List<String> INVOCATION_CONVERSATION_SOURCES = List.of("agent_call", "agent_evaluation");

    private final ProjectMapper projectMapper;
    private final ConversationMapper conversationMapper;
    private final JdbcTemplate jdbc;

    public ProjectRepository(ProjectMapper projectMapper, ConversationMapper conversationMapper, JdbcTemplate jdbc) {
        this.projectMapper = projectMapper;
        this.conversationMapper = conversationMapper;
        this.jdbc = jdbc;
    }

    /** 新增项目。 */
    public Project add(Project project) {
        if (project.getCreatedAt() == null) {
            project.setCreatedAt(com.wisesoft.wenqu.common.DateTimeUtils.utcNowNaive());
        }
        if (project.getUpdatedAt() == null) {
            project.setUpdatedAt(com.wisesoft.wenqu.common.DateTimeUtils.utcNowNaive());
        }
        projectMapper.insert(project);
        return project;
    }

    /** 按用户读取项目。 */
    public Project getForUser(String projectId, String uid) {
        return projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getId, projectId).eq(Project::getUid, String.valueOf(uid)));
    }

    /** 锁定当前用户的 active 项目。 */
    public Project lockActiveForUser(String projectId, String uid) {
        return projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getUid, String.valueOf(uid))
                        .eq(Project::getStatus, "active")
                        .last("FOR UPDATE"));
    }

    /** 锁定当前用户可管理的 active selectable 项目。 */
    public Project lockActiveSelectableForUser(String projectId, String uid) {
        return projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getUid, String.valueOf(uid))
                        .eq(Project::getSelectionStatus, "selectable")
                        .eq(Project::getStatus, "active")
                        .last("FOR UPDATE"));
    }

    /** 按用户和幂等键读取项目。 */
    public Project getByIdempotencyKey(String idempotencyKey, String uid) {
        return projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getUid, String.valueOf(uid))
                        .eq(Project::getIdempotencyKey, idempotencyKey));
    }

    /** 列出用户可选择的项目。 */
    public List<Project> listSelectableForUser(String uid) {
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getUid, String.valueOf(uid))
                        .eq(Project::getSelectionStatus, "selectable")
                        .eq(Project::getStatus, "active")
                        .orderByDesc(Project::getUpdatedAt)
                        .orderByDesc(Project::getId));
    }

    /** 列出用户已选择项目的去重工作目录路径。 */
    public List<String> listSelectableWorkdirPathsForUser(String uid) {
        List<String> paths = new ArrayList<>();
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT DISTINCT workdir_path FROM projects WHERE uid = ? AND selection_status = 'selectable' "
                                + "AND status = 'active'",
                        uid);
        for (Map<String, Object> row : rows) {
            Object path = row.get("workdir_path");
            if (path != null) {
                paths.add(String.valueOf(path));
            }
        }
        return paths;
    }

    /** 列出可解析实际工作目录的普通历史对话（对话 + 其工作目录路径）。 */
    public List<Map<String, Object>> listHistoryCandidates(String uid) {
        List<Object> args = new ArrayList<>();
        args.add(uid);
        StringBuilder sql =
                new StringBuilder(
                        "SELECT c.*, p.workdir_path AS workdir_path FROM conversations c JOIN projects p "
                                + "ON p.uid = c.uid AND p.id = c.project_id WHERE c.uid = ? "
                                + "AND c.status = 'active' AND p.status = 'active' AND ("
                                + "c.extra_metadata IS NULL "
                                + "OR JSON_UNQUOTE(JSON_EXTRACT(c.extra_metadata, '$.source')) IS NULL "
                                + "OR JSON_UNQUOTE(JSON_EXTRACT(c.extra_metadata, '$.source')) NOT IN (");
        for (int i = 0; i < INVOCATION_CONVERSATION_SOURCES.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            args.add(INVOCATION_CONVERSATION_SOURCES.get(i));
        }
        sql.append(")) ORDER BY c.updated_at DESC, c.id DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * Project.to_dict() 的键与时间格式照搬（参考实现 models_business.Project.to_dict）。
     */
    public static Map<String, Object> toDict(Project project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.getId());
        result.put("uid", project.getUid());
        result.put("name", project.getName());
        result.put("selection_status", project.getSelectionStatus());
        result.put("workdir_path", project.getWorkdirPath());
        result.put("directory_mode", project.getDirectoryMode());
        result.put("status", project.getStatus());
        result.put("deleted_at", DateTimeUtils.formatUtcDatetime(project.getDeletedAt()));
        result.put("created_at", DateTimeUtils.formatUtcDatetime(project.getCreatedAt()));
        result.put("updated_at", DateTimeUtils.formatUtcDatetime(project.getUpdatedAt()));
        return result;
    }

    /** 重命名等场景的字段更新（显式 SET，未列字段不动）。 */
    @Transactional
    public Project updateProject(Project project) {
        projectMapper.update(
                null,
                new LambdaUpdateWrapper<Project>()
                        .eq(Project::getId, project.getId())
                        .set(Project::getName, project.getName())
                        .set(Project::getUpdatedAt, project.getUpdatedAt()));
        return project;
    }

    /** 在调用方事务内软删除项目及其全部对话，返回受影响的对话数。 */
    @Transactional
    public int softDeleteWithConversations(Project project, LocalDateTime deletedAt) {
        int affected =
                conversationMapper.update(
                        null,
                        new LambdaUpdateWrapper<Conversation>()
                                .eq(Conversation::getUid, project.getUid())
                                .eq(Conversation::getProjectId, project.getId())
                                .set(Conversation::getStatus, "deleted")
                                .set(Conversation::getUpdatedAt, deletedAt));
        projectMapper.update(
                null,
                new LambdaUpdateWrapper<Project>()
                        .eq(Project::getId, project.getId())
                        .set(Project::getStatus, "deleted")
                        .set(Project::getDeletedAt, deletedAt)
                        .set(Project::getUpdatedAt, deletedAt));
        return affected;
    }

    /** 便于调用方构造历史候选结果（键名与参考实现的联查列一致）。 */
    static Map<String, Object> historyCandidate(Conversation conversation, String workdirPath) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("conversation", conversation);
        item.put("workdir_path", workdirPath);
        return item;
    }
}
