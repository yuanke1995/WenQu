package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_graph_triples
 * <p>
 * 由参考实现的 models_knowledge 中 KnowledgeGraphTriple 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("knowledge_graph_triples")
public class KnowledgeGraphTriple {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("triple_id")
    private String tripleId;  // 非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("source_entity_id")
    private String sourceEntityId;
    @TableField("target_entity_id")
    private String targetEntityId;
    @TableField("relation_type")
    private String relationType;  // 非空
    private String content;  // 非空
    @TableField("vector_status")
    private String vectorStatus;  // 非空，默认 "pending"
    @TableField("vector_attempt_count")
    private Integer vectorAttemptCount;  // 非空，默认 0
    @TableField("vector_last_error")
    private String vectorLastError;
    @TableField("vector_next_retry_at")
    private LocalDateTime vectorNextRetryAt;
    @TableField("vector_locked_until")
    private LocalDateTime vectorLockedUntil;
    @TableField("vector_lock_token")
    private String vectorLockToken;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now
}
