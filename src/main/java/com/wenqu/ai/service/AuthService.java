package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.UserContextInterceptor;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.AuthCrypto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地登录鉴权：密码校验 + JWT 令牌签发/解析 + 首次运行初始化 + 失败锁定。
 * <p>
 * 密码为 PBKDF2 哈希（{@link AuthCrypto}），令牌为 HS256 JWT；均不引入第三方依赖。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private final UserMapper userMapper;
    private final AppProperties properties;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final IdentityService identityService;
    private final DeploymentSecrets secrets;
    /** 租户启停判据（停用租户挡登录的那条承诺落在这里与 {@link ExternalUserService#assertUsable}） */
    private final TenantService tenantService;

    /** 运行时解析后的签名密钥（取用规则与多副本一致性由 DeploymentSecrets 兜住） */
    private volatile String resolvedSecret;

    @PostConstruct
    void init() {
        String s = secrets.jwtSecret();
        if (s.length() < 32) {
            log.warn("[AUTH] 登录令牌密钥长度仅 {} 位，建议使用 ≥32 位随机密钥", s.length());
        }
        resolvedSecret = s;
    }

    public String secret() { return resolvedSecret; }

    public String issuer() { return properties.getAuth().getIssuer(); }

    public String audience() { return properties.getAuth().getAudience(); }

    public long ttlSeconds() { return properties.getAuth().getTokenTtlHours() * 3600L; }

    /** 解析令牌（校验签名/有效期/iss/aud），返回 uid；无效返回 null */
    public String uidFromToken(String token) {
        Map<String, Object> claims = AuthCrypto.parseToken(resolvedSecret, issuer(), audience(), token);
        if (claims == null) return null;
        String sub = String.valueOf(claims.get("sub"));
        return sub.isBlank() ? null : sub;
    }

    /** 解析令牌全量载荷（校验同上），无效返回 null；会话治理的 ep 代数判据在拦截器里比对 */
    public Map<String, Object> claimsFromToken(String token) {
        return AuthCrypto.parseToken(resolvedSecret, issuer(), audience(), token);
    }

    /** 该用户当前的会话代数（签发令牌时写进 ep；null 视为 0，与存量行兼容） */
    public long tokenEpoch(User user) {
        return user == null || user.getTokenEpoch() == null ? 0L : user.getTokenEpoch();
    }

    /** 是否「首次运行」：库中尚无任何已设置密码的用户（仅此时允许初始化管理员） */
    public boolean needsInitialize() {
        Long n = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .isNotNull(User::getPasswordHash).ne(User::getPasswordHash, ""));
        return n == null || n == 0;
    }

    /** 初始化管理员：仅在「无任何已设密码用户」时允许；创建或升级指定用户为 superadmin 并设置密码，随后直接登录 */
    @Transactional
    public Map<String, Object> initializeAdmin(String uid, String username, String password) {
        String u = required(uid, "登录账号");
        String name = required(username, "用户昵称");
        checkPassword(password);
        if (!needsInitialize()) throw new BizException("系统已初始化，请直接登录或由管理员添加账号");

        User user = userMapper.selectById(u);
        if (user == null) {
            user = new User();
            user.setUid(u);
            user.setUsername(name);
            user.setStatus(1);
            user.setPasswordHash(AuthCrypto.hashPassword(password));
            user.setLoginFailCount(0);
            userMapper.insert(user);
        } else {
            // 存量用户（可能由成员管理建过、无密码）→ 升级为超管并设密码
            user.setUsername(name);
            user.setStatus(1);
            user.setPasswordHash(AuthCrypto.hashPassword(password));
            user.setLoginFailCount(0);
            user.setLockedUntil(null);
            userMapper.updateById(user);
        }
        // 超管走多角色表（c_ai_user_role），不再写 c_ai_user.role 旧列
        userRoleService.grant(u, "superadmin", "manual");
        audit.record("[AUDIT] 初始化管理员 uid={} username={}", u, name);
        return login(u, password);
    }

    /** 登录：identifier 仅认 uid（登录账号）；昵称仅作展示，不再参与登录 */
    @Transactional
    public Map<String, Object> login(String identifier, String password) {
        String id = required(identifier, "账号");
        required(password, "密码");
        User user = userMapper.selectById(id);
        // 统一文案，不暴露"账号是否存在"
        if (user == null) throw new BizException("账号或密码不正确");
        if (user.getStatus() != null && user.getStatus() == 0) throw new BizException("账号已被禁用");
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BizException("登录失败次数过多，请于 " + user.getLockedUntil() + " 后重试");
        }
        if (!AuthCrypto.isHashed(user.getPasswordHash())) {
            throw new BizException("该账号尚未设置密码，请联系管理员初始化");
        }
        if (!AuthCrypto.verifyPassword(user.getPasswordHash(), password)) {
            registerFailure(user);
            throw new BizException("账号或密码不正确");
        }
        // 所属租户被平台停用 → 拒（判据与 SSO/SCIM/目录同步共用 TenantService.isActive，见手册「停用只挡登录」）。
        // 放在口令校验<b>之后</b>：口令不对的人只该听到「账号或密码不正确」，不该被告知「这家租户还在平台上」。
        tenantService.requireActive(user.getTenantId());
        boolean lockDirty = (user.getLoginFailCount() != null && user.getLoginFailCount() > 0) || user.getLockedUntil() != null;
        if (lockDirty) {
            user.setLoginFailCount(0);
            user.setLockedUntil(null);
        }
        // 最近登录时间：每次登录成功都落库（成员管理「最近登录时间」列的数据源；OIDC 回调在 OidcService 同口径记）
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);
        String token = AuthCrypto.issueToken(secret(), issuer(), audience(),
                user.getUid(), userRoleService.primaryRole(user.getUid()), ttlSeconds(), tokenEpoch(user));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("user", userInfo(user));
        audit.record("[AUDIT] 登录成功 uid={} roles={}", user.getUid(), userRoleService.rolesOf(user.getUid()));
        return out;
    }

    /** 本人改密（校验旧密码） */
    public void changePassword(String uid, String oldPassword, String newPassword) {
        required(uid, "登录账号");
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        if (!AuthCrypto.verifyPassword(user.getPasswordHash(), oldPassword)) {
            throw new BizException("原密码不正确");
        }
        checkPassword(newPassword);
        user.setPasswordHash(AuthCrypto.hashPassword(newPassword));
        // 会话治理：改密即全端下线——代数 +1 后，此人所有已签发令牌（含其他设备/被盗的会话）当场失效
        user.setTokenEpoch(tokenEpoch(user) + 1);
        userMapper.updateById(user);
        UserContextInterceptor.evict(uid);
        audit.record("[AUDIT] 修改密码 uid={}（会话代数升至 {}，全端已下线）", uid, user.getTokenEpoch());
    }

    /** 管理员重置他人密码（同时清除锁定） */
    public void resetPassword(String uid, String newPassword) {
        checkPassword(newPassword);
        // 与新建/编辑成员同一条平台视图判据（理由见 OrgService.asPlatformIfSuper 的注释）：
        // 名单对 superadmin 是跨租户摊开的，这里不切视图就会出现「列表里看得见这个人、点重置报用户不存在」，
        // 而批准开通时发出的那次性口令一旦丢失，平台侧就再没有恢复入口。
        com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() -> {
            User user = userMapper.selectById(uid);
            if (user == null) throw new BizException("用户不存在");
            user.setPasswordHash(AuthCrypto.hashPassword(newPassword));
            user.setLoginFailCount(0);
            user.setLockedUntil(null);
            // 会话治理：重置密码即解锁 + 全端下线（离职/被盗场景的兜底动作，新口令只发到该发的人手里）
            user.setTokenEpoch(tokenEpoch(user) + 1);
            userMapper.updateById(user);
            UserContextInterceptor.evict(uid);
            audit.record("[AUDIT] 重置密码 uid={}（会话代数升至 {}，全端已下线）", uid, user.getTokenEpoch());
        });
    }

    /**
     * 管理员强制下线：会话代数 +1，该账号已签发的全部令牌（所有设备）当场失效。
     * 账号疑似被盗、离职即踢、或「他改完密码怎么还在登录态」的答案都在这一处。
     * 与改密/重置共用同一代数机制——踢人只有一种判据，就不会有漏网的那种。
     */
    public void forceLogout(String uid) {
        required(uid, "登录账号");
        com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() -> {
            User user = userMapper.selectById(uid);
            if (user == null) throw new BizException("用户不存在");
            user.setTokenEpoch(tokenEpoch(user) + 1);
            userMapper.updateById(user);
            UserContextInterceptor.evict(uid);
            audit.record("[AUDIT] 强制下线 uid={}（会话代数升至 {}）", uid, user.getTokenEpoch());
        });
    }

    /** 用户公开信息（不含敏感字段）：多角色并集判定管理员；外部身份绑定取自统一身份表 */
    public Map<String, Object> userInfo(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> roles = userRoleService.rolesOf(u.getUid());
        m.put("uid", u.getUid());
        m.put("username", u.getUsername());
        m.put("role", userRoleService.primaryRole(u.getUid()));
        m.put("roles", roles);
        m.put("departmentId", u.getDepartmentId());
        m.put("admin", roleService.isAdminAny(roles));
        // 外部身份绑定（成员管理展示用）：首个绑定的认证源 id 与外部标识；未绑定则均为 null
        com.wenqu.ai.model.UserIdentity ident = identityService.listOf(u.getUid())
                .stream().findFirst().orElse(null);
        m.put("oidcProvider", ident == null ? null : ident.getProviderId());
        m.put("oidcSub", ident == null ? null : ident.getExternalId());
        return m;
    }

    /**
     * 内置管理员角色（历史口径保留：仅 admin/superadmin 两值）。
     * <p>统一的管理员级判定请走 {@link RoleService#isAdminCode}（含自定义 admin_flag=1 角色）；
     * 本静态方法仅保留给无注入场景的语义兜底。</p>
     */
    public static boolean isAdminRole(String role) {
        return "admin".equals(role) || "superadmin".equals(role);
    }

    /** 校验密码策略并返回哈希（供建用户复用） */
    public String hashNewPassword(String rawPassword) {
        checkPassword(rawPassword);
        return AuthCrypto.hashPassword(rawPassword);
    }

    /** 当前身份（供 /auth/me）：返回 uid/username/roles/departmentId 与是否管理员 */
    public Map<String, Object> currentUser(String uid, boolean adminFromGuard) {
        User u = (uid == null || uid.isBlank()) ? null : userMapper.selectById(uid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", uid);
        if (u != null) {
            m.put("uid", u.getUid());
            m.put("username", u.getUsername());
            m.put("avatar", u.getAvatar());
            m.put("role", userRoleService.primaryRole(u.getUid()));
            m.put("roles", userRoleService.rolesOf(u.getUid()));
            m.put("departmentId", u.getDepartmentId());
        }
        m.put("admin", adminFromGuard || (u != null && userRoleService.isAdmin(u.getUid())));
        return m;
    }

    // ==================== 内部 ====================

    private void registerFailure(User user) {
        int max = properties.getAuth().getMaxLoginFailures();
        if (max <= 0) return;
        int count = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        if (count >= max) {
            user.setLoginFailCount(0);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(properties.getAuth().getLockMinutes()));
            log.warn("[AUTH] 账号 {} 连续登录失败达 {} 次，已锁定 {} 分钟", user.getUid(), max, properties.getAuth().getLockMinutes());
        } else {
            user.setLoginFailCount(count);
        }
        userMapper.updateById(user);
    }

    private void checkPassword(String password) {
        int min = Math.max(1, properties.getAuth().getMinPasswordLength());
        if (password == null || password.length() < min) {
            throw new BizException("密码至少 " + min + " 位");
        }
        if (password.length() > 128) throw new BizException("密码过长（最多 128 位）");
    }

    private static String required(String v, String label) {
        if (v == null || v.isBlank()) throw new BizException(label + "不能为空");
        return v.trim();
    }
}
