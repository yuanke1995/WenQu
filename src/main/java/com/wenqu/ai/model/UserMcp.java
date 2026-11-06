package com.wenqu.ai.model;

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

    /**
     * 平台级行的归属哨兵前缀：后面拼租户 id（{@code __platform__:huazhang}）。
     * <p>为什么必须带租户：{@code uk_uid_name} 是 {@code (uid, name)}，不含 tenant_id——
     * 平台行在每个租户各一份、服务名又都是「浏览器操作」，不拼租户就会在第二个租户上撞唯一键。
     * 前缀本身保证它不等于任何登录 uid，与个人资产不撞归属。
     */
    public static final String PLATFORM_UID = "__platform__";

    /** 归属维度：平台为全员提供（管理员维护、不可删除、个人只读） */
    public static final String SCOPE_PLATFORM = "platform";

    /** 归属维度：用户自己登记的个人资产（默认，也是存量行的取值） */
    public static final String SCOPE_PERSONAL = "personal";

    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户 uid（平台级行为 {@link #PLATFORM_UID}） */
    private String uid;

    /**
     * 归属维度：{@code platform}=平台为全员提供，{@code personal}=本人登记。
     * <p>按 uid 隔离是正确的默认（别人的服务不能顶替自己的），但「浏览器操作」这类平台能力
     * 若要求每人先去面板填一次地址，智能体按名字指定的服务就会对没填的人落空。
     * 平台行让全员可读可用，写路径只放管理员。
     */
    private String scope;

    /** 服务显示名（个人维度唯一；作为工具名前缀防冲突） */
    private String name;

    /** 服务地址（可含路径） */
    private String url;

    /** 传输类型：streamable | sse */
    private String type;

    /**
     * 自定义请求头（JSON 对象，整块 JWE 信封加密存储，{@code JWE:} 前缀）。
     * <p>实体层只存密文：加解密只在 service 层进出边界发生，接口回显只给键名。
     */
    private String headers;

    /** 启用：1=启用 0=停用 */
    private Integer enabled;

    /**
     * 工具中文名（JSON 文本）：{@code {"items":{"裸工具名":"中文名"}}}。
     * <p>由用户在 MCP 页的工具清单里逐条手工起名（写 {@code McpToolLabelService}），
     * 没起名的工具展示原名。MCP 动态工具不在注册中心登记，中文名只能落在这张表里随卡下发。
     */
    private String toolLabels;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
