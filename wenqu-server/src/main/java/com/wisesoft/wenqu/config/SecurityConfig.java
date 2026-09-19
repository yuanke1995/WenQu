package com.wisesoft.wenqu.config;

import com.wisesoft.wenqu.common.ResultJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 内部鉴权拦截器：按路由契约分两道闸
 * <ol>
 *   <li><b>登录门禁</b>（{@code /api/**}，全局唯一）：require-login 开启时，除登录引导端点
 *       （login/first-run/initialize/logout）外均需有效登录令牌 —— 对应参考实现各路由
 *       {@code Depends(get_required_user)} 的「必须登录」前置。</li>
 *   <li><b>管理员闸</b>（仅 {@code /api/ai/**}，即本产品既有契约路由）：普通用户仅开放问答链路，
 *       其余端点需管理员（判定见 {@link AdminGuard}；未命中返回 403，fail-closed）。</li>
 * </ol>
 * <p>参考实现契约路由（{@code /api/knowledge/**}、{@code /api/dashboard/**}、{@code /api/tasks} 等）
 * **不套**这道粗粒度管理员闸：它们的权限粒度由路由方法首行的
 * {@link AuthGuards#requireUser()} / {@link AuthGuards#requireAdmin()} /
 * {@link AuthGuards#requireSuperadmin()} 显式声明（与参考实现的 Depends 调用点一一对应），
 * 管理员闸若一并套上会把参考实现中的普通用户端点（如 {@code /api/projects}、{@code /api/mention/*}）
 * 误判为 403。</p>
 * <p>登录鉴权由 {@link UserContextInterceptor} 解析 Authorization: Bearer JWT 完成。
 * 身份只认登录令牌，不接受任何客户端自报的用户标识请求头。</p>
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final AdminGuard adminGuard;
    private final UserContextInterceptor userContextInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 身份先行：解析登录令牌 → RequestUser（ThreadLocal）；后续两道闸据「是否已登录」与角色判定
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/api/**");
        // 登录门禁：全部 /api/**（两套路由契约共用）
        registry.addInterceptor(new LoginGateInterceptor())
                .addPathPatterns("/api/**");
        // 管理员闸：仅本产品既有契约路由（/api/ai/**）；参考实现契约路由在方法上自声明权限
        registry.addInterceptor(new AdminGateInterceptor())
                .addPathPatterns("/api/ai/**");
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
     *
     * <p>另含参考实现中本就无 Depends 的公开端点（{@code /api/system/health}、{@code /ready}、
     * {@code /discovery}、{@code /info}）——它们在参考实现里同样不要求登录。
     */
    private static boolean isAuthBootstrapEndpoint(String method, String path) {
        if (path == null) return false;
        if ("GET".equals(method) && path.equals("/api/ai/auth/first-run")) return true;
        if ("GET".equals(method) && path.startsWith("/api/system/")) {
            return path.equals("/api/system/health")
                    || path.equals("/api/system/ready")
                    || path.equals("/api/system/discovery")
                    || path.equals("/api/system/info");
        }
        return "POST".equals(method) && (path.equals("/api/ai/auth/login")
                || path.equals("/api/ai/auth/initialize")
                || path.equals("/api/ai/auth/logout"));
    }

    /**
     * 登录门禁：require-login 开启时，除登录引导端点（login/first-run/initialize/logout）外均需有效登录令牌。
     * <p>身份一律取自 {@link UserContextInterceptor} 装载的登录态，不读取用户自报请求头。</p>
     */
    class LoginGateInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String method = request.getMethod();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean authenticated = Boolean.TRUE.equals(request.getAttribute(UserContextInterceptor.ATTR_AUTHENTICATED));
            if (properties.getAuth().isRequireLogin() && !authenticated && !isAuthBootstrapEndpoint(method, path)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ResultJson.error(401, "请先登录")));
                return false;
            }
            return true;
        }
    }

    /**
     * 管理员闸（仅 {@code /api/ai/**}）：问答用户白名单之外一律管理员判定，fail-closed
     * ——普通用户不隐式获得管理权。
     */
    class AdminGateInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
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
