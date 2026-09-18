package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evaluation_dataset_items
 * <p>
 * 由参考实现的 models_knowledge 中 EvaluationDatasetItem 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("evaluation_dataset_items")
public class EvaluationDatasetItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("item_id")
    private String itemId;  // 唯一，非空
    @TableField("dataset_id")
    private String datasetId;
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("item_index")
    private Integer itemIndex;  // 非空
    @TableField("query_text")
    private String queryText;  // 非空
    @TableField("gold_chunk_ids")
    private String goldChunkIds;
    @TableField("gold_answer")
    private String goldAnswer;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
}
