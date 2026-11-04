package com.wenqu.ai.util;

import java.util.List;

/**
 * 当前请求用户透传（ThreadLocal）。
 * <p>
 * 由 {@code UserContextInterceptor} 在请求入口解析登录令牌（{@code Authorization: Bearer <JWT>}）后装载
 * （含部门/角色集）。没有请求身份时不再造一个可登录的“匿名用户”，而是显式落到 {@link #SYSTEM}：
 * 后台线程、公开端点与漏装身份的调用点都看得见这个哨兵，但 {@link #roles()} 为空，不能借它拿到任何权限。
 * <p>
 * <b>多角色口径</b>（RBAC 并集判定）：
 * <ul>
 *   <li>{@link #roles()}：全部角色——鉴权判定一律用它（任一角色为管理员级即管理员；
 *       接口/菜单授权各角色取并集）；</li>
 *   <li>{@link #role()}：主角色（首个管理员级角色，否则首个）——仅供展示、日志与
 *       「角色标签」类读端点使用，<b>不</b>作为鉴权依据。</li>
 * </ul>
 *
 * @author yuanke
 */
public final class RequestUser {

    /**
     * 无请求身份的显式哨兵：后台任务、公开端点与漏装身份的线程。
     * <p>它不是可登录账号，不应拥有业务资源；{@link #roles()} 对它恒为空，鉴权按 fail-closed 处理。
     */
    public static final String SYSTEM = "system";

    private static final ThreadLocal<RequestUser> CURRENT = new ThreadLocal<>();

    private final String uid;
    private final String departmentId;
    /** 全部角色（管理员级在前；真实用户无角色时按 ["user"]，SYSTEM 为空） */
    private final List<String> roles;
    /** 主角色（首个管理员级，否则首个；展示/日志用，不作鉴权依据） */
    private final String role;

    private RequestUser(String uid, String departmentId, List<String> roles) {
        String u = (uid == null || uid.isBlank()) ? SYSTEM : uid;
        this.uid = u;
        this.departmentId = departmentId;
        List<String> rs = com.wenqu.ai.service.UserRoleService.distinct(roles);
        if (SYSTEM.equals(u)) rs = List.of();
        else if (rs.isEmpty()) rs = List.of("user");
        this.roles = rs;
        this.role = rs.isEmpty() ? SYSTEM : rs.get(0);
    }

    /** 多角色装载（推荐入口：UserContextInterceptor / 流水线身份装载用） */
    public static void set(String uid, String departmentId, List<String> roles) {
        CURRENT.set(new RequestUser(uid, departmentId, roles));
    }

    /** 单角色装载（等价于单元素角色集；快照恢复也走这里） */
    public static void set(String uid, String departmentId, String role) {
        CURRENT.set(new RequestUser(uid, departmentId, role == null ? List.of() : List.of(role)));
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前用户标识；无请求上下文时为 {@link #SYSTEM} */
    public static String uid() {
        RequestUser u = CURRENT.get();
        return u == null ? SYSTEM : u.uid;
    }

    public static String departmentId() {
        RequestUser u = CURRENT.get();
        return u == null ? null : u.departmentId;
    }

    /** 当前用户全部角色（鉴权判定口径：并集）；无上下文为空表（fail-closed） */
    public static List<String> roles() {
        RequestUser u = CURRENT.get();
        return u == null ? List.of() : u.roles;
    }

    /** 当前用户主角色（首个管理员级，否则首个）；仅供展示/日志，不作鉴权依据 */
    public static String role() {
        RequestUser u = CURRENT.get();
        return u == null ? SYSTEM : u.role;
    }

    /** 当前是否没有真实登录用户（后台线程 / 公开端点 / 漏装身份） */
    public static boolean isSystem() {
        return SYSTEM.equals(uid());
    }

    /** 当前用户是否持有 superadmin（多角色口径：任一角色为 superadmin） */
    public static boolean superadmin() {
        return roles().contains("superadmin");
    }

    /**
     * 身份快照（跨线程传递用）。
     * <p>
     * <b>为什么需要</b>：本类用<b>裸 {@link ThreadLocal}</b>，只在装载它的线程可见。工作区里凡是
     * 「换线程执行用户态逻辑」的地方都会丢失身份并回落 {@link #SYSTEM}，而 SYSTEM 不是真实用户——
     * 检索层会判它「什么都读不到」，于是<b>在主链路明明召回到、换个线程就恒空</b>（线上实测：
     * {@code searchKnowledge} 在 {@code boundedElastic-*} 工具线程里回落 SYSTEM → 库门永假 → 融合召回 0）。
     * 同一坑已踩多次（{@code SubAgentOrchestrator} 分支检索也为此打了局部补丁），故在此提供
     * 通用快照/恢复，由调用点在<b>线程切换之前</b>捕获、切换之后恢复。
     */
    public record Snapshot(String uid, String departmentId, String role, List<String> roles) {
    }

    /** 捕获当前身份快照（应在提交异步任务<b>之前</b>调用，此时身份还可见） */
    public static Snapshot snapshot() {
        RequestUser u = CURRENT.get();
        return u == null ? new Snapshot(SYSTEM, null, SYSTEM, List.of())
                : new Snapshot(u.uid, u.departmentId, u.role, u.roles);
    }

    /** 在<b>新线程里</b>恢复身份；返回的 AutoCloseable 用于 finally 复原，避免污染线程池里后续复用该线程的任务 */
    public static AutoCloseable restore(Snapshot s) {
        if (s == null) return () -> { };
        RequestUser prev = CURRENT.get();
        CURRENT.set(new RequestUser(s.uid(), s.departmentId(), s.roles()));
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
