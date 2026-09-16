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
    private final com.wisesoft.ai.service.ApiKeyService apiKeyService;
    private final UserContextInterceptor userContextInterceptor;

    /** 请求属性名：本次请求通过 API Key 认证（权限固定为问答链路，不参与管理员判定） */
    public static final String ATTR_API_KEY_ID = "ai.apiKeyId";

    @PostConstruct
    public void validate() {
        if (properties.getTrustedToken() == null || properties.getTrustedToken().isBlank()) {
            throw new IllegalStateException(
                    "缺少必要配置：AI_TRUSTED_TOKEN 环境变量未设置，服务拒绝启动");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 身份先行：解析登录令牌 → RequestUser（ThreadLocal）；TrustedTokenInterceptor 后续据「是否已登录」与角色判定
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(new TrustedTokenInterceptor())
                .addPathPatterns("/api/**");
    }

    /**
     * 普通用户问答端点白名单判断：白名单内无需管理员；其余（文档/配置/评估/看板/索引/知识管理/推荐写入等）默认需管理员。
     * <p>
     * 白名单 = 问答链路的最小闭环：
     * chat、会话（列表/新建/历史/删除/置顶收藏/重命名/批量/组删除撤销）、反馈提交、
     * 引用溯源（GET 单个知识块详情）、公开运行时配置（GET /config/public）、推荐问题读取（GET /suggested）、身份查询（/auth/me）、
     * 对话页智能体下拉（GET /agent/available，只读精简字段）。
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
        // 登录相关端点不要求管理员（登录门禁另判：见 isAuthBootstrapEndpoint）
        if (isAuthEndpoint(path)) return true;
        if ("GET".equals(method) && path.equals("/api/ai/suggested")) return true;
        if ("GET".equals(method) && path.equals("/api/ai/config/public")) return true;
        // 对话页智能体下拉：只读精简列表（不含提示词/知识库范围等管理配置），问答用户可用；
        // 管理端 /api/ai/agent/list 等仍走管理员判定。
        if ("GET".equals(method) && path.equals("/api/ai/agent/available")) return true;
        // 引用溯源：GET /knowledge/{单个id}（list 是管理端点：按文档列块，排除）
        if ("GET".equals(method)) {
            var m = KNOWLEDGE_SINGLE_GET.matcher(path);
            if (m.matches() && !"list".equals(m.group(1))) return true;
        }
        return false;
    }

    /** 是否为登录鉴权相关端点（不允许 API Key 访问：凭据语义不同） */
    private static boolean isAuthEndpoint(String path) {
        return path != null && path.startsWith("/api/ai/auth/");
    }

    /**
     * 登录引导端点：未登录也必须可访问，否则无法登录（否则死锁）。
     * 其余端点在校验完平台信任 token 后，若开启 require-login 则要求携带有效登录令牌。
     */
    private static boolean isAuthBootstrapEndpoint(String method, String path) {
        if (path == null) return false;
        if ("GET".equals(method) && path.equals("/api/ai/auth/first-run")) return true;
        return "POST".equals(method) && (path.equals("/api/ai/auth/login")
                || path.equals("/api/ai/auth/initialize")
                || path.equals("/api/ai/auth/logout"));
    }

    class TrustedTokenInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String method = request.getMethod();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            // 1. 平台信任 token（恒定时间比较）
            String token = request.getHeader("X-Trusted-Token");
            String expected = properties.getTrustedToken();
            boolean ok = token != null
                    && MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            expected.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                // 1b. API Key（对外开放问答能力的入口）：仅放行「问答链路」白名单端点。
                //     管理端点即便持有效 Key 也不放行——Key 泄露时危害被限制在问答能力内；
                //     校验失败与无凭据返回同一 401 文案，不暴露服务支持哪些认证方式。
                String plainKey = request.getHeader("X-Api-Key");
                var rec = apiKeyService.verify(plainKey);
                if (rec != null && !isAuthEndpoint(path) && isPublicUserEndpoint(method, path)) {
                    request.setAttribute(ATTR_API_KEY_ID, rec.getId());
                    apiKeyService.touchLastUsed(rec.getId());
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ResultJson.error("无权访问 AI 服务")));
                return false;
            }
            // 2. 登录门禁：require-login=true 时，除登录引导端点外必须持有效登录令牌
            boolean authenticated = Boolean.TRUE.equals(request.getAttribute(UserContextInterceptor.ATTR_AUTHENTICATED));
            if (properties.getAuth().isRequireLogin() && !authenticated && !isAuthBootstrapEndpoint(method, path)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ResultJson.error(401, "请先登录")));
                return false;
            }
            // 3. 问答用户白名单之外 → 管理员判定（fail-closed：普通用户不隐式获得管理权）
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
