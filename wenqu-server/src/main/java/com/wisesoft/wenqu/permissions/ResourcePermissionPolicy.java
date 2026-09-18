package com.wisesoft.wenqu.permissions;

import java.util.Map;

/**
 * 声明资源类型允许的角色上限，不包含共享范围匹配逻辑。
 *
 * <p>由参考实现的 permissions/resource_permission.py 中 ResourcePermissionPolicy 与其三个
 * 策略常量逐条翻译：知识库的普通用户封顶只读；智能体与技能对所有角色都允许到可管理。
 */
public record ResourcePermissionPolicy(Map<String, ResourcePermission> roleCeiling) {

    /** 知识库权限策略：普通用户最多只读。 */
    public static final ResourcePermissionPolicy KNOWLEDGE_BASE =
            new ResourcePermissionPolicy(
                    Map.of(
                            "user", ResourcePermission.READ,
                            "admin", ResourcePermission.MANAGE,
                            "superadmin", ResourcePermission.MANAGE));

    /** 智能体权限策略：所有角色都可到可管理（是否真的可管理由共享范围决定）。 */
    public static final ResourcePermissionPolicy AGENT =
            new ResourcePermissionPolicy(
                    Map.of(
                            "user", ResourcePermission.MANAGE,
                            "admin", ResourcePermission.MANAGE,
                            "superadmin", ResourcePermission.MANAGE));

    /** 技能权限策略：与智能体一致。 */
    public static final ResourcePermissionPolicy SKILL = AGENT;

    /** 未知角色时的兜底上限（与参考实现的 {@code policy.role_ceiling.get(role, READ)} 一致）。 */
    public ResourcePermission ceilingFor(String role) {
        ResourcePermission permission = roleCeiling.get(role);
        return permission == null ? ResourcePermission.READ : permission;
    }
}
