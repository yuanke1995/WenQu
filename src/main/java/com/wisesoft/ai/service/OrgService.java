package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.mapper.DepartmentMapper;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.model.Department;
import com.wisesoft.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

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
    private final AppProperties properties;

    /** 上传头像 URL 前缀（与 ImageWebConfig 资源映射、前端渲染口径一致） */
    private static final String AVATAR_URL_PREFIX = "/ai/images/avatar/";

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
        if (userMapper.selectById(u) != null) throw new BizException("该登录账号已存在");
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
        // 用户级长期记忆开关（null=历史行缺省，视为开）：个人设置「长期记忆」页开关数据源
        m.put("memoryEnabled", u == null || !Integer.valueOf(0).equals(u.getMemoryEnabled()));
        m.put("models", modelRegistryService.available(ModelRegistryService.TYPE_CHAT, uid, role));
        return m;
    }

    /**
     * 设置个人偏好（多槽位，字段缺省(null)=不修改，空串=清除）：
     * 个人默认模型校验引用有效、对本人可用（平台级 + 自己登记的个人级）、且登记类型与槽位一致（防选到不可用模型）。
     * memoryEnabled=用户级长期记忆自动提炼开关（Boolean，null=不修改）。
     * 向量不提供个人默认（向量空间与索引一一对应，归知识库）；重排个人默认已退役（归知识库检索设置）。
     */
    public void setPreference(String uid, String chatRef, String visionRef, Boolean memoryEnabled) {
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
        if (memoryEnabled != null) {
            u.setMemoryEnabled(Boolean.TRUE.equals(memoryEnabled) ? 1 : 0);
        }
        userMapper.updateById(u);
        log.info("[AUDIT] 个人偏好已更新 uid={} chat={} vision={} memoryEnabled={}", uid,
                chatRef == null ? "(未改)" : chatRef.isBlank() ? "(清空)" : chatRef,
                visionRef == null ? "(未改)" : visionRef.isBlank() ? "(清空)" : visionRef,
                memoryEnabled == null ? "(未改)" : memoryEnabled);
    }

    /**
     * 本人修改个人资料：username/avatar 各自缺省(null)=不修改；avatar 空串=清除头像（回落昵称首字）。
     * 昵称校验唯一性；头像写入走 setAvatar（含旧上传图清理、取值合法性校验）。
     */
    public void updateOwnProfile(String uid, String username, String avatar) {
        if (uid == null || uid.isBlank()) throw new BizException("未登录");
        if (userMapper.selectById(uid) == null) throw new BizException("用户不存在");
        if (username != null) {
            String name = normalizeName(username, "昵称");
            ensureUsernameUnique(name, uid);
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getUid, uid).set(User::getUsername, name));
            log.info("[AUDIT] 用户修改昵称 uid={} username={}", uid, name);
        }
        if (avatar != null) {
            setAvatar(uid, avatar);
        }
    }

    /**
     * 设置头像（本人）：avatar 语义——null=不修改；""=清除（回落昵称首字）；
     * 以 /ai/images/avatar/ 开头=上传图片 URL；其余=emoji 字符（原样存库，前端渲染为表情块）。
     * 旧头像是上传图片且被替换/清除时删除磁盘文件，避免孤儿文件累积。
     */
    public String setAvatar(String uid, String avatar) {
        User u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        if (avatar == null) return u.getAvatar(); // 不修改
        String next = avatar.trim().isEmpty() ? null : resolveAvatarValue(avatar.trim());
        String prev = u.getAvatar();
        if (prev != null && prev.startsWith(AVATAR_URL_PREFIX) && !prev.equals(next)) {
            deleteAvatarFile(prev);
        }
        // 非 Lambda 的 UpdateWrapper.set(String,Object) 含 null → 清空（null）能真正落库，
        // 而 LambdaUpdateWrapper.set(SFunction,..) 会按 NOT_NULL 策略跳过 null 导致清空失效
        userMapper.update(null, new UpdateWrapper<User>()
                .eq("uid", uid).set("avatar", (Object) next));
        log.info("[AUDIT] 用户头像已更新 uid={} avatar={}", uid, next == null ? "(清空)" : next);
        return next;
    }

    /** 校验头像取值合法：上传 URL 必须是我们生成的 /ai/images/avatar/ 路径（防任意 URL / 路径穿越）；emoji 原样存 */
    private String resolveAvatarValue(String a) {
        if (a.startsWith("/")) {
            if (!a.matches("^/ai/images/avatar/[A-Za-z0-9._-]+\\.(?i)(png|jpe?g|gif|webp)$")) {
                throw new BizException("头像图片地址无效");
            }
            return a;
        }
        if (a.length() > 64) throw new BizException("头像内容过长");
        return a;
    }

    /** 上传头像图片（本人）：校验类型/大小/魔数 → 落盘 images/avatar/ → 写入用户头像（含旧文件清理），返回访问 URL */
    public String uploadAvatar(String uid, MultipartFile file) {
        if (uid == null || uid.isBlank()) throw new BizException("未登录");
        if (file == null || file.isEmpty()) throw new BizException("请选择图片");
        if (file.getSize() > 2L * 1024 * 1024) throw new BizException("头像图片不能超过 2MB");
        String ext = switch (file.getContentType() == null ? "" : file.getContentType()) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "";
        };
        if (ext.isEmpty()) throw new BizException("仅支持 PNG/JPEG/GIF/WebP 图片");
        byte[] bytes = readUploadBytes(file);
        if (!isImageBytes(bytes)) throw new BizException("文件不是有效图片");
        Path dir = Paths.get(properties.getImages().getDir(), "images", "avatar");
        try { Files.createDirectories(dir); } catch (IOException e) { throw new BizException("头像存储目录创建失败"); }
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try { Files.write(dir.resolve(name), bytes); } catch (IOException e) { throw new BizException("头像保存失败"); }
        return setAvatar(uid, AVATAR_URL_PREFIX + name);
    }

    private byte[] readUploadBytes(MultipartFile f) {
        try { return f.getBytes(); } catch (IOException e) { throw new BizException("读取上传文件失败"); }
    }

    /** 魔数校验：仅接受常见图片格式（防把非图片当图片存） */
    private boolean isImageBytes(byte[] b) {
        if (b == null || b.length < 12) return false;
        if (b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return true; // PNG
        if (b[0] == (byte) 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return true;                  // JPEG
        if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return true;        // GIF
        if (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return true; // WEBP
        return false;
    }

    /** 按存储 URL 删除头像文件（URL 形如 /ai/images/avatar/{name}）；失败仅告警不阻断 */
    private void deleteAvatarFile(String url) {
        try {
            String name = url.substring(AVATAR_URL_PREFIX.length());
            Files.deleteIfExists(Paths.get(properties.getImages().getDir(), "images", "avatar", name));
        } catch (Exception e) {
            log.warn("[WARN] 删除旧头像文件失败: {} ({})", url, e.getMessage());
        }
    }

    /** 校验个人默认模型引用：存在、对本人可用、且登记类型与槽位一致（未登记类型放行——遗留手填名兼容；期望 vision 时放宽——vision/omni 或具备图片理解能力的模型均通过） */
    private void validateDefaultModel(String ref, String expectedType, String label, String uid, String role) {
        if (ref.isEmpty()) return;
        // 归属校验：引用他人登记的个人级供应商一律拒绝（该 Key 属于别人）
        modelRegistryService.assertUsable(ref, uid, role);
        if (modelRegistryService.resolveReference(ref) == null) {
            throw new BizException("默认模型无效或已被删除，请重新选择");
        }
        if (!modelRegistryService.referenceMatchesType(ref, expectedType)) {
            String type = modelRegistryService.referenceType(ref);
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
        if (u.isEmpty()) throw new BizException("登录账号不能为空");
        if (!u.matches(UID_PATTERN)) throw new BizException("登录账号含非法字符（仅允许字母/数字/_@.-）");
        if (u.length() > 64) throw new BizException("登录账号过长");
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

    /** 昵称全库唯一（仅展示用，不参与登录） */
    private void ensureUsernameUnique(String username, String excludeUid) {
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<User>().eq(User::getUsername, username);
        if (excludeUid != null) q.ne(User::getUid, excludeUid);
        Long c = userMapper.selectCount(q);
        if (c != null && c > 0) throw new BizException("昵称已被占用");
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
