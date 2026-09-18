package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.SpringContext;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repositories.TaskRepository;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可持久重建任务的处理器注册表。
 *
 * <p>由参考实现的 services/task_registry.py 逐条翻译：7 个任务定义（类型 / 处理器 /
 * 成功与失败钩子 / 版本）与「未知类型、未知版本拒绝」的口径照搬。
 *
 * <p>必要替换：
 * <ul>
 *   <li>参考实现的处理器是「模块路径 + 函数名」字符串、运行时 {@code import_module + getattr}
 *       惰性加载；本工程为「Java 类名 + 方法名」，经反射 + 容器取得服务实例后调用
 *       （模块级函数自建仓储 → 容器 bean，见 {@link SpringContext}）。惰性语义一致：
 *       定义只存字符串，目标类随对应模块照搬落地后即可解析；在那之前加载失败，
 *       由 publish/process 的 fail_pending 路径按"无法重建"收敛——与参考实现模块缺失同型。
 *   <li>钩子签名去掉 session 首参（参考实现接收会话以便同事务写库；本工程钩子在
 *       调用方事务内执行，仓储直接可用）：success 为 (record, result)、failure 为 (record, error)。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}。
 * </ul>
 */
public final class TaskRegistry {

    private TaskRegistry() {}

    /** 任务处理器：从 Task 意图重建并在租约保护下执行。 */
    @FunctionalInterface
    public interface TaskHandler {
        Object run(TaskService.TaskContext context) throws Exception;
    }

    /** 成功钩子：在同一事务内做领域收敛。 */
    @FunctionalInterface
    public interface TaskSuccessHandler {
        void apply(TaskRecord record, Object result) throws Exception;
    }

    /** 失败钩子：在同一事务内做领域收敛。 */
    @FunctionalInterface
    public interface TaskFailureHandler {
        void apply(TaskRecord record, String error) throws Exception;
    }

    /** 描述可持久重建的任务 Handler。 */
    public record TaskDefinition(
            String taskType,
            String module,
            String function,
            String successFunction,
            String failureFunction,
            int version) {

        public TaskHandler loadHandler() {
            ResolvedHandler resolved = resolve(function, "handler", TaskService.TaskContext.class);
            return context -> resolved.invoke(context);
        }

        public TaskSuccessHandler loadSuccessHandler() {
            if (successFunction == null) {
                return null;
            }
            ResolvedHandler resolved = resolve(successFunction, "success handler", TaskRecord.class, Object.class);
            return (record, result) -> resolved.invoke(record, result);
        }

        public TaskFailureHandler loadFailureHandler() {
            if (failureFunction == null) {
                return null;
            }
            ResolvedHandler resolved = resolve(failureFunction, "failure handler", TaskRecord.class, String.class);
            return (record, error) -> resolved.invoke(record, error);
        }

        /** 惰性加载并校验一个注册 Handler（类名 + 方法名 → 容器实例上的方法）。 */
        private ResolvedHandler resolve(String methodName, String label, Class<?>... parameterTypes) {
            Class<?> clazz;
            try {
                clazz = Class.forName(module);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(
                        "Task " + label + " is not callable: " + module + ":" + methodName, exception);
            }
            Method method;
            try {
                method = clazz.getMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(
                        "Task " + label + " is not callable: " + module + ":" + methodName, exception);
            }
            Object target = java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null : SpringContext.bean(clazz);
            return new ResolvedHandler(target, method);
        }
    }

    /** 已解析的处理器：目标实例（静态方法为 null）与方法。 */
    private record ResolvedHandler(Object target, Method method) {
        Object invoke(Object... args) throws Exception {
            return method.invoke(target, args);
        }
    }

    private static final Map<String, TaskDefinition> TASK_DEFINITIONS = buildDefinitions();

    private static Map<String, TaskDefinition> buildDefinitions() {
        Map<String, TaskDefinition> definitions = new LinkedHashMap<>();
        TaskDefinition[] values = {
            new TaskDefinition(
                    "knowledge_ingest",
                    "com.wisesoft.wenqu.service.KnowledgeTaskService",
                    "runKnowledgeIngest",
                    null,
                    "failKnowledgeFileTask",
                    1),
            new TaskDefinition(
                    "knowledge_parse",
                    "com.wisesoft.wenqu.service.KnowledgeTaskService",
                    "runKnowledgeParse",
                    null,
                    "failKnowledgeFileTask",
                    1),
            new TaskDefinition(
                    "knowledge_index",
                    "com.wisesoft.wenqu.service.KnowledgeTaskService",
                    "runKnowledgeIndex",
                    null,
                    "failKnowledgeFileTask",
                    1),
            new TaskDefinition(
                    "knowledge_graph_index",
                    "com.wisesoft.wenqu.service.KnowledgeTaskService",
                    "runKnowledgeGraph",
                    null,
                    null,
                    1),
            new TaskDefinition(
                    "knowledge_virtual_folder_migration",
                    "com.wisesoft.wenqu.service.KnowledgeTaskService",
                    "runVirtualFolderMigration",
                    null,
                    null,
                    1),
            new TaskDefinition(
                    "dataset_generation",
                    "com.wisesoft.wenqu.knowledge.eval.EvalTaskService",
                    "runDatasetGenerationTask",
                    "finishDatasetGenerationTask",
                    "failDatasetGenerationTask",
                    1),
            new TaskDefinition(
                    "rag_evaluation",
                    "com.wisesoft.wenqu.knowledge.eval.EvalTaskService",
                    "runRagEvaluationTask",
                    "finishRagEvaluationTask",
                    "failRagEvaluationTask",
                    1),
        };
        for (TaskDefinition definition : values) {
            definitions.put(definition.taskType(), definition);
        }
        return definitions;
    }

    /** 返回当前 shipping TaskDefinition，并拒绝未知类型或版本。 */
    public static TaskDefinition getTaskDefinition(String taskType, int handlerVersion) {
        TaskDefinition definition = TASK_DEFINITIONS.get(taskType);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown task type: " + taskType);
        }
        if (definition.version() != handlerVersion) {
            throw new IllegalArgumentException(
                    "Unsupported handler version for " + taskType + ": " + handlerVersion
                            + "; expected " + definition.version());
        }
        return definition;
    }

    public static TaskDefinition getTaskDefinition(String taskType) {
        return getTaskDefinition(taskType, 1);
    }

    /** 把 failure hook 适配为仓储事务前钩子（受检异常包装为非受检，使事务回滚，语义一致）。 */
    public static TaskRepository.BeforeTaskChange asBeforeChange(TaskFailureHandler handler, String error) {
        if (handler == null) {
            return null;
        }
        return record -> {
            try {
                handler.apply(record, error);
            } catch (Exception exception) {
                throw new IllegalStateException("failure hook 执行失败", exception);
            }
        };
    }

    /** 把 success hook 适配为仓储事务前钩子。 */
    public static TaskRepository.BeforeTaskChange asBeforeChange(TaskSuccessHandler handler, Object result) {
        if (handler == null) {
            return null;
        }
        return record -> {
            try {
                handler.apply(record, result);
            } catch (Exception exception) {
                throw new IllegalStateException("success hook 执行失败", exception);
            }
        };
    }

    /** 只为迁移生成的 legacy v0 复用当前 failure hook。 */
    public static TaskDefinition getFailureTaskDefinition(String taskType, int handlerVersion) {
        if (handlerVersion == 0) {
            return getTaskDefinition(taskType);
        }
        return getTaskDefinition(taskType, handlerVersion);
    }
}
