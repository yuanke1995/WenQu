package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * departments
 * <p>
 * 由参考实现的 models_business 中 Department 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("departments")
public class Department {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String name;  // 唯一，非空
    private String description;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
}
