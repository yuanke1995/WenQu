package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组→角色/部门 自动映射规则：某个认证源下的 IdP 组命中时授予什么角色/部门。
 * 组成员是 IdP 侧事实（{@code c_ai_group_member}），本表是本地治理配置——
 * {@code role_code} 可为 NULL（组已登记、规则待管理员在映射管理页配置）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_group_role_mapping")
public class GroupRoleMapping {

    /** 主键 (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID（Phase4 启用，当前 NULL） */
    private String tenantId;

    /** 认证源 (c_ai_identity_provider.id) */
    private String providerId;

    /** IdP 侧组名/组标识 */
    private String externalGroup;

    /** 映射到的角色（c_ai_role.code；NULL=未配置） */
    private String roleCode;

    /** 映射到的部门（c_ai_department.id；NULL=不动部门） */
    private String departmentId;

    /** 多组命中裁决用，大优先 */
    private Integer priority;

    private LocalDateTime createTime;
}
