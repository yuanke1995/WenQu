package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台任务路由，逐端点对齐参考实现 {@code server/routers/system_task_router.py}。
 *
 * <p>错误语义照搬：任务不存在 404 {@code Task not found}；不可取消 400 {@code Task cannot be cancelled}；
 * 非终态删除 409 {@code Task must exist and be terminal before deletion}。
 *
 * <p>平台差异（必要替换）：{@code Depends(get_admin_user)} → {@link AuthGuards#requireAdmin()}；
 * 查询参数 {@code Query(limit, ge=1, le=100)} → Jakarta 约束注解（同范围，超界 422）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@Tag(name = "tasks", description = "后台任务查询与管理")
public class TaskController {

    private final TaskService taskService;

    @Operation(summary = "任务列表", description = "可按 status 过滤；limit 范围 1..100，默认 100")
    @GetMapping
    public Map<String, Object> listTasks(
            @Parameter(description = "任务状态过滤") @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        AuthGuards.requireAdmin();
        int boundedLimit = Math.min(100, Math.max(1, limit));
        return taskService.listTasks(status, boundedLimit);
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{task_id}")
    public Map<String, Object> getTask(@PathVariable("task_id") String taskId) {
        AuthGuards.requireAdmin();
        Map<String, Object> task = taskService.getTask(taskId);
        if (task == null) {
            throw new ApiHttpException(404, "Task not found");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        return result;
    }

    @Operation(summary = "请求取消任务", description = "返回 {task_id,status,cancel_requested}")
    @PostMapping("/{task_id}/cancel")
    public Map<String, Object> cancelTask(@PathVariable("task_id") String taskId) {
        AuthGuards.requireAdmin();
        TaskService.Task task;
        try {
            task = taskService.cancelTask(taskId);
        } catch (Exception exc) {
            log.warn("取消任务失败 taskId={} err={}", taskId, exc.getMessage());
            throw new ApiHttpException(400, "Task cannot be cancelled");
        }
        if (task == null) {
            throw new ApiHttpException(400, "Task cannot be cancelled");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("status", task.status);
        result.put("cancel_requested", task.cancelRequested);
        return result;
    }

    @Operation(summary = "删除任务", description = "仅终态任务可删除")
    @DeleteMapping("/{task_id}")
    public Map<String, Object> deleteTask(@PathVariable("task_id") String taskId) {
        AuthGuards.requireAdmin();
        boolean success = taskService.deleteTask(taskId);
        if (!success) {
            throw new ApiHttpException(409, "Task must exist and be terminal before deletion");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("status", "deleted");
        return result;
    }
}
