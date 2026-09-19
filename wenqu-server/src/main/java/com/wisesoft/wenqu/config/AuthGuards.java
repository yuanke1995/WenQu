package com.wisesoft.wenqu.config;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.RequestUser;
import com.wisesoft.wenqu.service.AuthService;

import java.util.Map;

/**
 * 请求级鉴权守卫：参考实现 {@code server/utils/auth_middleware.py} 中 FastAPI 依赖注入函数的 Java 载体。
 *
 * <p>对应关系（逐函数对齐，错误码与文案不变）：
 * <ul>
 *   <li>{@code get_current_user} → {@link UserContextInterceptor} 装载的 {@link RequestUser}
 *       （未携带/无效令牌即匿名；有效令牌解析登录态，与参考实现的「无 token 返回 None」等价）；</li>
 *   <li>{@code get_required_user} → {@link #requireUser()}：未登录 401 {@code 请登录后再访问}
 *       （带 {@code WWW-Authenticate: Bearer}），已登录但未绑定部门 400 {@code 当前用户未绑定部门}；</li>
 *   <li>{@code get_admin_user} → {@link #requireAdmin()}：角色非 admin/superadmin → 403 {@code 需要管理员权限}；</li>
 *   <li>{@code get_superadmin_user} → {@link #requireSuperadmin()}：角色非 superadmin → 403 {@code 需要超级管理员权限}。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：FastAPI 用 {@code Depends(...)} 在函数签名上声明依赖，
 * Spring MVC 无等价机制，故改为在每个路由方法首行显式调用（调用点与参考实现的依赖声明一一对应）。
 * 返回值是 Java 侧等价于「当前用户对象」的最小载体——uid（各服务方法本就按 uid 取数）。
 */
public final class AuthGuards {

    private AuthGuards() {
    }

    /** 当前请求 uid；未登录为 {@link RequestUser#ANONYMOUS}。 */
    public static String currentUid() {
        return RequestUser.uid();
    }

    /** 是否已登录（携带有效登录令牌）。 */
    public static boolean authenticated() {
        String uid = RequestUser.uid();
        return uid != null && !uid.isBlank() && !RequestUser.ANONYMOUS.equals(uid);
    }

    /**
     * 要求已登录且已绑定部门（对应 get_required_user）。
     *
     * @return 当前用户 uid
     */
    public static String requireUser() {
        if (!authenticated()) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        String departmentId = RequestUser.departmentId();
        if (departmentId == null || departmentId.isBlank()) {
            throw new ApiHttpException(400, "当前用户未绑定部门");
        }
        return RequestUser.uid();
    }

    /**
     * 要求管理员（对应 get_admin_user，内部含 requireUser 前置）。
     *
     * @return 当前用户 uid
     */
    public static String requireAdmin() {
        String uid = requireUser();
        if (!AuthService.isAdminRole(RequestUser.role())) {
            throw new ApiHttpException(403, "需要管理员权限");
        }
        return uid;
    }

    /**
     * 要求超级管理员（对应 get_superadmin_user，内部含 requireUser 前置）。
     *
     * @return 当前用户 uid
     */
    public static String requireSuperadmin() {
        String uid = requireUser();
        if (!"superadmin".equals(RequestUser.role())) {
            throw new ApiHttpException(403, "需要超级管理员权限");
        }
        return uid;
    }
}
