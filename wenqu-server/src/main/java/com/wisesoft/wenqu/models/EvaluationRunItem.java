package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evaluation_run_items
 * <p>
 * 由参考实现的 models_knowledge 中 EvaluationRunItem 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("evaluation_run_items")
public class EvaluationRunItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("run_id")
    private String runId;
    @TableField("dataset_item_id")
    private String datasetItemId;
    @TableField("item_index")
    private Integer itemIndex;  // 非空
    @TableField("query_text")
    private String queryText;  // 非空
    @TableField("gold_chunk_ids")
    private String goldChunkIds;
    @TableField("gold_answer")
    private String goldAnswer;
    @TableField("generated_answer")
    private String generatedAnswer;
    @TableField("retrieved_chunks")
    private String retrievedChunks;
    private String metrics;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
}
