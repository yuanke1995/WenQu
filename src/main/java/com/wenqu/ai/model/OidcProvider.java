package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OIDC 认证源表（多 IdP）：把原先 c_ai_config 里 20 个扁平 oidc.* 键搬成「每行一个认证源」。
 * <p>
 * 形状照搬 {@link Provider}（模型供应商）：client_secret 以 RSA 密文存储（复用 {@code ConfigCryptoService}），
 * 列表回显只脱敏后 4 位，管理端点走管理员判定。一个 IdP = 一行；登录页按 sort_order 顺序渲染入口按钮。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_oidc_provider")
public class OidcProvider {

    /** 主键ID (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 认证源名称（登录按钮文案） */
    private String name;

    /** 启用: 1=启用 0=停用 */
    private Integer enabled;

    /** IdP issuer（discovery 源） */
    private String issuerUrl;

    /** 客户端 ID */
    private String clientId;

    /** 客户端密钥（RSA 密文，RSA: 前缀） */
    private String clientSecret;

    /** 回调地址（空=按访问来源推导） */
    private String redirectUri;

    /** 登录后前端跳转基地址（空=同源相对路径） */
    private String frontendBaseUrl;

    /** 授权端点（空=discovery） */
    private String authorizationEndpoint;

    /** 令牌端点（空=discovery） */
    private String tokenEndpoint;

    /** 用户信息端点（空=discovery） */
    private String userinfoEndpoint;

    /** 请求 scope（空格分隔） */
    private String scopes;

    /** 授权请求带 prompt=login */
    private Integer forcePromptLogin;

    /** 用户名字段 */
    private String usernameClaim;

    /** 显示名字段 */
    private String nameClaim;

    /** 用 IdP 用户名当账号标识 */
    private Integer useRawUsername;

    /** 首次登录自动建号 */
    private Integer autoCreateUser;

    /** 自动建档默认角色 */
    private String defaultRole;

    /** 自动建档默认部门 */
    private String defaultDepartment;

    /** 从 userinfo 同步部门 */
    private Integer fetchDepartmentInfo;

    /** 部门映射字段 */
    private String departmentClaim;

    /** 排序（小在前） */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
