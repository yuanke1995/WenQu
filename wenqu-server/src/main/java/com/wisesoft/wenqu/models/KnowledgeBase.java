package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_bases
 * <p>
 * 由参考实现的 models_knowledge 中 KnowledgeBase 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("knowledge_bases")
public class KnowledgeBase {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("kb_id")
    private String kbId;  // 唯一，非空
    private String name;  // 非空
    private String description;
    @TableField("kb_type")
    private String kbType;  // 非空
    @TableField("embedding_model_spec")
    private String embeddingModelSpec;
    @TableField("llm_model_spec")
    private String llmModelSpec;
    @TableField("query_params")
    private String queryParams;
    @TableField("additional_params")
    private String additionalParams;
    @TableField("share_config")
    private String shareConfig;
    private String mindmap;
    @TableField("mindmap_file_ids")
    private String mindmapFileIds;
    @TableField("mindmap_metadata")
    private String mindmapMetadata;
    @TableField("sample_questions")
    private String sampleQuestions;
    @TableField("created_by")
    private String createdBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now
}
