package com.wenqu.ai.config;

import com.wenqu.ai.service.ConfigService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放 API 的跨域 Filter（对外能力第二批·管道层）：只作用于 {@code /api/ai/v1/**}，
 * 白名单每请求现读 {@code apiOpen.allowedOrigins}（逗号分隔；<b>空 = 不回 CORS 头 = 现状</b>）。
 * <p>
 * 为什么是 Filter 而不是 MVC 的 {@code addCorsMappings}：后者在启动期把映射注册死，
 * 运行时改白名单不生效——而开放面的收口恰恰要「改完即生效」（管理员收紧来源不该等重启）。
 * Filter 每次请求现读配置，热更即刻可见；Origin 白名单判定的家族先例是
 * {@code McpServerService.checkOrigin}（那边只需拒绝，这里要把 ACAO 头真回给浏览器）。
 * <p>
 * 只开 v1 命名空间：CORS 是「允许别人的浏览器 JS 直调」，开放面必须能收口在一个可枚举前缀里。
 * 问渠自己的页面与后端同源（代理/同源转发），不走这里。nginx 层刻意不加 ACAO 头，
 * 避免与这里双重回写（同一响应两个 Access-Control-Allow-Origin 会被浏览器拒）。
 * 预检（OPTIONS）在 {@link SecurityConfig} 已放行（安全链对 OPTIONS 不设门）。
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiCorsConfig {

    private static final String PATH_PREFIX = "/api/ai/v1/";

    private final ConfigService configService;

    @Bean
    public FilterRegistrationBean<Filter> openApiCorsFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((req, res, chain) -> {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (path.startsWith(PATH_PREFIX)) {
                Set<String> origins = allowedOrigins();
                String origin = request.getHeader("Origin");
                if (origin != null && !origin.isBlank() && origins.contains(origin)) {
                    response.setHeader("Access-Control-Allow-Origin", origin);
                    response.setHeader("Vary", "Origin");
                    if ("OPTIONS".equals(request.getMethod())) {
                        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
                        response.setHeader("Access-Control-Allow-Headers",
                                "Authorization, X-Api-Key, Content-Type");
                        response.setHeader("Access-Control-Max-Age", "3600");
                        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        return;   // 预检到此为止，不进业务链
                    }
                }
            }
            chain.doFilter(req, res);
        });
        reg.addUrlPatterns("/api/ai/v1/*");
        reg.setOrder(10);   // 尽早收口（安全链对 OPTIONS 本就放行，先后无碍）
        reg.setName("openApiCorsFilter");
        return reg;
    }

    /** 白名单每请求现读（热更即时生效）；空/未配置 = 空集 = 不回 CORS 头 */
    private Set<String> allowedOrigins() {
        String raw = configService.get("apiOpen.allowedOrigins");
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
