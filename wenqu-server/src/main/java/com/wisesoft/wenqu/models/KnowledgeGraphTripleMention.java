package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_graph_triple_mentions
 * <p>
 * 由参考实现的 models_knowledge 中 KnowledgeGraphTripleMention 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("knowledge_graph_triple_mentions")
public class KnowledgeGraphTripleMention {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("triple_id")
    private String tripleId;  // 外键 → knowledge_graph_triples.triple_id，非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("file_id")
    private String fileId;  // 外键 → knowledge_files.file_id，非空
    @TableField("chunk_id")
    private String chunkId;  // 外键 → knowledge_chunks.chunk_id，非空
    private String text;
    @TableField("extractor_type")
    private String extractorType;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
}
