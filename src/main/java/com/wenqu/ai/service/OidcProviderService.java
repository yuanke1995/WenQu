package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.OidcProviderMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.OidcProvider;
import com.wenqu.ai.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OIDC 认证源管理：CRUD + 启用停用 + 删除守门 + client_secret 逐行加密/脱敏。
 * <p>
 * 形状照搬 {@link ModelRegistryService}（模型供应商）：敏感字段 RSA 加密入库、列表只回显后 4 位、
 * 未修改提交掩码不覆盖原值。写操作目前是管理员全局视角（认证源是平台级资源，不是个人资产）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class OidcProviderService {

    private final OidcProviderMapper mapper;
    private final ConfigCryptoService crypto;
    private final RoleService roleService;
    private final UserMapper userMapper;

    public OidcProviderService(OidcProviderMapper mapper, ConfigCryptoService crypto,
                              RoleService roleService, UserMapper userMapper) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.roleService = roleService;
        this.userMapper = userMapper;
    }

    /** 全部认证源（排序后），client_secret 掩码脱敏；供管理页 */
    public List<Map<String, Object>> listMasked() {
        List<OidcProvider> all = mapper.selectList(new LambdaQueryWrapper<OidcProvider>()
                .orderByAsc(OidcProvider::getSortOrder));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (OidcProvider p : all) {
            out.add(toMaskedView(p));
        }
        return out;
    }

    /** 登录页公开清单：仅 enabled 且配置完整的认证源，只回 {id, name}（不含任何敏感字段） */
    public List<Map<String, Object>> listPublic() {
        List<OidcProvider> all = mapper.selectList(new LambdaQueryWrapper<OidcProvider>()
                .eq(OidcProvider::getEnabled, 1)
                .orderByAsc(OidcProvider::getSortOrder));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (OidcProvider p : all) {
            if (!configured(p)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            out.add(m);
        }
        return out;
    }

    public OidcProvider getById(String id) {
        return mapper.selectById(id);
    }

    /**
     * 新建或更新一个认证源。client_secret 留空/掩码 → 保留已存值（不覆盖）；否则 RSA 加密入库。
     * 保存期即校验：默认角色必须已启用；若启用则 clientId 与 (issuerUrl 或 authorizationEndpoint) 必填。
     */
    public OidcProvider save(String id, String name, Integer enabled,
                             String issuerUrl, String clientId, String clientSecret, String redirectUri,
                             String frontendBaseUrl, String authorizationEndpoint, String tokenEndpoint,
                             String userinfoEndpoint, String scopes, Integer forcePromptLogin,
                             String usernameClaim, String nameClaim, Integer useRawUsername,
                             Integer autoCreateUser, String defaultRole, String defaultDepartment,
                             Integer fetchDepartmentInfo, String departmentClaim, Integer sortOrder) {
        boolean isNew = (id == null || id.isBlank());
        OidcProvider p = isNew ? new OidcProvider() : mapper.selectById(id);
        if (p == null) {
            p = new OidcProvider();
            isNew = true;
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("认证源名称不能为空");
        p.setName(name.trim());
        p.setEnabled(enabled == null || enabled == 1 ? 1 : 0);
        p.setIssuerUrl(blankToNull(issuerUrl));
        p.setClientId(blankToNull(clientId));
        // 空值/掩码 → 保留已存密钥（isNew 时空值存空，PKCE 等无密钥流程）
        if (clientSecret != null && !clientSecret.isBlank() && !clientSecret.startsWith("****")) {
            p.setClientSecret(crypto.encrypt(clientSecret.trim()));
        } else if (p.getClientSecret() == null) {
            p.setClientSecret("");
        }
        p.setRedirectUri(blankToNull(redirectUri));
        p.setFrontendBaseUrl(blankToNull(frontendBaseUrl));
        p.setAuthorizationEndpoint(blankToNull(authorizationEndpoint));
        p.setTokenEndpoint(blankToNull(tokenEndpoint));
        p.setUserinfoEndpoint(blankToNull(userinfoEndpoint));
        p.setScopes(scopes == null || scopes.isBlank() ? "openid profile email" : scopes.trim());
        p.setForcePromptLogin(forcePromptLogin == null || forcePromptLogin == 1 ? 1 : 0);
        p.setUsernameClaim(usernameClaim == null || usernameClaim.isBlank() ? "preferred_username" : usernameClaim.trim());
        p.setNameClaim(nameClaim == null || nameClaim.isBlank() ? "name" : nameClaim.trim());
        p.setUseRawUsername(useRawUsername == null || useRawUsername == 1 ? 1 : 0);
        p.setAutoCreateUser(autoCreateUser == null || autoCreateUser == 1 ? 1 : 0);
        p.setDefaultRole(defaultRole == null || defaultRole.isBlank() ? "user" : defaultRole.trim().toLowerCase());
        p.setDefaultDepartment(blankToNull(defaultDepartment));
        p.setFetchDepartmentInfo(fetchDepartmentInfo == null || fetchDepartmentInfo == 1 ? 1 : 0);
        p.setDepartmentClaim(departmentClaim == null || departmentClaim.isBlank() ? "department" : departmentClaim.trim());
        p.setSortOrder(sortOrder == null ? 0 : sortOrder);

        // 保存期校验（不再等首次登录才报错，管理员后知后觉）
        if (!roleService.existsActive(p.getDefaultRole())) {
            throw new IllegalArgumentException("默认角色「" + p.getDefaultRole() + "」不存在或已停用，请在权限管理中创建或改用其它角色");
        }
        if (p.getEnabled() == 1) {
            if (blank(p.getClientId()) || (blank(p.getIssuerUrl()) && blank(p.getAuthorizationEndpoint()))) {
                throw new IllegalArgumentException("启用认证源必须填写 Client ID，并至少填写 Issuer URL 或授权端点");
            }
        }

        if (isNew) {
            p.setId(UUID.randomUUID().toString());
            mapper.insert(p);
            log.info("[OIDC] 认证源已创建: {}（{}）", p.getName(), p.getId());
        } else {
            mapper.updateById(p);
            log.info("[OIDC] 认证源已更新: {}（{}）", p.getName(), p.getId());
        }
        return p;
    }

    public void setEnabled(String id, boolean enabled) {
        OidcProvider p = require(id);
        if (enabled) {
            if (blank(p.getClientId()) || (blank(p.getIssuerUrl()) && blank(p.getAuthorizationEndpoint()))) {
                throw new IllegalArgumentException("启用认证源必须填写 Client ID，并至少填写 Issuer URL 或授权端点");
            }
        }
        p.setEnabled(enabled ? 1 : 0);
        mapper.updateById(p);
        log.info("[OIDC] 认证源已{}: {}（{}）", enabled ? "启用" : "停用", p.getName(), id);
    }

    /**
     * 删除认证源：有账号绑定（oidc_provider = id 且 oidc_sub 非空）时拒绝，提示先到成员管理解绑——
     * 防止登录入口静默消失、账号成孤儿。
     */
    public void delete(String id) {
        OidcProvider p = require(id);
        long bound = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getOidcProvider, id).isNotNull(User::getOidcSub));
        if (bound > 0) {
            throw new IllegalArgumentException("认证源「" + p.getName() + "」仍绑定 " + bound
                    + " 个账号，请先在「成员管理」解绑后再删除");
        }
        mapper.deleteById(id);
        log.info("[OIDC] 认证源已删除: {}（{}）", p.getName(), id);
    }

    /** 配置完整（启用判定在调用方）：clientId + (issuerUrl 或 authorizationEndpoint) 即可生成登录链接 */
    public boolean configured(OidcProvider p) {
        return p != null && !blank(p.getClientId())
                && (!blank(p.getIssuerUrl()) || !blank(p.getAuthorizationEndpoint()));
    }

    /** 解密 client_secret（供测试连接等需要真密钥的场景） */
    public String decryptSecret(OidcProvider p) {
        return p == null || blank(p.getClientSecret()) ? null : crypto.decrypt(p.getClientSecret());
    }

    /** 脱敏：只回显后 4 位（与 ConfigService 快照脱敏同口径） */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) return "";
        if (secret.length() <= 4) return secret;
        return "****" + secret.substring(secret.length() - 4);
    }

    /** 由 OidcMigrationRunner 调用：把旧 oidc.* 配置组装成 default 行（跳过校验，原样搬入） */
    public OidcProvider createFromMigration(String id, String name, OidcProvider src) {
        OidcProvider p = new OidcProvider();
        p.setId(id);
        p.setName(name);
        p.setEnabled(src.getEnabled());
        p.setIssuerUrl(src.getIssuerUrl());
        p.setClientId(src.getClientId());
        p.setClientSecret(src.getClientSecret() == null ? "" : src.getClientSecret());
        p.setRedirectUri(src.getRedirectUri());
        p.setFrontendBaseUrl(src.getFrontendBaseUrl());
        p.setAuthorizationEndpoint(src.getAuthorizationEndpoint());
        p.setTokenEndpoint(src.getTokenEndpoint());
        p.setUserinfoEndpoint(src.getUserinfoEndpoint());
        p.setScopes(src.getScopes());
        p.setForcePromptLogin(src.getForcePromptLogin());
        p.setUsernameClaim(src.getUsernameClaim());
        p.setNameClaim(src.getNameClaim());
        p.setUseRawUsername(src.getUseRawUsername());
        p.setAutoCreateUser(src.getAutoCreateUser());
        p.setDefaultRole(src.getDefaultRole());
        p.setDefaultDepartment(src.getDefaultDepartment());
        p.setFetchDepartmentInfo(src.getFetchDepartmentInfo());
        p.setDepartmentClaim(src.getDepartmentClaim());
        p.setSortOrder(0);
        mapper.insert(p);
        log.info("[OIDC] 迁移组装认证源: {}（{}）", p.getName(), p.getId());
        return p;
    }

    private OidcProvider require(String id) {
        OidcProvider p = mapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("认证源不存在（可能已被删除，请刷新后重试）");
        return p;
    }

    private Map<String, Object> toMaskedView(OidcProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("enabled", p.getEnabled() == null ? 0 : p.getEnabled());
        m.put("issuerUrl", p.getIssuerUrl());
        m.put("clientId", p.getClientId());
        m.put("clientSecret", mask(decryptSecret(p)));
        m.put("redirectUri", p.getRedirectUri());
        m.put("frontendBaseUrl", p.getFrontendBaseUrl());
        m.put("authorizationEndpoint", p.getAuthorizationEndpoint());
        m.put("tokenEndpoint", p.getTokenEndpoint());
        m.put("userinfoEndpoint", p.getUserinfoEndpoint());
        m.put("scopes", p.getScopes());
        m.put("forcePromptLogin", p.getForcePromptLogin() == null ? 0 : p.getForcePromptLogin());
        m.put("usernameClaim", p.getUsernameClaim());
        m.put("nameClaim", p.getNameClaim());
        m.put("useRawUsername", p.getUseRawUsername() == null ? 0 : p.getUseRawUsername());
        m.put("autoCreateUser", p.getAutoCreateUser() == null ? 0 : p.getAutoCreateUser());
        m.put("defaultRole", p.getDefaultRole());
        m.put("defaultDepartment", p.getDefaultDepartment());
        m.put("fetchDepartmentInfo", p.getFetchDepartmentInfo() == null ? 0 : p.getFetchDepartmentInfo());
        m.put("departmentClaim", p.getDepartmentClaim());
        m.put("sortOrder", p.getSortOrder() == null ? 0 : p.getSortOrder());
        m.put("configured", configured(p));
        return m;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
