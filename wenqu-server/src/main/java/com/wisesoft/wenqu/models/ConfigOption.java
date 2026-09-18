package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * config_options
 * <p>
 * 由参考实现的 models_business 中 ConfigOption 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("config_options")
public class ConfigOption {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String key;  // 唯一，非空
    private String name;  // 非空
    private String description;  // 非空，默认 ""
    private String params;  // 非空，默认 dict
    private String value;  // 非空，默认 dict
    @TableField("created_by")
    private String createdBy;
    @TableField("updated_by")
    private String updatedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
