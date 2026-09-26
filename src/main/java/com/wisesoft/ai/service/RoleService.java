package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.ApiEndpointMapper;
import com.wisesoft.ai.mapper.MenuMapper;
import com.wisesoft.ai.mapper.RoleApiMapper;
import com.wisesoft.ai.mapper.RoleMapper;
import com.wisesoft.ai.mapper.RoleMenuMapper;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.model.ApiEndpoint;
import com.wisesoft.ai.model.Role;
import com.wisesoft.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
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
 * 缓存一致性：本进程单实例部署形态，volatile 引用替换即生效，无需跨节点失效。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    /** 角色编码规则：小写字母开头，允许小写字母/数字/下划线/连字符，≤20 字符（对齐 c_ai_user.role VARCHAR(20)） */
    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,19}");

    /** 内置管理员级角色编码（硬编码直通，不依赖种子数据是否就绪） */
    private static final Set<String> BUILTIN_ADMIN = Set.of("superadmin", "admin");

    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleApiMapper roleApiMapper;
    private final MenuMapper menuMapper;
    private final ApiEndpointMapper apiEndpointMapper;
    private final UserMapper userMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // ==================== 缓存 ====================

    /** 角色全量快照（code → Role；含停用角色，启用状态由调用方按需判断） */
    private volatile Map<String, Role> roleSnapshot = Map.of();

    /** 角色绑定的接口 pattern 快照（roleCode → [(method, path)]；method 大写或 ALL） */
    private volatile Map<String, List<ApiPattern>> apiPatternSnapshot = Map.of();

    /** 角色绑定的菜单 id 快照（roleCode → [menuId]） */
    private volatile Map<String, List<String>> menuBindingSnapshot = Map.of();

    /** 角色绑定的接口 id 快照（roleCode → [apiId]，权限页回显与 canAccess 同源失效） */
    private volatile Map<String, List<String>> apiIdSnapshot = Map.of();

    /** 接口 pattern（method + path 二元组） */
    private record ApiPattern(String method, String path) {
    }

    /** 角色快照（懒加载：首次访问时从库构建；角色写操作后失效） */
    private Map<String, Role> roles() {
        Map<String, Role> snap = roleSnapshot;
        if (!snap.isEmpty()) return snap;
        synchronized (this) {
            if (roleSnapshot.isEmpty()) rebuildRoleSnapshot();
            return roleSnapshot;
        }
    }

    private synchronized void rebuildRoleSnapshot() {
        Map<String, Role> m = new LinkedHashMap<>();
        for (Role r : roleMapper.selectList(null)) m.put(r.getCode(), r);
        roleSnapshot = m;
    }

    /** 角色或其绑定变化后调用：整体失效（表小，重建成本低） */
    public synchronized void invalidateCaches() {
        roleSnapshot = Map.of();
        apiPatternSnapshot = Map.of();
        menuBindingSnapshot = Map.of();
        apiIdSnapshot = Map.of();
    }

    /** 接口表变化（扫描登记/增删改）后调用：仅失效接口相关缓存 */
    public synchronized void invalidateApiPatterns() {
        apiPatternSnapshot = Map.of();
        apiIdSnapshot = Map.of();
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

    /** 角色绑定的接口 id（权限管理页回显用，与 canAccess 同源缓存） */
    public List<String> apiIdsOf(String role) {
        if (role == null || role.isBlank()) return List.of();
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
        log.info("[AUDIT] 新建角色 code={} name={} admin={}", c, n, r.getAdminFlag());
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
        log.info("[AUDIT] 编辑角色 code={} name={} admin={} status={}", code, r.getName(), r.getAdminFlag(), r.getStatus());
    }

    /** 删除角色：内置不可删；仍有用户挂靠不可删；级联清绑定 */
    @Transactional
    public void delete(String code) {
        Role r = roleMapper.selectById(code);
        if (r == null) throw new BizException("角色不存在");
        if (r.getBuiltin() != null && r.getBuiltin() == 1) throw new BizException("内置角色不可删除");
        Long used = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, code));
        if (used != null && used > 0) {
            throw new BizException("仍有 " + used + " 名用户使用该角色，请先调整其角色");
        }
        roleMapper.deleteById(code);
        roleMenuMapper.unbindAllOfRole(code);
        roleApiMapper.unbindAllOfRole(code);
        invalidateCaches();
        log.info("[AUDIT] 删除角色 code={} name={}", code, r.getName());
    }

    // ==================== 菜单 / 接口绑定 ====================

    /** 绑定菜单 id 集合（全量替换语义；校验角色与菜单存在性） */
    @Transactional
    public void saveMenus(String code, List<String> menuIds) {
        requireRole(code);
        Set<String> valid = menuIdSet();
        roleMenuMapper.unbindAllOfRole(code);
        if (menuIds != null) {
            for (String id : menuIds) {
                if (id != null && valid.contains(id)) roleMenuMapper.bind(code, id);
            }
        }
        invalidateCaches();
        log.info("[AUDIT] 保存角色菜单绑定 code={} count={}", code, menuIds == null ? 0 : menuIds.size());
    }

    /** 绑定接口 id 集合（全量替换语义；校验角色与接口存在性） */
    @Transactional
    public void saveApis(String code, List<String> apiIds) {
        requireRole(code);
        Set<String> valid = apiIdSet();
        roleApiMapper.unbindAllOfRole(code);
        if (apiIds != null) {
            for (String id : apiIds) {
                if (id != null && valid.contains(id)) roleApiMapper.bind(code, id);
            }
        }
        invalidateCaches();
        log.info("[AUDIT] 保存角色接口绑定 code={} count={}", code, apiIds == null ? 0 : apiIds.size());
    }

    private void requireRole(String code) {
        if (code == null || code.isBlank() || roleMapper.selectById(code) == null) {
            throw new BizException("角色不存在");
        }
    }

    private Set<String> menuIdSet() {
        Set<String> s = new java.util.HashSet<>();
        for (com.wisesoft.ai.model.Menu m : menuMapper.selectList(null)) s.add(m.getId());
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
