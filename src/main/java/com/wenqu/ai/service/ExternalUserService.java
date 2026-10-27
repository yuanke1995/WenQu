package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Department;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 外部账号落位：把「外部身份（OIDC sub / SAML NameID / …）」落到本地账号，OIDC 与 SAML
 * 登录共用同一套语义（原为 OidcService 私有逻辑，SAML 接入时抽出）：
 * <ol>
 *   <li>已绑定（providerId + externalId 命中 {@code c_ai_user_identity}）→ 直接登录；</li>
 *   <li>{@code useRawUsername} 且外部用户名对应的账号存在 → 绑定后登录
 *       （同一 (provider, externalId) 已绑他人则拒绝——防冒用）；</li>
 *   <li>否则按 {@code autoCreateUser} 自动建档（默认角色 grant 到 {@code c_ai_user_role} 的
 *       {@code idp} 行、默认/同步部门落 {@code c_ai_user.department_id}），或拒绝并提示联系管理员。</li>
 * </ol>
 * 建档刻意不设密码：SSO 账号不走本地密码登录，同时不干扰「首次运行初始化管理员」的判定。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalUserService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private final com.wenqu.ai.mapper.IdentityProviderMapper providerMapper;
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final OrgService orgService;
    private final RoleService roleService;
    private final IdentityService identityService;
    private final UserRoleService userRoleService;

    /** 账号标识字符集（与 OrgService 的 uid 校验一致：带冒号的外部标识不能直接当 uid） */
    private static final Pattern UID_PATTERN = Pattern.compile("[A-Za-z0-9_@.\\-]+");
    private static final int UID_MAX = 64;
    private static final int USERNAME_MAX = 100;
    private static final int DEPT_NAME_MAX = 50;

    /** 从外部 IdP 提取出的用户画像 */
    public record ExternalProfile(String externalId, String username, String displayName, String departmentName) {
    }

    /** 认证源侧的落位配置（来源：认证源行） */
    public record ProvisionSpec(boolean useRawUsername, boolean autoCreateUser, boolean fetchDepartmentInfo,
                                String defaultRole, String defaultDepartment) {
    }

    /**
     * 外部身份 → 本地账号（绑定/绑定既有/自动建档三段式，见类注释）。
     *
     * @param protocol 协议标签，用于日志与报错文案（"OIDC" / "SAML"）
     * @param uidPrefix 自动派生账号标识的前缀（oidc / saml）
     */
    public User resolveUser(String protocol, String uidPrefix, String providerId,
                            ExternalProfile profile, ProvisionSpec spec) {
        return inProviderTenant(providerId, () -> resolveUserScoped(protocol, uidPrefix, providerId, profile, spec));
    }

    private User resolveUserScoped(String protocol, String uidPrefix, String providerId,
                                   ExternalProfile profile, ProvisionSpec spec) {
        User bound = findByExternal(providerId, profile.externalId());
        if (bound != null) return assertUsable(bound);

        if (spec.useRawUsername() && notBlank(profile.username())) {
            String uid = rawUid(profile.username());
            if (uid != null) {
                User exist = userMapper.selectById(uid);
                if (exist != null) {
                    // 同一账号可绑定多个认证源的身份；仅当该 (provider, externalId) 已绑到别人时拒绝
                    String boundUid = identityService.uidOfExternal(providerId, profile.externalId());
                    if (boundUid != null && !boundUid.equals(uid)) {
                        throw new BizException("该 " + protocol + " 身份已绑定其他账号，请联系管理员处理绑定冲突");
                    }
                    identityService.bind(uid, providerId, profile.externalId());
                    audit.record("[AUDIT] {} 身份绑定到既有账号 uid={} provider={} external={}",
                            protocol, uid, providerId, profile.externalId());
                    return assertUsable(exist);
                }
            }
        }

        if (!spec.autoCreateUser()) {
            throw new BizException("用户未注册，请联系管理员开通账号");
        }
        return createUser(protocol, uidPrefix, providerId, profile, spec);
    }

    private User createUser(String protocol, String uidPrefix, String providerId,
                            ExternalProfile profile, ProvisionSpec spec) {
        String base = spec.useRawUsername() ? rawUid(profile.username()) : null;
        String uid = ensureUniqueUid(base == null ? sanitizeUid(uidPrefix, profile.externalId()) : base,
                profile.externalId());
        String username = ensureUniqueUsername(notBlank(profile.displayName()) ? profile.displayName() : profile.username(),
                profile.externalId());
        String role = normalizeRole(protocol, spec.defaultRole());
        String deptId = resolveDepartmentId(protocol, spec, profile);

        User u = new User();
        u.setUid(uid);
        u.setUsername(username);
        u.setDepartmentId(deptId);
        u.setStatus(1);
        u.setLoginFailCount(0);
        try {
            userMapper.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            String racedUid = identityService.uidOfExternal(providerId, profile.externalId());
            if (racedUid != null && userMapper.selectById(racedUid) != null) {
                return assertUsable(userMapper.selectById(racedUid));
            }
            throw new BizException("创建 " + protocol + " 账号失败（登录账号或昵称冲突），请重试或联系管理员");
        }
        // 外部身份与默认角色落统一身份/多角色表（不写 c_ai_user 的 oidc_sub/oidc_provider/role 旧列）
        identityService.bind(uid, providerId, profile.externalId());
        userRoleService.grant(uid, role, "idp");
        audit.record("[AUDIT] {} 自动建档 provider={} uid={} username={} role={} dept={}",
                protocol, providerId, uid, username, role, deptId);
        return u;
    }

    private User findByExternal(String providerId, String externalId) {
        String uid = identityService.uidOfExternal(providerId, externalId);
        return uid == null ? null : userMapper.selectById(uid);
    }

    /** 账号可用性守门：禁用即拒 */
    public User assertUsable(User u) {
        if (u.getStatus() != null && u.getStatus() == 0) throw new BizException("账号已被禁用，请联系管理员");
        return u;
    }

    /** 外部用户名 → 账号标识；不合法（超长/含非法字符）时返回 null 由调用方回退到外部标识派生 */
    private String rawUid(String username) {
        String u = username == null ? "" : username.trim();
        if (u.isEmpty() || u.length() > UID_MAX || !UID_PATTERN.matcher(u).matches()) {
            if (!u.isEmpty()) log.warn("[IDENTITY] 外部用户名 {} 不能作为账号标识（含非法字符或过长），改用外部标识派生", u);
            return null;
        }
        return u;
    }

    /** 外部标识 → 合法账号标识（协议前缀 + 非法字符替换，超长截断）；SCIM 建号等外部场景复用 */
    public String sanitizeUid(String prefix, String externalId) {
        String s = externalId.replaceAll("[^A-Za-z0-9_@.\\-]", "_");
        String uid = prefix + "_" + s;
        return uid.length() > UID_MAX ? uid.substring(0, UID_MAX) : uid;
    }

    /**
     * 把外部落位逻辑跑在<b>认证源自己的租户</b>里（Phase 4 多租户）。
     * <p>登录/同步线程上还没有已登录身份 ⇒ 线程没绑租户，而「未绑定」在租户拦截器里等于「不过滤」。
     * 那时按外部用户名去认领既有账号，会跨租户捞到别家的同名账号直接放行；建出来的新档也会落进
     * 默认租户——认证源明明属于 acme，人却成了 default 的成员。这两个都不是「少一条数据」级别的错，
     * 所以判据放在唯一入口上，而不是散在各协议里。
     * <p>认证源行自身的 {@code tenant_id} 用平台视图读（当前线程可能正绑着别的租户）。
     * 取不到租户（老库里 providerId 已不存在）时按原样跑，行为与本次改造前一致。
     */
    public <T> T inProviderTenant(String providerId, java.util.function.Supplier<T> body) {
        String tenant = providerTenant(providerId);
        return tenant == null ? body.get() : com.wenqu.ai.util.TenantContext.as(tenant, body);
    }

    /** {@link #inProviderTenant} 的无返回值版（同步作业只写不读） */
    public void inProviderTenant(String providerId, Runnable body) {
        inProviderTenant(providerId, () -> {
            body.run();
            return null;
        });
    }

    private String providerTenant(String providerId) {
        if (providerId == null || providerId.isBlank()) return null;
        try {
            com.wenqu.ai.model.IdentityProvider p = com.wenqu.ai.util.TenantContext
                    .asPlatform(() -> providerMapper.selectById(providerId));
            return p == null ? null : p.getTenantId();
        } catch (Exception e) {
            log.warn("[SSO] 认证源租户归属读取失败 provider={}（按未绑定继续，建出的账号会落默认租户）: {}",
                    providerId, e.getMessage());
            return null;
        }
    }

    /**
     * 账号标识是否已被占用——<b>平台级</b>判据，理由见 {@link #ensureUniqueUid}。
     */
    private boolean uidTaken(String uid) {
        return com.wenqu.ai.util.TenantContext.asPlatform(() -> userMapper.selectById(uid)) != null;
    }

    /**
     * 账号标识唯一化（冲突时追加短哈希/序号）；SCIM 建号等外部场景复用。
     * <p>查重跨全平台而不是只查本租户：uid 是全平台一个命名空间（磁盘上的记忆目录 {@code agentscope/{uid}}、
     * 沙盒工作区 {@code shared/{uid}/workspace} 与令牌里的身份都只带 uid），两个租户各起一个同名账号
     * 会互相踩对方的文件。本租户过滤下查出「没占用」、插入时撞主键，只会把一条清楚的报错换成一次 500。
     */
    public String ensureUniqueUid(String base, String seed) {
        if (!uidTaken(base)) return base;
        String suffix = "_" + shortHash(seed);
        String candidate = base;
        if (base.length() + suffix.length() > UID_MAX) candidate = base.substring(0, UID_MAX - suffix.length());
        candidate = candidate + suffix;
        if (!uidTaken(candidate)) return candidate;
        for (int i = 2; i < 100; i++) {
            String c = candidate + "_" + i;
            if (!uidTaken(c)) return c;
        }
        throw new BizException("无法生成可用账号标识，请联系管理员");
    }

    /** 用户名（显示名）唯一化（冲突时追加短哈希/序号）；SCIM 建号等外部场景复用 */
    public String ensureUniqueUsername(String preferred, String seed) {
        String base = preferred == null ? "" : preferred.trim();
        if (base.isEmpty()) base = "sso_" + shortHash(seed);
        if (base.length() > USERNAME_MAX) base = base.substring(0, USERNAME_MAX);
        if (usernameFree(base)) return base;
        String suffix = "-" + shortHash(seed);
        String candidate = base.length() + suffix.length() > USERNAME_MAX
                ? base.substring(0, USERNAME_MAX - suffix.length()) + suffix : base + suffix;
        if (usernameFree(candidate)) return candidate;
        for (int i = 2; i < 100; i++) {
            String c = candidate + "-" + i;
            if (c.length() <= USERNAME_MAX && usernameFree(c)) return c;
        }
        throw new BizException("无法生成可用昵称，请联系管理员");
    }

    private boolean usernameFree(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) == 0;
    }

    /** 默认角色必须是权限管理中「已启用」的角色：否则会建出登录得进、但处处 403 的账号，比直接报错难查得多 */
    private String normalizeRole(String protocol, String role) {
        String r = (role == null || role.isBlank()) ? "user" : role.trim().toLowerCase();
        if (!roleService.existsActive(r)) {
            throw new BizException(protocol + " 默认角色「" + r + "」不存在或已停用，请在权限管理中创建或改用其它角色");
        }
        return r;
    }

    /**
     * 部门归属：优先用 IdP 给的部门名（需开启「从 IdP 同步部门」），否则用配置的默认部门；
     * 部门不存在则按名称创建（按名称匹配，不维护层级）。
     */
    private String resolveDepartmentId(String protocol, ProvisionSpec spec, ExternalProfile profile) {
        String name = spec.fetchDepartmentInfo() ? trimToNull(profile.departmentName()) : null;
        if (name == null) name = trimToNull(spec.defaultDepartment());
        if (name == null) return null;
        if (name.length() > DEPT_NAME_MAX) name = name.substring(0, DEPT_NAME_MAX);

        Department exist = departmentMapper.selectOne(new LambdaQueryWrapper<Department>()
                .eq(Department::getName, name).last("LIMIT 1"));
        if (exist != null) return exist.getId();
        try {
            return orgService.createDepartment(name, name + "部门", null).getId();
        } catch (BizException e) {
            // 并发创建或同名部门已存在（含软删）：退回按名称再查一次，仍无则不带部门（登录不受影响）
            Department again = departmentMapper.selectOne(new LambdaQueryWrapper<Department>()
                    .eq(Department::getName, name).last("LIMIT 1"));
            if (again != null) return again.getId();
            log.warn("[{}] 部门「{}」创建失败，本次不带部门：{}", protocol, name, e.getMessage());
            return null;
        }
    }

    private static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
