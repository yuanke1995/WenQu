package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.ApiEndpointMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.MenuMapper;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 角色服务（RBAC 核心）：角色 CRUD、管理员判定、接口鉴权匹配、菜单/接口绑定。
 * <p>
 * 鉴权入口（每请求都会走，故全走内存缓存）：
 * <ul>
 *   <li>{@link #isAdminCode(String)}：内置 superadmin/admin 直通；自定义角色看 {@code admin_flag=1 且 status=1}。
 *       角色表极小（≤几十行），缓存为全量快照，任何角色写操作后整体失效重建。</li>
 *   <li>{@link #canAccessAny(Collection, String, String)}：唯一的接口判定入口——
 *       端点 → 授权点（{@code c_ai_api.permission_code}，扫描器按 {@code @Permission} 写入）
 *       → 角色授权点绑定（{@code c_ai_role_permission}）。未标注端点只有管理员级可达，
 *       未绑定一律拒绝（fail-closed 由拦截器保证）。</li>
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
    /** 统一审计事件：同一份格式串派生日志与结构化事件，不再两处抄 */
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
    private final MenuMapper menuMapper;
    private final com.wenqu.ai.mapper.MenuApiMapper menuApiMapper;
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

    // ==================== 缓存（按租户键控） ====================

    /**
     * 每租户一份角色快照：角色/绑定全部按租户隔离后，判定热路径必须按
     * {@code TenantContext} 键控取各自的快照，否则 A 租户能读到 B 租户的角色定义（直接越权源）。
     * 键规则：平台视图（asPlatform）与未绑定线程（启动任务/调度器）共用 {@link #PLATFORM_KEY}
     * ——这两类线程本来就跨租户遍历，快照不做租户过滤；
     * 绑定态请求按租户各建各的。表小（每租户 ≤几十行），整份重建成本可忽略。
     */
    private record Snap(Map<String, Role> roles,
                        Map<String, List<String>> menuBindings,
                        Map<String, RolePermBinding> permBindings,
                        long expireAt) {
        static Snap empty() {
            return new Snap(Map.of(), Map.of(), Map.of(), 0L);
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
        return new Snap(roles, Map.of(), Map.of(), System.currentTimeMillis() + SNAPSHOT_TTL_MS);
    }

    /** 启动即订阅跨副本失效广播：别的副本改了角色/绑定，本副本当场清快照。订阅失败只告警（退化为仅 TTL） */
    @PostConstruct
    void subscribeReplicaInvalidation() {
        try {
            replicaCoordination.listen(CHANNEL_RBAC_INVALIDATE, (payload, from) -> {
                String scope = payload == null ? null : payload.getString("scope");
                if ("api".equals(scope)) invalidateEndpointSnapshotLocal();
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
    }

    /** 端点表变了（扫描登记/接口增删改）：端点→授权点映射作废，下次判定重建 */
    private synchronized void invalidateEndpointSnapshotLocal() {
        apiPermExact = Map.of();
        apiPermPatterns = List.of();
        apiPermLoaded = false;
    }

    /**
     * 角色或其绑定变化后调用：本地整体失效（表小，重建成本低）+ 广播其它副本当场失效。
     * 写路径统一走这里；被动过期与广播接收侧走 {@link #invalidateCachesLocal()}。
     */
    public synchronized void invalidateCaches() {
        invalidateCachesLocal();
        broadcastInvalidation("role");
    }

    /** 端点表变化（扫描登记/增删改）后调用：失效「端点→授权点」映射 + 广播 */
    public synchronized void invalidateEndpointCache() {
        invalidateEndpointSnapshotLocal();
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

    // ==================== 授权点判定 ====================

    /** 角色的授权点绑定：allow 集 + deny 行（deny → 是否豁免；exempt=true 只压本角色） */
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

    /**
     * 多角色接口鉴权（唯一判定入口）：端点 → 授权点（{@code @Permission} 扫描结果）→ 角色绑定。
     * <ul>
     *   <li>未标注授权点的端点：只有管理员级可达（新端点默认对管理员开，其余拒绝，fail-closed）；</li>
     *   <li>其余走 {@link #judgePermission}：全局 deny 压过一切，管理员级展开为全量 allow，
     *       普通角色按 allow 并集（exempt deny 只压本角色）。</li>
     * </ul>
     */
    public boolean canAccessAny(Collection<String> roles, String method, String path) {
        if (roles == null || roles.isEmpty() || method == null || path == null) return false;
        String perm = permissionOfEndpoint(method, path);
        if (perm == null) return hasAdminLevel(roles);
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
     * 授权点直查（控制器内 adminGuard.isAdmin() 的替代判定入口）。
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
                putSnap(cur -> new Snap(cur.roles(), cur.menuBindings(),
                        put(cur.permBindings(), role, none), cur.expireAt()));
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
            putSnap(cur -> new Snap(cur.roles(), cur.menuBindings(),
                    put(cur.permBindings(), role, b), cur.expireAt()));
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

    /** 按角色懒加载缓存入口统一先触达 snap()：TTL 过期在其内部重建（含绑定表清零） */
    private void refreshIfStale() {
        snap();
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
            putSnap(cur -> new Snap(cur.roles(),
                    put(cur.menuBindings(), role, ids), cur.permBindings(), cur.expireAt()));
            return ids;
        }
    }

    // ==================== 角色 CRUD ====================

    /** 角色列表（内置在前，其余按编码排序；平台视图混排多租户时带 tenantId 供前端标注） */
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
        // 过期行不占位：到期即失效的临时授权不该把角色钉成「删不掉」
        Long used = userRoleMapper.selectCount(
                activeRoleRows().eq(com.wenqu.ai.model.UserRole::getRoleCode, code));
        if (used != null && used > 0) {
            throw new BizException("仍有 " + used + " 名用户使用该角色，请先调整其角色");
        }
        roleMapper.deleteById(code);
        roleMenuMapper.unbindAllOfRole(code);
        rolePermissionMapper.unbindAllOfRoleById(code); // 删除角色连 deny 行一并清
        roleDataScopeMapper.deleteById(code);           // 数据范围行一并清（C2）
        resourceVisibilityService.invalidateScopes();   // 范围快照别等到 TTL
        invalidateCaches();
        audit.record("[AUDIT] 删除角色 code={} name={}", code, r.getName());
    }

    // ==================== 菜单 / 接口绑定 ====================

    /**
     * 权限页唯一写入路径：菜单 + 授权点 allow + deny，一次事务全量替换。
     * <p>判定数据源只有 {@code c_ai_role_permission} 一张表。
     *
     * @param denies null=本次不动 deny 行；空表=清空
     */
    @Transactional
    public void savePermissionPoints(String code, List<String> menuIds, List<String> permissionCodes,
                                     List<Map<String, Object>> denies) {
        requireRole(code);
        Set<String> validMenus = menuIdSet();
        roleMenuMapper.unbindAllOfRole(code);
        if (menuIds != null) {
            for (String id : menuIds) {
                if (id != null && validMenus.contains(id)) roleMenuMapper.bind(code, id);
            }
        }
        Set<String> knownPerms = new java.util.HashSet<>(permissionCatalogMapper.allCodes());
        // 清 allow 不分管理员级：管理员级只是「不落 allow 行」，不是「不清旧行」——
        // 否则关掉 admin_flag 时历史绑定会原样复活
        rolePermissionMapper.unbindAllowsOfRole(code);
        Set<String> granted = new java.util.LinkedHashSet<>();
        if (permissionCodes != null) {
            for (String perm : permissionCodes) {
                if (perm == null || perm.isBlank() || !knownPerms.contains(perm)) continue;
                if (granted.add(perm)) rolePermissionMapper.bindAllow(code, perm);
            }
        }
        // deny 与 allow 同源提交；管理员级也要能写 deny——「只读管理员」= admin_flag + deny 写类授权点
        int denyWritten = writeDenies(code, granted, denies);
        invalidateCaches();
        audit.record("[AUDIT] 保存角色权限绑定（授权点）code={} permissions={} menus={}{}", code,
                List.copyOf(granted), menuIds == null ? List.of() : menuIds,
                denyWritten < 0 ? "" : " denies=" + denies);
    }

    /**
     * 保存角色的 deny 规则（B2 全量替换，只动 deny 行）：{permissionCode, exempt} 列表。
     * deny 落在授权点粒度；exempt=1 表示只在该角色内部生效、不压其它角色给的 allow（D3 豁免）。
     */
    @Transactional
    public void saveDenyRules(String code, List<Map<String, Object>> denies) {
        requireRole(code);
        int n = writeDenies(code, permBindingOf(code).allows(), denies);
        invalidateCaches();
        audit.record("[AUDIT] 保存角色 deny 规则 code={} denies={}", code,
                denies == null ? List.of() : denies);
    }

    /**
     * deny 行的实际写入（全量替换语义）；返回真实写入条数。denies 为 null 表示本次不动 deny。
     * <p>同一授权点不能同时出现在 allow 与 deny 里：表主键不含 effect，同键第二行会被
     * {@code INSERT IGNORE} 静默丢弃，结果是「保存成功但权限没收紧」——这里当场拒绝并点名。
     */
    private int writeDenies(String code, Set<String> allows, List<Map<String, Object>> denies) {
        if (denies == null) return -1;
        Set<String> knownPerms = new java.util.HashSet<>(permissionCatalogMapper.allCodes());
        List<String> conflicts = new ArrayList<>();
        for (Map<String, Object> d : denies) {
            if (d == null) continue;
            String perm = d.get("permissionCode") == null ? null : String.valueOf(d.get("permissionCode")).trim();
            if (perm != null && !perm.isEmpty() && allows != null && allows.contains(perm)) conflicts.add(perm);
        }
        if (!conflicts.isEmpty()) {
            throw new BizException("同一授权点不能同时允许和禁止：" + String.join("、", conflicts));
        }
        rolePermissionMapper.unbindDeniesOfRole(code);
        int n = 0;
        for (Map<String, Object> d : denies) {
            if (d == null) continue;
            String perm = d.get("permissionCode") == null ? null : String.valueOf(d.get("permissionCode")).trim();
            if (perm == null || perm.isEmpty() || !knownPerms.contains(perm)) continue;
            boolean exempt = Integer.valueOf(1).equals(safeInt(d.get("exempt")));
            n += rolePermissionMapper.bindDeny(code, perm, exempt ? 1 : 0);
        }
        return n;
    }

    /** 某角色的 deny 行（权限页回显） */
    public List<Map<String, Object>> denyRulesOfRole(String code) {
        return rolePermissionMapper.denyRowsOfRole(code);
    }

    // ==================== 反查 ====================

    /** 各角色成员数（角色列表出参用；一次全量，表小） */
    public Map<String, Long> memberCountsByRole() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (com.wenqu.ai.model.UserRole ur : userRoleMapper.selectList(activeRoleRows())) {
            out.merge(ur.getRoleCode(), 1L, Long::sum);
        }
        return out;
    }

    /**
     * 未过期的角色行条件：到期行既不给权限，也不算「这个角色还有人」。
     * <p>反查/守门口径必须与判定同侧——{@code delete(code)} 的占用数、成员徽标、最后一名管理员
     * 保护按原始行算的话，一条已过期的管理员授权会同时骗过这三处：界面说角色删不掉、
     * 徽标说还有人挂着、并且挡住下一次管理员调整说「不能删最后一个」。
     */
    static LambdaQueryWrapper<com.wenqu.ai.model.UserRole> activeRoleRows() {
        return new LambdaQueryWrapper<com.wenqu.ai.model.UserRole>()
                .and(w -> w.isNull(com.wenqu.ai.model.UserRole::getExpireAt)
                        .or().gt(com.wenqu.ai.model.UserRole::getExpireAt, java.time.LocalDateTime.now()));
    }

    /** 角色成员清单（谁挂着这个角色；granted_by 区分手动/IdP 授予） */
    public List<Map<String, Object>> membersOfRole(String code) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.wenqu.ai.model.UserRole ur : userRoleMapper.selectList(
                activeRoleRows().eq(com.wenqu.ai.model.UserRole::getRoleCode, code))) {
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

    /** 菜单 → 可见角色清单（菜单页「可见角色」列用；只读反查，不参与鉴权） */
    public Map<String, List<String>> rolesByMenu() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : roleMenuMapper.allBindings()) {
            String role = String.valueOf(row.get("roleCode"));
            String menu = String.valueOf(row.get("menuId"));
            out.computeIfAbsent(menu, k -> new ArrayList<>()).add(role);
        }
        out.values().forEach(list -> list.sort(String::compareTo));
        return out;
    }

    /**
     * 权限域概览（权限页首屏与角色统计）：一次聚合角色、菜单、接口、授权点与临期授权。
     * <p>只读展示口径，不写任何表、不影响鉴权缓存。</p>
     */
    public Map<String, Object> overview() {
        List<Role> roles = list();
        Map<String, Long> memberCounts = memberCountsByRole();
        List<com.wenqu.ai.model.Menu> menus = menuMapper.selectList(null);
        List<com.wenqu.ai.model.ApiEndpoint> apis = apiEndpointMapper.selectList(null);
        Set<String> boundApiIds = new HashSet<>(menuApiMapper.boundApiIds());
        long permissionTotal = permissionCatalogMapper.allCodes().size();

        long activeRoles = roles.stream().filter(r -> r.getStatus() == null || r.getStatus() != 0).count();
        long adminRoles = roles.stream().filter(r -> r.getAdminFlag() != null && r.getAdminFlag() == 1).count();
        long emptyRoles = roles.stream()
                .filter(r -> (r.getBuiltin() == null || r.getBuiltin() != 1)
                        && memberCounts.getOrDefault(r.getCode(), 0L) == 0)
                .count();
        long hiddenMenus = menus.stream().filter(m -> m.getVisible() != null && m.getVisible() == 0).count();
        long apisWithoutPermission = apis.stream()
                .filter(a -> a.getPermissionCode() == null || a.getPermissionCode().isBlank()).count();
        long apisWithoutMenu = apis.stream().filter(a -> !boundApiIds.contains(a.getId())).count();
        long expiring;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            expiring = userRoleMapper.selectCount(new LambdaQueryWrapper<com.wenqu.ai.model.UserRole>()
                    .gt(com.wenqu.ai.model.UserRole::getExpireAt, now)
                    .le(com.wenqu.ai.model.UserRole::getExpireAt, now.plusDays(7)));
        } catch (Exception e) {
            expiring = 0;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roles", (long) roles.size());
        summary.put("activeRoles", activeRoles);
        summary.put("adminRoles", adminRoles);
        summary.put("rolesWithoutMembers", emptyRoles);
        summary.put("menus", (long) menus.size());
        summary.put("hiddenMenus", hiddenMenus);
        summary.put("apis", (long) apis.size());
        summary.put("apisWithoutPermission", apisWithoutPermission);
        summary.put("apisWithoutMenu", apisWithoutMenu);
        summary.put("permissions", permissionTotal);
        summary.put("expiringGrants", expiring);

        List<Map<String, Object>> roleStats = new ArrayList<>();
        for (Role role : roles) {
            List<Map<String, Object>> rows = rolePermissionMapper.rowsOfRole(role.getCode());
            long allowCount = rows.stream().filter(r -> !"deny".equalsIgnoreCase(String.valueOf(r.get("effect")))).count();
            long denyCount = rows.stream().filter(r -> "deny".equalsIgnoreCase(String.valueOf(r.get("effect")))).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", role.getCode());
            m.put("memberCount", memberCounts.getOrDefault(role.getCode(), 0L));
            m.put("menuCount", (long) roleMenuMapper.menuIdsOfRole(role.getCode()).size());
            m.put("permissionCount", allowCount);
            m.put("denyCount", denyCount);
            m.put("lastUpdated", role.getUpdateTime());
            roleStats.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("roles", roleStats);
        return out;
    }

    // ==================== 数据范围 ====================

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
