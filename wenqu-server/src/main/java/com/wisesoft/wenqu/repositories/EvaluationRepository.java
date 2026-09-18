package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.EvaluationDataset;
import com.wisesoft.wenqu.models.EvaluationDatasetItem;
import com.wisesoft.wenqu.models.EvaluationRun;
import com.wisesoft.wenqu.models.EvaluationRunItem;
import com.wisesoft.wenqu.repository.port.EvaluationDatasetItemMapper;
import com.wisesoft.wenqu.repository.port.EvaluationDatasetMapper;
import com.wisesoft.wenqu.repository.port.EvaluationRunItemMapper;
import com.wisesoft.wenqu.repository.port.EvaluationRunMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测仓储。
 *
 * <p>由参考实现的 repositories/evaluation_repository.py 逐方法翻译：数据集/数据项与
 * 评测运行/运行项的增删查改、关联生成任务（不覆盖同任务的终态、清除旧错误信息）、
 * 运行项按 (run_id, item_index) 的 upsert、以及清空全部评测数据。
 *
 * <p>必要替换说明：参考实现区分"在调用方事务中执行"（*_in_session 静态方法）与
 * "自带事务执行"两种入口。本工程对应关系为——{@code xxxInSession} 系列不标注事务
 * （由调用方开启，等价于复用调用方会话），无后缀的方法标注 {@code @Transactional}
 * （等价于参考实现的开独立会话）。行锁 {@code with_for_update()} → {@code FOR UPDATE}。
 */
@Repository
public class EvaluationRepository {

    private final EvaluationDatasetMapper datasetMapper;
    private final EvaluationDatasetItemMapper datasetItemMapper;
    private final EvaluationRunMapper runMapper;
    private final EvaluationRunItemMapper runItemMapper;

    public EvaluationRepository(
            EvaluationDatasetMapper datasetMapper,
            EvaluationDatasetItemMapper datasetItemMapper,
            EvaluationRunMapper runMapper,
            EvaluationRunItemMapper runItemMapper) {
        this.datasetMapper = datasetMapper;
        this.datasetItemMapper = datasetItemMapper;
        this.runMapper = runMapper;
        this.runItemMapper = runItemMapper;
    }

    /** 在调用方事务中创建数据集。 */
    public EvaluationDataset createDatasetInSession(Map<String, Object> datasetData) {
        EvaluationDataset dataset = new EvaluationDataset();
        applyDatasetFields(dataset, datasetData);
        if (dataset.getCreatedAt() == null) {
            dataset.setCreatedAt(DateTimeUtils.utcNowNaive());
        }
        if (dataset.getUpdatedAt() == null) {
            dataset.setUpdatedAt(DateTimeUtils.utcNowNaive());
        }
        datasetMapper.insert(dataset);
        return dataset;
    }

    /** 创建数据集及其数据项（同一事务）。 */
    @Transactional
    public EvaluationDataset createDatasetWithItems(
            Map<String, Object> datasetData, List<Map<String, Object>> itemsData) {
        EvaluationDataset dataset = createDatasetInSession(datasetData);
        addDatasetItemsInSession(itemsData);
        return dataset;
    }

    /** 在调用方事务中关联生成 Task，且不覆盖相同 Task 的终态。 */
    public EvaluationDataset attachDatasetGenerationTaskInSession(String datasetId, String taskId) {
        EvaluationDataset record =
                datasetMapper.selectOne(
                        new LambdaQueryWrapper<EvaluationDataset>()
                                .eq(EvaluationDataset::getDatasetId, datasetId)
                                .last("FOR UPDATE"));
        if (record == null) {
            return null;
        }
        Map<String, Object> metadata = RepoValues.parseObject(record.getBuildMetadata());
        if (!"generated".equals(String.valueOf(metadata.get("source")))
                || "completed".equals(String.valueOf(metadata.get("status")))) {
            return record;
        }
        if (java.util.Objects.equals(String.valueOf(metadata.get("task_id")), String.valueOf(taskId))) {
            return record;
        }
        metadata.put("task_id", taskId);
        metadata.put("status", "pending");
        metadata.put("message", "等待 worker 执行");
        metadata.remove("error_message");
        datasetMapper.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getDatasetId, datasetId)
                        .set(EvaluationDataset::getBuildMetadata, com.alibaba.fastjson2.JSON.toJSONString(metadata)));
        record.setBuildMetadata(com.alibaba.fastjson2.JSON.toJSONString(metadata));
        return record;
    }

    /** 在调用方事务中更新数据集。 */
    public EvaluationDataset updateDatasetInSession(String datasetId, Map<String, Object> data) {
        EvaluationDataset record =
                datasetMapper.selectOne(
                        new LambdaQueryWrapper<EvaluationDataset>()
                                .eq(EvaluationDataset::getDatasetId, datasetId)
                                .last("FOR UPDATE"));
        if (record == null) {
            return null;
        }
        applyDatasetFields(record, data);
        datasetMapper.updateById(record);
        return record;
    }

    /** 更新数据集（自带事务）。 */
    @Transactional
    public EvaluationDataset updateDataset(String datasetId, Map<String, Object> data) {
        return updateDatasetInSession(datasetId, data);
    }

    /** 在调用方事务中批量新增数据项。 */
    public void addDatasetItemsInSession(List<Map<String, Object>> itemsData) {
        for (Map<String, Object> itemData : itemsData) {
            EvaluationDatasetItem item = new EvaluationDatasetItem();
            applyDatasetItemFields(item, itemData);
            if (item.getCreatedAt() == null) {
                item.setCreatedAt(DateTimeUtils.utcNowNaive());
            }
            datasetItemMapper.insert(item);
        }
    }

    public EvaluationDataset getDataset(String datasetId) {
        return datasetMapper.selectOne(
                new LambdaQueryWrapper<EvaluationDataset>().eq(EvaluationDataset::getDatasetId, datasetId));
    }

    public List<EvaluationDataset> listDatasets(String kbId) {
        return datasetMapper.selectList(
                new LambdaQueryWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getKbId, kbId)
                        .orderByDesc(EvaluationDataset::getCreatedAt));
    }

    public List<EvaluationDatasetItem> listDatasetItems(String datasetId, int offset, int limit) {
        return datasetItemMapper.selectList(
                new LambdaQueryWrapper<EvaluationDatasetItem>()
                        .eq(EvaluationDatasetItem::getDatasetId, datasetId)
                        .orderByAsc(EvaluationDatasetItem::getItemIndex)
                        .last("LIMIT " + limit + " OFFSET " + offset));
    }

    public int countDatasetItems(String datasetId) {
        Long count =
                datasetItemMapper.selectCount(
                        new LambdaQueryWrapper<EvaluationDatasetItem>().eq(EvaluationDatasetItem::getDatasetId, datasetId));
        return count == null ? 0 : count.intValue();
    }

    public List<EvaluationDatasetItem> listAllDatasetItems(String datasetId) {
        return datasetItemMapper.selectList(
                new LambdaQueryWrapper<EvaluationDatasetItem>()
                        .eq(EvaluationDatasetItem::getDatasetId, datasetId)
                        .orderByAsc(EvaluationDatasetItem::getItemIndex));
    }

    public void deleteDataset(String datasetId) {
        EvaluationDataset record = getDataset(datasetId);
        if (record != null) {
            datasetMapper.deleteById(record.getId());
        }
    }

    /** 在调用方事务中创建评估运行。 */
    public EvaluationRun createRunInSession(Map<String, Object> data) {
        EvaluationRun run = new EvaluationRun();
        applyRunFields(run, data);
        if (run.getStartedAt() == null) {
            run.setStartedAt(DateTimeUtils.utcNowNaive());
        }
        runMapper.insert(run);
        return run;
    }

    public EvaluationRun getRun(String runId) {
        return runMapper.selectOne(new LambdaQueryWrapper<EvaluationRun>().eq(EvaluationRun::getRunId, runId));
    }

    public List<EvaluationRun> listRuns(String kbId) {
        return runMapper.selectList(
                new LambdaQueryWrapper<EvaluationRun>()
                        .eq(EvaluationRun::getKbId, kbId)
                        .orderByDesc(EvaluationRun::getStartedAt));
    }

    /** 在调用方事务中更新运行。 */
    public EvaluationRun updateRunInSession(String runId, Map<String, Object> data) {
        EvaluationRun record =
                runMapper.selectOne(
                        new LambdaQueryWrapper<EvaluationRun>().eq(EvaluationRun::getRunId, runId).last("FOR UPDATE"));
        if (record == null) {
            return null;
        }
        applyRunFields(record, data);
        runMapper.updateById(record);
        return record;
    }

    /** 更新运行（自带事务）。 */
    @Transactional
    public EvaluationRun updateRun(String runId, Map<String, Object> data) {
        return updateRunInSession(runId, data);
    }

    /** 删除运行及其运行项。 */
    @Transactional
    public void deleteRun(String runId) {
        runItemMapper.delete(new LambdaQueryWrapper<EvaluationRunItem>().eq(EvaluationRunItem::getRunId, runId));
        EvaluationRun record = getRun(runId);
        if (record != null) {
            runMapper.deleteById(record.getId());
        }
    }

    /** 在调用方事务中按 (run_id, item_index) upsert 运行项。 */
    public EvaluationRunItem upsertRunItemInSession(String runId, int itemIndex, Map<String, Object> data) {
        EvaluationRunItem record =
                runItemMapper.selectOne(
                        new LambdaQueryWrapper<EvaluationRunItem>()
                                .eq(EvaluationRunItem::getRunId, runId)
                                .eq(EvaluationRunItem::getItemIndex, itemIndex)
                                .last("FOR UPDATE"));
        if (record == null) {
            record = new EvaluationRunItem();
            record.setRunId(runId);
            record.setItemIndex(itemIndex);
            applyRunItemFields(record, data);
            if (record.getCreatedAt() == null) {
                record.setCreatedAt(DateTimeUtils.utcNowNaive());
            }
            runItemMapper.insert(record);
        } else {
            applyRunItemFields(record, data);
            runItemMapper.updateById(record);
        }
        return record;
    }

    public List<EvaluationRunItem> listRunItems(String runId, int offset, int limit) {
        return runItemMapper.selectList(
                new LambdaQueryWrapper<EvaluationRunItem>()
                        .eq(EvaluationRunItem::getRunId, runId)
                        .orderByAsc(EvaluationRunItem::getItemIndex)
                        .last("LIMIT " + limit + " OFFSET " + offset));
    }

    public int countRunItems(String runId) {
        Long count =
                runItemMapper.selectCount(new LambdaQueryWrapper<EvaluationRunItem>().eq(EvaluationRunItem::getRunId, runId));
        return count == null ? 0 : count.intValue();
    }

    /** 清空全部评测数据（顺序：运行项 → 运行 → 数据项 → 数据集）。 */
    @Transactional
    public void deleteAll() {
        runItemMapper.delete(new LambdaQueryWrapper<>());
        runMapper.delete(new LambdaQueryWrapper<>());
        datasetItemMapper.delete(new LambdaQueryWrapper<>());
        datasetMapper.delete(new LambdaQueryWrapper<>());
    }

    // ==================== 字段写入 ====================

    private static void applyDatasetFields(EvaluationDataset dataset, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "dataset_id" -> dataset.setDatasetId(RepoValues.asString(value));
                case "kb_id" -> dataset.setKbId(RepoValues.asString(value));
                case "name" -> dataset.setName(RepoValues.asString(value));
                case "description" -> dataset.setDescription(RepoValues.asString(value));
                case "item_count" -> dataset.setItemCount(RepoValues.toInt(value));
                case "has_gold_chunks" -> dataset.setHasGoldChunks(RepoValues.toBoolean(value));
                case "has_gold_answers" -> dataset.setHasGoldAnswers(RepoValues.toBoolean(value));
                case "build_metadata" -> dataset.setBuildMetadata(RepoValues.toJsonText(value));
                case "created_by" -> dataset.setCreatedBy(RepoValues.asString(value));
                case "created_at" -> dataset.setCreatedAt(RepoValues.toLocalDateTime(value));
                case "updated_at" -> dataset.setUpdatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键忽略
                }
            }
        }
    }

    private static void applyDatasetItemFields(EvaluationDatasetItem item, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "item_id" -> item.setItemId(RepoValues.asString(value));
                case "dataset_id" -> item.setDatasetId(RepoValues.asString(value));
                case "kb_id" -> item.setKbId(RepoValues.asString(value));
                case "item_index" -> item.setItemIndex(RepoValues.toInt(value));
                case "query_text" -> item.setQueryText(RepoValues.asString(value));
                case "gold_chunk_ids" -> item.setGoldChunkIds(RepoValues.toJsonText(value));
                case "gold_answer" -> item.setGoldAnswer(RepoValues.asString(value));
                case "created_at" -> item.setCreatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键忽略
                }
            }
        }
    }

    private static void applyRunFields(EvaluationRun run, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "run_id" -> run.setRunId(RepoValues.asString(value));
                case "name" -> run.setName(RepoValues.asString(value));
                case "kb_id" -> run.setKbId(RepoValues.asString(value));
                case "dataset_id" -> run.setDatasetId(RepoValues.asString(value));
                case "status" -> run.setStatus(RepoValues.asString(value));
                case "retrieval_config" -> run.setRetrievalConfig(RepoValues.toJsonText(value));
                case "metrics" -> run.setMetrics(RepoValues.toJsonText(value));
                case "overall_score" -> run.setOverallScore(value == null ? null : ((Number) value).doubleValue());
                case "total_items" -> run.setTotalItems(RepoValues.toInt(value));
                case "completed_items" -> run.setCompletedItems(RepoValues.toInt(value));
                case "started_at" -> run.setStartedAt(RepoValues.toLocalDateTime(value));
                case "completed_at" -> run.setCompletedAt(RepoValues.toLocalDateTime(value));
                case "created_by" -> run.setCreatedBy(RepoValues.asString(value));
                default -> {
                    // 未知键忽略
                }
            }
        }
    }

    private static void applyRunItemFields(EvaluationRunItem item, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "run_id" -> item.setRunId(RepoValues.asString(value));
                case "dataset_item_id" -> item.setDatasetItemId(RepoValues.asString(value));
                case "item_index" -> item.setItemIndex(RepoValues.toInt(value));
                case "query_text" -> item.setQueryText(RepoValues.asString(value));
                case "gold_chunk_ids" -> item.setGoldChunkIds(RepoValues.toJsonText(value));
                case "gold_answer" -> item.setGoldAnswer(RepoValues.asString(value));
                case "generated_answer" -> item.setGeneratedAnswer(RepoValues.asString(value));
                case "retrieved_chunks" -> item.setRetrievedChunks(RepoValues.toJsonText(value));
                case "metrics" -> item.setMetrics(RepoValues.toJsonText(value));
                case "created_at" -> item.setCreatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键忽略
                }
            }
        }
    }
}
