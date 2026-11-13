package com.wenqu.ai.openapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>顺带采集 SDK 版本头：JS / Python SDK 每次请求带 {@code X-Wenqu-Sdk-Version}，
 * 版本与 request_id 一起落到 debug 日志，线上排查「契约改了但调用方还是旧 SDK」时不用再猜。
 * 只记不用——版本不参与路由、鉴权或任何能力判断。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Wenqu-Request-Id";
    public static final String ATTR = "wenqu.requestId";
    /** SDK 自报版本（B1：后端 debug 日志比对，提前发现版本错配） */
    public static final String SDK_VERSION_HEADER = "X-Wenqu-Sdk-Version";
    public static final String ATTR_SDK_VERSION = "wenqu.sdkVersion";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String rid = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTR, rid);
        response.setHeader(HEADER, rid);
        String sdk = request.getHeader(SDK_VERSION_HEADER);
        if (sdk != null && SAFE_VERSION.matcher(sdk).matches()) {
            request.setAttribute(ATTR_SDK_VERSION, sdk);
            log.debug("[SDK] version={} rid={} {} {}", sdk, rid, request.getMethod(), request.getRequestURI());
        }
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
