package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 编排知识库历史虚拟目录迁移。
 *
 * <p>由参考实现的 services/knowledge_folder_service.py 逐方法翻译：按可续跑批次迁移全部
 * 路径型历史记录，进度/结果/取消全部经 TaskContext 的租约边界写入。
 *
 * <p>必要替换：参考实现的 {@code migrate_batch(session, _task_record)} 闭包在本工程为
 * 直接调用仓储方法（{@code run_owned_transaction} 的操作体已在 owner 行锁事务内，
 * 仓储方法以 REQUIRED 传播加入同一事务）。
 */
@Service
public class KnowledgeFolderService {

    /** 参考实现的默认批大小（migrate_virtual_folder_batch 的 batch_size 缺省值）。 */
    private static final int BATCH_SIZE = 500;

    private final KnowledgeFileRepository repository;

    public KnowledgeFolderService(KnowledgeFileRepository repository) {
        this.repository = repository;
    }

    /** 检测知识库是否仍有路径型历史记录。 */
    public Map<String, Object> detectVirtualFolderData(String kbId) {
        return repository.detectVirtualFolderData(kbId);
    }

    /** 按可续跑批次迁移全部历史路径记录。 */
    public Map<String, Object> migrateVirtualFolderData(
            TaskService.TaskContext context, String kbId, String operatorId) throws Exception {
        Map<String, Object> initial = repository.detectVirtualFolderData(kbId);
        long initialSteps = ((Number) initial.get("remaining_steps")).longValue();
        long processedSteps = 0;
        long createdFolders = 0;
        Set<String> conflictFileIds = new LinkedHashSet<>();
        String cursor = null;
        long passProgress = 0;

        while (true) {
            context.raiseIfCancelled();
            // 单批次迁移在 owner 行锁事务内执行（对应参考实现的 migrate_batch 闭包）
            final String afterFileId = cursor;
            Map<String, Object> batch =
                    new LinkedHashMap<>();
            context.runOwnedTransaction(
                    record -> batch.putAll(repository.migrateVirtualFolderBatch(kbId, operatorId, afterFileId, BATCH_SIZE)));

            long scanned = ((Number) batch.get("scanned")).longValue();
            if (scanned == 0) {
                if (passProgress == 0) {
                    break;
                }
                cursor = null;
                passProgress = 0;
                continue;
            }

            cursor = (String) batch.get("last_file_id");
            long processed = ((Number) batch.get("processed")).longValue();
            processedSteps += processed;
            passProgress += processed;
            createdFolders += ((Number) batch.get("created_folders")).longValue();
            @SuppressWarnings("unchecked")
            Iterable<String> conflicts = (Iterable<String>) batch.get("conflict_file_ids");
            if (conflicts != null) {
                conflicts.forEach(conflictFileIds::add);
            }
            double progress =
                    initialSteps == 0 ? 100.0 : Math.min(processedSteps * 100.0 / initialSteps, 99.0);
            context.setProgress(progress, "已转换 " + processedSteps + "/" + initialSteps + " 个目录层级");
        }

        Map<String, Object> remaining = repository.detectVirtualFolderData(kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed_steps", processedSteps);
        result.put("created_folders", createdFolders);
        result.put("conflict_files", conflictFileIds.size());
        result.put("remaining_files", ((Number) remaining.get("file_count")).longValue());
        context.setResult(result);
        String message =
                ((Number) result.get("remaining_files")).longValue() != 0
                        ? "转换结束，仍有 " + result.get("remaining_files") + " 个冲突文件"
                        : "历史虚拟文件夹转换完成";
        context.setProgress(100.0, message);
        return result;
    }
}
