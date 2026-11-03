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
 * 2026-11-02 移除共享范围（share_config 已随功能删除）：资源一律私有——仅创建者可见可管；
 * 管理员级（含 superadmin）同样没有全量短路（无归属的历史数据已迁给 admin）。
 * <p>
 * <b>2026-11-04 批次 C2</b>：部门数据隔离回归，但形态是<b>角色自带数据范围</b>
 * （{@code c_ai_role_data_scope}，方案拍板 D6"重建能力不重建形态"）——资源侧没有共享入口，
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
        public static final Principal ANONYMOUS = new Principal("anonymous", null, "user");
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

    /** 数据范围快照 TTL（60s，与角色/用户缓存同口径；写路径 invalidate 后当场重建） */
    private volatile Map<String, RoleDataScope> scopeSnapshot = Map.of();
    private volatile long scopeExpireAt = 0L;
    private static final long SCOPE_TTL_MS = 60_000;

    /** 部门父子映射 TTL 缓存（部门表极小，整表快照） */
    private volatile Map<String, String> deptParentSnapshot = Map.of();
    private volatile long deptExpireAt = 0L;

    private Map<String, RoleDataScope> scopes() {
        if (!scopeSnapshot.isEmpty() && System.currentTimeMillis() < scopeExpireAt) return scopeSnapshot;
        synchronized (this) {
            if (scopeSnapshot.isEmpty() || System.currentTimeMillis() >= scopeExpireAt) {
                Map<String, RoleDataScope> m = new LinkedHashMap<>();
                try {
                    for (RoleDataScope ds : roleDataScopeMapper.selectList(null)) m.put(ds.getRoleCode(), ds);
                    scopeSnapshot = m;
                } catch (Exception e) {
                    // 表未就绪等异常：按无范围处理（fail-closed 回到谁建归谁），不阻塞请求
                    log.warn("[DATA-SCOPE] 数据范围查询失败（按无范围处理）: {}", e.getMessage());
                    scopeSnapshot = m;
                }
                scopeExpireAt = System.currentTimeMillis() + SCOPE_TTL_MS;
            }
            return scopeSnapshot;
        }
    }

    /** 数据范围写路径失效（RoleService.saveDataScope 调用） */
    public void invalidateScopes() {
        scopeSnapshot = Map.of();
        scopeExpireAt = 0L;
    }

    private Map<String, String> deptParents() {
        if (!deptParentSnapshot.isEmpty() && System.currentTimeMillis() < deptExpireAt) return deptParentSnapshot;
        synchronized (this) {
            if (deptParentSnapshot.isEmpty() || System.currentTimeMillis() >= deptExpireAt) {
                Map<String, String> m = new LinkedHashMap<>();
                try {
                    for (Department d : departmentMapper.selectList(null)) m.put(d.getId(), d.getParentId());
                    deptParentSnapshot = m;
                } catch (Exception e) {
                    log.warn("[DATA-SCOPE] 部门树查询失败（按无部门处理）: {}", e.getMessage());
                    deptParentSnapshot = m;
                }
                deptExpireAt = System.currentTimeMillis() + SCOPE_TTL_MS;
            }
            return deptParentSnapshot;
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
        if (p == null) p = Principal.ANONYMOUS;
        if (createdBy != null && !createdBy.isBlank() && createdBy.equals(p.uid())) {
            return Permission.MANAGE; // 创建者短路（范围再小也管自己的东西）
        }
        if (p.uid() == null || p.uid().isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(p.uid())) {
            return Permission.NONE;
        }
        int best = 0; // 0=无 2=department 3=children 4=all（own 不经由范围路径：创建者短路已覆盖）
        for (String role : rolesOf(p)) {
            RoleDataScope ds = scopes().get(role);
            if (ds == null) continue;
            int rank = switch (ds.getScopeType() == null ? "own" : ds.getScopeType()) {
                case "all" -> 4;
                case "department_and_children" -> 3;
                case "department" -> 2;
                default -> 0;
            };
            if (rank > best) best = rank;
        }
        if (best <= 0) return Permission.NONE;
        String ownerDept = departmentOfOwner(createdBy);
        if (ownerDept == null) return Permission.NONE;
        String callerDept = p.departmentId();
        return switch (best) {
            case 4 -> Permission.READ;
            case 3 -> callerDept != null && (inSubtree(callerDept, ownerDept) || callerDept.equals(ownerDept))
                    ? Permission.READ : Permission.NONE;
            case 2 -> callerDept != null && callerDept.equals(ownerDept) ? Permission.READ : Permission.NONE;
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
