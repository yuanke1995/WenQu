package com.wisesoft.ai.config;

import com.wisesoft.ai.dto.ResultJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 内部鉴权拦截器：两层控制
 * <ol>
 *   <li>平台信任 token：所有 /api/** 必须携带正确 X-Trusted-Token（来自平台网关/管理端，恒定时间比较防时序攻击）</li>
 *   <li>权限模型：普通用户仅开放问答链路，其余端点需管理员
 *       （判定见 {@link AdminGuard}；未命中返回 403，fail-closed）</li>
 * </ol>
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final AiAppProperties properties;
    private final ObjectMapper objectMapper;
    private final AdminGuard adminGuard;

    @PostConstruct
    public void validate() {
        if (properties.getTrustedToken() == null || properties.getTrustedToken().isBlank()) {
            throw new IllegalStateException(
                    "缺少必要配置：AI_TRUSTED_TOKEN 环境变量未设置，服务拒绝启动");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TrustedTokenInterceptor())
                .addPathPatterns("/api/**");
    }

    /**
     * 普通用户问答端点白名单判断：白名单内无需管理员；其余（文档/配置/评估/看板/索引/知识管理/推荐写入等）默认需管理员。
     * <p>
     * 白名单 = 问答链路的最小闭环：
     * chat、会话（列表/新建/历史/删除/置顶收藏/重命名/批量/组删除撤销）、反馈提交、
     * 引用溯源（GET 单个知识块详情）、公开运行时配置（GET /config/public）、推荐问题读取（GET /suggested）、身份查询（/auth/me）。
     */
    private static final Pattern KNOWLEDGE_SINGLE_GET = Pattern.compile("/api/ai/knowledge/([^/]+)");

    private boolean isPublicUserEndpoint(String method, String path) {
        if (method == null || path == null) return false;
        // 会话管理（含全部方法：GET/DELETE/PUT/POST）
        if (path.equals("/api/ai/session") || path.startsWith("/api/ai/session/")
                || path.equals("/api/ai/sessions") || path.startsWith("/api/ai/sessions/")) {
            return true;
        }
        if (path.equals("/api/ai/message-group") || path.startsWith("/api/ai/message-group/")) {
            return true;
        }
        if ("POST".equals(method) && path.equals("/api/ai/chat")) return true;
        if ("POST".equals(method) && path.equals("/api/ai/feedback")) return true;
        if (path.equals("/api/ai/auth/me")) return true;
        if ("GET".equals(method) && path.equals("/api/ai/suggested")) return true;
        if ("GET".equals(method) && path.equals("/api/ai/config/public")) return true;
        // 引用溯源：GET /knowledge/{单个id}（list 是管理端点：按文档列块，排除）
        if ("GET".equals(method)) {
            var m = KNOWLEDGE_SINGLE_GET.matcher(path);
            if (m.matches() && !"list".equals(m.group(1))) return true;
        }
        return false;
    }

    class TrustedTokenInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            // 1. 平台信任 token（恒定时间比较）
            String token = request.getHeader("X-Trusted-Token");
            String expected = properties.getTrustedToken();
            boolean ok = token != null
                    && MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            expected.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ResultJson.error("无权访问 AI 服务")));
                return false;
            }
            // 2. 问答用户白名单之外 → 管理员判定（fail-closed：普通用户不隐式获得管理权）
            String method = request.getMethod();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!isPublicUserEndpoint(method, path) && !adminGuard.isAdmin(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ResultJson.error(403, "仅管理员可访问该功能")));
                return false;
            }
            return true;
        }
    }
}
