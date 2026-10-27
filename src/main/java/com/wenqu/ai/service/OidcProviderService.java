package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.mapper.IdentityProviderMapper;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.model.OidcProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OIDC 认证源的登录流数据视图：统一表行 ↔ {@link OidcProvider} 视图互转、登录页公开清单、密钥解密。
 * <p>
 * <b>数据统一存 {@code c_ai_identity_provider}(type='oidc')</b>（2026-10-26 任务 #8：旧表
 * c_ai_oidc_provider 已退役）：协议参数收纳进 {@code config} JSON（键名与 {@link OidcProvider}
 * 视图字段一致，clientSecret 为 RSA 密文），name/enabled/defaultRole/defaultDepartment/
 * autoCreateUser/sortOrder 用统一表列。
 * <p>
 * OIDC 的管理面（CRUD/启停/删除）已退役（2026-10-27）：四类认证源统一走
 * {@link IdentityProviderService}（「认证源」页签）；本服务只服务登录链路
 * （{@link OidcService}）与存量迁移（OidcMigrationRunner）。
 * <p>
 * config JSON 的密文形态兼容两种（读侧按 "RSA: " 前缀判断）：存量库桥接产物为明文 JSON +
 * clientSecret 字段级 RSA（IdentityMigrationRunner 原样搬旧表密文）；新写入沿同一形态。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class OidcProviderService {

    private final IdentityProviderMapper mapper;
    private final ConfigCryptoService crypto;
    private final ObjectMapper json = new ObjectMapper();

    public OidcProviderService(IdentityProviderMapper mapper, ConfigCryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    /** 统一表 OIDC 行 → 视图（config JSON 解析；坏配置按空配置降级，登录/管理不炸） */
    private OidcProvider toView(IdentityProvider row) {
        OidcProvider p = new OidcProvider();
        p.setId(row.getId());
        p.setName(row.getName());
        p.setEnabled(row.getEnabled() == null ? 0 : row.getEnabled());
        p.setSortOrder(row.getSortOrder() == null ? 0 : row.getSortOrder());
        p.setDefaultRole(row.getDefaultRole());
        p.setDefaultDepartment(row.getDefaultDepartment());
        p.setAutoCreateUser(row.getAutoCreateUser());
        JsonNode c = parseConfig(row.getConfig());
        if (c != null) {
            p.setIssuerUrl(text(c, "issuerUrl"));
            p.setClientId(text(c, "clientId"));
            p.setClientSecret(text(c, "clientSecret"));
            p.setRedirectUri(text(c, "redirectUri"));
            p.setFrontendBaseUrl(text(c, "frontendBaseUrl"));
            p.setAuthorizationEndpoint(text(c, "authorizationEndpoint"));
            p.setTokenEndpoint(text(c, "tokenEndpoint"));
            p.setUserinfoEndpoint(text(c, "userinfoEndpoint"));
            p.setScopes(text(c, "scopes"));
            p.setForcePromptLogin(intOf(c, "forcePromptLogin"));
            p.setUsernameClaim(text(c, "usernameClaim"));
            p.setNameClaim(text(c, "nameClaim"));
            p.setUseRawUsername(intOf(c, "useRawUsername"));
            p.setFetchDepartmentInfo(intOf(c, "fetchDepartmentInfo"));
            p.setDepartmentClaim(text(c, "departmentClaim"));
            // 桥接产物把这几个也放进了 config：统一表列优先，config 仅兜底
            if (p.getDefaultRole() == null) p.setDefaultRole(text(c, "defaultRole"));
            if (p.getDefaultDepartment() == null) p.setDefaultDepartment(text(c, "defaultDepartment"));
            if (p.getAutoCreateUser() == null) p.setAutoCreateUser(intOf(c, "autoCreateUser"));
        }
        return p;
    }

    /** 视图 → 统一表行（config JSON 组装；clientSecret 字段为 RSA 密文，与桥接产物同形态） */
    private IdentityProvider toRow(OidcProvider p) {
        IdentityProvider row = mapper.selectById(p.getId());
        if (row == null) {
            row = new IdentityProvider();
            row.setId(p.getId());
            row.setType("oidc");
        }
        row.setName(p.getName());
        row.setEnabled(p.getEnabled());
        row.setDefaultRole(p.getDefaultRole());
        row.setDefaultDepartment(p.getDefaultDepartment());
        row.setAutoCreateUser(p.getAutoCreateUser());
        row.setSortOrder(p.getSortOrder());
        Map<String, Object> cfg = new LinkedHashMap<>();
        put(cfg, "issuerUrl", p.getIssuerUrl());
        put(cfg, "clientId", p.getClientId());
        put(cfg, "clientSecret", p.getClientSecret());
        put(cfg, "redirectUri", p.getRedirectUri());
        put(cfg, "frontendBaseUrl", p.getFrontendBaseUrl());
        put(cfg, "authorizationEndpoint", p.getAuthorizationEndpoint());
        put(cfg, "tokenEndpoint", p.getTokenEndpoint());
        put(cfg, "userinfoEndpoint", p.getUserinfoEndpoint());
        put(cfg, "scopes", p.getScopes());
        put(cfg, "forcePromptLogin", p.getForcePromptLogin());
        put(cfg, "usernameClaim", p.getUsernameClaim());
        put(cfg, "nameClaim", p.getNameClaim());
        put(cfg, "useRawUsername", p.getUseRawUsername());
        put(cfg, "fetchDepartmentInfo", p.getFetchDepartmentInfo());
        put(cfg, "departmentClaim", p.getDepartmentClaim());
        try {
            row.setConfig(json.writeValueAsString(cfg));
        } catch (Exception e) {
            throw new IllegalStateException("OIDC 认证源配置序列化失败: " + e.getMessage(), e);
        }
        return row;
    }

    // ==================== 查询 ====================

    /** 登录页公开清单：仅 enabled 且配置完整的认证源，只回 {id, name}（不含任何敏感字段） */
    public List<Map<String, Object>> listPublic() {
        List<IdentityProvider> all = mapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                .eq(IdentityProvider::getType, "oidc")
                .eq(IdentityProvider::getEnabled, 1)
                .orderByAsc(IdentityProvider::getSortOrder));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (IdentityProvider row : all) {
            OidcProvider p = toView(row);
            if (!configured(p)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            out.add(m);
        }
        return out;
    }

    public OidcProvider getById(String id) {
        IdentityProvider row = mapper.selectById(id);
        return row == null || !"oidc".equals(row.getType()) ? null : toView(row);
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
        mapper.insert(toRow(p));
        log.info("[OIDC] 迁移组装认证源: {}（{}）", p.getName(), p.getId());
        return p;
    }

    // ==================== 杂项 ====================

    /** 配置完整（启用判定在调用方）：clientId + (issuerUrl 或 authorizationEndpoint) 即可生成登录链接 */
    public boolean configured(OidcProvider p) {
        return p != null && !blank(p.getClientId())
                && (!blank(p.getIssuerUrl()) || !blank(p.getAuthorizationEndpoint()));
    }

    /** 解密 client_secret（供测试连接等需要真密钥的场景） */
    public String decryptSecret(OidcProvider p) {
        return p == null || blank(p.getClientSecret()) ? null : crypto.decrypt(p.getClientSecret());
    }

    /** config JSON 解析（兼容整块 RSA 加密与明文两种形态；坏配置返回 null） */
    private JsonNode parseConfig(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = crypto.decrypt(raw);
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            log.warn("[OIDC] 认证源 config 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
    }

    private static Integer intOf(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        try {
            return Integer.parseInt(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void put(Map<String, Object> cfg, String k, Object v) {
        if (v != null) cfg.put(k, v);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
