package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.IdentityProviderMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.AuthCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAML 2.0 单点登录（SP 侧）：认证源清单 / 用户落位 / ACS 成功处理。
 * <p>
 * 协议层（AuthnRequest 生成、Response 验签、InResponseTo/Destination/NotOnOrAfter 校验）
 * 交 Spring Security SAML2 + OpenSAML 5（见 {@code SamlSecurityConfig}），本服务只做业务侧：
 * 认证源加载与校验、SAML 属性 → 本地账号（复用 {@link ExternalUserService} 三段式）、
 * 签发本系统 JWT 并以一次性 code 跳回前端（复用 OIDC 同一条回调页与兑换端点）。
 * <p>
 * 认证源登记在 {@code c_ai_identity_provider}（type=saml），config JSON 字段：
 * <ul>
 *   <li>{@code spBaseUrl}（必填）：本系统对外可达根地址，派生 ACS 与 SP EntityID；</li>
 *   <li>{@code metadataUrl}：IdP 元数据地址（首选，配了它则手填三件可省）；</li>
 *   <li>{@code idpEntityId}/{@code idpSsoUrl}/{@code idpCertificate}：无元数据时手填
 *       （certificate 为 PEM 正文）；</li>
 *   <li>{@code spCertificate}/{@code spPrivateKey}：可选，配了则 AuthnRequest 签名
 *       （ADFS 默认要求签名请求时必须配）；</li>
 *   <li>{@code frontendBaseUrl}：登录成功跳回的前端根地址（回落相对路径）；</li>
 *   <li>{@code usernameAttribute}/{@code nameAttribute}/{@code departmentAttribute}：
 *       SAML 属性名映射（缺省 uid/displayName/department）；</li>
 *   <li>{@code useRawUsername}/{@code fetchDepartmentInfo}：布尔，语义同 OIDC。</li>
 * </ul>
 * config 整块可 RSA 加密（"RSA: " 前缀，含 IdP 证书与 SP 私钥时建议开启）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SamlService {

    private final IdentityProviderMapper mapper;
    private final ConfigCryptoService crypto;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final UserRoleService userRoleService;
    private final SsoLoginCodeService loginCodeService;
    private final ExternalUserService externalUserService;

    private final ObjectMapper json = new ObjectMapper();

    /** 前端回调路由（复用 OIDC 的回调页；批次 5 前端统一改名 /auth/sso/callback） */
    public static final String FRONTEND_CALLBACK_PATH = "/auth/oidc/callback";
    /** 前端登录页路径（失败跳回；错误参数沿用 oidc_error，前端同一处展示） */
    public static final String FRONTEND_LOGIN_PATH = "/login";

    // ==================== 对外 ====================

    /** 前端登录页用的公开配置：只回 enabled 且配置完整的 SAML 认证源 {id, name} */
    public List<Map<String, Object>> listPublic() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IdentityProvider p : listEnabled()) {
            if (!configured(p)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            out.add(m);
        }
        return out;
    }

    public IdentityProvider getById(String id) {
        return mapper.selectById(id);
    }

    public boolean configured(IdentityProvider p) {
        if (p == null) return false;
        JsonNode c = config(p);
        return c != null && (hasText(c, "metadataUrl")
                || (hasText(c, "idpEntityId") && hasText(c, "idpSsoUrl") && hasText(c, "idpCertificate")));
    }

    /** 登录入口校验：可用且配置完整，否则抛 BizException（Controller 转 302 错误页） */
    public IdentityProvider loadUsable(String providerId) {
        if (providerId == null || providerId.isBlank()) throw new BizException("缺少认证源参数");
        IdentityProvider p = mapper.selectById(providerId);
        if (p == null || !"saml".equals(p.getType())
                || p.getEnabled() == null || p.getEnabled() != 1) {
            throw new BizException("该认证源不可用或已停用，请联系管理员");
        }
        if (!configured(p)) {
            throw new BizException("SAML 登录暂不可用：请先在认证源配置中填写 spBaseUrl 与 IdP 元数据（或手填三件套）");
        }
        return p;
    }

    /** spBaseUrl（必填）：派生 ACS 与 SP EntityID 的对外根地址（去尾部 /） */
    public String spBaseUrl(IdentityProvider p) {
        String base = text(config(p), "spBaseUrl");
        if (base == null) throw new BizException("SAML 认证源缺少 spBaseUrl 配置");
        return base.replaceAll("/+$", "");
    }

    /** 登录成功后前端跳转根地址（未配置回落相对路径：同源部署仍可达） */
    public String frontBaseUrl(IdentityProvider p) {
        String base = text(config(p), "frontendBaseUrl");
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    /**
     * SAML 登录成功（ACS 验签通过后）：
     * NameID + 属性 → 本地账号（三段式）→ 签发 JWT → 一次性 code → 前端回调页。
     *
     * @param attributes SAML attribute 原始值（首个取值生效）
     * @param redirectPath 登录后前端要去的路径（登录入口暂存，原样带回）
     */
    public String onSuccess(IdentityProvider p, String nameId, Map<String, List<Object>> attributes, String redirectPath) {
        try {
            JsonNode c = config(p);
            ExternalUserService.ExternalProfile profile = extract(c, nameId, attributes);
            if (profile.externalId() == null || profile.externalId().isBlank()) {
                return loginErrorRedirect("SAML 断言缺少 NameID，无法确定用户身份");
            }
            User user;
            try {
                user = externalUserService.resolveUser("SAML", "saml", p.getId(), profile,
                        new ExternalUserService.ProvisionSpec(
                                bool(c, "useRawUsername", false),
                                p.getAutoCreateUser() == null || p.getAutoCreateUser() == 1,
                                bool(c, "fetchDepartmentInfo", false),
                                p.getDefaultRole(), p.getDefaultDepartment()));
            } catch (BizException e) {
                return loginErrorRedirect(e.getMessage());
            }
            user.setLastLoginTime(LocalDateTime.now());
            userMapper.updateById(user);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("token", AuthCrypto.issueToken(authService.secret(), authService.issuer(),
                    authService.audience(), user.getUid(), userRoleService.primaryRole(user.getUid()),
                    authService.ttlSeconds()));
            payload.put("user", authService.userInfo(user));
            payload.put("redirectPath", redirectPath == null || redirectPath.isBlank() ? "/" : redirectPath);

            String oneTime = loginCodeService.issue(payload);
            log.info("[AUDIT] SAML 登录成功 provider={} uid={} nameId={} roles={}",
                    p.getId(), user.getUid(), nameId, userRoleService.rolesOf(user.getUid()));
            return frontBaseUrl(p) + FRONTEND_CALLBACK_PATH + "?code=" + urlEnc(oneTime);
        } catch (BizException e) {
            return loginErrorRedirect(e.getMessage());
        } catch (Exception e) {
            log.error("[SAML] 登录处理异常 provider={}", p.getId(), e);
            return loginErrorRedirect("SAML 登录处理失败，请重试或联系管理员");
        }
    }

    /** 失败跳回登录页（错误参数沿用 oidc_error，前端同一处展示） */
    public String loginErrorRedirect(String message) {
        return FRONTEND_LOGIN_PATH + "?oidc_error=" + urlEnc(message);
    }

    // ==================== 解析 ====================

    /** config JSON（整块 RSA 解密后解析；坏配置返回 null 而不是炸登录链路） */
    public JsonNode config(IdentityProvider p) {
        String raw = p.getConfig();
        if (raw == null || raw.isBlank()) return null;
        if (raw.startsWith("RSA:")) raw = crypto.decrypt(raw);
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            log.warn("[SAML] 认证源 config 解析失败 provider={}: {}", p.getId(), e.getMessage());
            return null;
        }
    }

    private ExternalUserService.ExternalProfile extract(JsonNode c, String nameId, Map<String, List<Object>> attributes) {
        String usernameAttr = c == null ? null : text(c, "usernameAttribute");
        String nameAttr = c == null ? null : text(c, "nameAttribute");
        String deptAttr = c == null ? null : text(c, "departmentAttribute");

        String username = firstOf(attributes, usernameAttr, "uid", "username", "urn:oid:0.9.2342.19200300.100.1.1");
        if (username == null && nameId != null && nameId.contains("@")) {
            username = nameId.split("@")[0]; // NameID 常见为 email
        }
        if (username == null) username = nameId;
        String displayName = firstOf(attributes, nameAttr, "displayName", "cn", "urn:oid:2.16.840.1.113730.3.1.241");
        if (displayName == null) displayName = username;
        String department = firstOf(attributes, deptAttr, "department", "ou");
        return new ExternalUserService.ExternalProfile(nameId, username, displayName, department);
    }

    private static String firstOf(Map<String, List<Object>> attributes, String... names) {
        if (attributes == null) return null;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            List<Object> v = attributes.get(n);
            if (v != null && !v.isEmpty() && v.get(0) != null) {
                String s = String.valueOf(v.get(0)).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    // ==================== 证书（RelyingPartyRegistration 构建用） ====================

    /** PEM 正文 → X509Certificate（手填 IdP/SP 证书用；失败抛 IllegalArgumentException） */
    public static X509Certificate parseCertificate(String pem) {
        try {
            String body = pem == null ? "" : pem
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("X.509 证书解析失败: " + e.getMessage(), e);
        }
    }

    /** PEM PKCS#8 私钥 → RSAPrivateKey（SP 签名用；失败抛 IllegalArgumentException） */
    public static java.security.interfaces.RSAPrivateKey parsePrivateKey(String pem) {
        try {
            String body = pem == null ? "" : pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            var spec = new java.security.spec.PKCS8EncodedKeySpec(der);
            var key = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
            return (java.security.interfaces.RSAPrivateKey) key;
        } catch (Exception e) {
            throw new IllegalArgumentException("SP 私钥解析失败（须为 PKCS#8 RSA 私钥）: " + e.getMessage(), e);
        }
    }

    /** config 短指纹（日志/异常定位用，不回显内容） */
    public static String briefFingerprint(JsonNode c) {
        if (c == null) return "null";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d).substring(0, 8);
        } catch (Exception e) {
            return "err";
        }
    }

    // ==================== 杂项 ====================

    private List<IdentityProvider> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                .eq(IdentityProvider::getType, "saml")
                .eq(IdentityProvider::getEnabled, 1)
                .orderByAsc(IdentityProvider::getSortOrder));
    }

    private static boolean hasText(JsonNode c, String field) {
        return text(c, field) != null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
    }

    private static boolean bool(JsonNode node, String field, boolean dft) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? dft : v.asBoolean(dft);
    }

    private static String urlEnc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
