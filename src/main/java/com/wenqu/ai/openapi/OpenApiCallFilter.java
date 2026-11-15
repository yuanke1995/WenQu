package com.wenqu.ai.openapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 开放 API 调用台账的采集点（B5 接入漏斗）。
 *
 * <p><b>为什么用过滤器而不是拦截器</b>：拦截器只能看见「进了 DispatcherServlet 的请求」，
 * 而集成方最常踩的两个坑恰恰发生在它之前——没带 Key、Key 无效（身份层就 401 了）。
 * 过滤器在整条链的最外层，<b>无论成功还是被拒都能记上一笔</b>，这正是漏斗要的
 * 「签发 → 首调 → 持续调用」每一段的真实分母。</p>
 *
 * <p><b>采集范围</b>：{@code /api/ai/v1/**} 全量采集；其余 {@code /api/ai/**} 只在请求
 * 带了 Key 凭据时采集。后者不是顺手练手——OpenAI 兼容端点（{@code /v1/chat/completions} 之外的
 * 历史路径）与知识库/工作流端点都是 Key 的既有用法，只统计 v1 会把「签发后其实一直在用」
 * 误判成「签发后没人用」，漏斗口径就假了。</p>
 *
 * <p>OPTIONS 预检不记：它不是调用，浏览器发它不代表集成方写了代码。</p>
 *
 * @author yuanke
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class OpenApiCallFilter extends OncePerRequestFilter {

    private final OpenApiCallRecorder recorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = pathOf(request);
        if (path.startsWith(OpenApiRegistry.PREFIX)) return false;
        return !(path.startsWith("/api/ai/") && presentsKey(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            // finally：被拒绝的请求也要落账（401/403 是漏斗里最有信息量的两段）
            recorder.record(request, response.getStatus());
        }
    }

    /** 是否带了 Key 形态的凭据（只看形状，有效性由 ApiKeyService 判；这里不参与鉴权） */
    private static boolean presentsKey(HttpServletRequest request) {
        String key = request.getHeader("X-Api-Key");
        if (key != null && !key.isBlank()) return true;
        String auth = request.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer sk-", 0, 10);
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
