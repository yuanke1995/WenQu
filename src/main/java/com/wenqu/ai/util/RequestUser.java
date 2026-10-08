package com.wenqu.ai.util;

/**
 * 当前请求用户透传（ThreadLocal）。
 * <p>
 * 由 {@code UserContextInterceptor} 在请求入口解析登录令牌（{@code Authorization: Bearer <JWT>}）后装载
 * （含部门/角色）；未登录一律为 {@link #ANONYMOUS}。业务层（创建资源写 created_by、检索层按可见范围过滤）
 * 直接读取，无需层层透传参数。参照项目既有 {@code SubAgentOrchestrator.CTX} 的 ThreadLocal 模式。
 *
 * @author yuanke
 */
public final class RequestUser {

    /** 匿名兜底身份：未登录请求的归属（该名下会话为全局共享的历史兼容池） */
    public static final String ANONYMOUS = "anonymous";

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

    /** 当前用户标识；未登录/无上下文时为 {@link #ANONYMOUS}（与会话兼容池一致） */
    public static String uid() {
        RequestUser u = CURRENT.get();
        return u == null ? ANONYMOUS : u.uid;
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

    /**
     * 身份快照（跨线程传递用）。
     * <p>
     * <b>为什么需要</b>：本类用<b>裸 {@link ThreadLocal}</b>，只在装载它的线程可见。项目里凡是
     * 「换线程执行用户态逻辑」的地方都会丢失身份并回落 {@link #ANONYMOUS}，而 {@code anonymous}
     * 不是一个安全的降级值——检索层 {@code loadVisibleKbIds()} 会判它「什么都读不到」，
     * 于是<b>在主链路明明召回到、换个线程就恒空</b>（线上实测：{@code searchKnowledge} 在
     * {@code boundedElastic-*} 工具线程里 {@code uid=anonymous} →库门永假 → 融合召回 0）。
     * 同一坑已踩多次（{@code SubAgentOrchestrator} 分支检索也为此打了局部补丁），故在此提供
     * 通用快照/恢复，由调用点在<b>线程切换之前</b>捕获、切换之后恢复。
     */
    public record Snapshot(String uid, String departmentId, String role) {
    }

    /** 捕获当前身份快照（应在提交异步任务<b>之前</b>调用，此时身份还可见） */
    public static Snapshot snapshot() {
        RequestUser u = CURRENT.get();
        return u == null ? new Snapshot(ANONYMOUS, null, "user")
                : new Snapshot(u.uid, u.departmentId, u.role);
    }

    /** 在<b>新线程里</b>恢复身份；返回的 AutoCloseable 用于 finally 复原，避免污染线程池里后续复用该线程的任务 */
    public static AutoCloseable restore(Snapshot s) {
        if (s == null) return () -> { };
        RequestUser prev = CURRENT.get();
        CURRENT.set(new RequestUser(s.uid(), s.departmentId(), s.role()));
        return () -> {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        };
    }

    /** 以快照身份执行并自动复原（用于同步包裹一段跨线程调用） */
    public static <T> T callAs(Snapshot s, java.util.function.Supplier<T> action) {
        try (AutoCloseable ignored = restore(s)) {
            return action.get();
        } catch (Exception e) {
            throw new IllegalStateException("恢复用户身份失败", e);
        }
    }
}
