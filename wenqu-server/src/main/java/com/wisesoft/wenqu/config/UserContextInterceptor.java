package com.wisesoft.wenqu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.ResultJson;
import com.wisesoft.wenqu.repository.UserMapper;
import com.wisesoft.wenqu.model.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AuthService;
import com.wisesoft.wenqu.common.RequestUser;
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
 * <h3>为什么同时识别两套令牌（过渡态，不是语义改动）</h3>
 * 本工程存在两套并存的登录契约，签发方与用户表都不同：
 * <ol>
 *   <li><b>参考实现契约</b>（{@code POST /api/auth/token} → {@code AuthRouterController}）：
 *       JWT 由 {@link AuthUtils} 自持密钥（{@code JWT_SECRET_KEY}）签发，{@code sub} 是 {@code users.id}，
 *       issuer 为 {@code wenqu-know:<实例ID>}；账户落在 {@code users} 表。</li>
 *   <li><b>本产品既有契约</b>（{@code POST /api/ai/auth/login} → {@code AuthController}）：
 *       JWT 由 {@code AuthCrypto} 用 {@code WENQU_JWT_SECRET} 签发，{@code sub} 是 {@code c_ai_user.uid}；
 *       账户落在 {@code c_ai_user} 表。</li>
 * </ol>
 * 两套令牌的签名密钥与 issuer 互不相同，因此可以安全地「先试参考实现契约、再试既有契约」而不会互相误判。
 * 若只认既有契约，参考实现契约的所有路由（{@code /api/skills}、{@code /api/knowledge/**}、
 * {@code /api/system/**} 等——它们的 {@code AuthGuards} 都读本拦截器装载的 {@link RequestUser}）
 * 会在登录成功后依然全部 401。
 * <p>
 * 注意这属于<b>双栈并存</b>的临时承载，不是最终形态：待既有契约路由全部退役后，第二段分支可整体删除。
 */
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    /** 请求属性名：本次请求已通过登录令牌认证 */
    public static final String ATTR_AUTHENTICATED = "ai.authenticated";

    private final UserMapper userMapper;
    private final AuthService authService;
    private final UserRepository accountRepository;
    private final ObjectMapper objectMapper;

    public UserContextInterceptor(
            UserMapper userMapper,
            AuthService authService,
            UserRepository accountRepository,
            ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = bearerToken(request);
        if (token != null) {
            // ① 参考实现契约令牌（sub = users.id）
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
            // ② 本产品既有契约令牌（sub = c_ai_user.uid）
            String uid = authService.uidFromToken(token);
            if (uid == null) return reject(response, "登录状态已失效，请重新登录");
            User u = safeLoad(uid);
            if (u == null || (u.getStatus() != null && u.getStatus() == 0)) {
                return reject(response, "账号不存在或已被禁用");
            }
            request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
            RequestUser.set(u.getUid(), u.getDepartmentId(), u.getRole());
            return true;
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

    private User safeLoad(String uid) {
        if (uid == null || uid.isBlank()) return null;
        try {
            return userMapper.selectById(uid);
        } catch (Exception ignored) {
            // 用户表未就绪/查询异常都不应阻断请求：降级为匿名可见范围
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
