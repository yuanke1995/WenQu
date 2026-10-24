package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.ProjectTodoMapper;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectTodo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区事项（{@code c_ai_project_todo}）的服务：记下 → 去做 → 勾掉，刻意保持最短闭环。
 *
 * <p>为什么独立成类而不是塞进 {@link ProjectService}：后者是容器服务，类注释写明「只做三件事、
 * 刻意保持薄」；事项是长在容器上的一种内容（像文件、产物），自己一个服务一段生命周期。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTodoService {

    private final ProjectTodoMapper todoMapper;
    private final ProjectService projectService;

    /** 单个清单的条数上限：事项堆到这个数说明该清理了，防列接口被无限撑长 */
    private static final int MAX_TODOS = 200;

    /**
     * 某工作区的事项清单：未完成在前（新添的在上），已完成按完成时刻倒序收尾。
     */
    public List<ProjectTodo> list(String uid, String projectId) {
        projectService.detail(uid, projectId);   // 归属校验（不存在/他人/已删一律抛）
        List<ProjectTodo> all = todoMapper.selectList(new LambdaQueryWrapper<ProjectTodo>()
                .eq(ProjectTodo::getProjectId, projectId)
                .orderByDesc(ProjectTodo::getCreateTime)
                .last("LIMIT " + MAX_TODOS));
        List<ProjectTodo> open = new ArrayList<>(), done = new ArrayList<>();
        for (ProjectTodo t : all) {
            (ProjectTodo.STATUS_DONE.equals(t.getStatus()) ? done : open).add(t);
        }
        done.sort((a, b) -> {
            LocalDateTime x = a.getDoneTime(), y = b.getDoneTime();
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });
        List<ProjectTodo> out = new ArrayList<>(open.size() + done.size());
        out.addAll(open);
        out.addAll(done);
        return out;
    }

    public ProjectTodo create(String uid, String projectId, String title) {
        Project p = projectService.detail(uid, projectId);
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("事项内容不能为空");
        }
        if (t.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException("事项内容过长（最多 " + MAX_TITLE_LEN + " 字）");
        }
        ProjectTodo todo = new ProjectTodo();
        todo.setUid(p.getUid());
        todo.setProjectId(p.getId());
        todo.setTitle(t);
        todo.setStatus(ProjectTodo.STATUS_OPEN);
        todo.setDeleted(0);
        todo.setCreateTime(LocalDateTime.now());
        todoMapper.insert(todo);
        return todo;
    }

    /**
     * 更新事项：传 {@code title} 改内容、传 {@code done} 勾/取消完成（传才改）。
     * 勾完成记当前时刻，取消完成清掉——「什么时候做完的」也是这条事项的事实。
     */
    public ProjectTodo update(String uid, String projectId, String todoId, String title, Boolean done) {
        projectService.detail(uid, projectId);   // 归属校验
        ProjectTodo todo = todoMapper.selectById(todoId);
        if (todo == null || !projectId.equals(todo.getProjectId()) || !uid.equals(todo.getUid())) {
            throw new IllegalArgumentException("事项不存在或不属于这个工作区");
        }
        if (title != null) {
            String t = title.trim();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("事项内容不能为空");
            }
            if (t.length() > MAX_TITLE_LEN) {
                throw new IllegalArgumentException("事项内容过长（最多 " + MAX_TITLE_LEN + " 字）");
            }
            todo.setTitle(t);
        }
        if (done != null) {
            todo.setStatus(Boolean.TRUE.equals(done) ? ProjectTodo.STATUS_DONE : ProjectTodo.STATUS_OPEN);
            todo.setDoneTime(Boolean.TRUE.equals(done) ? LocalDateTime.now() : null);
        }
        todo.setUpdateTime(LocalDateTime.now());
        todoMapper.updateById(todo);
        return todo;
    }

    public void delete(String uid, String projectId, String todoId) {
        projectService.detail(uid, projectId);
        ProjectTodo todo = todoMapper.selectById(todoId);
        if (todo == null || !projectId.equals(todo.getProjectId()) || !uid.equals(todo.getUid())) {
            throw new IllegalArgumentException("事项不存在或不属于这个工作区");
        }
        todoMapper.deleteById(todoId);
        log.info("[PROJECT] 删除事项 uid={} project={} todo={}", uid, projectId, todoId);
    }

    /** 事项内容长度上限（一句话的量级，比约定短得多） */
    public static final int MAX_TITLE_LEN = 200;
}
