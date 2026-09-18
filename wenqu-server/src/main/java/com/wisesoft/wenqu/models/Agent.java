package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * agents
 * <p>
 * 由参考实现的 models_business 中 Agent 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("agents")
public class Agent {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String slug;  // 唯一，非空
    @TableField("backend_id")
    private String backendId;  // 非空
    private String name;  // 非空
    private String description;
    private String icon;
    private String pics;  // 非空，默认 list
    @TableField("config_json")
    private String configJson;  // 非空，默认 dict
    @TableField("share_config")
    private String shareConfig;  // 非空
    @TableField("is_default")
    private Boolean isDefault;  // 非空，默认 False
    @TableField("is_subagent")
    private Boolean isSubagent;  // 非空，默认 False
    @TableField("created_by")
    private String createdBy;
    @TableField("updated_by")
    private String updatedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
