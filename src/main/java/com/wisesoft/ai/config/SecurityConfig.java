package com.wisesoft.ai.config;

import com.wisesoft.ai.dto.ResultJson;
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
 * 内部鉴权拦截器：三层控制
 * <ol>
 *   <li>API Key 认证：带对 X-Api-Key 且命中问答白名单 → 放行（管理端点不放行）</li>
 *   <li>登录门禁：require-login=true 时除登录引导端点外须持有效登录令牌</li>
 *   <li>权限模型（2026-09-26 RBAC 化）：白名单端点全员可用；其余端点
 *       管理员级角色直通，非管理员角色按角色绑定的接口（c_ai_role_api）匹配，
 *       未授权 403 fail-closed（判定见 {@link com.wisesoft.ai.service.RoleService}）</li>
 * </ol>
 * <p>登录鉴权由 {@link UserContextInterceptor} 解析 Authorization: Bearer JWT 完成；
 * require-login 开启时，除登录引导端点（login/first-run/initialize/logout）外均需有效登录令牌。
 * 身份只认登录令牌，不接受任何客户端自报的用户标识请求头。</p>
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final com.wisesoft.ai.service.ApiKeyService apiKeyService;
    private final UserContextInterceptor userContextInterceptor;
    private final com.wisesoft.ai.service.RoleService roleService;

    /** 请求属性名：本次请求通过 API Key 认证（权限固定为问答链路，不参与管理员判定） */
    public static final String ATTR_API_KEY_ID = "ai.apiKeyId";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 身份先行：解析登录令牌 → RequestUser（ThreadLocal）；AccessControlInterceptor 后续据「是否已登录」与角色判定
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(new AccessControlInterceptor())
                .addPathPatterns("/api/**");
    }

    /**
     * 普通用户问答端点白名单判断：白名单内无需管理员；其余（文档/配置/评估/看板/索引/知识管理等）默认需管理员。
     * <p>
     * 白名单 = 问答链路的最小闭环：
     * chat、会话（列表/新建/历史/删除/置顶收藏/重命名/批量/组删除撤销）、反馈提交、
     * 引用溯源（GET 单个知识块详情）、公开运行时配置（GET /config/public）、身份查询（/auth/me）、
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
        if ("GET".equals(method) && path.equals("/api/ai/config/public")) return true;
        // 对话页智能体下拉：只读精简列表（不含提示词/知识库范围等管理配置），问答用户可用；
        // 管理端 /api/ai/agent/list 等仍走管理员判定。
        if ("GET".equals(method) && path.equals("/api/ai/agent/available")) return true;
        // 技能与 MCP 已于 2026-09-26 从「管理员全局设置」下沉为「个人资产」：
        // 每个登录用户增删改查自己的，控制器统一按 RequestUser.uid() 过滤（看不到也改不了别人的），
        // 因此不再要求管理员；未登录请求受 require-login 门禁保护（放行时归属 anonymous）。
        if (path.equals("/api/ai/skill") || path.startsWith("/api/ai/skill/")) return true;
        if (path.equals("/api/ai/mcp") || path.startsWith("/api/ai/mcp/")) return true;
        // 可用模型清单（聊天页模型选择器/个人设置数据源）：只读、不含 baseUrl/apiKey
        if ("GET".equals(method) && path.equals("/api/ai/provider/available")) return true;
        // 模型供应商（2026-09-26）：普通用户可登记**自己的**网关与 Key（个人级，仅本人可见可用），
        // 管理员登记的为平台级（所有人可用）。资源级判定在 ProviderController.denyUnlessManageable
        // 内做（改/删/启停/登记模型必须先通过归属校验），这里只按端点放行。
        if (isProviderSelfEndpoint(method, path)) return true;
        // 个人偏好（本人默认模型）：读改自己的设置；自助改密（非管理员只能改自己，Controller 内校验）
        if (path.equals("/api/ai/user/preference")) return true;
        if ("PUT".equals(method) && path.matches("/api/ai/user/[^/]+/password")) return true;
        // 知识库 / 文档 / 智能体对普通用户开放**自建自管**（2026-09-26，用户数据隔离由
        // ResourceVisibilityService 在 Controller 内做资源级判定：创建者=可管理、他人按共享范围、
        // 知识库对他人封顶只读）。这里按端点精确放行；不匹配的（如 /agent/{id}/default 设默认、
        // /document/stats 命中统计、/knowledge/list 知识块批量）仍走管理员判定，fail-closed。
        if (isKbOrDocEndpoint(method, path) || isAgentSelfEndpoint(method, path)) return true;
        // 产物交付（我的产物）：普通用户可看/删**自己的**产物（ArtifactController 内按 uid 归属校验，
        // 管理员可越权）；文件下载走 /artifacts/** 签名地址，不经过本拦截器。
        if (path.equals("/api/ai/artifact") || path.startsWith("/api/ai/artifact/")) return true;
        // 定时执行智能体（个人资产）：每人管自己的任务（ScheduledJobController 内按 uid 归属校验）
        if (path.equals("/api/ai/scheduled") || path.startsWith("/api/ai/scheduled/")) return true;
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
     * 知识库 / 文档的自建自管端点（普通用户可访问；资源级权限由 Controller 内判定）。
     * <p>刻意排除：{@code /document/stats}（全库命中统计）、{@code /knowledge/list}（知识块批量列）等
     * 跨资源的管理视图，仍仅管理员。</p>
     */
    private static boolean isKbOrDocEndpoint(String method, String path) {
        if (path == null) return false;
        if (path.equals("/api/ai/document/stats")) return false;
        // 知识库：列表 / 详情 / 新建 / 编辑 / 删除 / 移动文档
        if (path.equals("/api/ai/kb") || path.equals("/api/ai/kb/list")
                || path.matches("/api/ai/kb/[^/]+") || path.matches("/api/ai/kb/doc/[^/]+")) return true;
        // 文档：列表 / 上传 / 批量 / 单个的启停用·共享·重解析·补图述·版本·回滚·源文件·删除
        if (path.equals("/api/ai/document") || path.equals("/api/ai/document/list")
                || path.equals("/api/ai/document/upload") || path.equals("/api/ai/document/upload/batch")
                || path.equals("/api/ai/document/batch/delete") || path.equals("/api/ai/document/batch/reparse")
                || path.matches("/api/ai/document/[^/]+")
                || path.matches("/api/ai/document/[^/]+/(source|versions|status|share|reparse|backfill-descriptions|rollback)")) {
            return true;
        }
        return false;
    }

    /**
     * 智能体的自建自管端点。刻意排除：{@code /{id}/default}（设默认是全局动作，影响所有人的下拉预选，
     * 仅管理员）；{@code /available}（本就公开，且在更早的白名单分支已放行）。
     */
    private static boolean isAgentSelfEndpoint(String method, String path) {
        if (path == null) return false;
        return path.equals("/api/ai/agent") || path.equals("/api/ai/agent/list") || path.equals("/api/ai/agent/sub")
                || path.matches("/api/ai/agent/[^/]+") || path.matches("/api/ai/agent/[^/]+/share");
    }

    /**
     * 模型供应商的自助端点（普通用户可访问）。
     * <p>
     * 覆盖：列表 / 新建（{@code /provider}）、单个的改删与启停、单个的模型登记、
     * 先测后存用的 {@code /models/fetch} 与 {@code /test}。
     * 写操作的归属判定不在拦截器做——由 {@code ProviderController.denyUnlessManageable}
     * 按「管理员级全部 / 普通用户仅自己登记的个人级」逐资源裁决。
     * {@code /provider/available} 在更早的白名单分支已放行。
     */
    private static boolean isProviderSelfEndpoint(String method, String path) {
        if (path == null) return false;
        return path.equals("/api/ai/provider")
                || path.matches("/api/ai/provider/[^/]+")
                || path.matches("/api/ai/provider/[^/]+/(models|enabled)")
                || path.equals("/api/ai/provider/models/fetch")
                || path.equals("/api/ai/provider/test");
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

    /**
     * 访问控制：API Key 放行 → 登录门禁 → 管理员判定（三层，fail-closed）。
     * <p>身份一律取自 {@link UserContextInterceptor} 装载的登录态，不读取用户自报请求头。</p>
     */
    class AccessControlInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String method = request.getMethod();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            // 1. API Key 认证（对外开放问答能力的入口）：带对 X-Api-Key 且命中问答白名单 → 放行。
            //    未带 / 校验失败的 Key 不在此处拦截，继续走登录门禁与管理员判定。
            String plainKey = request.getHeader("X-Api-Key");
            var rec = apiKeyService.verify(plainKey);
            if (rec != null && !isAuthEndpoint(path) && isPublicUserEndpoint(method, path)) {
                request.setAttribute(ATTR_API_KEY_ID, rec.getId());
                apiKeyService.touchLastUsed(rec.getId());
                return true;
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
            // 3. 问答用户白名单之外 → RBAC 判定（fail-closed）：
            //    ① 管理员级角色（内置 superadmin/admin 或自定义 admin_flag=1）直通；
            //    ② 其余角色按 c_ai_role_api 绑定匹配（method + AntPathMatcher，/{id} 占位可匹配），
            //       未绑定一律 403——权限在权限管理页「角色 → 接口权限」里显式授予。
            //    OPTIONS 预检放行（浏览器 CORS 预检不携带语义，交给 CORS 配置处理）。
            if (!isPublicUserEndpoint(method, path)) {
                String role = com.wisesoft.ai.util.RequestUser.role();
                boolean pass = "OPTIONS".equals(method)
                        || roleService.isAdminCode(role)
                        || roleService.canAccess(role, method, path);
                if (!pass) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(
                            ResultJson.error(403, "无权访问该功能（角色未授权，可在权限管理中为角色绑定该接口）")));
                    return false;
                }
            }
            return true;
        }
    }
}
