package com.wenqu.ai.config;

import com.wenqu.ai.openapi.OpenApiRegistry;
import com.wenqu.ai.openapi.RequestIdFilter;
import com.wenqu.ai.service.ConfigService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放 API 的跨域 Filter：只作用于 {@code /api/ai/v1/**}，
 * 白名单每请求现读 {@code apiOpen.allowedOrigins}（逗号分隔；<b>空 = 不回 CORS 头 = 现状</b>）。
 *
 * <p>为什么是 Filter 而不是 MVC 的 {@code addCorsMappings}：后者在启动期把映射注册死，
 * 运行时改白名单不生效——开放面的收口恰恰要「改完即生效」。
 *
 * <p><b>允许方法由开放契约注册表派生</b>：此前写死 {@code POST, OPTIONS}，
 * 而 v1 里登记着 GET 端点（库列表/详情/文档/模型清单/契约文档），它们的跨域预检会被浏览器直接拒掉。
 * 现在按请求路径去注册表取该方法集合，新增 GET/PUT 端点不需要再改这里。
 *
 * <p>只开 v1 命名空间：CORS 是「允许别人的浏览器 JS 直调」，开放面必须能收口在一个可枚举前缀里。
 * 问渠自己的页面与后端同源，不走这里；nginx 层刻意不加 ACAO 头，避免双重回写。</p>
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiCorsConfig {

    private final ConfigService configService;
    private final OpenApiRegistry openApiRegistry;

    @Bean
    public FilterRegistrationBean<Filter> openApiCorsFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((req, res, chain) -> {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (path.startsWith(OpenApiRegistry.PREFIX)) {
                Set<String> origins = allowedOrigins();
                String origin = request.getHeader("Origin");
                if (origin != null && !origin.isBlank() && origins.contains(origin)) {
                    response.setHeader("Access-Control-Allow-Origin", origin);
                    response.setHeader("Vary", "Origin");
                    response.setHeader("Access-Control-Expose-Headers", RequestIdFilter.HEADER);
                    if ("OPTIONS".equals(request.getMethod())) {
                        response.setHeader("Access-Control-Allow-Methods", allowedMethodsFor(path));
                        response.setHeader("Access-Control-Allow-Headers",
                                "Authorization, X-Api-Key, Content-Type, " + RequestIdFilter.HEADER);
                        response.setHeader("Access-Control-Max-Age", "3600");
                        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        return;   // 预检到此为止，不进业务链
                    }
                }
            }
            chain.doFilter(req, res);
        });
        reg.addUrlPatterns("/api/ai/v1/*");
        reg.setOrder(10);
        reg.setName("openApiCorsFilter");
        return reg;
    }

    /** 预检回的允许方法取自契约注册表，未登记的路径只留 OPTIONS（默认拒绝，不猜） */
    private String allowedMethodsFor(String path) {
        Set<String> methods = new LinkedHashSet<>(openApiRegistry.methodsFor(path));
        methods.add("OPTIONS");
        return String.join(", ", methods);
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
