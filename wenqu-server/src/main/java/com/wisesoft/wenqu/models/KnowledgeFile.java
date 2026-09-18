package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_files
 * <p>
 * 由参考实现的 models_knowledge 中 KnowledgeFile 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("knowledge_files")
public class KnowledgeFile {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("file_id")
    private String fileId;  // 唯一，非空
    @TableField("kb_id")
    private String kbId;  // 外键 → knowledge_bases.kb_id，非空
    @TableField("parent_id")
    private String parentId;  // 外键 → knowledge_files.file_id
    private String filename;  // 非空
    @TableField("original_filename")
    private String originalFilename;
    @TableField("file_type")
    private String fileType;
    private String path;
    @TableField("minio_url")
    private String minioUrl;
    @TableField("markdown_file")
    private String markdownFile;
    private String status;  // 默认 "uploaded"
    @TableField("content_hash")
    private String contentHash;
    @TableField("file_size")
    private Long fileSize;
    @TableField("chunk_count")
    private Integer chunkCount;  // 默认 0
    @TableField("token_count")
    private Long tokenCount;  // 默认 0
    @TableField("content_type")
    private String contentType;
    @TableField("processing_params")
    private String processingParams;
    @TableField("is_folder")
    private Boolean isFolder;  // 默认 False
    @TableField("error_message")
    private String errorMessage;
    @TableField("processing_task_id")
    private String processingTaskId;
    @TableField("processing_owner")
    private String processingOwner;
    @TableField("created_by")
    private String createdBy;
    @TableField("updated_by")
    private String updatedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now
}
