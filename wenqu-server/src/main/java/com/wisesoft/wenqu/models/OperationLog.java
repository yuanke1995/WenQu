package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * operation_logs
 * <p>
 * 由参考实现的 models_business 中 OperationLog 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("operation_logs")
public class OperationLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("user_id")
    private Integer userId;  // 外键 → users.id，非空
    private String operation;  // 非空
    private String details;
    @TableField("ip_address")
    private String ipAddress;
    private LocalDateTime timestamp;  // 默认 utc_now_naive
}
