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
 * 内部鉴权拦截器：按路由契约分两道前置
 * <ol>
 *   <li><b>身份解析</b>（{@code /api/**}，全局）：由 {@link UserContextInterceptor} 解析
 *       Authorization: Bearer JWT 写入 RequestUser（ThreadLocal）；身份只认登录令牌，
 *       不接受任何客户端自报的用户标识请求头。</li>
 *   <li><b>登录门禁</b>（{@code /api/**}，全局唯一）：require-login 开启时，除登录引导端点
 *       （login/first-run/initialize/logout）外均需有效登录令牌 —— 对应参考实现各路由
 *       {@code Depends(get_required_user)} 的「必须登录」前置。</li>
 * </ol>
 * <p>细粒度权限（普通用户 / 管理员 / 超级管理员）不由这里统一拦截，而由路由方法首行的
 * {@link AuthGuards#requireUser()} / {@link AuthGuards#requireAdmin()} /
 * {@link AuthGuards#requireSuperadmin()} 显式声明（与参考实现的 Depends 调用点一一对应）。</p>
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final UserContextInterceptor userContextInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 身份先行：解析登录令牌 → RequestUser（ThreadLocal）；登录门禁据「是否已登录」判定
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(new LoginGateInterceptor())
                .addPathPatterns("/api/**");
    }

    /**
     * 登录引导端点：未登录也必须可访问，否则无法登录（否则死锁）。
     * 其余端点在校验完平台信任 token 后，若开启 require-login 则要求携带有效登录令牌。
     *
     * <p>另含参考实现中本就无 Depends 的公开端点（{@code /api/system/health}、{@code /ready}、
     * {@code /discovery}、{@code /info}）——它们在参考实现里同样不要求登录。
     *
     * <p>{@code /api/auth/**}（参考实现 {@code auth_router}）中同样未声明任何用户依赖的端点也必须放行，
     * 否则登录、首次初始化、CLI 设备码换密钥与 OIDC 回调链路全部死锁：
     * <ul>
     *   <li>POST {@code /token}、{@code /initialize}；</li>
     *   <li>POST {@code /cli/sessions}、{@code /cli/sessions/token}（CLI 未登录侧）；</li>
     *   <li>POST {@code /oidc/exchange-code}；</li>
     *   <li>GET {@code /check-first-run}、{@code /oidc/config}、{@code /oidc/login-url}、{@code /oidc/callback}。</li>
     * </ul>
     */
    private static boolean isAuthBootstrapEndpoint(String method, String path) {
        if (path == null) return false;
        if ("GET".equals(method) && path.startsWith("/api/system/")) {
            return path.equals("/api/system/health")
                    || path.equals("/api/system/ready")
                    || path.equals("/api/system/discovery")
                    || path.equals("/api/system/info");
        }
        if (path.startsWith("/api/auth/")) {
            if ("POST".equals(method)) {
                return path.equals("/api/auth/token")
                        || path.equals("/api/auth/initialize")
                        || path.equals("/api/auth/cli/sessions")
                        || path.equals("/api/auth/cli/sessions/token")
                        || path.equals("/api/auth/oidc/exchange-code");
            }
            if ("GET".equals(method)) {
                return path.equals("/api/auth/check-first-run")
                        || path.equals("/api/auth/oidc/config")
                        || path.equals("/api/auth/oidc/login-url")
                        || path.equals("/api/auth/oidc/callback");
            }
            return false;
        }
        return false;
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

}
