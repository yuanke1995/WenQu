package com.wisesoft.ai.config;

import com.wisesoft.ai.service.AuthService;
import com.wisesoft.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 管理员判定（管理端点访问控制）。
 * <p>
 * 权限模型：普通用户仅开放问答链路（chat/会话/反馈/引用溯源），
 * 文档管理、系统配置、评估、看板、索引运维等管理端点仅管理员可访问。
 * <p>
 * 管理员判定：登录用户角色为 admin / superadmin（由 UserContextInterceptor
 * 从 JWT 令牌 + 用户表装载）。未登录或普通用户一律拒绝（fail-closed）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class AdminGuard {

    /** 是否为管理员请求（判定不抛异常、不落日志噪音，纯布尔） */
    public boolean isAdmin(HttpServletRequest request) {
        if (request == null) return false;
        return AuthService.isAdminRole(RequestUser.role());
    }
}
