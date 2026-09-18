package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evaluation_datasets
 * <p>
 * 由参考实现的 models_knowledge 中 EvaluationDataset 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("evaluation_datasets")
public class EvaluationDataset {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("dataset_id")
    private String datasetId;  // 唯一，非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    private String name;  // 非空
    private String description;
    @TableField("item_count")
    private Integer itemCount;  // 默认 0
    @TableField("has_gold_chunks")
    private Boolean hasGoldChunks;  // 默认 False
    @TableField("has_gold_answers")
    private Boolean hasGoldAnswers;  // 默认 False
    @TableField("build_metadata")
    private String buildMetadata;
    @TableField("created_by")
    private String createdBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now
}
