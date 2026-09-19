package com.wisesoft.wenqu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录限流过滤器：逐行对齐参考实现 {@code server/main.py} 的 {@code LoginRateLimitMiddleware}。
 *
 * <p>语义（常量、文案、响应头全部照搬）：
 * <ul>
 *   <li>窗口：{@value #RATE_LIMIT_WINDOW_SECONDS} 秒内同一 IP {@value #RATE_LIMIT_MAX_ATTEMPTS} 次即触发；</li>
 *   <li>触发时返回 {@code 429} + {@code Retry-After}，响应体 {@code {"detail": "登录尝试过于频繁，请稍后再试"}}；</li>
 *   <li>窗口内提前成功（状态码 &lt; 400）即清空该 IP 记录；</li>
 *   <li>仅在 {@code (归一化路径, 方法)} 命中限流端点集合时生效，其余请求直接放行。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：
 * <ol>
 *   <li>{@code BaseHTTPMiddleware} → Spring {@link OncePerRequestFilter}；{@code asyncio.Lock} →
 *       同 JVM 内的 {@link ConcurrentHashMap#compute} 原子段（参考实现亦为单进程内存计数）；</li>
 *   <li>参考实现 {@code request.url.path} 无上下文路径 → Java 侧先剥掉 {@code context-path}；</li>
 *   <li>限流端点集合：保留参考实现常量 {@code /api/auth/token}（新契约认证路由搬入后即生效），
 *       同时登记本系统当前生效的登录端点 {@code /api/ai/auth/login}——两个入口都在请求路径上，
 *       否则既有限流保护在迁移期形同失效。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LoginRateLimitFilter extends OncePerRequestFilter {

    /** 窗口内允许的最大登录尝试次数（参考实现 RATE_LIMIT_MAX_ATTEMPTS）。 */
    public static final int RATE_LIMIT_MAX_ATTEMPTS = 10;

    /** 限流窗口秒数（参考实现 RATE_LIMIT_WINDOW_SECONDS）。 */
    public static final int RATE_LIMIT_WINDOW_SECONDS = 60;

    /** 限流端点集合（参考实现 RATE_LIMIT_ENDPOINTS + 本系统既有登录端点）。 */
    private static final Set<String> RATE_LIMIT_ENDPOINTS = Set.of(
            "POST /api/auth/token",
            "POST /api/ai/auth/login");

    private final Map<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public LoginRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = normalizedPath(request);
        String signature = request.getMethod().toUpperCase() + " " + path;
        if (!RATE_LIMIT_ENDPOINTS.contains(signature)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);
        long now = System.nanoTime();
        long windowNanos = RATE_LIMIT_WINDOW_SECONDS * 1_000_000_000L;

        final boolean[] limited = {false};
        final int[] retryAfter = {1};
        loginAttempts.compute(clientIp, (key, history) -> {
            Deque<Long> attempts = history == null ? new ArrayDeque<>() : history;
            while (!attempts.isEmpty() && now - attempts.peekFirst() > windowNanos) {
                attempts.pollFirst();
            }
            if (attempts.size() >= RATE_LIMIT_MAX_ATTEMPTS) {
                long remainingNanos = windowNanos - (now - attempts.peekFirst());
                retryAfter[0] = (int) Math.max(1, remainingNanos / 1_000_000_000L);
                limited[0] = true;
                return attempts;
            }
            attempts.addLast(now);
            return attempts;
        });

        if (limited[0]) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", String.valueOf(retryAfter[0]));
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("detail", "登录尝试过于频繁，请稍后再试")));
            return;
        }

        chain.doFilter(request, response);

        if (response.getStatus() < 400) {
            loginAttempts.remove(clientIp);
        }
    }

    /** 归一化请求路径：去掉 context-path 与尾部斜杠（对应参考实现 url.path.rstrip("/") or "/"）。 */
    private static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = (context != null && !context.isEmpty() && uri.startsWith(context))
                ? uri.substring(context.length())
                : uri;
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /** 提取客户端 IP（对应参考实现 _extract_client_ip）。 */
    private static String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("x-forwarded-for");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isEmpty()) ? "unknown" : remote;
    }
}
