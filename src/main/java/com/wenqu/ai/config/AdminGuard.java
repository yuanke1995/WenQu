package com.wenqu.ai.config;

import com.wenqu.ai.service.RoleService;
import com.wenqu.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 管理员判定（管理端点访问控制）。
 * <p>
 * 权限模型（2026-09-26 RBAC 化）：管理员级 = 内置 admin / superadmin，
 * 或自定义角色 {@code c_ai_role.admin_flag=1 且 status=1}（判定走 {@link RoleService} 缓存）。
 * 未登录（SYSTEM）或普通角色一律拒绝（fail-closed）。
 * <p>
 * RBAC 批次 B3 收口（2026-11）：控制器内的散落判定统一走 {@link #requirePermission}——
 * 按<b>授权点</b>判（全局 deny 压过一切；管理员级展开为全量 allow 仍受 deny；其余角色 allow 并集），
 * 「只读管理员/运维管理员」才能落地。{@link #isAdmin} 保留给<b>展示语义</b>的调用点
 * （如 /auth/me 的管理员徽标）——那是「他是不是管理员」，不是「他能不能做这件事」。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final RoleService roleService;

    /** 是否为管理员级请求（判定不抛异常、不落日志噪音，纯布尔；仅展示语义用，鉴权用 requirePermission） */
    public boolean isAdmin(HttpServletRequest request) {
        if (request == null) return false;
        return roleService.isAdminAny(RequestUser.roles());
    }

    /**
     * 授权点判定（批次 B3 收口）：调用方拥有任一给定授权点即放行（并集语义）。
     * 判定口径与端点拦截器一致（{@link RoleService#hasPermission}）：
     * 全局 deny 压过一切，管理员级展开为全量 allow（仍受 deny）。
     */
    public boolean requirePermission(HttpServletRequest request, String... permissions) {
        if (request == null || permissions == null || permissions.length == 0) return false;
        var roles = RequestUser.roles();
        for (String perm : permissions) {
            if (roleService.hasPermission(roles, perm)) return true;
        }
        return false;
    }
}
