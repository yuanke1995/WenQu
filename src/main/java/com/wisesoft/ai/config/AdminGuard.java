package com.wisesoft.ai.config;

import com.wisesoft.ai.service.RoleService;
import com.wisesoft.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 管理员判定（管理端点访问控制）。
 * <p>
 * 权限模型（2026-09-26 RBAC 化）：管理员级 = 内置 admin / superadmin，
 * 或自定义角色 {@code c_ai_role.admin_flag=1 且 status=1}（判定走 {@link RoleService} 缓存）。
 * 未登录（anonymous）或普通角色一律拒绝（fail-closed）。
 * 非管理员级角色对个别管理端点的授权走 {@code c_ai_role_api} 绑定，
 * 由 {@code SecurityConfig.AccessControlInterceptor} 统一裁决；本判定只回答「是否管理员级」。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final RoleService roleService;

    /** 是否为管理员级请求（判定不抛异常、不落日志噪音，纯布尔） */
    public boolean isAdmin(HttpServletRequest request) {
        if (request == null) return false;
        return roleService.isAdminCode(RequestUser.role());
    }
}
