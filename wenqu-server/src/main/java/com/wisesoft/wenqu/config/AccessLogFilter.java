package com.wisesoft.wenqu.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志过滤器：逐行对齐参考实现 {@code server/utils/access_log_middleware.py}。
 *
 * <p>日志行格式（与参考实现一致）：
 * {@code {client_ip}:{port} - "{METHOD} {path}[?{query}] HTTP/{version}" {status} - {ms}ms}
 * <p>日志使用独立 logger {@code access_logger}（对应参考实现中不向根 logger 传播的专用 logger，
 * 其 handler 格式 {@code %m-%d %H:%M:%S} 见 {@code logback-spring.xml}）。
 *
 * <p><b>在过滤器链中的位置</b>：参考实现的中间件装配序是（先加的在内层）
 * CORS → 本类 → {@code LoginRateLimitMiddleware}，故请求执行序为
 * {@link LoginRateLimitFilter} → 本类 → CORS → 路由。本工程以 {@code @Order} 升序表达同一顺序：
 * {@link LoginRateLimitFilter} 取 {@code +10}、本类取 {@code +20}。因此本类只记录<b>通过限流</b>的请求
 * ——被限流短路的 429 在更外层即返回，与参考实现一致。
 *
 * <p>平台差异（必要替换）：FastAPI/Starlette 的 {@code BaseHTTPMiddleware} → Spring 的
 * {@link OncePerRequestFilter}；{@code request.scope["http_version"]} → {@code request.getProtocol()}
 * 去掉 {@code HTTP/} 前缀（拼接后形态一致）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AccessLogFilter extends OncePerRequestFilter {

    /** 专用访问日志记录器（对应参考实现的 access_logger）。 */
    public static final Logger ACCESS_LOGGER = LoggerFactory.getLogger("access_logger");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String clientIp = extractClientIp(request);
        int port = request.getRemotePort();

        try {
            chain.doFilter(request, response);
        } finally {
            long processTimeMs = (System.nanoTime() - start) / 1_000_000L;
            String query = request.getQueryString();
            String path = request.getRequestURI();
            String full = (query == null || query.isEmpty()) ? path : path + "?" + query;
//            ACCESS_LOGGER.info("{}:{} - \"{} {} HTTP/{}\" {} - {}ms",
//                    clientIp,
//                    port > 0 ? String.valueOf(port) : "unknown",
//                    request.getMethod(),
//                    full,
//                    httpVersion(request),
//                    response.getStatus(),
//                    processTimeMs);
        }
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

    /** HTTP 版本号（如 1.1 / 2），对应参考实现 scope["http_version"]。 */
    private static String httpVersion(HttpServletRequest request) {
        String protocol = request.getProtocol();
        if (protocol == null) return "1.1";
        return protocol.startsWith("HTTP/") ? protocol.substring("HTTP/".length()) : protocol;
    }
}
