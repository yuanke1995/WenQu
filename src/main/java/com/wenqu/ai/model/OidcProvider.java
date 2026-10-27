package com.wenqu.ai.model;

import lombok.Data;

/**
 * OIDC 认证源配置视图：<b>不再映射独立表</b>——数据统一存 {@code c_ai_identity_provider}
 * （type='oidc'），协议参数收纳进 {@code config} JSON（键名与本类字段一致，clientSecret 为
 * RSA 密文），name/enabled/defaultRole/defaultDepartment/autoCreateUser/sortOrder 用统一表列。
 * {@code OidcProviderService} 负责行 ↔ 视图互转；存量库由 {@code IdentityMigrationRunner}
 * 从旧表 c_ai_oidc_provider（已退役）桥接而来，键名与本类对齐。
 *
 * @author yuanke
 */
@Data
public class OidcProvider {

    /** 主键ID (UUID；= c_ai_identity_provider.id) */
    private String id;

    /** 认证源名称（登录按钮文案） */
    private String name;

    /** 启用: 1=启用 0=停用 */
    private Integer enabled;

    /** IdP issuer（discovery 源） */
    private String issuerUrl;

    /** 客户端 ID */
    private String clientId;

    /** 客户端密钥（JWE 密文，JWE: 前缀） */
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
}
