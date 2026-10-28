package com.wenqu.ai.util;

import java.util.function.Supplier;

/**
 * 当前租户上下文（Phase 4 · P9 多租户）。
 *
 * <p>租户是<b>行级归属</b>：全表 {@code tenant_id} 由 MyBatis-Plus 的
 * {@code TenantLineInnerInterceptor} 在 SQL 上强制，判定用的就是这里的当前租户
 * （见 {@code WenquTenantLineHandler}）。业务代码不写租户条件——写了两处就会漂。
 *
 * <p>三种状态，语义不同，刻意分开：
 * <ul>
 *   <li><b>已绑定</b>：请求线程（登录用户）与流水线线程（随身份一起重放）；读写都限定在该租户内；</li>
 *   <li><b>平台视图</b>：跨租户的管理动作（开通租户、把成员挪到别的租户），只在显式 {@link #asPlatform} 里；
 *       它同时是「我知道我在跨租户」的记号——搜这个字面量就能列出全平台所有越界读写点；</li>
 *   <li><b>未绑定</b>：启动任务、调度器、清理任务这类没有请求身份的系统线程。它们本就要遍历全部租户的行
 *       （解析队列、超时回收、留存清理），所以不注入谓词；<b>代价是这些线程里的用户可见查询必须自己带条件</b>
 *       ——给某个成员发通知、按归属人汇总用量，都要显式 {@link #as} 到那个租户里再查，不能靠拦截器兜。</li>
 * </ul>
 *
 * <p>单租户部署不需要任何开关：平台自己就是 {@code default} 这个租户，谓词恒等于 {@code tenant_id='default'}，
 * 与加列前的行为一致。
 *
 * @author yuanke
 */
public final class TenantContext {

    /** 默认租户：单租户部署的那个租户，也是所有存量行的归属 */
    public static final String DEFAULT_TENANT = "default";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PLATFORM = new ThreadLocal<>();
    /** 归属解析中的重入闸（见 {@link #current()}） */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();
    /**
     * 成员 → 租户的兜底解析口（由 {@code TenantService} 在启动时装上）。
     * <p>为什么要有它：身份的重放点散在流水线、工作流、工具弹性线程、评估、MCP 端点好几处，
     * 每处都要求「顺手绑一下租户」就是新一类静默错位（漏一处等于那条线程看全平台）。
     * 现在只要线程上有 {@link RequestUser}，租户就跟着身份走；显式 {@link #bind} 只是省一次查询的快路。
     */
    private static volatile java.util.function.Function<String, String> resolver;

    private TenantContext() {
    }

    /** 装兜底解析口（启动期一次；没装上时按未绑定处理，不猜归属） */
    public static void setResolver(java.util.function.Function<String, String> r) {
        resolver = r;
    }

    /** 绑定当前线程的租户（null/空=解绑，回到「未绑定」态） */
    public static void bind(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId.trim());
        }
    }

    /**
     * 当前租户；未绑定返回 null（不兜底成 default：漏绑要能在诊断里看出来）。
     * <p>没显式绑定时按线程上的身份去查归属（见 {@link #resolver}）。查归属这件事本身要走数据库，
     * 而那条 SQL 又会被租户拦截器问一遍「当前租户是谁」——所以有一层重入闸：解析过程中一律回 null，
     * 让这条内部查询按未绑定（不过滤）跑完。少了这层闸就是无限递归。
     */
    public static String current() {
        String bound = CURRENT.get();
        if (bound != null) return bound;
        if (Boolean.TRUE.equals(RESOLVING.get())) return null;
        java.util.function.Function<String, String> r = resolver;
        String uid = RequestUser.uid();
        if (r == null || uid == null || RequestUser.ANONYMOUS.equals(uid)) return null;
        RESOLVING.set(Boolean.TRUE);
        try {
            String t = r.apply(uid);
            if (t != null && !t.isBlank()) CURRENT.set(t);   // 解析结果留在本线程：一条 SQL 要问两次
            return t;
        } finally {
            RESOLVING.remove();
        }
    }

    /** 是否处于平台视图（跨租户管理动作） */
    public static boolean inPlatformView() {
        return Boolean.TRUE.equals(PLATFORM.get());
    }

    /** 当前线程是否限定了租户（已绑定且不在平台视图里）——读侧判据与诊断用 */
    public static boolean scoped() {
        return current() != null && !inPlatformView();
    }

    /** 清理解绑（请求结束、池化线程复用前必须调） */
    public static void clear() {
        CURRENT.remove();
        PLATFORM.remove();
        RESOLVING.remove();
    }

    /**
     * 在指定租户里跑一段（后台任务给某个成员做事、跨租户统计）。
     * <p>嵌套安全：跑完恢复原值，而不是直接清掉——外层可能正绑着别的租户。
     */
    public static <T> T as(String tenantId, Supplier<T> body) {
        String prev = CURRENT.get();
        bind(tenantId);
        try {
            return body.get();
        } finally {
            bind(prev);
        }
    }

    /** 无返回值的 {@link #as} */
    public static void as(String tenantId, Runnable body) {
        as(tenantId, () -> {
            body.run();
            return null;
        });
    }

    /**
     * 以平台视图跑一段跨租户管理动作（开通租户、迁移成员归属）。
     * <p>只给管理端点用。业务读路径不要图省事往这里塞——那等于关掉这道闸。
     */
    public static <T> T asPlatform(Supplier<T> body) {
        String prevTenant = CURRENT.get();
        Boolean prevPlatform = PLATFORM.get();
        PLATFORM.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (prevPlatform == null) PLATFORM.remove();
            else PLATFORM.set(prevPlatform);
            bind(prevTenant);
        }
    }

    /**
     * 管理员写路径的统一落点：调用者是平台级（superadmin）才切平台视图，否则原样跑。
     * <p>为什么需要它：成员名单/资源清单对 superadmin 是<b>跨租户摊开</b>的，写侧若仍按本租户过滤，
     * 症状是「看得见、一保存就说用户不存在」；更坏的一种是 {@code updateById} 被拼上
     * {@code AND tenant_id = ?} 后匹配 0 行而<b>一声不响</b>——界面报「已保存」而什么都没改。
     * <p>租户管理员（role=admin，非 superadmin）不在放开范围内：他只管本租户的行。
     * 判据只写在这一处；散到各服务各写一遍，漏一处就留一个静默失效的口子。
     */
    public static <T> T asPlatformIfSuper(Supplier<T> body) {
        return com.wenqu.ai.util.RequestUser.superadmin() ? asPlatform(body) : body.get();
    }

    /** {@link #asPlatformIfSuper(Supplier)} 的无返回值版 */
    public static void asPlatformIfSuper(Runnable body) {
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            body.run();
            return;
        }
        asPlatform(() -> {
            body.run();
            return null;
        });
    }
}
