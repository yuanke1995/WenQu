package com.wenqu.ai.openapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路请求号：入口生成（或采信调用方传入的合法值），写回响应头，挂在请求属性上。
 *
 * <p>错误体、日志、出站回调都能引用同一个号；外部系统报「这笔请求失败了」时不必再靠时间戳猜。
 * 调用方传入值只接受安全字符集，脏值一律换成本服务生成的号，不给日志注入面。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Wenqu-Request-Id";
    public static final String ATTR = "wenqu.requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String rid = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTR, rid);
        response.setHeader(HEADER, rid);
        chain.doFilter(request, response);
    }

    /** 当前请求的请求号；不在 Web 请求上下文里（如定时任务）返回 null */
    public static String current() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return null;
        Object v = sra.getRequest().getAttribute(ATTR);
        return v == null ? null : String.valueOf(v);
    }
}
