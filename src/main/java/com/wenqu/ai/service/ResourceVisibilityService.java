package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.RoleDataScopeMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Department;
import com.wenqu.ai.model.RoleDataScope;
import com.wenqu.ai.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源可见性 / 权限解析器。
 * <p>
 * 资源一律私有——仅创建者可见可管；
 * 管理员级（含 superadmin）同样没有全量短路（无归属的历史数据归到 admin 名下）。
 * <p>
 * 部门数据隔离的形态是<b>角色自带数据范围</b>
 * （{@code c_ai_role_data_scope}）——资源侧没有共享入口，
 * 用户能看什么由其角色集合推导。判定 = 创建者（恒 MANAGE）∪ 角色数据范围（最高 READ）：
 * <ul>
 *   <li>{@code all}：全部资源可读；</li>
 *   <li>{@code department} / {@code department_and_children}：归属部门（可配，缺省取成员自己部门）
 *       创建的资源可读，children 含部门子树；</li>
 *   <li>无范围行或缺省 {@code own}：回到"谁建归谁"基线（只有创建者可见）。</li>
 * </ul>
 * 范围授予的是 <b>READ</b>（看），不是 MANAGE（管）——管理权仍只在创建者手里，
 * 避免"部门主管顺手改了别人的库"这类事故。
 * <p>
 * 官方内置资源的「登录即可读」不经本类：手册库（builtin=1）走 {@link #canReadBuiltinKb}，
 * 内置智能体（isBuiltin=1）走 {@code AgentService.readable} 的内置豁免；
 * 管理侧入口（列表/编辑/删块）由各调用点按「内置资源仅管理员级」另行拦截。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ResourceVisibilityService {

    /** 权限等级（ordinal 顺序用于比较大小） */
    public enum Permission { NONE, READ, MANAGE }

    /** 当前操作者抽象（从登录态 RequestUser 派生） */
    public record Principal(String uid, String departmentId, String role) {
        public static final Principal SYSTEM = new Principal(
                com.wenqu.ai.util.RequestUser.SYSTEM, null, "system");
        public boolean superadmin() { return "superadmin".equals(role); }
    }

    private final RoleDataScopeMapper roleDataScopeMapper;
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;

    public ResourceVisibilityService(RoleDataScopeMapper roleDataScopeMapper,
                                     UserMapper userMapper,
                                     DepartmentMapper departmentMapper) {
        this.roleDataScopeMapper = roleDataScopeMapper;
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
    }

    /**
     * 数据范围与部门树快照（按租户键控，TTL 60s；写路径 invalidate 后当场重建）。
     * <p>数据范围快照按租户键控（租户谓词由框架拼在查询上）：
     * 若存成一张全局 volatile 快照，租户 A 的请求建好快照后，
     * B 的请求在 TTL 内会直接复用 <b>A 的范围配置</b>，造成越权。
     * 键规则与 {@code RoleService.tenantKey()} 一致：平台视图与未绑定线程共用一个不过滤键。
     */
    private record Timed<T>(T value, long expireAt) {
    }

    private static final String PLATFORM_KEY = "\u0000platform";
    private final java.util.concurrent.ConcurrentHashMap<String, Timed<Map<String, RoleDataScope>>> scopeSnapshots =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Timed<Map<String, String>>> deptParentSnapshots =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SCOPE_TTL_MS = 60_000;

    private static String tenantKey() {
        if (com.wenqu.ai.util.TenantContext.inPlatformView()) return PLATFORM_KEY;
        String t = com.wenqu.ai.util.TenantContext.current();
        return t == null || t.isBlank() ? PLATFORM_KEY : t;
    }

    private Map<String, RoleDataScope> scopes() {
        String key = tenantKey();
        long now = System.currentTimeMillis();
        Timed<Map<String, RoleDataScope>> cached = scopeSnapshots.get(key);
        if (cached != null && cached.expireAt() > now) return cached.value();
        synchronized (this) {
            cached = scopeSnapshots.get(key);
            if (cached == null || cached.expireAt() <= System.currentTimeMillis()) {
                Map<String, RoleDataScope> m = new LinkedHashMap<>();
                try {
                    for (RoleDataScope ds : roleDataScopeMapper.selectList(null)) m.put(ds.getRoleCode(), ds);
                } catch (Exception e) {
                    // 表未就绪等异常：按无范围处理（fail-closed 回到谁建归谁），不阻塞请求
                    log.warn("[DATA-SCOPE] 数据范围查询失败（按无范围处理）: {}", e.getMessage());
                }
                cached = new Timed<>(m, System.currentTimeMillis() + SCOPE_TTL_MS);
                scopeSnapshots.put(key, cached);
            }
            return cached.value();
        }
    }

    /**
     * 数据范围写路径失效（RoleService.saveDataScope 调用）。
     * <p>广播可能来自任一租户的写操作，故按全租户清（表小，重建便宜，正确性优先）——与 RoleService 同口径。
     */
    public void invalidateScopes() {
        scopeSnapshots.clear();
    }

    private Map<String, String> deptParents() {
        String key = tenantKey();
        long now = System.currentTimeMillis();
        Timed<Map<String, String>> cached = deptParentSnapshots.get(key);
        if (cached != null && cached.expireAt() > now) return cached.value();
        synchronized (this) {
            cached = deptParentSnapshots.get(key);
            if (cached == null || cached.expireAt() <= System.currentTimeMillis()) {
                Map<String, String> m = new LinkedHashMap<>();
                try {
                    for (Department d : departmentMapper.selectList(null)) m.put(d.getId(), d.getParentId());
                } catch (Exception e) {
                    log.warn("[DATA-SCOPE] 部门树查询失败（按无部门处理）: {}", e.getMessage());
                }
                cached = new Timed<>(m, System.currentTimeMillis() + SCOPE_TTL_MS);
                deptParentSnapshots.put(key, cached);
            }
            return cached.value();
        }
    }

    /** 资源归属人的部门（查不到用户/未配部门 → null，无范围可套） */
    private String departmentOfOwner(String ownerUid) {
        try {
            User u = userMapper.selectById(ownerUid);
            return u == null ? null : (u.getDepartmentId() == null || u.getDepartmentId().isBlank() ? null : u.getDepartmentId());
        } catch (Exception e) {
            return null;
        }
    }

    /** dept 是否为 ancestor 的子孙（含自身）；部门树环异常时如实返回 false */
    private boolean inSubtree(String ancestor, String dept) {
        if (ancestor == null || dept == null) return false;
        Map<String, String> parents = deptParents();
        String cur = dept;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            if (cur.equals(ancestor)) return true;
            cur = parents.get(cur);
        }
        return false;
    }

    /**
     * 调用者角色清单（判定侧）：Principal 刻意不带角色（构造点零改动），
     * 这里从登录态直读——ResourceVisibilityService 只在请求线程被调用。
     */
    private List<String> rolesOf(Principal p) {
        try {
            List<String> roles = com.wenqu.ai.util.RequestUser.roles();
            return roles == null ? List.of() : roles;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析用户的<b>有效权限</b>：创建者 → MANAGE（短路）；否则按角色数据范围授予 READ；无范围 → NONE。
     */
    public Permission resolve(Principal p, String createdBy) {
        if (p == null) p = Principal.SYSTEM;
        if (createdBy != null && !createdBy.isBlank() && createdBy.equals(p.uid())) {
            return Permission.MANAGE; // 创建者短路（范围再小也管自己的东西）
        }
        if (p.uid() == null || p.uid().isBlank() || com.wenqu.ai.util.RequestUser.SYSTEM.equals(p.uid())) {
            return Permission.NONE;
        }
        int best = 0; // 0=无 2=department 3=children 4=all（own 不经由范围路径：创建者短路已覆盖）
        RoleDataScope bestScope = null;
        for (String role : rolesOf(p)) {
            RoleDataScope ds = scopes().get(role);
            if (ds == null) continue;
            int rank = switch (ds.getScopeType() == null ? "own" : ds.getScopeType()) {
                case "all" -> 4;
                case "department_and_children" -> 3;
                case "department" -> 2;
                default -> 0;
            };
            if (rank > best) {
                best = rank;
                bestScope = ds;   // 锚点跟着取胜的那一行走，否则多角色时「谁的范围」说不清
            }
        }
        if (best <= 0) return Permission.NONE;
        // all 不再排在归属人部门之后判：原来的顺序是 ownerDept==null 先 return NONE，
        // 于是「没配部门的创建者」的资源，对 scope=all 的角色永远看不见——那是功能坏掉，不是收紧。
        if (best == 4) return Permission.READ;
        String ownerDept = departmentOfOwner(createdBy);
        if (ownerDept == null) return Permission.NONE;
        // 锚点（该范围的 department_id 列）：配了=这个角色成员看「那个部门（含下级）」的资源，
        // 不必自己就在那个部门里；没配=沿用「看自己部门」（原语义）。
        // 这列 schema 注释一直写着「可为空=取成员自己部门」，判定侧从来没读它——
        // 于是权限页上的「生效部门」是个存得进、显示得出、但不参与判定的死字段。
        String anchor = bestScope != null && bestScope.getDepartmentId() != null
                && !bestScope.getDepartmentId().isBlank() ? bestScope.getDepartmentId() : p.departmentId();
        if (anchor == null) return Permission.NONE;
        return switch (best) {
            // inSubtree 自含「相等」：锚点就是归属人所在部门时也算命中
            case 3 -> inSubtree(anchor, ownerDept) ? Permission.READ : Permission.NONE;
            case 2 -> anchor.equals(ownerDept) ? Permission.READ : Permission.NONE;
            default -> Permission.NONE;
        };
    }

    /** 当前用户是否能读取该资源（创建者或角色范围覆盖） */
    public boolean canRead(Principal p, String createdBy) {
        return resolve(p, createdBy).ordinal() >= Permission.READ.ordinal();
    }

    /** 当前用户是否能管理该资源（仅创建者——范围只授予读） */
    public boolean canManage(Principal p, String createdBy) {
        return resolve(p, createdBy) == Permission.MANAGE;
    }

    /**
     * 知识库<b>消费侧</b>可读判定（检索/对话/MCP 等登录后的消费入口）：
     * 官方内置库（builtin=1，如问渠使用手册）登录即可读——手册是随版本分发的系统资源，
     * 不是任何人的私有资产；其余按 resolve（创建者 ∪ 角色数据范围）。
     * <p>管理侧入口（知识库页/文档管理/知识块管理）不经本方法：
     * 那里按「内置库仅管理员级可见」另行拦截（各控制器已有判定），两处口径刻意不同。
     */
    public boolean canReadBuiltinKb(Principal p, Integer builtin, String createdBy) {
        if (builtin != null && builtin == 1) return true;
        return canRead(p, createdBy);
    }

}
