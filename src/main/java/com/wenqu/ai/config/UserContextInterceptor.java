package com.wenqu.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.User;
import com.wenqu.ai.service.AuthService;
import com.wenqu.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 请求入口解析当前用户身份并装载到 {@link RequestUser}（ThreadLocal）。
 * <p>
 * 身份来源两处：① {@code Authorization: Bearer <JWT>}（本地登录，含 OIDC/SAML 换来的令牌）；
 * ② {@code X-Api-Key} + 该 Key 的「代表账号」（集成方专用，见下方通道注释）。
 * 令牌失效、或用户不存在/被禁用 → 直接 401。
 * 两者都没带 → {@link RequestUser#ANONYMOUS}（不读任何客户端自报请求头，避免伪造身份/越权提权）。
 * <p>
 * 经令牌认证成功时在请求上打 {@link #ATTR_AUTHENTICATED} 标记，供 SecurityConfig 判断「是否已登录」。
 *
 * @author yuanke
 */
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    /** 请求属性名：本次请求已通过登录令牌认证 */
    public static final String ATTR_AUTHENTICATED = "ai.authenticated";

    /** 请求属性名：本次请求通过 API Key 建立身份，且该身份是哪个账号 */
    public static final String ATTR_API_KEY_UID = "ai.apiKeyUid";

    /**
     * 用户档案 60s 进程内缓存：每个 /api/** 请求都会走 {@code selectById}（JWT 是本地校验无 IO，
     * 用户行是唯一每请求必查的数据库热点）。管理端改完档案会调 {@link #evict} 当场逐出；
     * 没有走管理端点的改动（直接改库等）最迟 60s 生效。
     * 负结果（不存在/查询异常）不缓存，瞬态故障不放大成 401。
     * 容量上界 = 登录过的不同 uid 数（过期条目惰性失效），企业规模下可忽略。
     */
    private record CachedUser(User user, long expireAt) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedUser> USER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long USER_CACHE_TTL_MS = 60_000;

    private final UserMapper userMapper;
    private final AuthService authService;
    private final com.wenqu.ai.service.UserRoleService userRoleService;
    private final com.wenqu.ai.service.ApiKeyService apiKeyService;
    private final com.wenqu.ai.service.ServiceAccountService serviceAccountService;
    private final ObjectMapper objectMapper;

    public UserContextInterceptor(UserMapper userMapper, AuthService authService,
                                  com.wenqu.ai.service.UserRoleService userRoleService,
                                  com.wenqu.ai.service.ApiKeyService apiKeyService,
                                  com.wenqu.ai.service.ServiceAccountService serviceAccountService,
                                  ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.userRoleService = userRoleService;
        this.apiKeyService = apiKeyService;
        this.serviceAccountService = serviceAccountService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // SCIM 2.0 入站（机器凭据）：Authorization 是认证源专属 Bearer 而非用户 JWT，
        // 这里不做解析/不设登录态，整条链对它豁免——鉴权由 ScimService.authenticate 强校验
        String scimPath = request.getRequestURI().substring(request.getContextPath().length());
        if (scimPath.startsWith("/api/ai/scim/")) {
            RequestUser.set(RequestUser.ANONYMOUS, null, "user");
            return true;
        }
        String token = bearerToken(request);
        if (token != null) {
            Map<String, Object> claims = authService.claimsFromToken(token);
            if (claims == null) return reject(response, "登录状态已失效，请重新登录");
            String uid = String.valueOf(claims.get("sub"));
            if (uid == null || uid.isBlank() || "null".equals(uid)) return reject(response, "登录状态已失效，请重新登录");
            User u = safeLoad(uid);
            if (u == null || (u.getStatus() != null && u.getStatus() == 0)) {
                return reject(response, "账号不存在或已被禁用");
            }
            // 会话治理：令牌 ep 与用户当前会话代数不符即失效——改密/管理员强制下线后，旧令牌（含其他
            // 设备与被盗会话）当场作废。存量令牌没有 ep 声明按代数 0 兼容：升级不踢人，安全动作才踢。
            long want = u.getTokenEpoch() == null ? 0L : u.getTokenEpoch();
            Object ep = claims.get("ep");
            long got = ep instanceof Number n ? n.longValue() : 0L;
            if (got != want) {
                return reject(response, "登录状态已失效（账号安全状态已变更或被强制下线），请重新登录");
            }
            request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
            // 多角色装载（并集判定口径）：鉴权一律用 roles()，role() 仅展示
            RequestUser.set(u.getUid(), u.getDepartmentId(), userRoleService.rolesOf(u.getUid()));
            // 租户绑定（Phase 4）：与身份同处装载，行级过滤由 WenquTenantLineHandler 拼在 SQL 上。
            // 空归属按 default 处理（存量行加列时由数据库默认值回填，不该出现空，出现也不能变成「看全部」）
            com.wenqu.ai.util.TenantContext.bind(
                    u.getTenantId() == null || u.getTenantId().isBlank()
                            ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : u.getTenantId());
            return true;
        }
        // 无令牌：先看是不是服务账号通道（批次 C3，非人类身份），再看 API Key，都不是才落匿名。
        // 服务账号的判定口径与人类用户完全一致（角色绑在 c_ai_user_role，uid=svc:{id}）——
        // 差异只在「凭据是长期机器令牌、不在 c_ai_user 表、不能登录网页」。
        String svcToken = request.getHeader("X-Service-Token");
        if (svcToken != null && !svcToken.isBlank() && com.wenqu.ai.service.ServiceAccountService.isServiceToken(svcToken)) {
            com.wenqu.ai.model.ServiceAccount acc = serviceAccountService.verify(svcToken);
            if (acc == null) return reject(response, "服务账号令牌无效、已停用或已过期");
            String svcUid = com.wenqu.ai.service.ServiceAccountService.uidOf(acc);
            request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
            RequestUser.set(svcUid, null, userRoleService.rolesOf(svcUid));
            com.wenqu.ai.util.TenantContext.bind(com.wenqu.ai.util.TenantContext.DEFAULT_TENANT);
            return true;
        }
        // 无令牌：先看是不是 API Key 通道（集成方专用），也不是才落匿名。
        //
        // 这条通道等价于「长期免密的某个账号」，所以边界写在两处：① 必须有显式代表账号
        // （不靠 created_by 推断——签发人往往是管理员，让第三方拿着 Key 变成管理员是反的）；
        // ② 能碰哪些端点由 SecurityConfig 那一侧收窄（账号自服务与能力配置面不从 Key 走）。
        // 身份装在这里、端点判定装在那里，是因为下游一切按 RequestUser 走：会话归属、沙盒工作区、
        // 个人 MCP 与技能、长期记忆、产物归属、模型默认值、租户行级过滤、限流分池全都要一个真 uid，
        // 少装这一步这些能力不是报错，是静默落到 anonymous 上（与那几条「弹性线程丢身份」同一个形状）。
        String plainKey = request.getHeader("X-Api-Key");
        if (plainKey != null && !plainKey.isBlank()) {
            com.wenqu.ai.model.ApiKey rec = apiKeyService.verify(plainKey);
            if (rec != null) {
                String actAs = rec.getActAsUid();
                if (actAs == null || actAs.isBlank()) {
                    return reject(response, "该 API Key 未设「代表账号」，不能用于问答链路：在 API Key 管理里指定一个专用账号");
                }
                User u = safeLoad(actAs);
                if (u == null || (u.getStatus() != null && u.getStatus() == 0)) {
                    return reject(response, "该 API Key 的代表账号不存在或已被禁用：" + actAs);
                }
                request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
                request.setAttribute(ATTR_API_KEY_UID, u.getUid());
                RequestUser.set(u.getUid(), u.getDepartmentId(), userRoleService.rolesOf(u.getUid()));
                com.wenqu.ai.util.TenantContext.bind(
                        u.getTenantId() == null || u.getTenantId().isBlank()
                                ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : u.getTenantId());
                return true;
            }
        }
        // 匿名。不再回落到客户端自报的 X-User-Id——那等价于把身份（含管理员角色）
        // 交给请求方自行声明，require-login=false 时可伪造管理员。管理员只能靠登录令牌获得。
        RequestUser.set(RequestUser.ANONYMOUS, null, "user");
        // 匿名不绑租户：分享/开放端点在各自的入口处按归属人绑定（访客看到的是发布者租户里的资源，
        // 不是「无租户」）；这里保持未绑定，让系统线程与匿名兜底走同一套不过滤语义
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestUser.clear();
        com.wenqu.ai.util.TenantContext.clear();
    }

    /**
     * 成员档案变更后逐出这条缓存（改归属/禁用/改角色）。
     * <p>TTL 内的延迟对多数管理动作只是「慢一分钟」，对归属不是：旧租户的口径多留 60 秒，
     * 就是 60 秒的跨租户读写——被挪走的人还能看见原租户的成员名单。所以改完当场逐出。
     */
    public static void evict(String uid) {
        if (uid != null && !uid.isBlank()) USER_CACHE.remove(uid);
    }

    private User safeLoad(String uid) {
        if (uid == null || uid.isBlank()) return null;
        CachedUser cached = USER_CACHE.get(uid);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.user();
        }
        try {
            User u = userMapper.selectById(uid);
            if (u != null) {
                USER_CACHE.put(uid, new CachedUser(u, System.currentTimeMillis() + USER_CACHE_TTL_MS));
            }
            return u;
        } catch (Exception ignored) {
            // 用户表未就绪/查询异常都不应阻断请求：降级为匿名可见范围
            return null;
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private boolean reject(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ResultJson.error(401, msg)));
        return false;
    }
}
