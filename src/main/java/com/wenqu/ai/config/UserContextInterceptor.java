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

/**
 * 请求入口解析当前用户身份并装载到 {@link RequestUser}（ThreadLocal）。
 * <p>
 * 身份来源唯一：{@code Authorization: Bearer <JWT>}（本地登录）。令牌失效、或用户不存在/被禁用 → 直接 401。
 * 未携带令牌 → {@link RequestUser#ANONYMOUS}（不读任何客户端自报请求头，避免伪造身份/越权提权）。
 * <p>
 * 经令牌认证成功时在请求上打 {@link #ATTR_AUTHENTICATED} 标记，供 SecurityConfig 判断「是否已登录」。
 *
 * @author yuanke
 */
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    /** 请求属性名：本次请求已通过登录令牌认证 */
    public static final String ATTR_AUTHENTICATED = "ai.authenticated";

    /**
     * 用户档案 60s 进程内缓存：每个 /api/** 请求都会走 {@code selectById}（JWT 是本地校验无 IO，
     * 用户行是唯一每请求必查的数据库热点）。TTL 内的禁用/角色变更最迟 60s 生效——管理动作的
     * 可接受延迟；负结果（不存在/查询异常）不缓存，瞬态故障不放大成 401。
     * 容量上界 = 登录过的不同 uid 数（过期条目惰性失效），企业规模下可忽略。
     */
    private record CachedUser(User user, long expireAt) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedUser> USER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long USER_CACHE_TTL_MS = 60_000;

    private final UserMapper userMapper;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public UserContextInterceptor(UserMapper userMapper, AuthService authService, ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = bearerToken(request);
        if (token != null) {
            String uid = authService.uidFromToken(token);
            if (uid == null) return reject(response, "登录状态已失效，请重新登录");
            User u = safeLoad(uid);
            if (u == null || (u.getStatus() != null && u.getStatus() == 0)) {
                return reject(response, "账号不存在或已被禁用");
            }
            request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
            RequestUser.set(u.getUid(), u.getDepartmentId(), u.getRole());
            return true;
        }
        // 无令牌：匿名。不再回落到客户端自报的 X-User-Id——那等价于把身份（含管理员角色）
        // 交给请求方自行声明，require-login=false 时可伪造管理员。管理员只能靠登录令牌获得。
        RequestUser.set(RequestUser.ANONYMOUS, null, "user");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestUser.clear();
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
