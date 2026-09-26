package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.DepartmentMapper;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.model.Department;
import com.wisesoft.ai.model.User;
import com.wisesoft.ai.util.AuthCrypto;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 单点登录（OIDC 授权码流程）。
 * <p>
 * 对应平台版 {@code backend/package/yuxi/services/oidc_service.py}。流程与语义照搬，差异集中在两点：
 * <ul>
 *   <li><b>配置来源</b>：蓝本用环境变量（{@code OIDCConfig.from_env()}，进程启动读一次）；
 *       本工程配置的唯一来源是 {@code config-schema.json} + {@code ConfigService}，
 *       故取值走 {@code configService.get("oidc.*")}，改配置即时生效、设置页可编辑，
 *       {@code clientSecret} 沿用敏感项机制（RSA 密文入库 + 快照只回显后 4 位）。</li>
 *   <li><b>身份绑定</b>：蓝本用「占位用户 {@code oidc:{sub}:{userId}}（标记已删除）」记录 sub 与账号的绑定，
 *       其注释自述原因是"在不修改表结构的前提下"保存绑定关系；本工程有 {@code SchemaMigrator} 可自动加列，
 *       故直接用 {@code c_ai_user.oidc_sub}（唯一索引）承载，不制造假用户行。</li>
 * </ul>
 * <p>
 * 未搬的部分（显式标注，不是遗漏）：蓝本的「已注销账号恢复」依赖其 {@code User.is_deleted} + {@code phone_number}/
 * {@code avatar} 列，本工程 {@code c_ai_user} 无软删与这两列 ⇒ 该路径不适用（按未注册处理）；{@code end_session_endpoint}
 * 与登出联动未接，故不落配置项（避免死配置）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OidcService {

    private final ConfigService configService;
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

    /** state → 授权上下文（一次性，消费即删） */
    private final Map<String, StateEntry> states = new ConcurrentHashMap<>();
    /** 一次性登录 code → 登录响应（一次性，消费即删） */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    private volatile Metadata metadata;
    private volatile String metadataKey;
    private volatile long metadataAt;
    private volatile String lastMetadataError;

    // ==================== 对外 ====================

    /**
     * 前端登录页用的公开配置：**只回「是否可用 + 认证源名称」**。
     * <p>与蓝本一致：未启用或配置不完整时返回 {@code enabled=false}，前端据此隐藏入口——
     * 只配了一半就显示按钮，用户点下去必然撞错误页。</p>
     */
    public Map<String, Object> publicConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", configured());
        m.put("providerName", providerName());
        return m;
    }

    /**
     * 生成授权地址（含一次性 state）。
     *
     * @param redirectPath 登录成功后前端要去的路径（随 state 暂存，回调时带回）
     */
    public String loginUrl(String redirectPath, HttpServletRequest request) {
        if (!configured()) {
            throw new BizException("OIDC 登录暂不可用：请先在系统设置里启用并填写 Issuer / Client ID");
        }
        Metadata m = metadata();
        if (m == null || blank(m.authorizationEndpoint())) {
            throw new BizException("生成登录链接失败：" + (lastMetadataError == null ? "授权端点不可用" : lastMetadataError));
        }
        String state = newToken();
        states.put(state, new StateEntry(redirectPath == null || redirectPath.isBlank() ? "/" : redirectPath,
                System.currentTimeMillis() + STATE_TTL_MS));
        sweep();

        StringBuilder q = new StringBuilder();
        appendQuery(q, "response_type", "code");
        appendQuery(q, "client_id", cfg("oidc.clientId"));
        appendQuery(q, "scope", blank(cfg("oidc.scopes")) ? "openid profile email" : cfg("oidc.scopes"));
        appendQuery(q, "redirect_uri", redirectUri(request));
        appendQuery(q, "state", state);
        if (configService.getBoolean("oidc.forcePromptLogin")) appendQuery(q, "prompt", "login");
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
        if (!tokenExchangeConfigured()) {
            return loginWithError("OIDC 配置不完整（缺少 Client Secret 或令牌端点），请联系管理员");
        }
        StateEntry se = state == null ? null : states.remove(state);
        if (se == null || se.expiresAt() < System.currentTimeMillis()) {
            return loginWithError("登录会话已过期，请返回登录页重试");
        }
        if (blank(code)) return loginWithError("IdP 未返回授权码（可能被拒绝授权），请返回登录页重试");

        Metadata m = metadata();
        if (m == null || blank(m.tokenEndpoint())) {
            return loginWithError("令牌端点不可用：" + (lastMetadataError == null ? "请检查 Issuer 配置" : lastMetadataError));
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri(request));
        form.put("client_id", cfg("oidc.clientId"));
        form.put("client_secret", cfg("oidc.clientSecret"));
        JsonNode token = postForm(m.tokenEndpoint(), form);
        String accessToken = token == null ? null : text(token, "access_token");
        if (blank(accessToken)) return loginWithError("无法获取访问令牌，请返回登录页重试");

        if (blank(m.userinfoEndpoint())) return loginWithError("未配置用户信息端点（Issuer 的 discovery 里也没有），请联系管理员");
        JsonNode userinfo = getJson(m.userinfoEndpoint(), Map.of("Authorization", "Bearer " + accessToken));
        if (userinfo == null) return loginWithError("无法获取用户信息，请返回登录页重试");

        Extracted ex = extract(userinfo);
        if (blank(ex.sub())) return loginWithError("无法获取用户标识（sub），请返回登录页重试");

        User user;
        try {
            user = resolveUser(ex);
        } catch (BizException e) {
            return loginWithError(e.getMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", issue(user));
        payload.put("user", authService.userInfo(user));
        payload.put("redirectPath", se.redirectPath());

        String oneTime = newToken();
        codes.put(oneTime, new CodeEntry(payload, System.currentTimeMillis() + LOGIN_CODE_TTL_MS));
        sweep();
        log.info("[AUDIT] OIDC 登录成功 uid={} sub={} role={}", user.getUid(), ex.sub(), user.getRole());
        return frontBase() + FRONTEND_CALLBACK_PATH + "?code=" + enc(oneTime);
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
     *   <li>已绑定（{@code oidc_sub} 命中）→ 直接登录；</li>
     *   <li>{@code useRawUsername} 且 IdP 用户名对应的账号存在且**未被其他 sub 绑定** → 绑定后登录
     *       （绑定冲突一律拒绝，不覆盖，防冒用）；</li>
     *   <li>否则按 {@code autoCreateUser} 自动建档，或拒绝并提示联系管理员。</li>
     * </ol>
     */
    private User resolveUser(Extracted ex) {
        User bound = findByOidcSub(ex.sub());
        if (bound != null) return assertUsable(bound);

        if (configService.getBoolean("oidc.useRawUsername") && !blank(ex.username())) {
            String uid = rawUid(ex.username());
            if (uid != null) {
                User exist = userMapper.selectById(uid);
                if (exist != null) {
                    if (blank(exist.getOidcSub())) {
                        exist.setOidcSub(ex.sub());
                        userMapper.updateById(exist);
                        log.info("[AUDIT] OIDC 身份绑定到既有账号 uid={} sub={}", uid, ex.sub());
                        return assertUsable(exist);
                    }
                    throw new BizException("账号 " + uid + " 已绑定其他 OIDC 身份，请联系管理员处理绑定冲突");
                }
            }
        }

        if (!configService.getBoolean("oidc.autoCreateUser")) {
            throw new BizException("用户未注册，请联系管理员开通账号");
        }
        return createUser(ex);
    }

    private User createUser(Extracted ex) {
        String base = configService.getBoolean("oidc.useRawUsername") ? rawUid(ex.username()) : null;
        String uid = ensureUniqueUid(base == null ? sanitizeUid(ex.sub()) : base, ex.sub());
        String username = ensureUniqueUsername(blank(ex.name()) ? ex.username() : ex.name(), ex.sub());
        String role = normalizeRole(cfg("oidc.defaultRole"));
        String deptId = resolveDepartmentId(ex);

        User u = new User();
        u.setUid(uid);
        u.setUsername(username);
        u.setRole(role);
        u.setDepartmentId(deptId);
        u.setStatus(1);
        u.setLoginFailCount(0);
        u.setOidcSub(ex.sub());
        // 刻意不设密码：OIDC 账号不能走本地密码登录；同时不干扰「首次运行初始化管理员」的判定
        // （needsInitialize() 数的是"已设置密码的用户"，给随机密码会让初始化流程消失）。
        try {
            userMapper.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            User raced = findByOidcSub(ex.sub());
            if (raced != null) return assertUsable(raced);
            throw new BizException("创建 OIDC 账号失败（账号标识或用户名冲突），请重试或联系管理员");
        }
        log.info("[AUDIT] OIDC 自动建档 uid={} username={} role={} dept={}", uid, username, role, deptId);
        return u;
    }

    private User findByOidcSub(String sub) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getOidcSub, sub).last("LIMIT 1"));
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
        throw new BizException("无法生成可用用户名，请联系管理员");
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
    private String resolveDepartmentId(Extracted ex) {
        String name = configService.getBoolean("oidc.fetchDepartmentInfo") ? trimToNull(ex.departmentName()) : null;
        if (name == null) name = trimToNull(cfg("oidc.defaultDepartment"));
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
    private Extracted extract(JsonNode ui) {
        String sub = text(ui, "sub");
        String username = firstNonBlank(text(ui, cfg("oidc.usernameClaim")), text(ui, "preferred_username"));
        if (username == null) {
            String email = text(ui, "email");
            username = email == null ? null : email.split("@")[0];
        }
        if (username == null && sub != null) username = sub.substring(0, Math.min(20, sub.length()));
        String name = firstNonBlank(text(ui, cfg("oidc.nameClaim")), text(ui, "name"), username);
        String deptName = null;
        if (configService.getBoolean("oidc.fetchDepartmentInfo")) {
            deptName = firstNonBlank(text(ui, cfg("oidc.departmentClaim")), text(ui, "department"));
        }
        return new Extracted(sub, username, name, deptName);
    }

    // ==================== 元数据（discovery） ====================

    /**
     * 端点来源：手填端点优先（IdP 不支持 discovery 时用），否则读 Issuer 的
     * {@code /.well-known/openid-configuration}。缓存键含全部配置值 ⇒ 改配置立即重新加载。
     */
    private Metadata metadata() {
        String auth = trimToNull(cfg("oidc.authorizationEndpoint"));
        String token = trimToNull(cfg("oidc.tokenEndpoint"));
        String ui = trimToNull(cfg("oidc.userinfoEndpoint"));
        String key = cfg("oidc.issuerUrl") + "|" + auth + "|" + token + "|" + ui;
        Metadata cached = metadata;
        if (cached != null && key.equals(metadataKey) && System.currentTimeMillis() - metadataAt < METADATA_TTL_MS) {
            return cached;
        }
        Metadata loaded;
        if (auth != null) {
            loaded = new Metadata(auth, token, ui);
            lastMetadataError = null;
        } else {
            String issuer = trimToNull(cfg("oidc.issuerUrl"));
            if (issuer == null) {
                lastMetadataError = "未配置 Issuer URL 且未手填授权端点";
                return null;
            }
            String url = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
            JsonNode doc = getJson(url, Map.of());
            if (doc == null) {
                lastMetadataError = "OIDC discovery 加载失败（" + url + "）";
                return null;
            }
            auth = text(doc, "authorization_endpoint");
            token = text(doc, "token_endpoint");
            ui = text(doc, "userinfo_endpoint");
            if (blank(auth)) {
                lastMetadataError = "discovery 响应缺少 authorization_endpoint";
                return null;
            }
            loaded = new Metadata(auth, token, ui);
            lastMetadataError = null;
        }
        metadata = loaded;
        metadataKey = key;
        metadataAt = System.currentTimeMillis();
        log.info("[OIDC] 元数据已加载 auth={} token={} userinfo={}", loaded.authorizationEndpoint(),
                loaded.tokenEndpoint(), loaded.userinfoEndpoint());
        return loaded;
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

    private boolean enabled() {
        return configService.getBoolean("oidc.enabled");
    }

    /** 生成登录链接的必要条件（与蓝本 is_configured 同口径） */
    private boolean configured() {
        return enabled() && !blank(cfg("oidc.clientId"))
                && (!blank(cfg("oidc.issuerUrl")) || !blank(cfg("oidc.authorizationEndpoint")));
    }

    /** 换 token 的必要条件（与蓝本 is_token_exchange_configured 同口径） */
    private boolean tokenExchangeConfigured() {
        return configured() && !blank(cfg("oidc.clientSecret"))
                && (!blank(cfg("oidc.issuerUrl")) || !blank(cfg("oidc.tokenEndpoint")));
    }

    /**
     * 回调地址：配置优先；留空则按本次请求的来源推导（Host 头 + 上下文路径）。
     * 反向代理改写了 Host 或一个 IdP 接多个域名时必须显式配置，否则把推导值登记到 IdP 侧即可。
     */
    private String redirectUri(HttpServletRequest request) {
        String configured = trimToNull(cfg("oidc.redirectUri"));
        if (configured != null) return configured;
        if (request == null) return CALLBACK_PATH;
        String proto = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null) return request.getContextPath() + CALLBACK_PATH;
        return proto + "://" + host + request.getContextPath() + CALLBACK_PATH;
    }

    private String frontBase() {
        String base = trimToNull(cfg("oidc.frontendBaseUrl"));
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    private String loginWithError(String message) {
        return loginErrorRedirect(message);
    }

    /** 失败时跳回登录页并带 oidc_error（供 Controller 在 IdP 直接回绝时复用） */
    public String loginErrorRedirect(String message) {
        return frontBase() + FRONTEND_LOGIN_PATH + "?oidc_error=" + enc(message);
    }

    private String issue(User u) {
        return AuthCrypto.issueToken(authService.secret(), authService.issuer(), authService.audience(),
                u.getUid(), u.getRole(), authService.ttlSeconds());
    }

    private String providerName() {
        String n = trimToNull(cfg("oidc.providerName"));
        return n == null ? "OIDC登录" : n;
    }

    private String cfg(String key) {
        String v = configService.get(key);
        return v == null ? "" : v;
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        codes.entrySet().removeIf(e -> e.getValue().expiresAt < now);
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

    /** 授权上下文（state → 前端目标路径） */
    private record StateEntry(String redirectPath, long expiresAt) {
    }

    /** 一次性登录结果 */
    private record CodeEntry(Map<String, Object> payload, long expiresAt) {
    }

    /** 从用户信息里提取出的本地字段 */
    private record Extracted(String sub, String username, String name, String departmentName) {
    }

}
