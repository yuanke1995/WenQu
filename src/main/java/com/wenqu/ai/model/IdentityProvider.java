package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一身份认证源（OIDC/SAML/LDAP/SCIM）：一行一个认证源，协议参数集中在
 * {@code config} JSON 列（按 {@code type} 不同结构不同；敏感字段整块 RSA 加密，"RSA: " 前缀，
 * 经 {@code ConfigCryptoService.decrypt} 解出）。OIDC 存量配置仍在旧表 {@code c_ai_oidc_provider}
 * （迁移期并存，退役见任务 #7），本表是批次 1 起新认证源的唯一登记处。
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
