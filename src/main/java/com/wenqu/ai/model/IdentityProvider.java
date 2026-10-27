package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一身份认证源（OIDC/SAML/LDAP/SCIM）：一行一个认证源，协议参数集中在
 * {@code config} JSON 列（按 {@code type} 不同结构不同；整块 JWE 信封加密，{@code JWE:} 前缀，
 * 经 {@code ConfigCryptoService.decrypt} 解出）。四类认证源都登记在本表，
 * OIDC 的运行时配置由 {@code OidcProviderService} 按 {@code type='oidc'} 读写这里。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_identity_provider")
public class IdentityProvider {

    /** 主键 (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID（Phase4 启用，当前 NULL） */
    private String tenantId;

    /** 协议类型：oidc | saml | ldap | scim */
    private String type;

    /** 认证源名称（登录按钮/同步源名） */
    private String name;

    /** 启用: 1=启用 0=停用 */
    private Integer enabled;

    /**
     * 协议参数 JSON（按 type 不同）。SAML 形态：
     * <pre>{ "metadataUrl": "...", "spBaseUrl": "https://wq.example.com",
     *        "idpEntityId": "...", "idpSsoUrl": "...", "idpCertificate": "-----BEGIN CERTIFICATE...",
     *        "spCertificate": "...", "spPrivateKey": "...", "frontendBaseUrl": "..." }</pre>
     * OIDC 形态后续从旧表并轨时补齐。
     */
    private String config;

    /** 自动建档默认角色（须是 c_ai_role.code 已启用值） */
    private String defaultRole;

    /** 自动建档默认部门（c_ai_department.id） */
    private String defaultDepartment;

    /** 首次登录自动建号（关闭则须管理员预先建好并绑定） */
    private Integer autoCreateUser;

    /** 排序（小在前，决定登录页按钮顺序） */
    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
