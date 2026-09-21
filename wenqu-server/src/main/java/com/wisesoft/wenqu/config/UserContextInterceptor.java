package com.wisesoft.wenqu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.ResultJson;
import com.wisesoft.wenqu.common.RequestUser;
import com.wisesoft.wenqu.repositories.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求入口解析当前用户身份并装载到 {@link RequestUser}（ThreadLocal）。
 * <p>
 * 身份来源唯一：{@code Authorization: Bearer <JWT>}（本地登录）。令牌失效、或用户不存在/被禁用 → 直接 401。
 * 未携带令牌 → {@link RequestUser#ANONYMOUS}（不读任何客户端自报请求头，避免伪造身份/越权提权）。
 * <p>
 * 经令牌认证成功时在请求上打 {@link #ATTR_AUTHENTICATED} 标记，供 SecurityConfig 判断「是否已登录」。
 *
 * <h3>2026-09-22：既有契约令牌分支已退役</h3>
 * 此前这里同时识别两套令牌：参考实现契约（{@code users} 表，{@link AuthUtils} 签发）与本产品既有契约
 * （{@code c_ai_user} 表，{@code AuthCrypto} 签发）。随着旧契约层（含 {@code c_ai_user} 实体、
 * {@code UserMapper}、{@code AuthController}）整体下线，第二段分支连同 {@code AuthService.uidFromToken}
 * 一并移除，身份来源回归唯一：只认参考实现契约令牌。
 */
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    /** 请求属性名：本次请求已通过登录令牌认证 */
    public static final String ATTR_AUTHENTICATED = "ai.authenticated";

    private final UserRepository accountRepository;
    private final ObjectMapper objectMapper;

    public UserContextInterceptor(
            UserRepository accountRepository,
            ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = bearerToken(request);
        if (token != null) {
            // 参考实现契约令牌（sub = users.id）
            Map<String, Object> claims = AuthUtils.decodeToken(token);
            if (claims != null) {
                com.wisesoft.wenqu.models.User account = loadAccount(claims.get("sub"));
                if (account == null) return reject(response, "账号不存在或已被禁用");
                request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
                RequestUser.set(
                        account.getUid(),
                        account.getDepartmentId() == null ? null : String.valueOf(account.getDepartmentId()),
                        account.getRole());
                return true;
            }
            // 令牌既不是参考实现契约签发 → 视为失效（旧契约令牌已随 c_ai_user 下线）
            return reject(response, "登录状态已失效，请重新登录");
        }
        // 无令牌：匿名。不再回落到客户端自报的 X-User-Id——那等价于把身份（含管理员角色）
        // 交给请求方自行声明，require-login=false 时可伪造管理员。管理员只能靠登录令牌获得。
        RequestUser.set(RequestUser.ANONYMOUS, null, "user");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestUser.clear();
    }

    /** 按参考实现契约的 {@code sub}（{@code users.id}）装载账户；软删账户视为不存在。 */
    private com.wisesoft.wenqu.models.User loadAccount(Object subject) {
        if (subject == null) return null;
        String raw = String.valueOf(subject).strip();
        if (raw.isEmpty()) return null;
        try {
            com.wisesoft.wenqu.models.User account = accountRepository.getById(Integer.valueOf(raw));
            if (account == null) return null;
            if (account.getIsDeleted() != null && account.getIsDeleted() == 1) return null;
            return account;
        } catch (RuntimeException ignored) {
            // sub 非数字 / 账户表未就绪等一律按「令牌无效」处理，由调用方回 401
            return null;
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private boolean reject(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ResultJson.error(401, msg)));
        return false;
    }
}
