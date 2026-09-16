package com.wisesoft.ai.config;

import com.wisesoft.ai.service.AuthService;
import com.wisesoft.ai.util.RequestUser;
import com.wisesoft.ai.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理员判定（问答用户白名单之外的管理端点访问控制）。
 * <p>
 * 权限模型：普通用户仅开放问答链路（chat/会话/反馈/引用溯源），
 * 文档管理、系统配置、评估、看板、索引运维等管理端点仅管理员可访问。
 * <p>
 * 管理员判定（任一命中）：
 * 1) 请求头 {@code X-Admin-Token} 与 ai-app.admin-token（AI_ADMIN_TOKEN）恒定时间相等（适合无网关/本地/管理专用凭据）；
 * 2) 请求身份 X-User-Id ∈ ai-app.admin-users（AI_ADMIN_USERS，逗号分隔白名单）；
 * 3) AI_ADMIN_USERS 配置为 "*" = 全员管理员（单机自用模式，行为等同关闭管理鉴权）。
 * <p>
 * 两者均未配置时管理端点默认拒绝（fail-closed）：只读问答的用户不会被隐式赋予管理权。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminGuard {

    /** 管理员访问口令请求头名 */
    public static final String HEADER = "X-Admin-Token";

    private final AppProperties properties;

    /** 是否为管理员请求（判定不抛异常、不落日志噪音，纯布尔） */
    public boolean isAdmin(HttpServletRequest request) {
        if (request == null) return false;
        String users = properties.getAdminUsers();
        // 1) 通配模式：全员管理员（单机自用）
        if (users != null) {
            for (String u : users.split(",")) {
                if ("*".equals(u.trim())) return true;
            }
        }
        // 2) 管理口令：恒定时间比较，防时序攻击
        String adminToken = properties.getAdminToken();
        String provided = request.getHeader(HEADER);
        if (adminToken != null && !adminToken.isBlank()
                && provided != null && !provided.isBlank()
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                adminToken.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        // 3) 用户白名单：网关按人透传 X-User-Id 后精确匹配
        if (users != null && !users.isBlank()) {
            String uid = UserContext.resolve(request);
            if (UserContext.ANONYMOUS.equals(uid)) return false; // 无网关/无身份的请求不套白名单
            for (String u : users.split(",")) {
                if (!u.isBlank() && u.trim().equals(uid)) return true;
            }
        }
        // 4) 本地登录用户：角色为 admin / superadmin（由 UserContextInterceptor 从令牌+用户表装载）
        if (AuthService.isAdminRole(RequestUser.role())) return true;
        return false;
    }
}
