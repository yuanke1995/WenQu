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

    private final OidcProviderService providerService;
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final OrgService orgService;
    private final AuthService authService;
    private final RoleService roleService;

    /** 授权 state 有效期（IdP 侧完成认证的耗时上限） */
    private static final long STATE_TTL_MS = 5 * 60 * 1000L;
    /** 一次性登录 code 有效期（浏览器跳回前端后立即兑换，无需长） */
    private static final long LOGIN_CODE_TTL_MS = 60 * 1000L;
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
    /** 一次性登录 code → 登录响应（一次性，消费即删） */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

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

        String oneTime = newToken();
        codes.put(oneTime, new CodeEntry(payload, System.currentTimeMillis() + LOGIN_CODE_TTL_MS));
        sweep();
        log.info("[AUDIT] OIDC 登录成功 provider={} uid={} sub={} role={}", providerId, user.getUid(), ex.sub(), user.getRole());
        return frontBase(p) + FRONTEND_CALLBACK_PATH + "?code=" + enc(oneTime);
    }

    /** 用一次性 code 兑换登录结果（兑换即失效，避免令牌留在浏览器历史/日志里） */
    public Map<String, Object> exchangeCode(String code) {
        CodeEntry ce = blank(code) ? null : codes.remove(code);
        if (ce == null || ce.expiresAt() < System.currentTimeMillis()) {
            throw new BizException("登录 code 无效或已过期，请重新登录");
        }
        return ce.payload;
    }

    // ==================== 账号解析 ====================

    /**
     * 把 OIDC 身份落到本地账号：
     * <ol>
     *   <li>已绑定（providerId + sub 命中）→ 直接登录；</li>
     *   <li>{@code useRawUsername} 且 IdP 用户名对应的账号存在且**未被其他 sub 绑定** → 绑定后登录
     *       （绑定冲突一律拒绝，不覆盖，防冒用）；</li>
     *   <li>否则按 {@code autoCreateUser} 自动建档，或拒绝并提示联系管理员。</li>
     * </ol>
     */
    private User resolveUser(OidcProvider p, Extracted ex) {
        User bound = findByOidcSub(p.getId(), ex.sub());
        if (bound != null) return assertUsable(bound);

        if (p.getUseRawUsername() != null && p.getUseRawUsername() == 1 && !blank(ex.username())) {
            String uid = rawUid(ex.username());
            if (uid != null) {
                User exist = userMapper.selectById(uid);
                if (exist != null) {
                    if (blank(exist.getOidcSub())) {
                        exist.setOidcSub(ex.sub());
                        exist.setOidcProvider(p.getId());
                        userMapper.updateById(exist);
                        log.info("[AUDIT] OIDC 身份绑定到既有账号 uid={} provider={} sub={}", uid, p.getId(), ex.sub());
                        return assertUsable(exist);
                    }
                    throw new BizException("账号 " + uid + " 已绑定其他 OIDC 身份，请联系管理员处理绑定冲突");
                }
            }
        }

        if (p.getAutoCreateUser() == null || p.getAutoCreateUser() != 1) {
            throw new BizException("用户未注册，请联系管理员开通账号");
        }
        return createUser(p, ex);
    }

    private User createUser(OidcProvider p, Extracted ex) {
        String base = (p.getUseRawUsername() != null && p.getUseRawUsername() == 1) ? rawUid(ex.username()) : null;
        String uid = ensureUniqueUid(base == null ? sanitizeUid(ex.sub()) : base, ex.sub());
        String username = ensureUniqueUsername(blank(ex.name()) ? ex.username() : ex.name(), ex.sub());
        String role = normalizeRole(p.getDefaultRole());
        String deptId = resolveDepartmentId(p, ex);

        User u = new User();
        u.setUid(uid);
        u.setUsername(username);
        u.setRole(role);
        u.setDepartmentId(deptId);
        u.setStatus(1);
        u.setLoginFailCount(0);
        u.setOidcSub(ex.sub());
        u.setOidcProvider(p.getId());
        // 刻意不设密码：OIDC 账号不能走本地密码登录；同时不干扰「首次运行初始化管理员」的判定
        // （needsInitialize() 数的是"已设置密码的用户"，给随机密码会让初始化流程消失）。
        try {
            userMapper.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            User raced = findByOidcSub(p.getId(), ex.sub());
            if (raced != null) return assertUsable(raced);
            throw new BizException("创建 OIDC 账号失败（登录账号或昵称冲突），请重试或联系管理员");
        }
        log.info("[AUDIT] OIDC 自动建档 provider={} uid={} username={} role={} dept={}", p.getId(), uid, username, role, deptId);
        return u;
    }

    private User findByOidcSub(String providerId, String sub) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getOidcSub, sub).eq(User::getOidcProvider, providerId).last("LIMIT 1"));
    }

    private User assertUsable(User u) {
        if (u.getStatus() != null && u.getStatus() == 0) throw new BizException("账号已被禁用，请联系管理员");
        return u;
    }

    /** IdP 用户名 → 账号标识；不合法（超长/含非法字符）时返回 null 由调用方回退到 sub 派生 */
    private String rawUid(String username) {
        String u = username == null ? "" : username.trim();
        if (u.isEmpty() || u.length() > UID_MAX || !UID_PATTERN.matcher(u).matches()) {
            if (!u.isEmpty()) log.warn("[OIDC] IdP 用户名 {} 不能作为账号标识（含非法字符或过长），改用 sub 派生", u);
            return null;
        }
        return u;
    }

    /** sub → 合法账号标识（`oidc_` 前缀 + 非法字符替换，超长截断） */
    private String sanitizeUid(String sub) {
        String s = sub.replaceAll("[^A-Za-z0-9_@.\\-]", "_");
        String uid = "oidc_" + s;
        return uid.length() > UID_MAX ? uid.substring(0, UID_MAX) : uid;
    }

    private String ensureUniqueUid(String base, String sub) {
        if (userMapper.selectById(base) == null) return base;
        String suffix = "_" + shortHash(sub);
        String candidate = base;
        if (base.length() + suffix.length() > UID_MAX) candidate = base.substring(0, UID_MAX - suffix.length());
        candidate = candidate + suffix;
        if (userMapper.selectById(candidate) == null) return candidate;
        for (int i = 2; i < 100; i++) {
            String c = candidate + "_" + i;
            if (userMapper.selectById(c) == null) return c;
        }
        throw new BizException("无法生成可用账号标识，请联系管理员");
    }

    /** 用户名（显示名）唯一：直接用 → 加 sub 短哈希 → 加序号（与蓝本同序） */
    private String ensureUniqueUsername(String preferred, String sub) {
        String base = preferred == null ? "" : preferred.trim();
        if (base.isEmpty()) base = "oidc_" + shortHash(sub);
        if (base.length() > USERNAME_MAX) base = base.substring(0, USERNAME_MAX);
        if (usernameFree(base)) return base;
        String suffix = "-" + shortHash(sub);
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
    private String normalizeRole(String role) {
        String r = (role == null || role.isBlank()) ? "user" : role.trim().toLowerCase();
        if (!roleService.existsActive(r)) {
            throw new BizException("OIDC 默认角色「" + r + "」不存在或已停用，请在权限管理中创建或改用其它角色");
        }
        return r;
    }

    /**
     * 部门归属：优先用 IdP 给的部门名（需开启「从 IdP 同步部门」），否则用配置的默认部门；
     * 部门不存在则按名称创建（与蓝本同语义：按名称匹配，不维护层级）。
     */
    private String resolveDepartmentId(OidcProvider p, Extracted ex) {
        String name = (p.getFetchDepartmentInfo() != null && p.getFetchDepartmentInfo() == 1)
                ? trimToNull(ex.departmentName()) : null;
        if (name == null) name = trimToNull(p.getDefaultDepartment());
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
            log.warn("[OIDC] 部门「{}」创建失败，本次不带部门：{}", name, e.getMessage());
            return null;
        }
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
        return AuthCrypto.issueToken(authService.secret(), authService.issuer(), authService.audience(),
                u.getUid(), u.getRole(), authService.ttlSeconds());
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        codes.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
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

    /** 一次性登录结果 */
    private record CodeEntry(Map<String, Object> payload, long expiresAt) {
    }

    /** 元数据缓存（按 providerId） */
    private record MetadataCache(Metadata metadata, String key, long at, String error) {
    }

    /** 从用户信息里提取出的本地字段 */
    private record Extracted(String sub, String username, String name, String departmentName) {
    }

}
