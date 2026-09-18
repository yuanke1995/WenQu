package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * api_keys
 * <p>
 * 由参考实现的 models_business 中 APIKey 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("api_keys")
public class APIKey {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("key_hash")
    private String keyHash;  // 唯一，非空
    @TableField("key_prefix")
    private String keyPrefix;  // 非空
    @TableField("request_id")
    private String requestId;  // 唯一
    @TableField("intent_hash")
    private String intentHash;
    private String name;  // 非空
    @TableField("user_id")
    private Integer userId;  // 外键 → users.id，非空
    @TableField("department_id")
    private Integer departmentId;  // 外键 → departments.id
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("is_enabled")
    private Boolean isEnabled;  // 非空，默认 True
    @TableField("revoked_at")
    private LocalDateTime revokedAt;
    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;
    @TableField("created_by")
    private String createdBy;  // 非空
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
}
