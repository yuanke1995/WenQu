package com.wenqu.ai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 标准档企业端点闸：{@link EnterpriseOnly} 标到方法或类上，profile=standard 时返回 404。
 *
 * <p>404 而不是 403：标准档里这些能力<b>不存在</b>，不该让用户从错误码推断平台还藏着什么。
 */
public class EnterpriseOnlyInterceptor implements HandlerInterceptor {

    private static final String BODY =
            "{\"success\":false,\"code\":404,\"msg\":\"当前部署为标准档，企业级功能未启用\"}";

    private final DeploymentGuard deploymentGuard;

    public EnterpriseOnlyInterceptor(DeploymentGuard deploymentGuard) {
        this.deploymentGuard = deploymentGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!"standard".equals(deploymentGuard.profile())) return true;
        if (!(handler instanceof HandlerMethod method)) return true;
        boolean enterprise = method.getMethodAnnotation(EnterpriseOnly.class) != null
                || method.getBeanType().getAnnotation(EnterpriseOnly.class) != null;
        if (!enterprise) return true;
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(BODY);
        return false;
    }
}
