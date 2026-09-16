package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.AiDepartmentMapper;
import com.wisesoft.ai.mapper.AiUserMapper;
import com.wisesoft.ai.model.AiDepartment;
import com.wisesoft.ai.model.AiUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

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

    private static final Set<String> ROLES = Set.of("superadmin", "admin", "user");
    private static final String UID_PATTERN = "[A-Za-z0-9_@.\\-]+";
    private static final int NAME_MAX = 100;

    private final AiDepartmentMapper departmentMapper;
    private final AiUserMapper userMapper;
    private final AuthService authService;

    // ==================== 部门 ====================

    public List<AiDepartment> listDepartments() {
        return departmentMapper.selectList(
                new LambdaQueryWrapper<AiDepartment>().orderByAsc(AiDepartment::getName));
    }

    public AiDepartment createDepartment(String name, String description) {
        String n = normalizeName(name, "部门名称");
        ensureDeptNameUnique(n, null);
        AiDepartment d = new AiDepartment();
        d.setName(n);
        d.setDescription(trimOrNull(description));
        departmentMapper.insert(d);
        log.info("[AUDIT] 新建部门 id={} name={}", d.getId(), n);
        return d;
    }

    public void updateDepartment(String id, String name, String description) {
        AiDepartment d = departmentMapper.selectById(id);
        if (d == null) throw new BizException("部门不存在");
        String n = normalizeName(name, "部门名称");
        ensureDeptNameUnique(n, id);
        d.setName(n);
        d.setDescription(trimOrNull(description));
        departmentMapper.updateById(d);
    }

    /** 删除部门：先校验无用户挂靠；物理删除（见 AiDepartmentMapper 注释，规避软删 + uk_name 的名称占位问题） */
    public void deleteDepartment(String id) {
        AiDepartment d = departmentMapper.selectById(id);
        if (d == null) throw new BizException("部门不存在");
        Long used = userMapper.selectCount(new LambdaQueryWrapper<AiUser>().eq(AiUser::getDepartmentId, id));
        if (used != null && used > 0) {
            throw new BizException("该部门下仍有 " + used + " 名用户，请先调整其归属");
        }
        departmentMapper.hardDeleteById(id);
        log.info("[AUDIT] 删除部门 id={} name={}", id, d.getName());
    }

    // ==================== 用户 ====================

    public List<AiUser> listUsers() {
        return userMapper.selectList(
                new LambdaQueryWrapper<AiUser>().orderByAsc(AiUser::getUsername));
    }

    /** 新建用户（必须设置初始密码，否则无法登录） */
    public AiUser createUser(String uid, String username, String departmentId, String role, String password) {
        String u = normalizeUid(uid);
        if (userMapper.selectById(u) != null) throw new BizException("该用户标识已存在");
        String name = trimOrNull(username);
        if (name != null) ensureUsernameUnique(name, null);
        String r = normalizeRole(role);
        ensureDeptExists(departmentId);
        AiUser user = new AiUser();
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
        AiUser user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        String r = normalizeRole(role);
        ensureDeptExists(departmentId);
        if (status != null && status != 0 && status != 1) throw new BizException("非法状态");
        // 最后一名 superadmin 不可降级，避免把自己锁死
        if ("superadmin".equals(user.getRole()) && !"superadmin".equals(r) && isLastSuperadmin()) {
            throw new BizException("至少保留一名超级管理员，无法降级最后一名");
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
        AiUser user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        if ("superadmin".equals(user.getRole()) && isLastSuperadmin()) {
            throw new BizException("至少保留一名超级管理员，无法删除最后一名");
        }
        userMapper.deleteById(uid);
        log.info("[AUDIT] 删除用户 uid={}", uid);
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

    private static String normalizeRole(String role) {
        String r = (role == null || role.isBlank()) ? "user" : role.trim();
        if (!ROLES.contains(r)) throw new BizException("角色不合法（superadmin/admin/user）");
        return r;
    }

    private void ensureDeptNameUnique(String name, String excludeId) {
        LambdaQueryWrapper<AiDepartment> q = new LambdaQueryWrapper<AiDepartment>().eq(AiDepartment::getName, name);
        if (excludeId != null) q.ne(AiDepartment::getId, excludeId);
        Long c = departmentMapper.selectCount(q);
        if (c != null && c > 0) throw new BizException("部门名称已存在");
    }

    /** 用户名唯一（登录可按用户名） */
    private void ensureUsernameUnique(String username, String excludeUid) {
        LambdaQueryWrapper<AiUser> q = new LambdaQueryWrapper<AiUser>().eq(AiUser::getUsername, username);
        if (excludeUid != null) q.ne(AiUser::getUid, excludeUid);
        Long c = userMapper.selectCount(q);
        if (c != null && c > 0) throw new BizException("用户名已存在");
    }

    private void ensureDeptExists(String departmentId) {
        String d = trimOrNull(departmentId);
        if (d == null) return;
        if (departmentMapper.selectById(d) == null) throw new BizException("指定部门不存在");
    }

    private boolean isLastSuperadmin() {
        Long c = userMapper.selectCount(new LambdaQueryWrapper<AiUser>().eq(AiUser::getRole, "superadmin"));
        return c != null && c <= 1;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
