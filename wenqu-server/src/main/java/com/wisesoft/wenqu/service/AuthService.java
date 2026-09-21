package com.wisesoft.wenqu.service;

/**
 * 管理员角色判定（超级管理员 = admin / superadmin，参考实现 {@code _is_admin_role} 的取值面）。
 *
 * <p>原类承担整套旧契约登录（密码校验 / JWT 签发 / 首次运行初始化 / 失败锁定），数据落在
 * {@code c_ai_user} 表。旧契约层整体下线后，那些能力随 {@code AuthController}
 * （{@code /api/ai/auth/**}）、{@code UserMapper}、{@code model.User} 一并移除；
 * 本类只保留这一个**不带状态的静态方法**，因为它被新契约的权限判定直接引用：
 * {@code AdminGuard} / {@code AuthGuards} / {@code McpController}。
 *
 * <p>类名保持不变是为了不改动上述三处引用；由于不再需要依赖注入，这里也不再是 Spring bean
 * （去掉 {@code @Service}），调用方一律走 {@code AuthService.isAdminRole(...)} 静态调用。
 */
public final class AuthService {

    private AuthService() {}

    /** admin 即管理员（与 {@code users.role} 的取值一致）。 */
    public static boolean isAdminRole(String role) {
        return "admin".equals(role) || "superadmin".equals(role);
    }
}
