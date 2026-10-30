package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Department;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private static final String UID_PATTERN = "[A-Za-z0-9_@.\\-]+";
    private static final int NAME_MAX = 100;

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final ModelRegistryService modelRegistryService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final IdentityService identityService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final AppProperties properties;
    /** 账号删除时一并清理个人记忆文件（MEMORY.md + 日账）；不清理会在磁盘上留下无主数据。 */
    private final com.wenqu.ai.memory.MemoryService memoryService;

    /** 上传头像 URL 前缀（与 ImageWebConfig 资源映射、前端渲染口径一致） */
    private static final String AVATAR_URL_PREFIX = "/ai/images/avatar/";

    // ==================== 部门 ====================

    /**
     * 成员管理类写操作在 superadmin 下走平台视图（Phase 4 多租户）。
     * <p>两个原因，第二个更要命：
     * <ol>
     *   <li>成员名单对 superadmin 是跨租户摊开的，写侧仍按本租户过滤就会出现「看得见、点保存说用户不存在」；</li>
     *   <li>{@code updateById} 会被拼上 {@code WHERE uid = ? AND tenant_id = ?}，匹配 0 行时<b>一声不响</b>——
     *       界面提示「已保存」而什么都没改，正是本仓最忌讳的静默失效。</li>
     * </ol>
     * 租户管理员（role=admin，非 superadmin）不在放开范围内：他只管本租户的成员。
     * 本人自助类操作（改自己的昵称/头像/偏好）不走这里——那些线程上绑的就是他自己的租户。
     */
    private <T> T asPlatformIfSuper(java.util.function.Supplier<T> body) {
        return com.wenqu.ai.util.TenantContext.asPlatformIfSuper(body);
    }

    private void asPlatformIfSuperVoid(Runnable body) {
        com.wenqu.ai.util.TenantContext.asPlatformIfSuper(body);
    }

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
        audit.record("[AUDIT] 新建部门 id={} name={} parent={}", d.getId(), n, d.getParentId());
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
        audit.record("[AUDIT] 编辑部门 id={} name={} parent={}", id, n, d.getParentId());
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
        audit.record("[AUDIT] 删除部门 id={} name={}", id, d.getName());
    }

    // ==================== 用户 ====================

    public List<User> listUsers() {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>().orderByAsc(User::getUsername));
    }

    /**
     * 用户列表（含多角色）：User 全字段 + {@code roles}（角色集，多角色数据源）+
     * {@code role}（主角色，管理员优先——前端单角色展示的兼容口径）。
     */
    public List<java.util.Map<String, Object>> listUsersWithRoles() {
        // 平台级（superadmin）看得到全部租户的成员：把成员挪进别的租户之后，
        // 若名单也按本租户过滤，就再也没有入口把他改回来——管理动作不能留这种死路。
        // 只放开这份名单，其它数据列表仍按本人租户过滤（跨租户读别人的知识库/会话不是管理成员所需）。
        if (com.wenqu.ai.util.RequestUser.superadmin()) {
            return com.wenqu.ai.util.TenantContext.asPlatform(this::listUsersWithRolesScoped);
        }
        return listUsersWithRolesScoped();
    }

    private List<java.util.Map<String, Object>> listUsersWithRolesScoped() {
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (User u : listUsers()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(
                    objectMapper.convertValue(u, java.util.Map.class));
            List<String> roles = userRoleService.rolesOf(u.getUid());
            m.put("roles", roles);
            m.put("role", roles.isEmpty() ? "user" : roles.get(0));
            // 身份来源（统一身份表）：成员管理「单点登录」列与解绑判定的数据源
            // （旧 oidc_sub/oidc_provider 两列不再写入，批次 2 起新绑定只落这里）
            List<java.util.Map<String, Object>> ids = new java.util.ArrayList<>();
            for (var i : identityService.listOf(u.getUid())) {
                ids.add(java.util.Map.of("providerId", (Object) i.getProviderId(),
                        "externalId", (Object) i.getExternalId()));
            }
            m.put("identities", ids);
            out.add(m);
        }
        return out;
    }

    /** 分页单页上限（前端可选 20/50/100；截住是防有人拿 size 参数把全表再捞一遍） */
    private static final int PAGE_MAX_SIZE = 100;

    /**
     * 成员分页查询——万级名单入口（「安全与运维 → 会话治理」）。
     * <p>与 {@link #listUsersWithRoles()} 的分工是规模口径：那份按全表下发，给选择器和计数用；
     * 这份把关键字/部门/状态压到 SQL 上，只补当页的角色与身份。成员涨到几万时，
     * 全表那份一次请求就撑不住（逐人取身份无缓存，是 N+1 里唯一没有兜底的那一条）。
     * <p>排序固定按最近登录倒序：治理动作找的是「刚动过的人」；MySQL 下 NULL 沉底，从未登录的排最后。
     *
     * @return {@code {total, items, page, size}}，items 字段口径与 {@code /user/list} 一致
     */
    public java.util.Map<String, Object> pageUsers(String keyword, String departmentId, Integer status,
                                                   int page, int size) {
        // 平台级看全部租户的成员，与 listUsersWithRoles 同一口径（跨租户挪人后不能没有找回入口）
        if (com.wenqu.ai.util.RequestUser.superadmin()) {
            return com.wenqu.ai.util.TenantContext.asPlatform(
                    () -> pageUsersScoped(keyword, departmentId, status, page, size));
        }
        return pageUsersScoped(keyword, departmentId, status, page, size);
    }

    private java.util.Map<String, Object> pageUsersScoped(String keyword, String departmentId, Integer status,
                                                          int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(PAGE_MAX_SIZE, Math.max(1, size));
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            w.and(q -> q.like(User::getUsername, kw).or().like(User::getUid, kw));
        }
        if (departmentId != null && !departmentId.isBlank()) w.eq(User::getDepartmentId, departmentId);
        if (status != null) w.eq(User::getStatus, status);
        w.orderByDesc(User::getLastLoginTime).orderByAsc(User::getUsername);
        var pg = userMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<User>(p, s), w);

        List<String> uids = new ArrayList<>();
        for (User u : pg.getRecords()) uids.add(u.getUid());
        var identities = identityService.mapOfUids(uids);

        List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (User u : pg.getRecords()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(
                    objectMapper.convertValue(u, java.util.Map.class));
            List<String> roles = userRoleService.rolesOf(u.getUid());
            m.put("roles", roles);
            m.put("role", roles.isEmpty() ? "user" : roles.get(0));
            List<java.util.Map<String, Object>> ids = new java.util.ArrayList<>();
            for (var i : identities.getOrDefault(u.getUid(), List.of())) {
                java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("providerId", i.getProviderId());
                one.put("externalId", i.getExternalId());
                ids.add(one);
            }
            m.put("identities", ids);
            items.add(m);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", pg.getTotal());
        out.put("items", items);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    /** 新建用户（必须设置初始密码，否则无法登录；角色须为角色表中启用的角色；落多角色表） */
    public User createUser(String uid, String username, String departmentId, String role, String password) {
        // 成员管理是平台动作（Phase 4）：判据与理由见 asPlatformIfSuper 的注释
        return asPlatformIfSuper(() -> createUserScoped(uid, username, departmentId, role, password));
    }

    private User createUserScoped(String uid, String username, String departmentId, String role, String password) {
        String u = normalizeUid(uid);
        // 账号标识是平台级命名空间（判据与后果见 ExternalUserService.ensureUniqueUid 的注释）：
        // 只查本租户会「查无此人」然后撞主键，把一句清楚的提示变成一次 500。
        if (com.wenqu.ai.util.TenantContext.asPlatform(() -> userMapper.selectById(u)) != null) {
            throw new BizException("该登录账号在本平台内已被占用（账号标识跨租户唯一，请换一个）");
        }
        String name = trimOrNull(username);
        if (name != null) ensureUsernameUnique(name, null);
        String r = normalizeRole(role);
        ensureDeptExists(departmentId);
        User user = new User();
        user.setUid(u);
        user.setUsername(name);
        user.setDepartmentId(trimOrNull(departmentId));
        user.setStatus(1);
        user.setPasswordHash(authService.hashNewPassword(password));
        user.setLoginFailCount(0);
        userMapper.insert(user);
        userRoleService.grant(u, r, "manual");
        audit.record("[AUDIT] 新建用户 uid={} roles=[{}] dept={}", u, r, user.getDepartmentId());
        return user;
    }

    /**
     * 编辑用户（批次 5 多角色化）：{@code roles} 为角色集（manual 全量替换，idp 行不动）；
     * 旧调用传单 {@code role} 时按单元素集合处理（兼容存量调用方，语义不变）。
     */
    public void updateUser(String uid, String username, String departmentId, List<String> roles, Integer status) {
        // 成员管理是平台动作（Phase 4）：判据与理由见 asPlatformIfSuper 的注释
        asPlatformIfSuperVoid(() -> updateUserScoped(uid, username, departmentId, roles, status));
    }

    private void updateUserScoped(String uid, String username, String departmentId, List<String> roles, Integer status) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        List<String> normalized = new ArrayList<>();
        if (roles != null) {
            for (String code : roles) {
                if (code == null || code.isBlank()) continue;
                String c = code.trim().toLowerCase();
                if (!roleService.existsActive(c)) {
                    throw new BizException("角色「" + c + "」不存在或已停用，请在权限管理中创建或改用其它角色");
                }
                if (!normalized.contains(c)) normalized.add(c);
            }
        }
        ensureDeptExists(departmentId);
        if (status != null && status != 0 && status != 1) throw new BizException("非法状态");
        // 最后一名管理员级账号不可降级为普通角色，避免把自己锁死（多角色口径：任一角色为管理员级）
        boolean targetAdmin = normalized.stream().anyMatch(roleService::isAdminCode);
        if (userRoleService.isAdmin(uid) && !targetAdmin && isLastAdminAccount()) {
            throw new BizException("至少保留一名管理员级账号，无法降级最后一名");
        }
        String name = trimOrNull(username);
        if (name != null) ensureUsernameUnique(name, uid);
        user.setUsername(name);
        user.setDepartmentId(trimOrNull(departmentId));
        if (status != null) user.setStatus(status);
        userMapper.updateById(user);
        // 档案整行被请求侧缓存 60s：改完不逐出，禁用/改角色/改部门要多等一分钟才对新的请求生效
        com.wenqu.ai.config.UserContextInterceptor.evict(uid);
        // 角色全量替换写多角色表（granted_by='manual' 行；idp 行不动——IdP 权威）
        userRoleService.setManualRoles(uid, normalized);
    }

    public void deleteUser(String uid) {
        // 成员管理是平台动作（Phase 4）：判据与理由见 asPlatformIfSuper 的注释
        asPlatformIfSuperVoid(() -> deleteUserScoped(uid));
    }

    private void deleteUserScoped(String uid) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        if (userRoleService.isAdmin(uid) && isLastAdminAccount()) {
            throw new BizException("至少保留一名管理员级账号，无法删除最后一名");
        }
        userMapper.deleteById(uid);
        com.wenqu.ai.config.UserContextInterceptor.evict(uid);
        // 级联清理角色/外部身份绑定（多角色与统一身份模型的归属行，不清理会在重建同名 uid 时继承旧授权）
        userRoleService.removeAll(uid);
        identityService.unbindAll(uid);
        // 记忆是用户资产，删人不能只删库行：MEMORY.md 与日账都在 user-data/agentscope/{uid} 下。
        // 清理失败只告警（账号已经删了），但必须显形——静默留下无主记忆会让下一个同名 uid 继承旧数据。
        try {
            memoryService.clearAll(uid);
        } catch (Exception e) {
            log.error("[MEMORY] 删除用户后清理记忆失败 uid={}（请手工删除 user-data/agentscope/{}）: {}",
                    uid, uid, e.getMessage());
        }
        audit.record("[AUDIT] 删除用户 uid={}", uid);
    }

    /**
     * 解绑单点登录：清除该账号在统一身份表（{@code c_ai_user_identity}）中的全部外部身份绑定。
     * 解绑后须重新走对应 IdP 登录才能再次绑定。
     */
    public void unbindOidc(String uid) {
        // 成员管理是平台动作（Phase 4）：判据与理由见 asPlatformIfSuper 的注释
        asPlatformIfSuperVoid(() -> unbindOidcScoped(uid));
    }

    private void unbindOidcScoped(String uid) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        identityService.unbindAll(uid);
        audit.record("[AUDIT] 解绑单点登录（全部源） uid={}", uid);
    }

    /**
     * 逐源解绑：只清除该账号在指定认证源下的外部身份绑定，其他认证源绑定不动。
     * 解绑后须重新走对应认证源登录才能再次绑定。
     */
    public void unbindIdentity(String uid, String providerId) {
        // 成员管理是平台动作（Phase 4）：判据与理由见 asPlatformIfSuper 的注释
        asPlatformIfSuperVoid(() -> unbindIdentityScoped(uid, providerId));
    }

    private void unbindIdentityScoped(String uid, String providerId) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        if (identityService.findOf(uid, providerId) == null) throw new BizException("该账号在此认证源下没有绑定");
        identityService.unbind(uid, providerId);
        audit.record("[AUDIT] 逐源解绑身份 uid={} provider={}", uid, providerId);
    }

    // ==================== 个人偏好（个人设置） ====================

    /**
     * 个人偏好读取：个人默认聊天模型（引用，空=未设默认）+ 可用聊天模型清单。
     * 个人默认重排/视觉模型已退役（重排归知识库检索设置；聊天图片理解只看模型能力位）。
     */
    public java.util.Map<String, Object> getPreference(String uid, String role) {
        User u = userMapper.selectById(uid);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        String defaultModel = u == null ? null : u.getDefaultModel();
        m.put("defaultModel", defaultModel);
        // 个人默认模型是否还能用（停用供应商 / 从模型列表移除之后，这一列不会自己变）：
        // 界面上「进入系统就知道」，而不是每轮撞一次上游报错
        m.put("defaultModelIssue", defaultModel == null || defaultModel.isBlank() ? ""
                : modelRegistryService.issueText(modelRegistryService
                        .referenceUsability(defaultModel, uid, role).issue()));
        // 用户级长期记忆开关（null=历史行缺省，视为开）：个人设置「长期记忆」页开关数据源
        m.put("memoryEnabled", u == null || !Integer.valueOf(0).equals(u.getMemoryEnabled()));
        m.put("models", modelRegistryService.available(ModelRegistryService.TYPE_CHAT, uid, role));
        return m;
    }

    /**
     * 设置个人偏好（多槽位，字段缺省(null)=不修改，空串=清除）：
     * 个人默认模型校验引用有效、对本人可用（平台级 + 自己登记的个人级）、且登记类型与槽位一致（防选到不可用模型）。
     * memoryEnabled=用户级长期记忆自动提炼开关（Boolean，null=不修改）。
     * 向量不提供个人默认（向量空间与索引一一对应，归知识库）；重排/视觉个人默认已退役
     * （重排归知识库检索设置；聊天图片理解只看模型能力位，见 ChatService）。
     */
    public void setPreference(String uid, String chatRef, Boolean memoryEnabled) {
        User u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        String role = userRoleService.primaryRole(uid);
        if (chatRef != null) {
            String v = chatRef.trim();
            validateDefaultModel(v, ModelRegistryService.TYPE_CHAT, "聊天", uid, role);
            u.setDefaultModel(v.isEmpty() ? null : v);
        }
        if (memoryEnabled != null) {
            u.setMemoryEnabled(Boolean.TRUE.equals(memoryEnabled) ? 1 : 0);
        }
        userMapper.updateById(u);
        audit.record("[AUDIT] 个人偏好已更新 uid={} chat={} memoryEnabled={}", uid,
                chatRef == null ? "(未改)" : chatRef.isBlank() ? "(清空)" : chatRef,
                memoryEnabled == null ? "(未改)" : memoryEnabled);
    }

    /**
     * 本人的「自动提炼记忆」是否开着（null=历史行缺省，视为开）。
     * <p>框架引擎按它决定这一路的 {@code MemoryConfig.flushTrigger}：关掉的只是每轮收尾的提炼，
     * 已沉淀的 {@code MEMORY.md} 照常注入——与个人设置里那句「仅关生成，不关注入」一致。
     */
    public boolean memoryFlushEnabled(String uid) {
        User u = uid == null || uid.isBlank() ? null : userMapper.selectById(uid);
        return u == null || !Integer.valueOf(0).equals(u.getMemoryEnabled());
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
            audit.record("[AUDIT] 用户修改昵称 uid={} username={}", uid, name);
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
        audit.record("[AUDIT] 用户头像已更新 uid={} avatar={}", uid, next == null ? "(清空)" : next);
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

    /** 是否仅剩这一名管理员级账号（多角色口径：持有任一管理员级角色的用户数 ≤1） */
    private boolean isLastAdminAccount() {
        return userRoleService.isLastAdminAccount();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
