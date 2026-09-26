package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.model.User;
import com.wisesoft.ai.util.AuthCrypto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    private final UserMapper userMapper;
    private final AppProperties properties;
    private final RoleService roleService;

    /** 运行时解析后的签名密钥（未配置时开发期随机生成） */
    private volatile String resolvedSecret;

    @PostConstruct
    void init() {
        AppProperties.Auth a = properties.getAuth();
        String s = a.getJwtSecret();
        if (s == null || s.isBlank()) {
            resolvedSecret = AuthCrypto.randomSecret();
            log.warn("[AUTH] 未配置 AI_JWT_SECRET，已生成临时随机密钥；服务重启后所有登录令牌将失效。生产环境请配置 ≥32 位随机串。");
        } else {
            if (s.length() < 32) {
                log.warn("[AUTH] AI_JWT_SECRET 长度仅 {} 位，建议使用 ≥32 位随机密钥", s.length());
            }
            resolvedSecret = s;
        }
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

    /** 是否「首次运行」：库中尚无任何已设置密码的用户（仅此时允许初始化管理员） */
    public boolean needsInitialize() {
        Long n = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .isNotNull(User::getPasswordHash).ne(User::getPasswordHash, ""));
        return n == null || n == 0;
    }

    /** 初始化管理员：仅在「无任何已设密码用户」时允许；创建或升级指定用户为 superadmin 并设置密码，随后直接登录 */
    @Transactional
    public Map<String, Object> initializeAdmin(String uid, String username, String password) {
        String u = required(uid, "用户标识");
        String name = required(username, "用户名");
        checkPassword(password);
        if (!needsInitialize()) throw new BizException("系统已初始化，请直接登录或由管理员添加账号");

        User user = userMapper.selectById(u);
        if (user == null) {
            user = new User();
            user.setUid(u);
            user.setUsername(name);
            user.setRole("superadmin");
            user.setStatus(1);
            user.setPasswordHash(AuthCrypto.hashPassword(password));
            user.setLoginFailCount(0);
            userMapper.insert(user);
        } else {
            // 存量用户（可能由成员管理建过、无密码）→ 升级为超管并设密码
            user.setUsername(name);
            user.setRole("superadmin");
            user.setStatus(1);
            user.setPasswordHash(AuthCrypto.hashPassword(password));
            user.setLoginFailCount(0);
            user.setLockedUntil(null);
            userMapper.updateById(user);
        }
        log.info("[AUDIT] 初始化管理员 uid={} username={}", u, name);
        return login(u, password);
    }

    /** 登录：identifier 允许 uid 或用户名 */
    @Transactional
    public Map<String, Object> login(String identifier, String password) {
        String id = required(identifier, "账号");
        required(password, "密码");
        User user = findByIdentifier(id);
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
        if ((user.getLoginFailCount() != null && user.getLoginFailCount() > 0) || user.getLockedUntil() != null) {
            user.setLoginFailCount(0);
            user.setLockedUntil(null);
            userMapper.updateById(user);
        }
        String token = AuthCrypto.issueToken(secret(), issuer(), audience(),
                user.getUid(), user.getRole(), ttlSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("user", userInfo(user));
        log.info("[AUDIT] 登录成功 uid={} role={}", user.getUid(), user.getRole());
        return out;
    }

    /** 本人改密（校验旧密码） */
    public void changePassword(String uid, String oldPassword, String newPassword) {
        required(uid, "用户标识");
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        if (!AuthCrypto.verifyPassword(user.getPasswordHash(), oldPassword)) {
            throw new BizException("原密码不正确");
        }
        checkPassword(newPassword);
        user.setPasswordHash(AuthCrypto.hashPassword(newPassword));
        userMapper.updateById(user);
        log.info("[AUDIT] 修改密码 uid={}", uid);
    }

    /** 管理员重置他人密码（同时清除锁定） */
    public void resetPassword(String uid, String newPassword) {
        User user = userMapper.selectById(uid);
        if (user == null) throw new BizException("用户不存在");
        checkPassword(newPassword);
        user.setPasswordHash(AuthCrypto.hashPassword(newPassword));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        userMapper.updateById(user);
        log.info("[AUDIT] 重置密码 uid={}", uid);
    }

    /** 用户公开信息（不含敏感字段；admin 按管理员级角色判定，含自定义 admin_flag=1） */
    public Map<String, Object> userInfo(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", u.getUid());
        m.put("username", u.getUsername());
        m.put("role", u.getRole() == null ? "user" : u.getRole());
        m.put("departmentId", u.getDepartmentId());
        m.put("admin", roleService.isAdminCode(u.getRole()));
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

    /** 当前身份（供 /auth/me）：返回 uid/username/role/departmentId 与是否管理员 */
    public Map<String, Object> currentUser(String uid, boolean adminFromGuard) {
        User u = (uid == null || uid.isBlank()) ? null : userMapper.selectById(uid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", uid);
        if (u != null) {
            m.put("uid", u.getUid());
            m.put("username", u.getUsername());
            m.put("role", u.getRole() == null ? "user" : u.getRole());
            m.put("departmentId", u.getDepartmentId());
        }
        m.put("admin", adminFromGuard || (u != null && roleService.isAdminCode(u.getRole())));
        return m;
    }

    // ==================== 内部 ====================

    private User findByIdentifier(String identifier) {
        User byId = userMapper.selectById(identifier);
        if (byId != null) return byId;
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, identifier).last("LIMIT 1"));
    }

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
