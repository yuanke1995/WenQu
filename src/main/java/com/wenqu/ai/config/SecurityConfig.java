package com.wenqu.ai.config;

import com.wenqu.ai.dto.ResultJson;
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
 *       未授权 403 fail-closed（判定见 {@link com.wenqu.ai.service.RoleService}）</li>
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
    private final com.wenqu.ai.service.ApiKeyService apiKeyService;
    private final UserContextInterceptor userContextInterceptor;
    private final com.wenqu.ai.service.RoleService roleService;

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
     * chat、会话（列表/新建/历史/删除/置顶收藏/重命名/批量/组删除撤销）、聊天附件上传（按 uid 隔离落盘）、
     * 反馈提交、站内通知（铃铛：列表/未读数/已读，按 uid 过滤）、
     * 引用溯源（GET 单个知识块详情）、公开运行时配置（GET /config/public）、身份查询（/auth/me）、
     * 对话页智能体下拉（GET /agent/available，只读精简字段）。
     */

    private boolean isPublicUserEndpoint(String method, String path) {
        if (method == null || path == null) return false;
        // 会话管理（含全部方法：GET/DELETE/PUT/POST）
        if (path.equals("/api/ai/session") || path.startsWith("/api/ai/session/")
                || path.equals("/api/ai/sessions") || path.startsWith("/api/ai/sessions/")) {
            return true;
        }
        // 分享管理（个人设置内的面板）：枚举 / 停用 / 清除记录，均只作用于**我自己**分享过的会话。
        // 注意不能靠上面的 "/api/ai/session/" 前缀带过——这里是 "session-shares"（连字符），
        // 不匹配 "session/"，漏写会让普通用户直接 403、只有管理员能看见自己的分享列表。
        // 前缀分支同时覆盖 /session-shares/{id}（停用）与 /session-shares/{id}/record（清除记录）。
        if (path.equals("/api/ai/session-shares") || path.startsWith("/api/ai/session-shares/")) {
            return true;
        }
        if (path.equals("/api/ai/message-group") || path.startsWith("/api/ai/message-group/")) {
            return true;
        }
        if ("POST".equals(method) && path.equals("/api/ai/chat")) return true;
        // 聊天附件上传（问答链路材料）：上传换 fileId，文件按登录 uid 隔离落盘并限频（ChatController 内处理）
        if ("POST".equals(method) && path.equals("/api/ai/chat/attachment")) return true;
        // 停止本轮（问答链路控制）：POST /chat/stop——归属由 RagService.stopTurn 按
        // 「停止人 uid == 发起本轮 uid」严格校验，别人停不动；不放行则普通用户点停止直接 403
        if ("POST".equals(method) && path.equals("/api/ai/chat/stop")) return true;
        // 运行中插话：POST /chat/steer——归属同样由 RagService.steerTurn 按「发起人 uid == 插话人 uid」校验
        if ("POST".equals(method) && path.equals("/api/ai/chat/steer")) return true;
        // 断线重连接流：GET /chat/run（探测）+ GET /chat/resume（接流）——归属由 RagService 按
        // 「接流人 uid == 发起本轮 uid」严格校验，别人的轮既看不到有没有在跑、也接不走。
        // 不放行则普通用户刷新页面后接不上自己那一轮（只有管理员能重连，等于这功能对多数用户是坏的）
        if ("GET".equals(method) && (path.equals("/api/ai/chat/run") || path.equals("/api/ai/chat/resume"))) {
            return true;
        }
        // 工具执行审批（人在回路）：POST /tool-approval/{id}——归属由 RagService.resolveApproval
        // 按"审批人 uid == 发起轮次用户"严格校验，非本人裁决一律拒绝。
        // GET 同样放行：GET /tool-approval/{id} 是铃铛通知的深链恢复入口、GET /tool-approval/pending 是
        // 会话加载/断线接回时重建审批卡的入口（控制器内都按 uid 过滤）。此前只放了 POST，普通用户点铃铛
        // 恢复审批卡会撞 403（管理员靠直通看不出来），接流补上审批卡恢复时一并修掉。
        if (("POST".equals(method) || "GET".equals(method)) && (path.equals("/api/ai/tool-approval")
                || path.startsWith("/api/ai/tool-approval/"))) return true;
        // 工具完整输出（外溢原文按需读回）：GET /tool-output/{spillId}——归属在 ToolSpillService.read 内
        // 按登录 uid 校验（目录按 uid 哈希 + meta 二次核对），别人的 id 读不到内容
        if ("GET".equals(method) && path.startsWith("/api/ai/tool-output")) return true;
        // 智能体提问（人在回路）：POST /ask-user/{id} 同口径——归属由 resolveAsk 按 uid 严格校验；
        // 放行是为了等答复超 120s 后 JWT 过期的场景也能拿到干净的"已失效"错误而不是 401 强登出。
        // GET /ask-user/pending 是卡片恢复入口（会话加载时取自己的待答提问），同样只按 uid 过滤。
        if (("POST".equals(method) || "GET".equals(method)) && (path.equals("/api/ai/ask-user")
                || path.startsWith("/api/ai/ask-user/"))) return true;
        // 执行计划裁决（计划模式，人在回路）：POST /plan-approval/{id} 与上两条同口径——归属由
        // resolvePlanApproval 按"裁决人 uid == 发起轮次用户"严格校验；等待窗口默认 10 分钟，
        // 放行保证等待期间 JWT 过期能拿到干净的「已失效」错误。GET /plan-approval/pending 是
        // 卡片恢复入口（会话加载时取自己的待批准计划），同样只按 uid 过滤。
        if (("POST".equals(method) || "GET".equals(method)) && (path.equals("/api/ai/plan-approval")
                || path.startsWith("/api/ai/plan-approval/"))) return true;
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
        // 个人偏好（本人默认模型 + 记忆开关）：读改自己的设置；自助改密（非管理员只能改自己，Controller 内校验）
        if (path.equals("/api/ai/user/preference")) return true;
        // 我的侧栏布局（个人资产）：读改自己的菜单顺序/显隐，归属一律取登录 uid（MenuController 内），
        // 只重排/隐藏「本人已有」的菜单（保存时按角色白名单过滤），与 /user/preference 同口径故全员可用。
        // 必须显式放行：/api/ai/menu/** 整体是权限管理页的管理端点，未放行时普通用户点侧栏齿轮直接 403
        // （管理员靠 isAdminCode 直通，故只在非管理员账号上暴露）。
        if (path.equals("/api/ai/menu/my-layout")) return true;
        // 个人对话偏好（相关建议条数等，schema personal 字段）：
        // 只读写本人的 c_ai_user_config（uid 取登录态），键限定为 schema 标记 personal 的字段
        if (path.equals("/api/ai/user/settings")) return true;
        // 自助修改昵称（PUT /user/profile，仅本人：uid 取登录态，Controller 内只动本人 username 列）
        if ("PUT".equals(method) && path.equals("/api/ai/user/profile")) return true;
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
        // 站内通知（铃铛，个人资产）：列表/未读数/标记已读——接收人一律取登录 uid（NotificationController 内过滤），
        // 每人只看得到并只操作得了自己的通知，所有角色可用
        if (path.equals("/api/ai/notification") || path.startsWith("/api/ai/notification/")) return true;
        // 沙盒工作区浏览（个人资产）：scope=(sessionId,uid)，sandboxId 由二者派生——探别人的会话 id
        // 只会按自己的 uid 派生 sandboxId，天然探不到别人的容器；开关 tool.sandbox.enabled 关闭时控制器 fail-closed
        if (path.equals("/api/ai/sandbox") || path.startsWith("/api/ai/sandbox/")) return true;
        // 知识块（2026-10）：预览/搜索/编辑对「文档可读/可管理」的普通用户开放——资源级判定在
        // KnowledgeController 内做（文档可读=列块与搜索命中，文档可管=改块删块；全局搜索按可见文档过滤）。
        // /knowledge/unmatched（无命中问题分析）与 POST /knowledge（手动补块）仍仅管理员。
        if (path.startsWith("/api/ai/knowledge/") && !path.equals("/api/ai/knowledge/unmatched")) return true;
        // 智能体公开分享（游客通道）：免登录，token 即凭据（/s/{token} 的后端 API）
        if (isShareGuestEndpoint(path)) return true;
        // 用户长期记忆（个人资产）：个人设置页增删改查自己的（归属在 MemoryController 按 uid 过滤）
        if (path.equals("/api/ai/memory") || path.startsWith("/api/ai/memory/")) return true;
        // 帮助中心（官方内置手册，只读）：数据源为全员只读的内置库，且 ManualController 只暴露手册篇目，
        // 登录即可读——入口是前端右下角全局悬浮按钮，所有角色都要能用
        if ("GET".equals(method) && (path.equals("/api/ai/manual/documents")
                || path.matches("/api/ai/manual/documents/[^/]+/content"))) return true;
        // 工作流（个人资产）：M0~M3 仅创建者本人可见可管（WorkflowService 按 uid 归属校验；M4 随发布语义启用共享）
        if (path.equals("/api/ai/workflow") || path.startsWith("/api/ai/workflow/")) return true;
        // 工作流凭据（第 3 期，个人资产）：http 节点以 {{credential:名称}} 引用；值加密落库、出参脱敏，
        // 控制器一律按 RequestUser.uid() 过滤（看不到也改不了别人的），故与技能/MCP 同口径不要求管理员
        if (path.equals("/api/ai/credential") || path.startsWith("/api/ai/credential/")) return true;
        // 工作流开放接口（M4）：/api/ai/v1/workflows/**——对外系统持 API Key 触发已发布工作流。
        // 与普通用户白名单同层（API Key 走第 1 层放行分支）；权限面收在 WorkflowService 内：
        // 工作流对该身份可读才可触发，不可见/未发布一律拒绝，不因端点开放而放宽资源级判定。
        if (path.startsWith("/api/ai/v1/workflows")) return true;
        return false;
    }

    /** 是否为登录鉴权相关端点（不允许 API Key 访问：凭据语义不同） */
    private static boolean isAuthEndpoint(String path) {
        return path != null && path.startsWith("/api/ai/auth/");
    }

    /**
     * 知识库 / 文档的自建自管端点（普通用户可访问；资源级权限由 Controller 内判定）。
     * <p>刻意排除：{@code /document/stats}（全库命中统计）等跨资源的管理视图，仍仅管理员；
     * {@code /document/queue/stats} 是全局解析队列计数（无用户数据），供普通用户的文档页展示。</p>
     */
    private static boolean isKbOrDocEndpoint(String method, String path) {
        if (path == null) return false;
        if (path.equals("/api/ai/document/stats")) return false;
        if (path.equals("/api/ai/document/queue/stats")) return true;
        // 运维端点（向量索引诊断 / 同模型重建）：**刻意不放行给普通用户**——
        // 重建会 DROP 整库索引并重新调嵌入模型，是重量级且花钱的操作，只走管理员判定。
        // 注意：新增 /xxx-yyy 形态的端点必须在此显式排除，否则会「看着像同一段」被漏掉而落默认门禁。
        if (path.equals("/api/ai/document/vector-diagnose")) return false;
        if (path.equals("/api/ai/document/rebuild-kb")) return false;
        // 知识库：列表 / 详情 / 新建 / 编辑 / 删除 / 移动文档
        if (path.equals("/api/ai/kb") || path.equals("/api/ai/kb/list")
                || path.matches("/api/ai/kb/[^/]+") || path.matches("/api/ai/kb/doc/[^/]+")) return true;
        // 文档：列表 / 上传 / 批量 / 单个的启停用·共享·重解析·补图述·版本·回滚·源文件·自动刷新·删除
        if (path.equals("/api/ai/document") || path.equals("/api/ai/document/list")
                || path.equals("/api/ai/document/upload") || path.equals("/api/ai/document/upload/batch")
                || path.equals("/api/ai/document/batch/delete") || path.equals("/api/ai/document/batch/reparse")
                || path.matches("/api/ai/document/[^/]+")
                || path.matches("/api/ai/document/[^/]+/(source|versions|status|share|reparse|backfill-descriptions|rollback|refresh-config)")) {
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
                || path.matches("/api/ai/agent/[^/]+") || path.matches("/api/ai/agent/[^/]+/share")
                || path.matches("/api/ai/agent/[^/]+/publish")
                || path.matches("/api/ai/agent/[^/]+/(versions|rollback)");
    }

    /**
     * 游客分享端点（公开链接 /s/{token} 的后端 API）：免登录可访问。
     * 安全面收窄在 {@code ShareController} 与 RagService 游客模式内做：
     * token 校验 + IP 限流 + 工具白名单收窄（沙盒/产物/MCP/技能不暴露）。
     */
    private static boolean isShareGuestEndpoint(String path) {
        return path != null && path.startsWith("/api/ai/share/");
    }

    /**
     * 模型供应商的自助端点（普通用户可访问）。
     * <p>
     * 覆盖：列表 / 新建（{@code /provider}）、单个的改删与启停、单个的模型登记、
     * 单个的被引用清单（{@code /{id}/references}）、先测后存用的 {@code /models/fetch} 与 {@code /test}。
     * 写操作的归属判定不在拦截器做——由 {@code ProviderController.denyUnlessManageable}
     * 按「管理员级全部 / 普通用户仅自己登记的个人级」逐资源裁决。
     * {@code /provider/available} 在更早的白名单分支已放行。
     */
    private static boolean isProviderSelfEndpoint(String method, String path) {
        if (path == null) return false;
        return path.equals("/api/ai/provider")
                || path.matches("/api/ai/provider/[^/]+")
                || path.matches("/api/ai/provider/[^/]+/(models|enabled|references)")
                || path.equals("/api/ai/provider/models/fetch")
                || path.equals("/api/ai/provider/test");
    }

    /**
     * 登录引导端点：未登录也必须可访问，否则无法登录（否则死锁）。
     * 其余端点在校验完平台信任 token 后，若开启 require-login 则要求携带有效登录令牌。
     */
    private static boolean isAuthBootstrapEndpoint(String method, String path) {
        if (path == null) return false;
        // OIDC 单点登录：四个端点都发生在"还没有本系统登录令牌"的阶段（配置探测 → 取授权地址 →
        // IdP 回调 → 一次性 code 兑换），若不放行会与登录门禁互相死锁。
        // API Key 仍不可用（isAuthEndpoint 对 /api/ai/auth/** 一律拒绝，凭据语义不同）。
        if (path.startsWith("/api/ai/auth/oidc/")) return true;
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
            // 2. 登录门禁：require-login=true 时，除登录引导端点与游客分享端点外必须持有效登录令牌
            boolean authenticated = Boolean.TRUE.equals(request.getAttribute(UserContextInterceptor.ATTR_AUTHENTICATED));
            if (properties.getAuth().isRequireLogin() && !authenticated
                    && !isAuthBootstrapEndpoint(method, path) && !isShareGuestEndpoint(path)) {
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
                String role = com.wenqu.ai.util.RequestUser.role();
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
