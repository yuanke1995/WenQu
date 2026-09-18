package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * cli_auth_sessions
 * <p>
 * 由参考实现的 models_business 中 CLIAuthSession 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("cli_auth_sessions")
public class CLIAuthSession {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("device_code_hash")
    private String deviceCodeHash;  // 唯一，非空
    @TableField("user_code")
    private String userCode;  // 唯一，非空
    private String status;  // 非空，默认 "pending"
    @TableField("key_name")
    private String keyName;  // 非空
    @TableField("approved_user_id")
    private Integer approvedUserId;  // 外键 → users.id
    @TableField("api_key_id")
    private Integer apiKeyId;  // 外键 → api_keys.id
    @TableField("created_at")
    private LocalDateTime createdAt;  // 非空，默认 utc_now_naive
    @TableField("expires_at")
    private LocalDateTime expiresAt;  // 非空
    @TableField("approved_at")
    private LocalDateTime approvedAt;
    @TableField("consumed_at")
    private LocalDateTime consumedAt;
}
