package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * projects
 * <p>
 * 由参考实现的 models_business 中 Project 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("projects")
public class Project {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;  // 主键
    private String uid;  // 外键 → users.uid，非空
    private String name;
    @TableField("selection_status")
    private String selectionStatus;  // 非空
    @TableField("workdir_path")
    private String workdirPath;  // 非空
    @TableField("directory_mode")
    private String directoryMode;  // 非空
    private String status;  // 非空，默认 "active"
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 非空，默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 非空，默认 utc_now_naive
}
