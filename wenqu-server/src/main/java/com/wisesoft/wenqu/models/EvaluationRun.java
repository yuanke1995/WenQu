package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evaluation_runs
 * <p>
 * 由参考实现的 models_knowledge 中 EvaluationRun 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("evaluation_runs")
public class EvaluationRun {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("run_id")
    private String runId;  // 唯一，非空
    private String name;  // 非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("dataset_id")
    private String datasetId;
    private String status;  // 默认 "running"
    @TableField("retrieval_config")
    private String retrievalConfig;
    private String metrics;
    @TableField("overall_score")
    private Double overallScore;
    @TableField("total_items")
    private Integer totalItems;  // 默认 0
    @TableField("completed_items")
    private Integer completedItems;  // 默认 0
    @TableField("started_at")
    private LocalDateTime startedAt;  // 默认 utc_now
    @TableField("completed_at")
    private LocalDateTime completedAt;
    @TableField("created_by")
    private String createdBy;
}
