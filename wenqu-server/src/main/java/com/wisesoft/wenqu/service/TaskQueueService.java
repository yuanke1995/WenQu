package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repositories.TaskRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 持久任务的发布 / 收敛编排。
 *
 * <p>由参考实现的 services/task_queue_service.py 逐函数翻译：把已提交的 PG Task 发布为
 * worker 唤醒消息、失败事务内的领域收敛、失联收敛后重发 pending、取消意图的即时收敛。
 *
 * <p>必要替换：
 * <ul>
 *   <li>参考实现的唤醒消息走 ARQ（{@code get_arq_pool().enqueue_job("process_task", task_id)}）；
 *       ARQ 是 Python 专用队列，本工程以 {@link ArqPort} 端口承载同一唤醒语义，未装配时
 *       {@link #publishTask} 抛出异常——与参考实现"Redis 不可达时 publish 失败"同型，
 *       由调用方（Tasker._publish_created_task）捕获记日志、pending intent 待重试。
 *   <li>健康键命名空间前缀按本系统约定（共享 Redis 中标识本系统的键空间）。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}。
 * </ul>
 */
@Service
public class TaskQueueService {

    public static final double TASK_LEASE_SECONDS = 30.0;
    public static final double TASK_HEARTBEAT_SECONDS = 10.0;
    public static final double TASK_RECONCILIATION_SECONDS = 30.0;
    /** 命名空间前缀为本系统约定，键名其余部分照搬参考实现。 */
    public static final String TASK_RECONCILIATION_HEALTH_KEY = "wenqu:worker:health:durable-task-reconciliation-v1";
    public static final int TASK_RECONCILIATION_HEALTH_TTL_SECONDS = (int) (TASK_RECONCILIATION_SECONDS * 2 + 5);

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);

    /** worker 唤醒端口（对应 ARQ 的 enqueue_job("process_task", task_id)；实现随 worker 体系照搬时提供）。 */
    public interface ArqPort {
        void enqueueProcessTask(String taskId) throws Exception;
    }

    private final TaskRepository taskRepository;
    private final ObjectProvider<ArqPort> arqPort;

    public TaskQueueService(TaskRepository taskRepository, ObjectProvider<ArqPort> arqPort) {
        this.taskRepository = taskRepository;
        this.arqPort = arqPort;
    }

    /** 把已提交的 PG Task 发布为 worker 唤醒消息；重复消息由数据库 claim 拒绝。 */
    public void publishTask(String taskId) throws Exception {
        ArqPort port = arqPort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("任务唤醒端口未装配（ARQ 队列未照搬），pending intent 将由收敛循环重试");
        }
        port.enqueueProcessTask(taskId);
    }

    /** 在 Task 失败事务内执行已注册的领域收敛。 */
    public void finalizeTaskFailure(TaskRecord record, String error) {
        int handlerVersion = record.getHandlerVersion() == null ? 1 : record.getHandlerVersion();
        TaskRegistry.TaskDefinition definition;
        try {
            definition = TaskRegistry.getFailureTaskDefinition(record.getType(), handlerVersion);
        } catch (IllegalArgumentException exception) {
            log.error(
                    "Cannot finalize unknown durable task: task_id={}, type={}, handler_version={}",
                    record.getId(),
                    record.getType(),
                    handlerVersion);
            return;
        }
        TaskRegistry.TaskFailureHandler handler = definition.loadFailureHandler();
        if (handler != null) {
            try {
                handler.apply(record, error);
            } catch (Exception exception) {
                throw new IllegalStateException("failure handler 执行失败: " + record.getId(), exception);
            }
        }
    }

    /** 收敛失联 owner，并发布所有当前 pending Task。 */
    public List<TaskRepository.ReconciledTask> reconcileAndPublishTasks() throws Exception {
        List<TaskRepository.ReconciledTask> reconciled =
                taskRepository.reconcileExpiredLeases(this::finalizeTaskFailure, null);
        publishPendingTasks(200);
        taskRepository.pruneTerminal(200);
        return reconciled;
    }

    /** 按参考实现的"解析失败仅记日志、hook 置空"口径加载 failure hook。 */
    private TaskRegistry.TaskFailureHandler loadFailureHandlerQuietly(TaskRecord record, int handlerVersion) {
        try {
            return TaskRegistry.getFailureTaskDefinition(record.getType(), handlerVersion).loadFailureHandler();
        } catch (IllegalArgumentException exception) {
            log.error(
                    "Cannot finalize unknown durable task: task_id={}, type={}, handler_version={}",
                    record.getId(),
                    record.getType(),
                    handlerVersion);
            return null;
        }
    }

    /** 重发 PG 中待执行的任务；重复唤醒消息由 task_id/attempt 去重。 */
    public List<String> publishPendingTasks(int limit) throws Exception {
        List<String> published = new ArrayList<>();
        for (TaskRecord record : taskRepository.listPending(limit)) {
            try {
                TaskRegistry.getTaskDefinition(record.getType());
            } catch (IllegalArgumentException exception) {
                log.error("Cannot publish unknown durable task: task_id={}, type={}", record.getId(), record.getType());
                taskRepository.failPending(record.getId(), exception.getMessage(), null, null);
                continue;
            }
            int handlerVersion = record.getHandlerVersion() == null ? 1 : record.getHandlerVersion();
            if (record.getCancelRequested() != null && record.getCancelRequested() != 0) {
                TaskRegistry.TaskFailureHandler failureHandler = loadFailureHandlerQuietly(record, handlerVersion);
                taskRepository.requestCancel(
                        record.getId(), TaskRegistry.asBeforeChange(failureHandler, "任务已取消"), null);
                continue;
            }
            String error = null;
            try {
                TaskRegistry.getTaskDefinition(record.getType(), handlerVersion);
            } catch (IllegalArgumentException exception) {
                error = exception.getMessage();
                log.error("Cannot rebuild durable task: task_id={}, type={}", record.getId(), record.getType());
                TaskRegistry.TaskFailureHandler failureHandler = null;
                try {
                    TaskRegistry.TaskDefinition failureDefinition =
                            TaskRegistry.getFailureTaskDefinition(record.getType(), handlerVersion);
                    failureHandler = failureDefinition.loadFailureHandler();
                } catch (IllegalArgumentException ignored) {
                    // 未知类型/版本的 failure hook：按参考实现置空
                }
                taskRepository.failPending(record.getId(), error, TaskRegistry.asBeforeChange(failureHandler, error), null);
                continue;
            }
            publishTask(record.getId());
            published.add(record.getId());
        }
        return published;
    }
}
