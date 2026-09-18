package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * mcp_servers
 * <p>
 * 由参考实现的 models_business 中 MCPServer 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("mcp_servers")
public class MCPServer {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String slug;  // 唯一，非空
    private String name;  // 非空
    private String description;
    private String transport;  // 非空
    private String url;
    private String command;
    private String args;
    private String env;
    private String headers;
    private Integer timeout;
    @TableField("sse_read_timeout")
    private Integer sseReadTimeout;
    private String tags;
    private String icon;
    private Integer enabled;  // 非空，默认 1
    @TableField("disabled_tools")
    private String disabledTools;
    @TableField("created_by")
    private String createdBy;  // 非空
    @TableField("updated_by")
    private String updatedBy;  // 非空
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
