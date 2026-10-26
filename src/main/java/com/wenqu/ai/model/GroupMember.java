package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组⇄用户成员关系（SCIM Groups 推送 / LDAP 目录同步共用的成员事实源）。
 * {@code (provider_id, external_group, uid)} 唯一。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_group_member")
public class GroupMember {

    /** 主键 (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID（Phase4 启用，当前 NULL） */
    private String tenantId;

    /** 认证源 (c_ai_identity_provider.id) */
    private String providerId;

    /** IdP 侧组名/组标识 */
    private String externalGroup;

    /** 本地账号 (c_ai_user.uid) */
    private String uid;

    /** 入组时间 */
    private LocalDateTime createTime;
}
