package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.DepartmentMapper;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.model.Department;
import com.wisesoft.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 组织管理：部门与用户的增删改查（供「成员管理」页与「共享范围」选择器使用）。
 * <p>
 * 鉴权仍由前置网关完成；本服务只维护画像/归属（部门、角色、状态）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgService {

    private static final String UID_PATTERN = "[A-Za-z0-9_@.\\-]+";
    private static final int NAME_MAX = 100;

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final ModelRegistryService modelRegistryService;
    private final RoleService roleService;

    // ==================== 部门 ====================

    public List<Department> listDepartments() {
        return departmentMapper.selectList(
                new LambdaQueryWrapper<Department>().orderByAsc(Department::getName));
    }

    /** 新建部门（树形：parentId 空=根；父须存在） */
    public Department createDepartment(String name, String description, String parentId) {
        String n = normalizeName(name, "部门名称");
        ensureDeptNameUnique(n, null);
        Department d = new Department();
        d.setParentId(validateDeptParent(parentId, null));
        d.setName(n);
        d.setDescription(trimOrNull(description));
        departmentMapper.insert(d);
        log.info("[AUDIT] 新建部门 id={} name={} parent={}", d.getId(), n, d.getParentId());
        return d;
    }

    /** 修改部门：父级变更做环检测（父不能是自己或自己的后代） */
    public void updateDepartment(String id, String name, String description, String parentId) {
        Department d = departmentMapper.selectById(id);
        if (d == null) throw new BizException("部门不存在");
        String n = normalizeName(name, "部门名称");
        ensureDeptNameUnique(n, id);
        d.setParentId(validateDeptParent(parentId, id));
        d.setName(n);
        d.setDescription(trimOrNull(description));
        departmentMapper.updateById(d);
        log.info("[AUDIT] 编辑部门 id={} name={} parent={}", id, n, d.getParentId());
    }

    /**
     * 删除部门：先校验无用户挂靠、无子部门；物理删除
     * （见 DepartmentMapper 注释，规避软删 + uk_name 的名称占位问题）。
     */
    public void deleteDepartment(String id) {
        Department d = departmentMapper.selectById(id);
        if (d == null) throw new BizException("部门不存在");
        Long used = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getDepartmentId, id));
        if (used != null && used > 0) {
            throw new BizException("该部门下仍有 " + used + " 名用户，请先调整其归属");
        }
        Long children = departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>().eq(Department::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException("该部门下仍有 " + children + " 个子部门，请先删除或移出子部门");
        }
        departmentMapper.hardDeleteById(id);
        log.info("[AUDIT] 删除部门 id={} name={}", id, d.getName());
    }

    // ==================== 用户 ====================

    public List<User> listUsers() {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>().orderByAsc(User::getUsername));
    }

    /** 新建用户（必须设置初始密码，否则无法登录；角色须为角色表中启用的角色） */
    public User createUser(String uid, String username, String departmentId, String role, String password) {
        String u = normalizeUid(uid);
        if (userMapper.selectById(u) != null) throw new BizException("该用户标识已存在");
        String name = trimOrNull(username);
        if (name != null) ensureUsernameUnique(name, null);
        String r = normalizeRole(role);
        ensureDeptExists(departmentId);
        User user = new User();
        user.setUid(u);
        user.setUsername(name);
        user.setDepartmentId(trimOrNull(departmentId));
        user.setRole(r);
        user.setStatus(1);
        user.setPasswordHash(authService.hashNewPassword(password));
        user.setLoginFailCount(0);
        userMapper.insert(user);
        log.info("[AUDIT] 新建用户 uid={} role={} dept={}", u, r, user.getDepartmentId());
        return user;
    }

    public void updateUser(String uid, String username, String departmentId, String role, Integer status) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        String r = normalizeRole(role);
        ensureDeptExists(departmentId);
        if (status != null && status != 0 && status != 1) throw new BizException("非法状态");
        // 最后一名管理员级账号不可降级为普通角色，避免把自己锁死（RBAC 化：superadmin 或 admin_flag=1）
        if (roleService.isAdminCode(user.getRole()) && !roleService.isAdminCode(r) && isLastAdminAccount()) {
            throw new BizException("至少保留一名管理员级账号，无法降级最后一名");
        }
        String name = trimOrNull(username);
        if (name != null) ensureUsernameUnique(name, uid);
        user.setUsername(name);
        user.setDepartmentId(trimOrNull(departmentId));
        user.setRole(r);
        if (status != null) user.setStatus(status);
        userMapper.updateById(user);
    }

    public void deleteUser(String uid) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        if (roleService.isAdminCode(user.getRole()) && isLastAdminAccount()) {
            throw new BizException("至少保留一名管理员级账号，无法删除最后一名");
        }
        userMapper.deleteById(uid);
        log.info("[AUDIT] 删除用户 uid={}", uid);
    }

    // ==================== 个人偏好（个人设置） ====================

    /**
     * 个人偏好读取：个人默认模型（chat/vision，引用，空=未设默认）+ 可用聊天模型清单。
     * 个人默认重排已退役（重排模型归知识库检索设置）。
     */
    public java.util.Map<String, Object> getPreference(String uid, String role) {
        User u = userMapper.selectById(uid);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("defaultModel", u == null ? null : u.getDefaultModel());
        m.put("defaultVisionModel", u == null ? null : u.getDefaultVisionModel());
        m.put("models", modelRegistryService.available(ModelRegistryService.TYPE_CHAT, uid, role));
        return m;
    }

    /**
     * 设置个人默认模型（二元组全量保存）：每个值 null=不修改，空串=清除，引用=设置。
     * 校验引用有效、对本人可用（平台级 + 自己登记的个人级）、且登记类型与槽位一致（防选到不可用模型）。
     * 向量不提供个人默认（向量空间与索引一一对应，归知识库）；重排个人默认已退役（归知识库检索设置）。
     */
    public void setPreference(String uid, String chatRef, String visionRef) {
        User u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        String role = u.getRole();
        if (chatRef != null) {
            String v = chatRef.trim();
            validateDefaultModel(v, ModelRegistryService.TYPE_CHAT, "聊天", uid, role);
            u.setDefaultModel(v.isEmpty() ? null : v);
        }
        if (visionRef != null) {
            String v = visionRef.trim();
            validateDefaultModel(v, ModelRegistryService.TYPE_VISION, "视觉", uid, role);
            u.setDefaultVisionModel(v.isEmpty() ? null : v);
        }
        userMapper.updateById(u);
        log.info("[AUDIT] 个人默认模型已更新 uid={} chat={} vision={}", uid,
                chatRef == null ? "(未改)" : chatRef.isBlank() ? "(清空)" : chatRef,
                visionRef == null ? "(未改)" : visionRef.isBlank() ? "(清空)" : visionRef);
    }

    /** 校验个人默认模型引用：存在、对本人可用、且登记类型与槽位一致（未登记类型的引用放行——遗留手填名兼容） */
    private void validateDefaultModel(String ref, String expectedType, String label, String uid, String role) {
        if (ref.isEmpty()) return;
        // 归属校验：引用他人登记的个人级供应商一律拒绝（该 Key 属于别人）
        modelRegistryService.assertUsable(ref, uid, role);
        if (modelRegistryService.resolveReference(ref) == null) {
            throw new BizException("默认模型无效或已被删除，请重新选择");
        }
        String type = modelRegistryService.referenceType(ref);
        if (type != null && !expectedType.equals(type)) {
            throw new BizException("个人默认" + label + "模型需为" + label + "类型（当前所选为 " + type + " 类型）");
        }
    }

    // ==================== 校验工具 ====================

    private static String normalizeName(String name, String label) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new BizException(label + "不能为空");
        if (n.length() > NAME_MAX) throw new BizException(label + "过长（最多 " + NAME_MAX + " 字）");
        return n;
    }

    private static String normalizeUid(String uid) {
        String u = uid == null ? "" : uid.trim();
        if (u.isEmpty()) throw new BizException("用户标识不能为空");
        if (!u.matches(UID_PATTERN)) throw new BizException("用户标识含非法字符（仅允许字母/数字/_@.-）");
        if (u.length() > 64) throw new BizException("用户标识过长");
        return u;
    }

    /** 角色校验（RBAC）：须为角色表中启用的角色（内置三角色由种子兜底恒存在） */
    private String normalizeRole(String role) {
        String r = (role == null || role.isBlank()) ? "user" : role.trim().toLowerCase();
        if (!roleService.existsActive(r)) throw new BizException("角色不存在或已停用（请先在权限管理中创建）");
        return r;
    }

    /** 部门父级校验：存在性 + 环检测（父不能是自己或自己的后代；深度硬上限防脏数据成环） */
    private String validateDeptParent(String parentId, String selfId) {
        String p = trimOrNull(parentId);
        if (p == null) return null;
        if (p.equals(selfId)) throw new BizException("父部门不能是自己");
        if (departmentMapper.selectById(p) == null) throw new BizException("指定的父部门不存在");
        String cursor = p;
        for (int i = 0; i < 50 && cursor != null; i++) {
            if (cursor.equals(selfId)) throw new BizException("父部门不能是其自身的后代（会成环）");
            Department cur = departmentMapper.selectById(cursor);
            cursor = cur == null ? null : cur.getParentId();
        }
        return p;
    }

    private void ensureDeptNameUnique(String name, String excludeId) {
        LambdaQueryWrapper<Department> q = new LambdaQueryWrapper<Department>().eq(Department::getName, name);
        if (excludeId != null) q.ne(Department::getId, excludeId);
        Long c = departmentMapper.selectCount(q);
        if (c != null && c > 0) throw new BizException("部门名称已存在");
    }

    /** 用户名唯一（登录可按用户名） */
    private void ensureUsernameUnique(String username, String excludeUid) {
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<User>().eq(User::getUsername, username);
        if (excludeUid != null) q.ne(User::getUid, excludeUid);
        Long c = userMapper.selectCount(q);
        if (c != null && c > 0) throw new BizException("用户名已存在");
    }

    private void ensureDeptExists(String departmentId) {
        String d = trimOrNull(departmentId);
        if (d == null) return;
        if (departmentMapper.selectById(d) == null) throw new BizException("指定部门不存在");
    }

    /** 是否仅剩这一名管理员级账号（role 为 superadmin/admin 或自定义 admin_flag=1 的用户数 ≤1） */
    private boolean isLastAdminAccount() {
        List<String> adminCodes = roleService.adminCodes();
        if (adminCodes.isEmpty()) return false;
        Long c = userMapper.selectCount(new LambdaQueryWrapper<User>().in(User::getRole, adminCodes));
        return c != null && c <= 1;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
