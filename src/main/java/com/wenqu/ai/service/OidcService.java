package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Department;
import com.wenqu.ai.model.OidcProvider;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.AuthCrypto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 单点登录（OIDC 授权码流程）—— 多认证源（多 IdP）版本。
 * <p>
 * 与平台版 {@code backend/package/yuxi/services/oidc_service.py} 同构；配置来源从原先的
 * {@code c_ai_config} 扁平键改为 {@code c_ai_oidc_provider} 表（每行一个认证源，照模型供应商形态：
 * client_secret 逐行 RSA 加密）。登录入口按 state 携带的 providerId 路由；回调 URL 保持不变
 * （多个 client 在各自 IdP 登记同一回调地址即可）。
 * <p>
 * 身份绑定落在 {@code c_ai_user.oidc_sub} + {@code c_ai_user.oidc_provider} 复合唯一索引上——
 * 一个 sub 在同一认证源下只能绑一个账号（防冒用），两个不同 IdP 签发相同 sub 值不再冲突。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OidcService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private final OidcProviderService providerService;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final UserRoleService userRoleService;
    private final SsoLoginCodeService loginCodeService;
    private final ExternalUserService externalUserService;

    /** 授权 state 有效期（IdP 侧完成认证的耗时上限） */
    private static final long STATE_TTL_MS = 5 * 60 * 1000L;
    /** 元数据缓存时长：缓存键含配置值，所以改配置立即失效；此 TTL 只兜「远端端点变更」 */
    private static final long METADATA_TTL_MS = 10 * 60 * 1000L;

    /** 后端回调路径（重定向回本服务，路径含上下文） */
    private static final String CALLBACK_PATH = "/api/ai/auth/oidc/callback";
    /** 前端回调路由（与 web/src/router.js 一致） */
    private static final String FRONTEND_CALLBACK_PATH = "/auth/oidc/callback";
    /** 前端登录页路径 */
    private static final String FRONTEND_LOGIN_PATH = "/login";

    /** 账号标识字符集（与 OrgService 的 uid 校验一致：带冒号的 sub 不能直接当 uid） */
    private static final Pattern UID_PATTERN = Pattern.compile("[A-Za-z0-9_@.\\-]+");
    private static final int UID_MAX = 64;
    private static final int USERNAME_MAX = 100;
    private static final int DEPT_NAME_MAX = 50;

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** state → 授权上下文（一次性，消费即删）；state 携带 providerId 以路由回调 */
    private final Map<String, StateEntry> states = new ConcurrentHashMap<>();

    /** 元数据缓存（按 providerId）：键含配置值，改配置即失效 */
    private final Map<String, MetadataCache> metadataCache = new ConcurrentHashMap<>();

    // ==================== 对外 ====================

    /**
     * 前端登录页用的公开配置：**只回「可登录的认证源清单」**。
     * <p>只回 enabled 且配置完整的认证源 {id, name}；配置未填完的认证源不出现，避免按钮点下去撞错误页。</p>
     */
    public Map<String, Object> publicConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providers", providerService.listPublic());
        return m;
    }

    /**
     * 生成某认证源的授权地址（含一次性 state，state 内携带 providerId）。
     *
     * @param providerId   认证源 ID（前端从 publicConfig 的 providers 拿）
     * @param redirectPath 登录成功后前端要去的路径（随 state 暂存，回调时带回）
     */
    public String loginUrl(String providerId, String redirectPath, HttpServletRequest request) {
        OidcProvider p = loadUsable(providerId);
        Metadata m = metadata(p);
        if (m == null || blank(m.authorizationEndpoint())) {
            throw new BizException("生成登录链接失败：" + (lastMetadataError(p.getId()) == null ? "授权端点不可用" : lastMetadataError(p.getId())));
        }
        String state = newToken();
        states.put(state, new StateEntry(providerId, redirectPath == null || redirectPath.isBlank() ? "/" : redirectPath,
                System.currentTimeMillis() + STATE_TTL_MS));
        sweep();

        StringBuilder q = new StringBuilder();
        appendQuery(q, "response_type", "code");
        appendQuery(q, "client_id", p.getClientId());
        appendQuery(q, "scope", blank(p.getScopes()) ? "openid profile email" : p.getScopes());
        appendQuery(q, "redirect_uri", redirectUri(p, request));
        appendQuery(q, "state", state);
        if (p.getForcePromptLogin() != null && p.getForcePromptLogin() == 1) appendQuery(q, "prompt", "login");
        String auth = m.authorizationEndpoint();
        return auth + (auth.contains("?") ? "&" : "?") + q;
    }

    /**
     * 处理 IdP 回调：换 token → 取用户信息 → 落到本地账号 → 签发本系统令牌，
     * 最后带一次性 code 跳回前端回调页（令牌不落在 URL 里）。
     *
     * @return 要让浏览器跳转的目标地址（成功=前端回调页，失败=前端登录页带 oidc_error）
     */
    public String handleCallback(String code, String state, HttpServletRequest request) {
        StateEntry se = state == null ? null : states.remove(state);
        if (se == null || se.expiresAt() < System.currentTimeMillis()) {
            return loginWithError("登录会话已过期，请返回登录页重试");
        }
        String providerId = se.providerId();
        OidcProvider p = providerService.getById(providerId);
        if (p == null || p.getEnabled() == null || p.getEnabled() != 1) {
            return loginWithError("认证源已下架或停用，请联系管理员");
        }
        if (!tokenExchangeConfigured(p)) {
            return loginWithError("OIDC 配置不完整（缺少 Client Secret 或令牌端点），请联系管理员");
        }
        if (blank(code)) return loginWithError("IdP 未返回授权码（可能被拒绝授权），请返回登录页重试");
        Metadata m = metadata(p);
        if (m == null || blank(m.tokenEndpoint())) {
            return loginWithError("令牌端点不可用：" + (lastMetadataError(providerId) == null ? "请检查 Issuer 配置" : lastMetadataError(providerId)));
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri(p, request));
        form.put("client_id", p.getClientId());
        form.put("client_secret", providerService.decryptSecret(p));
        JsonNode token = postForm(m.tokenEndpoint(), form);
        String accessToken = token == null ? null : text(token, "access_token");
        if (blank(accessToken)) return loginWithError("无法获取访问令牌，请返回登录页重试");

        if (blank(m.userinfoEndpoint())) return loginWithError("未配置用户信息端点（Issuer 的 discovery 里也没有），请联系管理员");
        JsonNode userinfo = getJson(m.userinfoEndpoint(), Map.of("Authorization", "Bearer " + accessToken));
        if (userinfo == null) return loginWithError("无法获取用户信息，请返回登录页重试");

        Extracted ex = extract(p, userinfo);
        if (blank(ex.sub())) return loginWithError("无法获取用户标识（sub），请返回登录页重试");

        User user;
        try {
            user = resolveUser(p, ex);
        } catch (BizException e) {
            return loginWithError(e.getMessage());
        }

        // 最近登录时间：OIDC 回调登录成功也记（与本地登录同口径，成员管理「最近登录时间」列同源）
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", issue(user));
        payload.put("user", authService.userInfo(user));
        payload.put("redirectPath", se.redirectPath());

        String oneTime = loginCodeService.issue(payload);
        audit.record("[AUDIT] OIDC 登录成功 provider={} uid={} sub={} roles={}",
                providerId, user.getUid(), ex.sub(), userRoleService.rolesOf(user.getUid()));
        return frontBase(p) + FRONTEND_CALLBACK_PATH + "?code=" + enc(oneTime);
    }

    // ==================== 账号解析 ====================

    /**
     * 把 OIDC 身份落到本地账号：三段式（已绑定 → 绑定既有账号 → 自动建档），
     * 语义统一在 {@link ExternalUserService}（OIDC/SAML 共用）。
     */
    private User resolveUser(OidcProvider p, Extracted ex) {
        return externalUserService.resolveUser("OIDC", "oidc", p.getId(),
                new ExternalUserService.ExternalProfile(ex.sub(), ex.username(), ex.name(), ex.departmentName()),
                new ExternalUserService.ProvisionSpec(
                        p.getUseRawUsername() != null && p.getUseRawUsername() == 1,
                        p.getAutoCreateUser() != null && p.getAutoCreateUser() == 1,
                        p.getFetchDepartmentInfo() != null && p.getFetchDepartmentInfo() == 1,
                        p.getDefaultRole(), p.getDefaultDepartment()));
    }

    // ==================== 用户信息映射 ====================

    /** 用户信息 → 本地字段（回落链与蓝本一致） */
    private Extracted extract(OidcProvider p, JsonNode ui) {
        String sub = text(ui, "sub");
        String username = firstNonBlank(text(ui, p.getUsernameClaim()), text(ui, "preferred_username"));
        if (username == null) {
            String email = text(ui, "email");
            username = email == null ? null : email.split("@")[0];
        }
        if (username == null && sub != null) username = sub.substring(0, Math.min(20, sub.length()));
        String name = firstNonBlank(text(ui, p.getNameClaim()), text(ui, "name"), username);
        String deptName = null;
        if (p.getFetchDepartmentInfo() != null && p.getFetchDepartmentInfo() == 1) {
            deptName = firstNonBlank(text(ui, p.getDepartmentClaim()), text(ui, "department"));
        }
        return new Extracted(sub, username, name, deptName);
    }

    // ==================== 元数据（discovery） ====================

    /**
     * 端点来源：手填端点优先（IdP 不支持 discovery 时用），否则读 Issuer 的
     * {@code /.well-known/openid-configuration}。缓存键含全部配置值 ⇒ 改配置立即重新加载。
     */
    private Metadata metadata(OidcProvider p) {
        String pid = p.getId();
        String auth = trimToNull(p.getAuthorizationEndpoint());
        String token = trimToNull(p.getTokenEndpoint());
        String ui = trimToNull(p.getUserinfoEndpoint());
        String key = trimToNull(p.getIssuerUrl()) + "|" + auth + "|" + token + "|" + ui;
        MetadataCache cached = metadataCache.get(pid);
        if (cached != null && key.equals(cached.key()) && System.currentTimeMillis() - cached.at() < METADATA_TTL_MS) {
            return cached.metadata();
        }
        Metadata loaded;
        if (auth != null) {
            loaded = new Metadata(auth, token, ui);
            metadataCache.put(pid, new MetadataCache(loaded, key, System.currentTimeMillis(), null));
            return loaded;
        }
        String issuer = trimToNull(p.getIssuerUrl());
        if (issuer == null) {
            metadataCache.put(pid, new MetadataCache(null, key, System.currentTimeMillis(), "未配置 Issuer URL 且未手填授权端点"));
            return null;
        }
        String url = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        JsonNode doc = getJson(url, Map.of());
        if (doc == null) {
            metadataCache.put(pid, new MetadataCache(null, key, System.currentTimeMillis(), "OIDC discovery 加载失败（" + url + "）"));
            return null;
        }
        auth = text(doc, "authorization_endpoint");
        token = text(doc, "token_endpoint");
        ui = text(doc, "userinfo_endpoint");
        if (blank(auth)) {
            metadataCache.put(pid, new MetadataCache(null, key, System.currentTimeMillis(), "discovery 响应缺少 authorization_endpoint"));
            return null;
        }
        loaded = new Metadata(auth, token, ui);
        metadataCache.put(pid, new MetadataCache(loaded, key, System.currentTimeMillis(), null));
        log.info("[OIDC] 元数据已加载 provider={} auth={} token={} userinfo={}", pid, auth, token, ui);
        return loaded;
    }

    private String lastMetadataError(String providerId) {
        MetadataCache c = metadataCache.get(providerId);
        return c == null ? null : c.error();
    }

    // ==================== HTTP ====================

    private JsonNode getJson(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET();
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                log.warn("[OIDC] GET {} 返回 {}：{}", url, r.statusCode(), brief(r.body()));
                return null;
            }
            return json.readTree(r.body());
        } catch (Exception e) {
            log.warn("[OIDC] GET {} 失败：{}", url, e.getMessage());
            return null;
        }
    }

    private JsonNode postForm(String url, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) body.append('&');
            body.append(enc(k)).append('=').append(enc(v == null ? "" : v));
        });
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[OIDC] POST {} 返回 {}：{}", url, resp.statusCode(), brief(resp.body()));
                return null;
            }
            return json.readTree(resp.body());
        } catch (Exception e) {
            log.warn("[OIDC] POST {} 失败：{}", url, e.getMessage());
            return null;
        }
    }

    // ==================== 杂项 ====================

    private OidcProvider loadUsable(String providerId) {
        if (blank(providerId)) throw new BizException("缺少认证源参数");
        OidcProvider p = providerService.getById(providerId);
        if (p == null || p.getEnabled() == null || p.getEnabled() != 1) {
            throw new BizException("该认证源不可用或已停用，请联系管理员");
        }
        if (!providerService.configured(p)) {
            throw new BizException("OIDC 登录暂不可用：请先在单点登录管理中启用并填写 Issuer / Client ID");
        }
        return p;
    }

    /** 换 token 的必要条件（与蓝本 is_token_exchange_configured 同口径）：clientSecret 已配置，且令牌端点可解析（手填或 discovery 源在） */
    private boolean tokenExchangeConfigured(OidcProvider p) {
        return p != null && !blank(p.getClientSecret())
                && (!blank(p.getIssuerUrl()) || !blank(p.getTokenEndpoint()));
    }

    /** 回调地址：配置优先；留空则按本次请求的来源推导（Host 头 + 上下文路径）。 */
    private String redirectUri(OidcProvider p, HttpServletRequest request) {
        String configured = trimToNull(p.getRedirectUri());
        if (configured != null) return configured;
        if (request == null) return CALLBACK_PATH;
        String proto = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null) return request.getContextPath() + CALLBACK_PATH;
        return proto + "://" + host + request.getContextPath() + CALLBACK_PATH;
    }

    private String frontBase(OidcProvider p) {
        String base = trimToNull(p.getFrontendBaseUrl());
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    private String loginWithError(String message) {
        return loginErrorRedirect(message);
    }

    /** 失败时跳回登录页并带 oidc_error（供 Controller 在 IdP 直接回绝时复用） */
    public String loginErrorRedirect(String message) {
        return frontBaseFallback() + FRONTEND_LOGIN_PATH + "?oidc_error=" + enc(message);
    }

    /**
     * 失败跳转的 frontBase 回落：回调时若 provider 已不可用无法取 frontBase，回落到相对路径
     * （同源部署场景下仍能带回登录页）。正常登录跳转由各 provider 的 frontBase 决定。
     */
    private String frontBaseFallback() {
        return "";
    }

    private String issue(User u) {
        // 第 7 参 tokenEpoch：OIDC 签发的令牌也要带会话代数（ep 声明），改密 / 强制下线才能一并踢掉 OIDC 会话，
        // 与 AuthService / SamlService 的登录签发同口径；会话治理把 issueToken 升到 7 参后漏改这里会编译失败。
        return AuthCrypto.issueToken(authService.secret(), authService.issuer(), authService.audience(),
                u.getUid(), userRoleService.primaryRole(u.getUid()), authService.ttlSeconds(),
                authService.tokenEpoch(u));
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
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

    private static void appendQuery(StringBuilder q, String k, String v) {
        if (q.length() > 0) q.append('&');
        q.append(enc(k)).append('=').append(enc(v == null ? "" : v));
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String brief(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /** 端点集合 */
    private record Metadata(String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint) {
    }

    /** 授权上下文（state → 认证源 + 前端目标路径） */
    private record StateEntry(String providerId, String redirectPath, long expiresAt) {
    }

    /** 一次性登录结果存储已抽到 {@link SsoLoginCodeService}（SAML 共用） */

    /** 元数据缓存（按 providerId） */
    private record MetadataCache(Metadata metadata, String key, long at, String error) {
    }

    /** 从用户信息里提取出的本地字段 */
    private record Extracted(String sub, String username, String name, String departmentName) {
    }

}
