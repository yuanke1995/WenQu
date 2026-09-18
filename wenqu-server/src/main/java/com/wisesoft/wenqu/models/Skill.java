package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * skills
 * <p>
 * 由参考实现的 models_business 中 Skill 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("skills")
public class Skill {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String slug;  // 唯一，非空
    private String name;  // 非空
    private String description;  // 非空
    @TableField("source_type")
    private String sourceType;
    @TableField("tool_dependencies")
    private String toolDependencies;  // 非空，默认 list
    @TableField("mcp_dependencies")
    private String mcpDependencies;  // 非空，默认 list
    @TableField("skill_dependencies")
    private String skillDependencies;  // 非空，默认 list
    @TableField("dir_path")
    private String dirPath;  // 非空
    private String version;
    @TableField("content_hash")
    private String contentHash;
    @TableField("share_config")
    private String shareConfig;  // 非空
    private Boolean enabled;  // 非空，默认 True
    @TableField("created_by")
    private String createdBy;
    @TableField("updated_by")
    private String updatedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
