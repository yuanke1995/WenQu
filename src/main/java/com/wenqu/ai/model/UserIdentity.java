package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-外部身份关联（统一身份模型）：一个本地账号可绑定多个认证源的外部身份
 * （OIDC sub / SAML NameID / SCIM id / LDAP dn 摘要），取代 {@code c_ai_user.oidc_sub/oidc_provider}
 * 两列的单绑定模型。
 * <p>
 * {@code (provider_id, external_id)} 复合唯一——同一认证源下同一外部身份只能绑一个本地账号（防冒用）；
 * 不同认证源签发相同标识不冲突。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_identity")
public class UserIdentity {

    /** 主键 (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID（Phase4 启用，当前 NULL） */
    private String tenantId;

    /** 本地账号 (c_ai_user.uid) */
    private String uid;

    /** 认证源 (c_ai_identity_provider.id；OIDC 存量迁移期=旧 c_ai_oidc_provider.id) */
    private String providerId;

    /** IdP 侧唯一标识（OIDC sub / SAML NameID / SCIM id / LDAP dn 摘要） */
    private String externalId;

    /** 最近同步的映射属性快照 JSON */
    private String externalAttrs;

    /** 最近一次同步时间 */
    private LocalDateTime lastSynced;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
