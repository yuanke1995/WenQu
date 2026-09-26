package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人 MCP Server（Model Context Protocol）
 * <p>
 * 原「全局配置 mcp.servers（JSON 数组）」下沉为按用户登记的独立记录：每人连自己的服务、
 * 只把服务里的工具暴露给自己的问答。连接的是外部进程，地址由用户自己填，
 * 连接失败仅跳过该服务（不影响问答主链路）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_mcp")
public class UserMcp {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户 uid */
    private String uid;

    /** 服务显示名（个人维度唯一；作为工具名前缀防冲突） */
    private String name;

    /** 服务地址（可含路径） */
    private String url;

    /** 传输类型：streamable | sse */
    private String type;

    /** 启用：1=启用 0=停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
