package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.ApiEndpointMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.RoleApiMapper;
import com.wenqu.ai.mapper.RoleMapper;
import com.wenqu.ai.mapper.RoleMenuMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.ApiEndpoint;
import com.wenqu.ai.model.Role;
import com.wenqu.ai.model.User;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 角色服务（RBAC 核心）：角色 CRUD、管理员判定、接口鉴权匹配、菜单/接口绑定。
 * <p>
 * 鉴权两个入口（每请求都会走，故全走内存缓存）：
 * <ul>
 *   <li>{@link #isAdminCode(String)}：内置 superadmin/admin 直通；自定义角色看 {@code admin_flag=1 且 status=1}。
 *       角色表极小（≤几十行），缓存为全量快照，任何角色写操作后整体失效重建。</li>
 *   <li>{@link #canAccess(String, String, String)}：非管理员角色的接口级校验——
 *       角色绑定的接口（method + AntPathMatcher path，支持 {@code /{id}} 占位）逐一匹配，
 *       未命中一律拒绝（fail-closed 由拦截器保证）。</li>
 * </ul>
 * 缓存一致性（多副本）：写操作后经 {@code ReplicaCoordination} 广播让所有副本当场失效，
 * 主通道是广播；快照另带 60s TTL（与 {@code UserRoleService} 同口径）作为
 * 单机 / 无 Redis / 广播丢包形态下的兜底——权限回收最迟 60s 生效，不再依赖重启。
 * Redis 不可用时广播如实失败（原语层已兜底），TTL 是唯一保险，系统照常运行。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    /** 角色编码规则：小写字母开头，允许小写字母/数字/下划线/连字符，≤20 字符（对齐 c_ai_user.role VARCHAR(20)） */
    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,19}");

    /** 内置管理员级角色编码（硬编码直通，不依赖种子数据是否就绪） */
    private static final Set<String> BUILTIN_ADMIN = Set.of("superadmin", "admin");

    /** 跨副本角色失效广播频道（scope=role|api；本副本自己发的已被原语层滤掉） */
    public static final String CHANNEL_RBAC_INVALIDATE = "rbac:invalidate";

    /** 快照 TTL：多副本下广播是主通道，TTL 是单机/无 Redis 形态的唯一保险（与 UserRoleService 60s 同口径） */
    private static final long SNAPSHOT_TTL_MS = 60_000;

    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleApiMapper roleApiMapper;
    private final MenuMapper menuMapper;
    private final ApiEndpointMapper apiEndpointMapper;
    private final UserMapper userMapper;
    private final com.wenqu.ai.mapper.UserRoleMapper userRoleMapper;
    private final com.wenqu.ai.mapper.RolePermissionMapper rolePermissionMapper;
    private final com.wenqu.ai.mapper.PermissionCatalogMapper permissionCatalogMapper;
    private final com.wenqu.ai.mapper.RoleDataScopeMapper roleDataScopeMapper;
    private final com.wenqu.ai.service.ResourceVisibilityService resourceVisibilityService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ReplicaCoordination replicaCoordination;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 授权点判定模式（方案 §8 M2 双跑）：legacy=旧端点绑定链路；shadow=新旧都算、按旧放行、记录差异；enforce=授权点判定生效 */
    @org.springframework.beans.factory.annotation.Value("${ai-app.rbac.permission-mode:legacy}")
    private String permissionMode = "legacy";

    // ==================== 缓存（批次 F2：按租户键控） ====================

    /**
     * 每租户一份角色快照（批次 F2）：角色/绑定全部按租户隔离后，判定热路径必须按
     * {@code TenantContext} 键控取各自的快照，否则 A 租户能读到 B 租户的角色定义（直接越权源）。
     * 键规则：平台视图（asPlatform）与未绑定线程（启动任务/调度器）共用 {@link #PLATFORM_KEY}
     * ——这两类线程本来就跨租户遍历，快照不做租户过滤（与租户化前的全局口径一致）；
     * 绑定态请求按租户各建各的。表小（每租户 ≤几十行），整份重建成本可忽略。
     */
    private record Snap(Map<String, Role> roles,
                        Map<String, List<ApiPattern>> apiPatterns,
                        Map<String, List<String>> menuBindings,
                        Map<String, List<String>> apiIds,
                        Map<String, RolePermBinding> permBindings,
                        long expireAt) {
        static Snap empty() {
            return new Snap(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0L);
        }
    }

    private static final String PLATFORM_KEY = "\u0000platform";
    private final ConcurrentHashMap<String, Snap> snapshots = new ConcurrentHashMap<>();

    private static String tenantKey() {
        if (com.wenqu.ai.util.TenantContext.inPlatformView()) return PLATFORM_KEY;
        String t = com.wenqu.ai.util.TenantContext.current();
        return t == null || t.isBlank() ? PLATFORM_KEY : t;
    }

    private Snap snap() {
        String key = tenantKey();
        Snap s = snapshots.get(key);
        if (s != null && System.currentTimeMillis() < s.expireAt()) return s;
        synchronized (this) {
            s = snapshots.get(key);
            if (s == null || System.currentTimeMillis() >= s.expireAt()) {
                s = rebuildSnap(s);
                snapshots.put(key, s);
            }
            return s;
        }
    }

    /** 重建整份快照：角色全量 + 各按角色懒加载的绑定表清零（随访问重建） */
    /** 角色快照（按租户键控，懒加载/过期重建在 {@link #snap()} 内） */
    private Map<String, Role> roles() {
        return snap().roles();
    }

    private Snap rebuildSnap(Snap old) {
        Map<String, Role> roles = new LinkedHashMap<>();
        for (Role r : roleMapper.selectList(null)) roles.put(r.getCode(), r);
        return new Snap(roles, Map.of(), Map.of(), Map.of(), Map.of(),
                System.currentTimeMillis() + SNAPSHOT_TTL_MS);
    }

    /** 接口 pattern（method + path 二元组） */
    private record ApiPattern(String method, String path) {
    }

    /** 启动即订阅跨副本失效广播：别的副本改了角色/绑定，本副本当场清快照。订阅失败只告警（退化为仅 TTL） */
    @PostConstruct
    void subscribeReplicaInvalidation() {
        try {
            replicaCoordination.listen(CHANNEL_RBAC_INVALIDATE, (payload, from) -> {
                String scope = payload == null ? null : payload.getString("scope");
                if ("api".equals(scope)) invalidateApiPatternsLocal();
                else invalidateCachesLocal();
                log.info("[RBAC] 收到副本 {} 的权限失效广播（scope={}），快照已清除", from, scope);
            });
        } catch (Exception e) {
            log.warn("[RBAC] 订阅跨副本权限失效广播失败（退化为仅 TTL 兜底）: {}", e.getMessage());
        }
    }

    /** 只清本地（被动过期与收到远端广播时用；不广播，否则两台副本互相触发成死循环）。
     *  广播可能来自任一租户的写操作，失效按全租户清（表小重建便宜，正确性优先）。 */
    private synchronized void invalidateCachesLocal() {
        snapshots.clear();
        apiPermExact = Map.of();
        apiPermPatterns = List.of();
        apiPermLoaded = false;
        shadowDiffs.clear();
    }

    private synchronized void invalidateApiPatternsLocal() {
        // 端点表变了 → 端点→授权点映射作废（permission_code 挂在 c_ai_api 行上，全局表）+ 各租户 pattern 表清零
        apiPermExact = Map.of();
        apiPermPatterns = List.of();
        apiPermLoaded = false;
        snapshots.replaceAll((k, v) -> new Snap(v.roles(), Map.of(), v.menuBindings(), Map.of(), v.permBindings(), v.expireAt()));
    }

    /**
     * 角色或其绑定变化后调用：本地整体失效（表小，重建成本低）+ 广播其它副本当场失效。
     * 写路径统一走这里；被动过期与广播接收侧走 {@link #invalidateCachesLocal()}。
     */
    public synchronized void invalidateCaches() {
        invalidateCachesLocal();
        broadcastInvalidation("role");
    }

    /** 接口表变化（扫描登记/增删改）后调用：仅失效接口相关缓存 + 广播 */
    public synchronized void invalidateApiPatterns() {
        invalidateApiPatternsLocal();
        broadcastInvalidation("api");
    }

    private void broadcastInvalidation(String scope) {
        try {
            replicaCoordination.broadcast(CHANNEL_RBAC_INVALIDATE, Map.of("scope", scope));
        } catch (Exception e) {
            // 广播失败不阻断写操作：对方最迟 60s 由 TTL 兜底
            log.debug("[RBAC] 跨副本失效广播失败（scope={}，对方最迟 60s 由 TTL 兜底）: {}", scope, e.getMessage());
        }
    }

    // ==================== 授权点判定（批次 A / 方案 §8 M2 双跑） ====================

    /** 角色的授权点绑定：allow 集 + deny 行（deny → 是否豁免；exempt=true 只压本角色，方案 D3） */
    private record RolePermBinding(Set<String> allows, Map<String, Boolean> denies) {
    }

    /** 端点 → 授权点 的匹配条目（pattern 路径单列，热路径少做一次精确查找） */
    private record ApiPermEntry(String method, String path, String perm) {
    }

    /** 端点 → 授权点 的精确映射（"METHOD path"/"ALL path" → perm；不含 {id} 占位路径） */
    private volatile Map<String, String> apiPermExact = Map.of();

    /** 端点 → 授权点 的 pattern 条目（路径含占位符，AntPathMatcher 逐个匹配） */
    private volatile List<ApiPermEntry> apiPermPatterns = List.of();

    /** apiPerm 快照是否已构建（全库无标注时靠它避免每请求重建） */
    private volatile boolean apiPermLoaded = false;

    /** shadow 模式差异记录：key → 计数（M2 切流观察用；enforce 稳定后随双跑一起删） */
    private final ConcurrentHashMap<String, long[]> shadowDiffs = new ConcurrentHashMap<>();

    /** 多角色接口鉴权（判定入口）：按 permission-mode 三态分流（方案 §8 M2） */
    public boolean canAccessAny(Collection<String> roles, String method, String path) {
        boolean legacy = canAccessAnyLegacy(roles, method, path);
        String mode = permissionMode == null ? "legacy" : permissionMode.trim().toLowerCase();
        if ("legacy".equals(mode) || mode.isBlank()) return legacy;
        boolean next = canAccessAnyByPermission(roles, method, path);
        if ("shadow".equals(mode)) {
            recordShadowDiff(method, path, roles, legacy, next);
            return legacy;
        }
        return next;
    }

    /** 旧链路（角色 → c_ai_role_api 端点绑定匹配）；enforce 稳定后随 c_ai_role_api 一起退役 */
    private boolean canAccessAnyLegacy(Collection<String> roles, String method, String path) {
        if (roles == null || roles.isEmpty()) return false;
        for (String r : roles) {
            if (canAccess(r, method, path)) return true;
        }
        return false;
    }

    /**
     * 授权点链路判定（方案 A3/D3）：
     * <ul>
     *   <li>端点未标注授权点 → false（fail-closed；管理员级直通在拦截器先行判定，不受此影响）；</li>
     *   <li>任一角色对该授权点有<b>非豁免 deny</b> → 全局拒绝（deny 压过其它角色的 allow，D3 拍板）；</li>
     *   <li>否则任一角色 allow（且该角色自身未对它下 exempt deny）→ 放行（并集放大权限）。</li>
     * </ul>
     */
    public boolean canAccessAnyByPermission(Collection<String> roles, String method, String path) {
        if (roles == null || roles.isEmpty() || method == null || path == null) return false;
        String perm = permissionOfEndpoint(method, path);
        if (perm == null) {
            // 未标注端点（批次 A.5）：enforce 下管理员级可达（新端点先对管理员开），其余角色拒——
            // 标注自检会把它揪出来进权限页「未标注」分组
            return hasAdminLevel(roles);
        }
        return judgePermission(roles, perm);
    }

    /** 管理员级展开（B1）：admin_flag=1 / 内置管理员 ⇒ 全量 allow，仍受全局 deny 约束（D5/D3） */
    private boolean hasAdminLevel(Collection<String> roles) {
        for (String role : roles) {
            if (isAdminCode(role)) return true;
        }
        return false;
    }

    /**
     * 授权点判定公共核：先收集<b>全部角色</b>（含管理员级角色自己挂的 deny——「只读管理员」
     * 正是 admin_flag 角色 + deny 写类授权点，先跳过它就收不到 deny 了）的全局 deny，
     * 再做管理员级展开与 allow 并集。
     */
    private boolean judgePermission(Collection<String> roles, String perm) {
        refreshIfStale();
        Set<String> globalDenies = new java.util.HashSet<>();
        List<RolePermBinding> bindings = new ArrayList<>();
        boolean admin = false;
        for (String role : roles) {
            RolePermBinding b = permBindingOf(role);
            if (b == null) continue;
            if (isAdminCode(role)) admin = true;
            bindings.add(b);
            for (Map.Entry<String, Boolean> d : b.denies().entrySet()) {
                if (!Boolean.TRUE.equals(d.getValue())) globalDenies.add(d.getKey());
            }
        }
        if (globalDenies.contains(perm)) return false;
        if (admin) return true;
        for (RolePermBinding b : bindings) {
            if (!b.allows().contains(perm)) continue;
            if (Boolean.TRUE.equals(b.denies().get(perm))) continue;
            return true;
        }
        return false;
    }

    /**
     * 授权点直查（B3 收口：控制器内 adminGuard.isAdmin() 的替代判定入口）。
     * 语义与端点判定一致：全局 deny 压过一切；管理员级 = 全量 allow（仍受 deny）；
     * 其余角色按 allow 并集（exempt deny 只压本角色）。
     */
    public boolean hasPermission(Collection<String> roles, String permission) {
        if (roles == null || roles.isEmpty() || permission == null || permission.isBlank()) return false;
        return judgePermission(roles, permission.trim());
    }

    /** 端点归属的授权点：精确 (method|ALL, path) → 占位 pattern 匹配；未标注/未登记 → null */
    public String permissionOfEndpoint(String method, String path) {
        loadApiPermSnapshot();
        String p = apiPermExact.get(method.toUpperCase() + " " + path);
        if (p != null) return p;
        p = apiPermExact.get("ALL " + path);
        if (p != null) return p;
        for (ApiPermEntry e : apiPermPatterns) {
            boolean methodOk = "ALL".equals(e.method()) || e.method().equalsIgnoreCase(method);
            if (methodOk && pathMatcher.match(e.path(), path)) return e.perm();
        }
        return null;
    }

    private void loadApiPermSnapshot() {
        refreshIfStale();
        if (apiPermLoaded) return;
        synchronized (this) {
            if (apiPermLoaded) return;
            Map<String, String> exact = new LinkedHashMap<>();
            List<ApiPermEntry> patterns = new ArrayList<>();
            for (ApiEndpoint api : apiEndpointMapper.selectList(null)) {
                if (api.getPermissionCode() == null || api.getPermissionCode().isBlank()) continue;
                String m = api.getMethod() == null ? "ALL" : api.getMethod().toUpperCase();
                ApiPermEntry e = new ApiPermEntry(m, api.getPath(), api.getPermissionCode());
                if (api.getPath() != null && api.getPath().indexOf('{') >= 0) patterns.add(e);
                else { exact.put(m + " " + api.getPath(), api.getPermissionCode()); exact.put("ALL " + api.getPath(), api.getPermissionCode()); }
            }
            apiPermExact = exact;
            apiPermPatterns = List.copyOf(patterns);
            apiPermLoaded = true;
        }
    }

    /** 角色的授权点绑定（懒加载 + 缓存；角色不存在/停用 → 空绑定，按无授权处理） */
    private RolePermBinding permBindingOf(String role) {
        if (role == null || role.isBlank()) return new RolePermBinding(Set.of(), Map.of());
        Snap s = snap();
        RolePermBinding cached = s.permBindings().get(role);
        if (cached != null) return cached;
        synchronized (this) {
            s = snap();
            cached = s.permBindings().get(role);
            if (cached != null) return cached;
            Role r = s.roles().get(role);
            if (r == null || (r.getStatus() != null && r.getStatus() == 0)) {
                RolePermBinding none = new RolePermBinding(Set.of(), Map.of());
                putSnap(cur -> new Snap(cur.roles(), cur.apiPatterns(), cur.menuBindings(),
                        cur.apiIds(), put(cur.permBindings(), role, none), cur.expireAt()));
                return none;
            }
            Set<String> allows = new java.util.LinkedHashSet<>();
            Map<String, Boolean> denies = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : rolePermissionMapper.rowsOfRole(role)) {
                String perm = String.valueOf(row.get("permissionCode"));
                boolean deny = "deny".equalsIgnoreCase(String.valueOf(row.get("effect")));
                if (deny) denies.put(perm, Integer.valueOf(1).equals(safeInt(row.get("exempt"))));
                else allows.add(perm);
            }
            RolePermBinding b = new RolePermBinding(Set.copyOf(allows), Map.copyOf(denies));
            putSnap(cur -> new Snap(cur.roles(), cur.apiPatterns(), cur.menuBindings(),
                    cur.apiIds(), put(cur.permBindings(), role, b), cur.expireAt()));
            return b;
        }
    }

    /** 单键 copy-on-write 写回当前租户快照（compute 原子，防并发丢写） */
    private void putSnap(java.util.function.UnaryOperator<Snap> fn) {
        snapshots.compute(tenantKey(), (k, cur) -> fn.apply(cur == null ? Snap.empty() : cur));
    }

    private static <V> Map<String, V> put(Map<String, V> m, String k, V v) {
        Map<String, V> next = new ConcurrentHashMap<>(m);
        next.put(k, v);
        return next;
    }

    private static Integer safeInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? 0 : Integer.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private void recordShadowDiff(String method, String path, Collection<String> roles, boolean legacy, boolean next) {
        if (legacy == next) return;
        String key = method + " " + path + " => " + (legacy ? "旧链路放行" : "旧链路拒绝") + "/" + (next ? "授权点放行" : "授权点拒绝");
        if (shadowDiffs.size() < 512) {
            long[] c = shadowDiffs.computeIfAbsent(key, k -> {
                log.info("[RBAC][SHADOW] 判定差异首次出现: {}（roles={}）", k, roles);
                return new long[1];
            });
            c[0]++;
        }
    }

    /** 当前是否 enforce（拦截器分流用：true=白名单收口为显式公开端点集 + 授权点判定生效） */
    public boolean permissionModeEnforced() {
        return "enforce".equalsIgnoreCase(permissionMode == null ? "" : permissionMode.trim());
    }

    /** 当前判定模式（诊断/前端展示用） */
    public String permissionMode() {
        return permissionMode == null ? "legacy" : permissionMode.trim().toLowerCase();
    }

    /** shadow 差异条数（运维排障 / M2 看板取数用） */
    public int shadowDiffCount() {
        return shadowDiffs.size();
    }

    /** 某角色的 allow 授权点集（权限页回显 / 反查用） */
    public List<String> allowPermissionsOfRole(String role) {
        RolePermBinding b = permBindingOf(role);
        return b == null ? List.of() : List.copyOf(b.allows());
    }

    /**
     * 用户的「有效授权点」视图（D-3）：各角色 allow 并集 − 全局 deny；
     * 管理员级返回 admin 标记 + 全局 deny 清单（B1 降级后管理员不再万能，deny 要显形）。
     */
    public Map<String, Object> effectivePermissionsOfUser(Collection<String> roles) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (roles == null || roles.isEmpty()) {
            out.put("permissions", List.of());
            out.put("globalDenies", List.of());
            return out;
        }
        Set<String> globalDenies = new java.util.HashSet<>();
        Set<String> allows = new java.util.LinkedHashSet<>();
        boolean admin = false;
        for (String role : roles) {
            RolePermBinding b = permBindingOf(role);
            if (b == null) continue;
            if (isAdminCode(role)) admin = true;
            allows.addAll(b.allows());
            for (Map.Entry<String, Boolean> d : b.denies().entrySet()) {
                if (!Boolean.TRUE.equals(d.getValue())) globalDenies.add(d.getKey());
            }
        }
        if (admin) {
            // B1 降级口径：管理员级 = 全量 allow − 全局 deny；deny 清单显形供排查
            out.put("permissions", List.of("(admin:all-allow)"));
            out.put("globalDenies", List.copyOf(globalDenies));
            return out;
        }
        allows.removeAll(globalDenies);
        out.put("permissions", List.copyOf(allows));
        out.put("globalDenies", List.copyOf(globalDenies));
        return out;
    }

    // ==================== 判定（每请求热路径） ====================

    /**
     * 是否管理员级角色：内置 superadmin/admin 恒真；
     * 自定义角色需 {@code admin_flag=1} 且 {@code status=1}；角色不存在/停用 → false（fail-closed）。
     */
    public boolean isAdminCode(String role) {
        if (role == null || role.isBlank()) return false;
        if (BUILTIN_ADMIN.contains(role)) return true;
        Role r = roles().get(role);
        return r != null && r.getAdminFlag() != null && r.getAdminFlag() == 1
                && (r.getStatus() == null || r.getStatus() == 1);
    }

    /** 角色集合中任一为管理员级（多角色并集判定：并集放大权限） */
    public boolean isAdminAny(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) return false;
        for (String r : roles) {
            if (isAdminCode(r)) return true;
        }
        return false;
    }

    /** 角色是否存在且启用（OrgService 用户建档校验用；内置角色恒存在——种子兜底） */
    public boolean existsActive(String role) {
        if (role == null || role.isBlank()) return false;
        if (BUILTIN_ADMIN.contains(role) || "user".equals(role)) return true;
        Role r = roles().get(role);
        return r != null && (r.getStatus() == null || r.getStatus() == 1);
    }

    /** 管理员级角色编码全集（内置 superadmin/admin + 自定义 admin_flag=1；锁死保护用） */
    public List<String> adminCodes() {
        List<String> out = new ArrayList<>(BUILTIN_ADMIN);
        for (Role r : roles().values()) {
            if (r.getAdminFlag() != null && r.getAdminFlag() == 1) out.add(r.getCode());
        }
        return out;
    }

    /**
     * 非管理员角色的接口级鉴权：按角色绑定的接口（method + path pattern）匹配。
     * 匹配规则：method 相等（或绑定侧为 ALL）；path 用 AntPathMatcher（{@code /api/ai/agent/{id}} 可匹配实际路径）。
     * 角色不存在/停用/无绑定 → false。
     */
    public boolean canAccess(String role, String method, String path) {
        if (role == null || role.isBlank() || method == null || path == null) return false;
        Role r = roles().get(role);
        if (r == null || (r.getStatus() != null && r.getStatus() == 0)) return false;
        List<ApiPattern> patterns = apiPatternsOf(role);
        for (ApiPattern p : patterns) {
            boolean methodOk = "ALL".equalsIgnoreCase(p.method()) || p.method().equalsIgnoreCase(method);
            if (methodOk && pathMatcher.match(p.path(), path)) return true;
        }
        return false;
    }

    /** 按角色懒加载缓存入口统一先触达 snap()：TTL 过期在其内部重建（含绑定表清零） */
    private void refreshIfStale() {
        snap();
    }

    /** 角色绑定的接口 id（权限管理页回显用，与 canAccess 同源缓存） */
    public List<String> apiIdsOf(String role) {
        if (role == null || role.isBlank()) return List.of();
        Snap s = snap();
        List<String> cached = s.apiIds().get(role);
        if (cached != null) return cached;
        synchronized (this) {
            s = snap();
            cached = s.apiIds().get(role);
            if (cached != null) return cached;
            List<String> ids = List.copyOf(roleApiMapper.apiIdsOfRole(role));
            putSnap(cur -> new Snap(cur.roles(), cur.apiPatterns(), cur.menuBindings(),
                    put(cur.apiIds(), role, ids), cur.permBindings(), cur.expireAt()));
            return ids;
        }
    }

    private List<ApiPattern> apiPatternsOf(String role) {
        Snap s = snap();
        List<ApiPattern> cached = s.apiPatterns().get(role);
        if (cached != null) return cached;
        synchronized (this) {
            s = snap();
            cached = s.apiPatterns().get(role);
            if (cached != null) return cached;
            List<String> ids = roleApiMapper.apiIdsOfRole(role);
            List<ApiPattern> built = new ArrayList<>();
            if (!ids.isEmpty()) {
                for (ApiEndpoint api : apiEndpointMapper.selectBatchIds(ids)) {
                    built.add(new ApiPattern(api.getMethod() == null ? "ALL" : api.getMethod().toUpperCase(),
                            api.getPath()));
                }
            }
            // 写回快照：copy-on-write，避免并发遍历旧引用被清空
            putSnap(cur -> new Snap(cur.roles(), put(cur.apiPatterns(), role, List.copyOf(built)),
                    cur.menuBindings(), cur.apiIds(), cur.permBindings(), cur.expireAt()));
            return built;
        }
    }

    /** 角色绑定的菜单 id（/auth/me 下发侧边栏用） */
    public List<String> menuIdsOf(String role) {
        if (role == null || role.isBlank()) return List.of();
        Snap s = snap();
        List<String> cached = s.menuBindings().get(role);
        if (cached != null) return cached;
        synchronized (this) {
            s = snap();
            cached = s.menuBindings().get(role);
            if (cached != null) return cached;
            List<String> ids = List.copyOf(roleMenuMapper.menuIdsOfRole(role));
            putSnap(cur -> new Snap(cur.roles(), cur.apiPatterns(),
                    put(cur.menuBindings(), role, ids), cur.apiIds(), cur.permBindings(), cur.expireAt()));
            return ids;
        }
    }

    // ==================== 角色 CRUD ====================

    /** 角色列表（内置在前，其余按编码排序；平台视图混排多租户时带 tenantId 供前端标注，批次 F4） */
    public List<Role> list() {
        List<Role> out = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .orderByDesc(Role::getBuiltin)
                .orderByAsc(Role::getCode));
        try {
            Map<String, String> codeTenant = new LinkedHashMap<>();
            for (Map<String, Object> row : roleTenantRows()) {
                codeTenant.put(String.valueOf(row.get("code")), String.valueOf(row.get("tenant_id")));
            }
            for (Role r : out) r.setTenantId(codeTenant.get(r.getCode()));
        } catch (Exception ignored) {
            // 展示字段填充失败不影响主表
        }
        return out;
    }

    /** code → tenant_id 全量映射（原生 SQL 绕过租户谓词——展示字段要跨租户标注，范围由主查询圈定） */
    private List<Map<String, Object>> roleTenantRows() {
        return jdbc.queryForList("SELECT tenant_id, code FROM c_ai_role");
    }

    /** 角色下拉选项（用户建档用：仅启用角色，含内置） */
    public List<Map<String, Object>> options() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Role r : list()) {
            if (r.getStatus() != null && r.getStatus() == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.getCode());
            m.put("name", r.getName());
            m.put("adminFlag", r.getAdminFlag());
            m.put("builtin", r.getBuiltin());
            out.add(m);
        }
        return out;
    }

    public Role get(String code) {
        return roles().get(code);
    }

    @Transactional
    public Role create(String code, String name, String description, Integer adminFlag) {
        String c = normalizeCode(code);
        String n = normalizeName(name);
        if (roles().containsKey(c)) throw new BizException("角色编码已存在");
        Role r = new Role();
        r.setCode(c);
        r.setName(n);
        r.setDescription(trimOrNull(description));
        r.setAdminFlag(Integer.valueOf(1).equals(adminFlag) ? 1 : 0);
        r.setBuiltin(0);
        r.setStatus(1);
        roleMapper.insert(r);
        invalidateCaches();
        audit.record("[AUDIT] 新建角色 code={} name={} admin={}", c, n, r.getAdminFlag());
        return r;
    }

    /**
     * 编辑角色。内置角色仅允许改名称/描述（admin_flag/status/builtin 由代码语义锁定，
     * 防止把内置管理员改停用导致全员锁死）；自定义角色可改全部可编辑字段。
     */
    @Transactional
    public void update(String code, String name, String description, Integer adminFlag, Integer status) {
        Role r = roleMapper.selectById(code);
        if (r == null) throw new BizException("角色不存在");
        boolean builtin = r.getBuiltin() != null && r.getBuiltin() == 1;
        if (name != null) r.setName(normalizeName(name));
        if (description != null) r.setDescription(trimOrNull(description));
        if (!builtin) {
            if (adminFlag != null) r.setAdminFlag(adminFlag == 1 ? 1 : 0);
            if (status != null) {
                if (status != 0 && status != 1) throw new BizException("非法状态");
                r.setStatus(status);
            }
        } else if ((adminFlag != null && adminFlag != r.getAdminFlag())
                || (status != null && !status.equals(r.getStatus()))) {
            throw new BizException("内置角色的管理员级与状态不可修改");
        }
        roleMapper.updateById(r);
        invalidateCaches();
        audit.record("[AUDIT] 编辑角色 code={} name={} admin={} status={}", code, r.getName(), r.getAdminFlag(), r.getStatus());
    }

    /** 删除角色：内置不可删；仍有用户挂靠不可删；级联清绑定 */
    @Transactional
    public void delete(String code) {
        Role r = roleMapper.selectById(code);
        if (r == null) throw new BizException("角色不存在");
        if (r.getBuiltin() != null && r.getBuiltin() == 1) throw new BizException("内置角色不可删除");
        // 占用计数走 c_ai_user_role（多角色模型）；直接依赖 Mapper 而非 UserRoleService，避免服务环依赖
        Long used = userRoleMapper.selectCount(
                new LambdaQueryWrapper<com.wenqu.ai.model.UserRole>().eq(com.wenqu.ai.model.UserRole::getRoleCode, code));
        if (used != null && used > 0) {
            throw new BizException("仍有 " + used + " 名用户使用该角色，请先调整其角色");
        }
        roleMapper.deleteById(code);
        roleMenuMapper.unbindAllOfRole(code);
        roleApiMapper.unbindAllOfRole(code);
        rolePermissionMapper.unbindAllOfRoleById(code); // 删除角色连 deny 行一并清
        roleDataScopeMapper.deleteById(code);           // 数据范围行一并清（C2）
        invalidateCaches();
        audit.record("[AUDIT] 删除角色 code={} name={}", code, r.getName());
    }

    // ==================== 菜单 / 接口绑定 ====================

    /**
     * 保存菜单 + 接口绑定（权限配置页单次提交；两者均为全量替换语义）。
     * 单事务、单次缓存失效；校验角色与 id 存在性（不存在的 id 静默丢弃）。
     */
    @Transactional
    public void savePermissions(String code, List<String> menuIds, List<String> apiIds) {
        requireRole(code);
        Set<String> validMenus = menuIdSet();
        roleMenuMapper.unbindAllOfRole(code);
        if (menuIds != null) {
            for (String id : menuIds) {
                if (id != null && validMenus.contains(id)) roleMenuMapper.bind(code, id);
            }
        }
        Set<String> validApis = apiIdSet();
        roleApiMapper.unbindAllOfRole(code);
        if (apiIds != null) {
            for (String id : apiIds) {
                if (id != null && validApis.contains(id)) roleApiMapper.bind(code, id);
            }
        }
        // 授权点链路双写（方案 A6/A7，双跑窗口内两表并存）：接口绑定折算成授权点 allow 行。
        // 管理员级角色不落行——判定按 admin_flag 展开（D5：不写 106 行绑定，新增授权点不漏配）。
        // enforce 稳定后 role_api 写路径随该表一起退役。
        Role roleDef = roles().get(code);
        if (roleDef == null || roleDef.getAdminFlag() == null || roleDef.getAdminFlag() != 1) {
            rolePermissionMapper.unbindAllowsOfRole(code); // 只清 allow；deny 行归 saveDenyRules 管（B2）
            if (apiIds != null && !apiIds.isEmpty()) {
                Set<String> perms = new java.util.LinkedHashSet<>();
                for (ApiEndpoint api : apiEndpointMapper.selectBatchIds(apiIds)) {
                    if (api.getPermissionCode() != null && !api.getPermissionCode().isBlank()) {
                        perms.add(api.getPermissionCode());
                    }
                }
                for (String perm : perms) rolePermissionMapper.bindAllow(code, perm);
            }
        }
        invalidateCaches();
        // G12：审计记明细（id 清单），不只记个数——事后要能回答「当时到底授权了哪些接口」
        audit.record("[AUDIT] 保存角色权限绑定 code={} menus={} apis={}", code,
                menuIds == null ? List.of() : menuIds, apiIds == null ? List.of() : apiIds);
    }

    /**
     * 按授权点保存角色权限（A7 主路径：前端权限页以授权点粒度勾选）。
     * 双跑窗口内反向双写：授权点 → 名下全部端点折算回 {@code c_ai_role_api}
     * （legacy 判定数据源必须与新语义一致，否则 shadow 差异全是假阳性）；
     * 管理员级角色不落行（D5，与正向双写同口径）。enforce 稳定后折算段退役。
     */
    @Transactional
    public void savePermissionPoints(String code, List<String> menuIds, List<String> permissionCodes) {
        requireRole(code);
        Set<String> validMenus = menuIdSet();
        roleMenuMapper.unbindAllOfRole(code);
        if (menuIds != null) {
            for (String id : menuIds) {
                if (id != null && validMenus.contains(id)) roleMenuMapper.bind(code, id);
            }
        }
        Set<String> knownPerms = new java.util.HashSet<>(permissionCatalogMapper.allCodes());
        rolePermissionMapper.unbindAllowsOfRole(code); // 只清 allow；deny 行归 saveDenyRules 管（B2）
        Set<String> granted = new java.util.LinkedHashSet<>();
        if (permissionCodes != null) {
            for (String perm : permissionCodes) {
                if (perm == null || perm.isBlank() || !knownPerms.contains(perm)) continue;
                if (granted.add(perm)) rolePermissionMapper.bindAllow(code, perm);
            }
        }
        Role roleDef = roles().get(code);
        if (roleDef == null || roleDef.getAdminFlag() == null || roleDef.getAdminFlag() != 1) {
            roleApiMapper.unbindAllOfRole(code);
            if (!granted.isEmpty()) {
                for (String perm : granted) {
                    for (ApiEndpoint api : apiEndpointMapper.selectList(new LambdaQueryWrapper<ApiEndpoint>()
                            .eq(ApiEndpoint::getPermissionCode, perm))) {
                        try {
                            roleApiMapper.bind(code, api.getId());
                        } catch (Exception ignored) {
                            // 唯一键冲突（同端点已被其它授权点路径写入）忽略
                        }
                    }
                }
            }
        }
        invalidateCaches();
        // G12：授权点 id 清单入 detail（不只记个数）
        audit.record("[AUDIT] 保存角色权限绑定（授权点）code={} permissions={} menus={}", code,
                List.copyOf(granted), menuIds == null ? List.of() : menuIds);
    }

    /**
     * 保存角色的 deny 规则（B2 全量替换，只动 deny 行）：{permissionCode, exempt} 列表。
     * deny 落在授权点粒度；exempt=1 表示只在该角色内部生效、不压其它角色给的 allow（D3 豁免）。
     */
    @Transactional
    public void saveDenyRules(String code, List<Map<String, Object>> denies) {
        requireRole(code);
        Set<String> knownPerms = new java.util.HashSet<>(permissionCatalogMapper.allCodes());
        rolePermissionMapper.unbindDeniesOfRole(code);
        int n = 0;
        if (denies != null) {
            for (Map<String, Object> d : denies) {
                if (d == null) continue;
                String perm = d.get("permissionCode") == null ? null : String.valueOf(d.get("permissionCode")).trim();
                if (perm == null || perm.isEmpty() || !knownPerms.contains(perm)) continue;
                boolean exempt = Integer.valueOf(1).equals(safeInt(d.get("exempt")));
                rolePermissionMapper.bindDeny(code, perm, exempt ? 1 : 0);
                n++;
            }
        }
        invalidateCaches();
        audit.record("[AUDIT] 保存角色 deny 规则 code={} denies={}", code,
                denies == null ? List.of() : denies);
    }

    /** 某角色的 deny 行（权限页回显） */
    public List<Map<String, Object>> denyRulesOfRole(String code) {
        return rolePermissionMapper.denyRowsOfRole(code);
    }

    // ==================== 反查（批次 D-1/D-2） ====================

    /** 各角色成员数（角色列表出参用；一次全量，表小） */
    public Map<String, Long> memberCountsByRole() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (com.wenqu.ai.model.UserRole ur : userRoleMapper.selectList(null)) {
            out.merge(ur.getRoleCode(), 1L, Long::sum);
        }
        return out;
    }

    /** 角色成员清单（谁挂着这个角色；granted_by 区分手动/IdP 授予） */
    public List<Map<String, Object>> membersOfRole(String code) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.wenqu.ai.model.UserRole ur : userRoleMapper.selectList(
                new LambdaQueryWrapper<com.wenqu.ai.model.UserRole>()
                        .eq(com.wenqu.ai.model.UserRole::getRoleCode, code))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uid", ur.getUid());
            m.put("grantedBy", ur.getGrantedBy());
            User u = userMapper.selectById(ur.getUid());
            if (u != null) {
                m.put("username", u.getUsername());
                m.put("status", u.getStatus());
            } else {
                m.put("username", null);   // 用户已删但角色行残留（脏数据显形）
                m.put("status", null);
            }
            out.add(m);
        }
        return out;
    }

    /** 授权点 → 绑定角色清单全量映射（接口页「已授权角色」列用，D-1） */
    public Map<String, List<String>> rolesByPermission() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Role role : list()) {
            RolePermBinding b = permBindingOf(role.getCode());
            if (b == null) continue;
            for (String perm : b.allows()) {
                out.computeIfAbsent(perm, k -> new ArrayList<>()).add(role.getCode());
            }
        }
        return out;
    }

    // ==================== 数据范围（批次 C2） ====================

    /** 某角色的数据范围（无行=own，谁建归谁） */
    public com.wenqu.ai.model.RoleDataScope dataScopeOf(String code) {
        com.wenqu.ai.model.RoleDataScope ds = roleDataScopeMapper.selectById(code);
        if (ds != null) return ds;
        com.wenqu.ai.model.RoleDataScope d = new com.wenqu.ai.model.RoleDataScope();
        d.setRoleCode(code);
        d.setScopeType("own");
        return d;
    }

    /** 保存角色数据范围（C2：角色自带数据范围，资源侧无共享入口） */
    @Transactional
    public void saveDataScope(String code, String scopeType, String departmentId) {
        requireRole(code);
        String st = scopeType == null ? "own" : scopeType.trim();
        if (!java.util.Set.of("all", "own", "department", "department_and_children").contains(st)) {
            throw new BizException("非法数据范围类型");
        }
        com.wenqu.ai.model.RoleDataScope ds = roleDataScopeMapper.selectById(code);
        boolean create = ds == null;
        if (create) {
            ds = new com.wenqu.ai.model.RoleDataScope();
            ds.setRoleCode(code);
        }
        ds.setScopeType(st);
        ds.setDepartmentId("department".equals(st) || "department_and_children".equals(st)
                ? (departmentId == null || departmentId.isBlank() ? null : departmentId.trim()) : null);
        if (create) roleDataScopeMapper.insert(ds);
        else roleDataScopeMapper.updateById(ds);
        resourceVisibilityService.invalidateScopes();
        audit.record("[AUDIT] 保存角色数据范围 code={} scopeType={} departmentId={}", code, st, ds.getDepartmentId());
    }

    /** 授权点目录（权限页分组展示；事实源在代码注解，此处只读） */
    public List<Map<String, Object>> permissionCatalog() {
        return permissionCatalogMapper.listAll();
    }

    private void requireRole(String code) {
        if (code == null || code.isBlank() || roleMapper.selectById(code) == null) {
            throw new BizException("角色不存在");
        }
    }

    private Set<String> menuIdSet() {
        Set<String> s = new java.util.HashSet<>();
        for (com.wenqu.ai.model.Menu m : menuMapper.selectList(null)) s.add(m.getId());
        return s;
    }

    private Set<String> apiIdSet() {
        Set<String> s = new java.util.HashSet<>();
        for (ApiEndpoint a : apiEndpointMapper.selectList(null)) s.add(a.getId());
        return s;
    }

    // ==================== 校验工具 ====================

    private static String normalizeCode(String code) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if (c.isEmpty()) throw new BizException("角色编码不能为空");
        if (!CODE_PATTERN.matcher(c).matches()) {
            throw new BizException("角色编码不合法（小写字母开头，仅小写字母/数字/_/-，≤20 字符）");
        }
        return c;
    }

    private static String normalizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new BizException("角色名称不能为空");
        if (n.length() > 50) throw new BizException("角色名称过长（最多 50 字）");
        return n;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
