package com.wisesoft.ai.util;

/**
 * 当前请求用户透传（ThreadLocal）。
 * <p>
 * 由 {@code UserContextInterceptor} 在请求入口按 X-User-Id 解析并装载（含部门/角色），
 * 业务层（创建资源写 created_by、检索层按可见范围过滤）直接读取，无需层层透传参数。
 * 参照项目既有 {@code SubAgentOrchestrator.CTX} 的 ThreadLocal 模式。
 *
 * @author yuanke
 */
public final class RequestUser {

    private static final ThreadLocal<RequestUser> CURRENT = new ThreadLocal<>();

    private final String uid;
    private final String departmentId;
    /** 角色：superadmin | admin | user（未建档按 user） */
    private final String role;

    private RequestUser(String uid, String departmentId, String role) {
        this.uid = uid;
        this.departmentId = departmentId;
        this.role = (role == null || role.isBlank()) ? "user" : role;
    }

    public static void set(String uid, String departmentId, String role) {
        CURRENT.set(new RequestUser(uid, departmentId, role));
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前用户标识；无上下文时回落 anonymous（与会话兼容池一致） */
    public static String uid() {
        RequestUser u = CURRENT.get();
        return u == null ? UserContext.ANONYMOUS : u.uid;
    }

    public static String departmentId() {
        RequestUser u = CURRENT.get();
        return u == null ? null : u.departmentId;
    }

    /** 当前用户角色；无上下文按普通用户 */
    public static String role() {
        RequestUser u = CURRENT.get();
        return u == null ? "user" : u.role;
    }

    public static boolean superadmin() {
        return "superadmin".equals(role());
    }
}
