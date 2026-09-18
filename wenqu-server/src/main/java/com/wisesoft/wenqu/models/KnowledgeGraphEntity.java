package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_graph_entities
 * <p>
 * 由参考实现的 models_knowledge 中 KnowledgeGraphEntity 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("knowledge_graph_entities")
public class KnowledgeGraphEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("entity_id")
    private String entityId;  // 非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("normalized_name")
    private String normalizedName;  // 非空
    private String label;  // 非空
    private String name;  // 非空
    private String attributes;
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
