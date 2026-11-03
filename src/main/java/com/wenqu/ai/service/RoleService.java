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
    private final ReplicaCoordination replicaCoordination;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 授权点判定模式（方案 §8 M2 双跑）：legacy=旧端点绑定链路；shadow=新旧都算、按旧放行、记录差异；enforce=授权点判定生效 */
    @org.springframework.beans.factory.annotation.Value("${ai-app.rbac.permission-mode:legacy}")
    private String permissionMode = "legacy";

    // ==================== 缓存 ====================

    /** 角色全量快照（code → Role；含停用角色，启用状态由调用方按需判断） */
    private volatile Map<String, Role> roleSnapshot = Map.of();

    /** 角色绑定的接口 pattern 快照（roleCode → [(method, path)]；method 大写或 ALL） */
    private volatile Map<String, List<ApiPattern>> apiPatternSnapshot = Map.of();

    /** 角色绑定的菜单 id 快照（roleCode → [menuId]） */
    private volatile Map<String, List<String>> menuBindingSnapshot = Map.of();

    /** 角色绑定的接口 id 快照（roleCode → [apiId]，权限页回显与 canAccess 同源失效） */
    private volatile Map<String, List<String>> apiIdSnapshot = Map.of();

    /** 快照构建时刻 + TTL 的到期点；0 = 已失效，下次访问重建（被动过期只清本地，绝不广播） */
    private volatile long snapshotExpireAt = 0L;

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

    private boolean snapshotStale() {
        return System.currentTimeMillis() >= snapshotExpireAt;
    }

    /** 角色快照（懒加载：首次访问/过期时从库构建；角色写操作后失效） */
    private Map<String, Role> roles() {
        Map<String, Role> snap = roleSnapshot;
        if (!snap.isEmpty() && !snapshotStale()) return snap;
        synchronized (this) {
            if (roleSnapshot.isEmpty() || snapshotStale()) rebuildRoleSnapshot();
            return roleSnapshot;
        }
    }

    private synchronized void rebuildRoleSnapshot() {
        Map<String, Role> m = new LinkedHashMap<>();
        for (Role r : roleMapper.selectList(null)) m.put(r.getCode(), r);
        roleSnapshot = m;
        snapshotExpireAt = System.currentTimeMillis() + SNAPSHOT_TTL_MS;
    }

    /** 只清本地（被动过期与收到远端广播时用；不广播，否则两台副本互相触发成死循环） */
    private synchronized void invalidateCachesLocal() {
        roleSnapshot = Map.of();
        apiPatternSnapshot = Map.of();
        menuBindingSnapshot = Map.of();
        apiIdSnapshot = Map.of();
        permBindingSnapshot = Map.of();
        apiPermExact = Map.of();
        apiPermPatterns = List.of();
        apiPermLoaded = false;
        shadowDiffs.clear();
        snapshotExpireAt = 0L;
    }

    private synchronized void invalidateApiPatternsLocal() {
        apiPatternSnapshot = Map.of();
        apiIdSnapshot = Map.of();
        // 端点表变了 → 端点→授权点映射同样作废（permission_code 挂在 c_ai_api 行上）
        apiPermExact = Map.of();
        apiPermPatterns = List.of();
        apiPermLoaded = false;
        snapshotExpireAt = 0L;
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

    /** 角色 → 授权点绑定（懒加载；invalidateCaches / TTL 与其它快照同生命周期） */
    private volatile Map<String, RolePermBinding> permBindingSnapshot = Map.of();

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
        if (perm == null) return false;
        Set<String> globalDenies = new java.util.HashSet<>();
        List<RolePermBinding> bindings = new ArrayList<>();
        for (String role : roles) {
            RolePermBinding b = permBindingOf(role);
            if (b == null) continue;
            bindings.add(b);
            for (Map.Entry<String, Boolean> d : b.denies().entrySet()) {
                if (!Boolean.TRUE.equals(d.getValue())) globalDenies.add(d.getKey());
            }
        }
        if (globalDenies.contains(perm)) return false;
        for (RolePermBinding b : bindings) {
            if (!b.allows().contains(perm)) continue;
            if (Boolean.TRUE.equals(b.denies().get(perm))) continue;
            return true;
        }
        return false;
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

    /** 角色的授权点绑定（懒加载 + 缓存；角色不存在/停用 → null，按无绑定处理） */
    private RolePermBinding permBindingOf(String role) {
        if (role == null || role.isBlank()) return null;
        refreshIfStale();
        RolePermBinding cached = permBindingSnapshot.get(role);
        if (cached != null) return cached;
        synchronized (this) {
            cached = permBindingSnapshot.get(role);
            if (cached != null) return cached;
            Role r = roles().get(role);
            if (r == null || (r.getStatus() != null && r.getStatus() == 0)) {
                RolePermBinding none = new RolePermBinding(Set.of(), Map.of());
                Map<String, RolePermBinding> next = new ConcurrentHashMap<>(permBindingSnapshot);
                next.put(role, none);
                permBindingSnapshot = next;
                return none;
            }
            Set<String> allows = new java.util.LinkedHashSet<>();
            Map<String, Boolean> denies = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : rolePermissionMapper.rowsOfRole(role)) {
                String perm = String.valueOf(row.get("permissionCode"));
                boolean deny = "deny".equalsIgnoreCase(String.valueOf(row.get("effect")));
                if (deny) denies.put(perm, Integer.valueOf(1).equals(((Number) row.get("exempt")).intValue())
                        || Integer.valueOf(1).equals(safeInt(row.get("exempt"))));
                else allows.add(perm);
            }
            RolePermBinding b = new RolePermBinding(Set.copyOf(allows), Map.copyOf(denies));
            Map<String, RolePermBinding> next = new ConcurrentHashMap<>(permBindingSnapshot);
            next.put(role, b);
            permBindingSnapshot = next;
            return b;
        }
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

    /** 用户的「有效授权点」视图（D-3 最小形态）：各角色 allow 并集 − 全局 deny；管理员级角色返回 ALL 标记 */
    public List<String> effectivePermissionsOfUser(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        Set<String> globalDenies = new java.util.HashSet<>();
        Set<String> allows = new java.util.LinkedHashSet<>();
        boolean admin = false;
        for (String role : roles) {
            if (isAdminCode(role)) { admin = true; continue; }
            RolePermBinding b = permBindingOf(role);
            if (b == null) continue;
            allows.addAll(b.allows());
            for (Map.Entry<String, Boolean> d : b.denies().entrySet()) {
                if (!Boolean.TRUE.equals(d.getValue())) globalDenies.add(d.getKey());
            }
        }
        if (admin) {
            // 管理员级 = 全量 allow（B1 将降级为「仍受 deny 约束」，当前保持旧口径）
            return List.of("(admin:all-allow)");
        }
        allows.removeAll(globalDenies);
        return List.copyOf(allows);
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

    /** 按角色懒加载缓存入口统一先过 TTL：过期则整体清空（含角色快照），下一次访问按需重建 */
    private void refreshIfStale() {
        if (!snapshotStale()) return;
        synchronized (this) {
            if (snapshotStale()) invalidateCachesLocal();
        }
    }

    /** 角色绑定的接口 id（权限管理页回显用，与 canAccess 同源缓存） */
    public List<String> apiIdsOf(String role) {
        if (role == null || role.isBlank()) return List.of();
        refreshIfStale();
        List<String> cached = apiIdSnapshot.get(role);
        if (cached != null) return cached;
        synchronized (this) {
            cached = apiIdSnapshot.get(role);
            if (cached != null) return cached;
            List<String> ids = List.copyOf(roleApiMapper.apiIdsOfRole(role));
            Map<String, List<String>> next = new ConcurrentHashMap<>(apiIdSnapshot);
            next.put(role, ids);
            apiIdSnapshot = next;
            return ids;
        }
    }

    private List<ApiPattern> apiPatternsOf(String role) {
        refreshIfStale();
        List<ApiPattern> cached = apiPatternSnapshot.get(role);
        if (cached != null) return cached;
        synchronized (this) {
            cached = apiPatternSnapshot.get(role);
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
            Map<String, List<ApiPattern>> next = new ConcurrentHashMap<>(apiPatternSnapshot);
            next.put(role, List.copyOf(built));
            apiPatternSnapshot = next;
            return built;
        }
    }

    /** 角色绑定的菜单 id（/auth/me 下发侧边栏用） */
    public List<String> menuIdsOf(String role) {
        if (role == null || role.isBlank()) return List.of();
        refreshIfStale();
        List<String> cached = menuBindingSnapshot.get(role);
        if (cached != null) return cached;
        synchronized (this) {
            cached = menuBindingSnapshot.get(role);
            if (cached != null) return cached;
            List<String> ids = List.copyOf(roleMenuMapper.menuIdsOfRole(role));
            Map<String, List<String>> next = new ConcurrentHashMap<>(menuBindingSnapshot);
            next.put(role, ids);
            menuBindingSnapshot = next;
            return ids;
        }
    }

    // ==================== 角色 CRUD ====================

    /** 角色列表（内置在前，其余按编码排序） */
    public List<Role> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .orderByDesc(Role::getBuiltin)
                .orderByAsc(Role::getCode));
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
        rolePermissionMapper.unbindAllOfRole(code);
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
            rolePermissionMapper.unbindAllOfRole(code);
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
        audit.record("[AUDIT] 保存角色权限绑定 code={} menus={} apis={}", code,
                menuIds == null ? 0 : menuIds.size(), apiIds == null ? 0 : apiIds.size());
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
