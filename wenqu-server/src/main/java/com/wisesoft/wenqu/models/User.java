package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * users
 * <p>
 * 由参考实现的 models_business 中 User 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("users")
public class User {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String username;  // 唯一，非空
    private String uid;  // 唯一，非空
    @TableField("phone_number")
    private String phoneNumber;  // 唯一
    private String avatar;
    @TableField("password_hash")
    private String passwordHash;  // 非空
    private String role;  // 非空，默认 "user"
    @TableField("department_id")
    private Integer departmentId;  // 外键 → departments.id
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("last_login")
    private LocalDateTime lastLogin;
    @TableField("login_failed_count")
    private Integer loginFailedCount;  // 非空，默认 0
    @TableField("last_failed_login")
    private LocalDateTime lastFailedLogin;
    @TableField("login_locked_until")
    private LocalDateTime loginLockedUntil;
    @TableField("is_deleted")
    private Integer isDeleted;  // 非空，默认 0
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
