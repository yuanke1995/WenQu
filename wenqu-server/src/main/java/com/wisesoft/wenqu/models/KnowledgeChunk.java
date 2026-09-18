package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_chunks
 * <p>
 * 由参考实现的 models_knowledge 中 KnowledgeChunk 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("knowledge_chunks")
public class KnowledgeChunk {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("chunk_id")
    private String chunkId;  // 非空
    @TableField("file_id")
    private String fileId;  // 外键 → knowledge_files.file_id，非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("chunk_index")
    private Integer chunkIndex;  // 非空
    private String content;  // 非空
    @TableField("start_char_pos")
    private Integer startCharPos;
    @TableField("end_char_pos")
    private Integer endCharPos;
    @TableField("start_token_pos")
    private Integer startTokenPos;
    @TableField("end_token_pos")
    private Integer endTokenPos;
    @TableField("graph_structure_indexed")
    private Boolean graphStructureIndexed;  // 非空，默认 False
    @TableField("graph_indexed")
    private Boolean graphIndexed;  // 默认 False
    @TableField("graph_extraction_details")
    private String graphExtractionDetails;
    @TableField("ent_ids")
    private String entIds;
    private String tags;
    @TableField("extraction_result")
    private String extractionResult;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now
}
