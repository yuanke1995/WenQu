package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.CLIAuthSession;
import com.wisesoft.wenqu.models.Department;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.DepartmentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.repository.port.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * OIDC 服务模块：统一封装 OIDC 配置、工具能力和认证业务处理逻辑。
 *
 * <p>由参考实现的 services/oidc_service.py 逐函数翻译：配置模型与环境变量装载、Provider 元数据
 * discovery、state/一次性登录 code 的内存存储、授权 URL 构造、授权码换 token、userinfo、
 * 用户提取与部门获取/创建、OIDC 用户创建/恢复/绑定占位、回调与 code 交换用例。
 *
 * <p>必要替换：
 * <ul>
 *   <li>{@code httpx.AsyncClient} → JDK {@link HttpClient}（同步发送；参考实现的 await
 *       语义由调用方线程承担）。超时 30s、{@code raise_for_status} 语义照搬。
 *   <li>{@code fastapi HTTPException(status_code, detail)} → {@link BizException}(code, message)。
 *   <li>{@code RedirectResponse(url, 302)} → {@link RedirectResponse} 记录（Controller 层
 *       尚未移植，先以数据形状承载，接入时转 302 响应）。
 *   <li>模块级单例 {@code oidc_config = OIDCConfig.from_env()} → 静态字段（进程启动时装载一次）。
 *   <li>IntegrityError → {@link DataIntegrityViolationException}；db.commit() 语义由
 *       各写入点的自动提交承担（与参考实现分步 commit 的边界一致）。
 *   <li>恢复注销用户时置空列（deleted_at/phone_number/avatar）用显式 {@code SET} 更新，
 *       规避框架默认更新策略跳过 null 列（参考实现直接属性赋值后 commit）。
 * </ul>
 */
@Service
public class OidcService {

    /** 前端 OIDC 回调路由路径（与 web/src/router/index.js 中的路由保持一致）。 */
    public static final String FRONTEND_CALLBACK_PATH = "/auth/oidc/callback";
    /** 登录页路径。 */
    public static final String FRONTEND_LOGIN_PATH = "/login";

    private static final Logger log = LoggerFactory.getLogger(OidcService.class);

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final OperationLogService operationLogService;
    private final UserMapper userMapper;
    private final com.wisesoft.wenqu.repository.port.DepartmentMapper departmentMapper;

    public OidcService(
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            OperationLogService operationLogService,
            UserMapper userMapper,
            com.wisesoft.wenqu.repository.port.DepartmentMapper departmentMapper) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.operationLogService = operationLogService;
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
    }

    // ==================== 配置模型 ====================

    /** OIDC 配置模型（参考实现 pydantic OIDCConfig，字段默认值照搬）。 */
    public static final class OIDCConfig {

        public boolean enabled = false;
        public String issuerUrl = "";
        public String clientId = "";
        public String clientSecret = "";
        public String redirectUri = "";
        public String authorizationEndpoint = "";
        public String tokenEndpoint = "";
        public String userinfoEndpoint = "";
        public String endSessionEndpoint = "";
        public String providerName = "OIDC登录";
        public String scopes = "openid profile email";
        public boolean autoCreateUser = true;
        public String defaultRole = "user";
        public String defaultDepartment = "OIDC用户";
        public String usernameClaim = "preferred_username";
        public String emailClaim = "email";
        public String nameClaim = "name";
        public boolean useRawUsername = false;
        public boolean fetchDepartmentInfo = false;
        public String departmentClaim = "department";
        public boolean forcePromptLogin = false;

        private static String env(String name, String defaultValue) {
            String value = System.getenv(name);
            return (value == null ? defaultValue : value).strip();
        }

        /** 从环境变量加载配置。 */
        public static OIDCConfig fromEnv() {
            boolean enabled = env("OIDC_ENABLED", "false").toLowerCase().equals("true");
            OIDCConfig config = new OIDCConfig();
            if (!enabled) {
                config.enabled = false;
                return config;
            }
            config.enabled = true;
            config.providerName = env("OIDC_PROVIDER_NAME", "OIDC登录");
            config.issuerUrl = env("OIDC_ISSUER_URL", "");
            config.clientId = env("OIDC_CLIENT_ID", "");
            config.clientSecret = env("OIDC_CLIENT_SECRET", "");
            config.redirectUri = env("OIDC_REDIRECT_URI", "");
            config.authorizationEndpoint = env("OIDC_AUTHORIZATION_ENDPOINT", "");
            config.tokenEndpoint = env("OIDC_TOKEN_ENDPOINT", "");
            config.userinfoEndpoint = env("OIDC_USERINFO_ENDPOINT", "");
            config.endSessionEndpoint = env("OIDC_END_SESSION_ENDPOINT", "");
            config.scopes = env("OIDC_SCOPES", "openid profile email");
            config.autoCreateUser = env("OIDC_AUTO_CREATE_USER", "true").toLowerCase().equals("true");
            config.defaultRole = env("OIDC_DEFAULT_ROLE", "user");
            config.defaultDepartment = env("OIDC_DEFAULT_DEPARTMENT", "OIDC用户");
            config.usernameClaim = env("OIDC_USERNAME_CLAIM", "preferred_username");
            config.emailClaim = env("OIDC_EMAIL_CLAIM", "email");
            config.nameClaim = env("OIDC_NAME_CLAIM", "name");
            config.useRawUsername = env("OIDC_USE_RAW_USERNAME", "false").toLowerCase().equals("true");
            config.fetchDepartmentInfo = env("OIDC_FETCH_DEPARTMENT_INFO", "false").toLowerCase().equals("true");
            config.departmentClaim = env("OIDC_DEPARTMENT_CLAIM", "department");
            config.forcePromptLogin = env("OIDC_FORCE_PROMPT_LOGIN", "true").toLowerCase().equals("true");
            return config;
        }

        /** 检查登录链接生成所需配置是否完整。 */
        public boolean isConfigured() {
            if (!enabled) {
                return false;
            }
            // 生成登录链接只要求 client_id + (issuer_url 或 authorization_endpoint)
            return !clientId.isEmpty() && (!issuerUrl.isEmpty() || !authorizationEndpoint.isEmpty());
        }

        /** 检查授权码换 token 所需配置是否完整。 */
        public boolean isTokenExchangeConfigured() {
            if (!enabled) {
                return false;
            }
            // 回调换 token 需要 client_id + client_secret + (issuer_url 或 token_endpoint)
            return !clientId.isEmpty()
                    && !clientSecret.isEmpty()
                    && (!issuerUrl.isEmpty() || !tokenEndpoint.isEmpty());
        }
    }

    /** 模块级单例（进程启动时从环境变量装载一次）。 */
    private static final OIDCConfig OIDC_CONFIG = OIDCConfig.fromEnv();

    public OIDCConfig config() {
        return OIDC_CONFIG;
    }

    // ==================== Provider 元数据 ====================

    /** OIDC Provider 元数据（参考实现 OIDCProviderMetadata）。 */
    public static final class OIDCProviderMetadata {

        public String authorizationEndpoint;
        public String tokenEndpoint;
        public String userinfoEndpoint;
        public String endSessionEndpoint;
        public String lastError;
        private boolean loaded;

        private static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        /** 从 discovery 端点加载元数据。 */
        public boolean load(String issuerUrl) {
            if (loaded) {
                return true;
            }
            String discoveryUrl = issuerUrl.replaceAll("/+$", "") + "/.well-known/openid-configuration";
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(discoveryUrl))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                JSONObject metadata = JSON.parseObject(response.body());

                authorizationEndpoint = metadata.getString("authorization_endpoint");
                tokenEndpoint = metadata.getString("token_endpoint");
                userinfoEndpoint = metadata.getString("userinfo_endpoint");
                endSessionEndpoint = metadata.getString("end_session_endpoint");

                // 登录 URL 生成至少需要 authorization_endpoint。
                if (authorizationEndpoint == null || authorizationEndpoint.isEmpty()) {
                    lastError = "discovery 响应缺少 authorization_endpoint";
                    log.error("Failed to load OIDC discovery: {}, url={}", lastError, discoveryUrl);
                    return false;
                }

                loaded = true;
                lastError = null;
                log.info("OIDC discovery loaded from {}", discoveryUrl);
                return true;
            } catch (Exception exc) {
                lastError = exc.getClass().getSimpleName() + ": " + exc;
                log.error("Failed to load OIDC discovery: {}, url={}", lastError, discoveryUrl);
                return false;
            }
        }
    }

    // ==================== OIDC 工具 ====================

    /** OIDC 工具集（参考实现 OIDCUtils：state/登录 code 内存存储 + 端点调用）。 */
    public static final class OIDCUtils {

        private static final ConcurrentHashMap<String, Map<String, Object>> STATE_STORE = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Map<String, Object>> LOGIN_CODE_STORE =
                new ConcurrentHashMap<>();
        private static final int STATE_TTL_SECONDS = 300;
        private static final int LOGIN_CODE_TTL_SECONDS = 60;
        private static volatile String lastMetadataError;
        private static volatile OIDCProviderMetadata metadata;

        private static void cleanupExpiredState() {
            double now = timeSeconds();
            for (Map.Entry<String, Map<String, Object>> entry : STATE_STORE.entrySet()) {
                Object expiresAt = entry.getValue().get("expires_at");
                if (expiresAt instanceof Number number && number.doubleValue() <= now) {
                    STATE_STORE.remove(entry.getKey());
                }
            }
        }

        private static void cleanupExpiredLoginCode() {
            double now = timeSeconds();
            for (Map.Entry<String, Map<String, Object>> entry : LOGIN_CODE_STORE.entrySet()) {
                Object expiresAt = entry.getValue().get("expires_at");
                if (expiresAt instanceof Number number && number.doubleValue() <= now) {
                    LOGIN_CODE_STORE.remove(entry.getKey());
                }
            }
        }

        /** 获取 OIDC Provider 元数据。 */
        public static OIDCProviderMetadata getMetadata() {
            if (!OIDC_CONFIG.enabled || !OIDC_CONFIG.isConfigured()) {
                lastMetadataError = "OIDC 未启用或基础配置不完整";
                return null;
            }

            if (metadata == null) {
                OIDCProviderMetadata fresh = new OIDCProviderMetadata();
                if (!OIDC_CONFIG.authorizationEndpoint.isEmpty()) {
                    fresh.authorizationEndpoint = OIDC_CONFIG.authorizationEndpoint;
                    fresh.tokenEndpoint = OIDC_CONFIG.tokenEndpoint;
                    fresh.userinfoEndpoint = OIDC_CONFIG.userinfoEndpoint;
                    fresh.endSessionEndpoint = OIDC_CONFIG.endSessionEndpoint;
                    fresh.loaded = true;
                    lastMetadataError = null;
                } else {
                    boolean success = fresh.load(OIDC_CONFIG.issuerUrl);
                    if (!success) {
                        lastMetadataError = fresh.lastError != null ? fresh.lastError : "OIDC discovery 加载失败";
                        return null;
                    }
                }
                metadata = fresh;
            }

            if (metadata.authorizationEndpoint == null || metadata.authorizationEndpoint.isEmpty()) {
                lastMetadataError = "OIDC 授权端点不可用";
                return null;
            }

            lastMetadataError = null;
            return metadata;
        }

        /** 获取最近一次 OIDC 元数据加载错误。 */
        public static String getLastMetadataError() {
            return lastMetadataError;
        }

        /** 生成 state 参数并存储。 */
        public static String generateState(String redirectPath) {
            cleanupExpiredState();
            String state = AuthUtils.tokenUrlSafe(32);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("redirect_path", redirectPath);
            data.put("expires_at", timeSeconds() + STATE_TTL_SECONDS);
            STATE_STORE.put(state, data);
            return state;
        }

        /** 验证并消费 state 参数；无效/过期返回 null。 */
        public static Map<String, Object> verifyState(String state) {
            Map<String, Object> stateData = STATE_STORE.remove(state);
            if (stateData == null) {
                return null;
            }
            Object expiresAt = stateData.get("expires_at");
            if (expiresAt instanceof Number number && number.doubleValue() <= timeSeconds()) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("redirect_path", stateData.get("redirect_path"));
            return result;
        }

        /** 生成一次性短期登录 code。 */
        public static String generateLoginCode(Map<String, Object> payload) {
            cleanupExpiredLoginCode();
            String code = AuthUtils.tokenUrlSafe(32);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payload", payload);
            data.put("expires_at", timeSeconds() + LOGIN_CODE_TTL_SECONDS);
            LOGIN_CODE_STORE.put(code, data);
            return code;
        }

        /** 消费一次性短期登录 code；无效/过期返回 null。 */
        public static Map<String, Object> consumeLoginCode(String code) {
            Map<String, Object> data = LOGIN_CODE_STORE.remove(code);
            if (data == null) {
                return null;
            }
            Object expiresAt = data.get("expires_at");
            if (expiresAt instanceof Number number && number.doubleValue() <= timeSeconds()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) data.get("payload");
            return payload;
        }

        /** 生成 nonce 参数。 */
        public static String generateNonce() {
            return AuthUtils.tokenUrlSafe(32);
        }

        /** 构建授权 URL；元数据不可用时返回 null。 */
        public static String buildAuthorizationUrl(String redirectPath) {
            OIDCProviderMetadata metadata = getMetadata();
            if (metadata == null || metadata.authorizationEndpoint == null) {
                return null;
            }

            String state = generateState(redirectPath);
            String nonce = generateNonce();

            String redirectUri = OIDC_CONFIG.redirectUri;
            if (redirectUri == null || redirectUri.isEmpty()) {
                redirectUri = "/api/auth/oidc/callback";
            }

            Map<String, String> params = new LinkedHashMap<>();
            params.put("client_id", OIDC_CONFIG.clientId);
            params.put("response_type", "code");
            params.put("scope", OIDC_CONFIG.scopes);
            params.put("redirect_uri", redirectUri);
            params.put("state", state);
            params.put("nonce", nonce);

            // 如果配置强制登录，添加 prompt=login 参数
            if (OIDC_CONFIG.forcePromptLogin) {
                params.put("prompt", "login");
            }

            String queryString = urlencode(params);
            return metadata.authorizationEndpoint + "?" + queryString;
        }

        /** 用授权码交换令牌；失败返回 null。 */
        public static Map<String, Object> exchangeCodeForToken(String code) {
            OIDCProviderMetadata metadata = getMetadata();
            if (metadata == null || metadata.tokenEndpoint == null) {
                return null;
            }

            String redirectUri =
                    OIDC_CONFIG.redirectUri == null || OIDC_CONFIG.redirectUri.isEmpty()
                            ? "/api/auth/oidc/callback"
                            : OIDC_CONFIG.redirectUri;

            Map<String, String> data = new LinkedHashMap<>();
            data.put("grant_type", "authorization_code");
            data.put("code", code);
            data.put("redirect_uri", redirectUri);
            data.put("client_id", OIDC_CONFIG.clientId);
            data.put("client_secret", OIDC_CONFIG.clientSecret);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(metadata.tokenEndpoint))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(urlencode(data), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                JSONObject body = JSON.parseObject(response.body());
                return new LinkedHashMap<>(body);
            } catch (Exception exc) {
                log.error("Failed to exchange code for token: {}", exc.getMessage());
                return null;
            }
        }

        /** 获取用户信息；失败返回 null。 */
        public static Map<String, Object> getUserinfo(String accessToken) {
            OIDCProviderMetadata metadata = getMetadata();
            if (metadata == null || metadata.userinfoEndpoint == null) {
                return null;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(metadata.userinfoEndpoint))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();
                HttpResponse<String> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                JSONObject body = JSON.parseObject(response.body());
                return new LinkedHashMap<>(body);
            } catch (Exception exc) {
                log.error("Failed to get userinfo: {}", exc.getMessage());
                return null;
            }
        }

        /** 构建登出 URL；元数据不可用时返回 null。 */
        public static String buildLogoutUrl(String idToken) {
            OIDCProviderMetadata metadata = getMetadata();
            if (metadata == null || metadata.endSessionEndpoint == null) {
                return null;
            }

            Map<String, String> params = new LinkedHashMap<>();
            params.put("client_id", OIDC_CONFIG.clientId);
            if (idToken != null && !idToken.isEmpty()) {
                params.put("id_token_hint", idToken);
            }
            if (OIDC_CONFIG.redirectUri != null && !OIDC_CONFIG.redirectUri.isEmpty()) {
                params.put("post_logout_redirect_uri", OIDC_CONFIG.redirectUri);
            }

            String queryString = urlencode(params);
            return metadata.endSessionEndpoint + "?" + queryString;
        }

        /** 从 userinfo 中提取用户信息（claim 映射与回退链照搬）。 */
        public static Map<String, Object> extractUserInfo(Map<String, Object> userinfo) {
            String sub = str(userinfo.getOrDefault("sub", ""));

            String username = str(userinfo.get(OIDC_CONFIG.usernameClaim));
            if (username.isEmpty()) {
                username = str(userinfo.get("preferred_username"));
            }
            if (username.isEmpty()) {
                username = str(userinfo.getOrDefault("email", "")).split("@", 2)[0];
            }
            if (username.isEmpty()) {
                username = codePointSubstring(sub, 0, 20);
            }

            String email = str(userinfo.get(OIDC_CONFIG.emailClaim));
            if (email.isEmpty()) {
                email = str(userinfo.get("email"));
            }

            String name = str(userinfo.get(OIDC_CONFIG.nameClaim));
            if (name.isEmpty()) {
                name = str(userinfo.get("name"));
            }
            if (name.isEmpty()) {
                name = username;
            }

            String departmentName = null;
            String departmentDescription = null;
            if (OIDC_CONFIG.fetchDepartmentInfo) {
                departmentName = strOrNull(userinfo.get(OIDC_CONFIG.departmentClaim));
                if (departmentName == null || departmentName.isEmpty()) {
                    departmentName = strOrNull(userinfo.get("department"));
                }

                // 获取部门描述
                departmentDescription = strOrNull(userinfo.get("department_description"));
                if (departmentDescription == null || departmentDescription.isEmpty()) {
                    departmentDescription = strOrNull(userinfo.get("department_desc"));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sub", sub);
            result.put("username", username);
            result.put("email", email);
            result.put("name", name);
            result.put("department_name", departmentName);
            result.put("department_description", departmentDescription);
            result.put("raw", userinfo);
            return result;
        }

        /**
         * urllib.parse.urlencode（quote_via=quote_plus 语义，UTF-8）。
         *
         * <p>JDK 的 {@link URLEncoder} 与 Python quote_plus 的保留字符集不同
         * （{@code *} 与 {@code ~} 行为互换，跨语言对拍实测），此处按 Python 语义实现：
         * 字母数字与 {@code _.-~} 原样，空格转 {@code +}，其余逐字节 %XX。
         */
        private static String urlencode(Map<String, String> params) {
            StringBuilder builder = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(quotePlus(entry.getKey()))
                        .append('=')
                        .append(quotePlus(entry.getValue() == null ? "" : entry.getValue()));
            }
            return builder.toString();
        }

        private static String quotePlus(String value) {
            StringBuilder builder = new StringBuilder();
            for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
                char c = (char) (b & 0xFF);
                if ((b >= 'A' && b <= 'Z')
                        || (b >= 'a' && b <= 'z')
                        || (b >= '0' && b <= '9')
                        || c == '_' || c == '.' || c == '-' || c == '~') {
                    builder.append(c);
                } else if (b == ' ') {
                    builder.append('+');
                } else {
                    builder.append('%').append(String.format("%02X", b));
                }
            }
            return builder.toString();
        }

        private static String str(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static String strOrNull(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        /** Python 切片语义的按码点截断（sub[:20]）。 */
        private static String codePointSubstring(String value, int begin, int end) {
            if (value == null) {
                return null;
            }
            int codePoints = value.codePointCount(0, value.length());
            int beginIndex = value.offsetByCodePoints(0, Math.min(begin, codePoints));
            int endIndex = value.offsetByCodePoints(0, Math.min(end, codePoints));
            return value.substring(beginIndex, endIndex);
        }
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static double timeSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    // ==================== 部门获取/创建 ====================

    /** 获取或创建 OIDC 用户的部门。 */
    public Department getOrCreateOidcDepartment(String deptNameFromOidc, String deptDescFromOidc) {
        // 清理并验证从 OIDC 获取的部门名称
        String processedDeptName = null;
        String processedDeptDesc = null;

        if (deptNameFromOidc != null) {
            processedDeptName = deptNameFromOidc.strip();
            // 截断到 50 字符（匹配数据库限制）
            processedDeptName = truncateCodePoints(processedDeptName, 50);
            // 如果处理后为空，放弃使用
            if (processedDeptName.isEmpty()) {
                processedDeptName = null;
            }
        }

        // 清理并验证从 OIDC 获取的部门描述
        if (deptDescFromOidc != null) {
            processedDeptDesc = deptDescFromOidc.strip();
            // 截断到 255 字符（匹配数据库限制）
            processedDeptDesc = truncateCodePoints(processedDeptDesc, 255);
            if (processedDeptDesc.isEmpty()) {
                processedDeptDesc = null;
            }
        }

        // 最终确定部门名称：优先使用处理后的OIDC部门名称，否则使用默认部门名称
        String finalDeptName = processedDeptName != null ? processedDeptName : OIDC_CONFIG.defaultDepartment;
        // 最终确定部门描述：优先使用处理后的OIDC部门描述，否则使用默认描述
        String finalDeptDesc =
                processedDeptDesc != null ? processedDeptDesc : finalDeptName + "部门";

        Department dept = departmentRepository.getByName(finalDeptName);
        if (dept != null) {
            // 部门已存在，直接返回
            log.info("Using existing department: {}", finalDeptName);
            return dept;
        }

        // 部门不存在，创建新部门
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", finalDeptName);
            data.put("description", finalDeptDesc);
            dept = departmentRepository.create(data);
            log.info("Created OIDC department: {}", finalDeptName);
            return dept;
        } catch (DataIntegrityViolationException exc) {
            // 并发创建时部门可能已存在，再次查询
            return departmentRepository.getByName(finalDeptName);
        }
    }

    /** Python 切片语义的按码点截断。 */
    private static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    // ==================== 用户查找与绑定 ====================

    /** 通过 OIDC sub 查找用户（标准 uid 与绑定占位两条路径）。 */
    public User findUserByOidcSub(String sub) {
        // 方法1: 检查是否有用户的 uid 直接等于 "oidc:{sub}"（标准 OIDC 用户）
        String standardOidcUid = "oidc:" + sub;
        // 占位绑定记录会被标记为 is_deleted=1，但我们仍需要查询它们来获取绑定关系
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUid, standardOidcUid)
                .eq(User::getIsDeleted, 0));
        if (user != null) {
            return user;
        }

        // 绑定占位用户被标记为 is_deleted=1，需要包括deleted来查询
        List<User> bindingUsers = userMapper.selectList(new LambdaQueryWrapper<User>()
                .likeRight(User::getUid, standardOidcUid + ":")
                .in(User::getIsDeleted, List.of(0, 1))
                .orderByAsc(User::getId));
        if (bindingUsers != null && !bindingUsers.isEmpty()) {
            for (User placeholder : bindingUsers) {
                Integer targetUserId = extractOidcPlaceholderTargetUserId(placeholder.getUid());
                if (targetUserId == null) {
                    continue;
                }
                User targetUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getId, targetUserId)
                        .eq(User::getIsDeleted, 0));
                if (targetUser != null) {
                    log.debug(
                            "Resolved OIDC binding placeholder {} to user {}", placeholder.getUid(), targetUserId);
                    return targetUser;
                }
            }
        }

        return null;
    }

    /** 查找已注销的 OIDC 账户（标准与历史后缀）。 */
    public User findDeletedOidcUserBySub(String sub) {
        String oidcUid = "oidc:" + sub;

        User deletedUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUid, oidcUid)
                .eq(User::getIsDeleted, 1));
        if (deletedUser != null) {
            return deletedUser;
        }

        List<User> bindingUsers = userMapper.selectList(new LambdaQueryWrapper<User>()
                .likeRight(User::getUid, oidcUid + ":")
                .eq(User::getIsDeleted, 1)
                .orderByAsc(User::getId));
        if (bindingUsers != null && !bindingUsers.isEmpty()) {
            for (User placeholder : bindingUsers) {
                Integer targetUserId = extractOidcPlaceholderTargetUserId(placeholder.getUid());
                if (targetUserId == null) {
                    continue;
                }
                User targetUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getId, targetUserId)
                        .eq(User::getIsDeleted, 1));
                if (targetUser != null) {
                    return targetUser;
                }
            }
        }
        return null;
    }

    /** 从占位 uid 中解析真实用户 ID，允许 sub 中包含冒号。 */
    static Integer extractOidcPlaceholderTargetUserId(String uid) {
        String value = uid == null ? "" : uid.strip();
        if (!value.startsWith("oidc:")) {
            return null;
        }

        // 占位格式始终以 `:{target_user_id}` 结尾，因此从右侧拆分即可避免 sub 中的冒号干扰。
        int lastColon = value.lastIndexOf(':');
        if (lastColon < 0) {
            return null;
        }
        try {
            return Integer.parseInt(value.substring(lastColon + 1));
        } catch (NumberFormatException exc) {
            return null;
        }
    }

    /**
     * 创建 OIDC sub 绑定占位用户（仅用于记录绑定关系，不用于登录）。
     *
     * <p>在 use_raw_username 模式下，创建一个占位用户格式: oidc:{sub}:{target_user_id}，
     * 占位用户标记为 is_deleted=1（不参与实际登录），仅用于存储绑定关系；
     * find_user_by_oidc_sub 查询时会读取该占位记录并解析出绑定的真实用户，
     * 这样就能在不修改User表结构的前提下，保持绑定关系可验证，防止账号冒用。
     */
    public void createOidcBindingPlaceholder(String sub, User targetUser) {
        // 占位用户格式: oidc:{sub}:{target_user_id}，这样find_user_by_oidc_sub可以解析出目标用户ID
        String oidcPlaceholderId = "oidc:" + sub + ":" + targetUser.getId();
        // 占位用户标记为 deleted，查询时需要特别包括deleted才能找到
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUid, oidcPlaceholderId)
                .in(User::getIsDeleted, List.of(0, 1)));
        if (existing != null) {
            // 占位用户已存在，无需重复创建
            return;
        }

        // 创建占位用户：使用随机密码，标记为deleted，不用于实际登录，仅存储绑定关系
        String randomPassword = AuthUtils.tokenUrlSafe(32);
        String passwordHash = AuthUtils.hashPassword(randomPassword);

        // username 使用 oidc-binding-{sub_hash} 避免冲突，sub_hash 基于完整 sub 生成
        String subHash = AuthUtils.sha256Hex(sub).substring(0, 8);
        String username = "oidc-binding-" + subHash;

        User placeholderUser = new User();
        placeholderUser.setUsername(username);
        placeholderUser.setUid(oidcPlaceholderId);
        placeholderUser.setPhoneNumber(null);
        placeholderUser.setAvatar(null);
        placeholderUser.setPasswordHash(passwordHash);
        placeholderUser.setRole(targetUser.getRole());
        placeholderUser.setDepartmentId(targetUser.getDepartmentId());
        placeholderUser.setIsDeleted(1); // 标记为deleted，不参与实际登录
        placeholderUser.setLastLogin(DateTimeUtils.utcNowNaive());

        try {
            userMapper.insert(placeholderUser);
            log.info(
                    "Created OIDC binding placeholder (deleted) for sub {} -> user {} ({})",
                    sub,
                    targetUser.getId(),
                    targetUser.getUid());
        } catch (DataIntegrityViolationException exc) {
            // 并发创建冲突，回滚后忽略
            log.info("OIDC binding placeholder already exists for sub {}", sub);
        }
    }

    // ==================== OIDC 用户创建/恢复 ====================

    /** 为 OIDC 用户生成不冲突的用户名。 */
    public String buildUniqueOidcUsername(String preferredUsername, String sub) {
        String baseUsername = preferredUsername != null ? preferredUsername.strip() : "";
        if (baseUsername.isEmpty()) {
            baseUsername = "oidc_" + OIDCUtils.codePointSubstring(sub, 0, 8);
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, baseUsername));
        if (count == null || count == 0) {
            return baseUsername;
        }

        String hashSuffix = AuthUtils.sha256Hex(sub).substring(0, 6);
        String candidate = baseUsername + "-" + hashSuffix;
        count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, candidate));
        if (count == null || count == 0) {
            return candidate;
        }

        for (int i = 2; i < 100; i++) {
            String indexedCandidate = candidate + "-" + i;
            count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, indexedCandidate));
            if (count == null || count == 0) {
                return indexedCandidate;
            }
        }

        throw new BizException(500, "无法生成可用用户名，请联系管理员");
    }

    /** 创建 OIDC 用户。 */
    public User createOidcUser(Map<String, Object> userInfo, Integer departmentId) {
        String sub = String.valueOf(userInfo.get("sub"));
        String preferredUsername =
                str(userInfo.get("name")).isEmpty() ? str(userInfo.get("username")) : str(userInfo.get("name"));

        // 根据配置决定 uid 是否带 oidc 前缀
        String uid;
        if (OIDC_CONFIG.useRawUsername) {
            uid = str(userInfo.get("username"));
            User existingUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUid, uid)
                    .eq(User::getIsDeleted, 0));
            if (existingUser != null) {
                // 用户已存在，必须验证当前sub是否已经绑定到这个用户
                // 如果sub未绑定该用户，不能直接复用，存在账号冒用风险
                User userBySub = findUserByOidcSub(sub);
                if (userBySub != null && userBySub.getId().equals(existingUser.getId())) {
                    // sub 已经正确绑定到该用户，允许返回
                    log.info(
                            "User with raw uid {} already exists and bound to sub {}, returning existing user", uid, sub);
                    return existingUser;
                } else if (userBySub == null) {
                    // sub 尚未绑定任何用户，可以将sub绑定到这个现有用户
                    log.info("Binding new OIDC sub {} to existing user with raw uid {}", sub, uid);
                    createOidcBindingPlaceholder(sub, existingUser);
                    return existingUser;
                } else {
                    // sub 已经绑定到另一个用户，冲突，拒绝创建
                    log.warn(
                            "Cannot create OIDC user with raw uid {}: sub {} is already bound to another user {}, conflict",
                            uid,
                            sub,
                            userBySub.getId());
                    throw new BizException(
                            409,
                            "UID " + uid + " 已存在且OIDC标识 " + sub + " 已绑定到其他账号，请联系管理员处理冲突");
                }
            }
        } else {
            uid = "oidc:" + sub;
        }

        String randomPassword = AuthUtils.tokenUrlSafe(32);
        String passwordHash = AuthUtils.hashPassword(randomPassword);

        String username = buildUniqueOidcUsername(preferredUsername, sub);

        for (int retryIndex = 0; retryIndex < 3; retryIndex++) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("username", username);
                data.put("uid", uid);
                data.put("phone_number", null);
                data.put("avatar", null);
                data.put("password_hash", passwordHash);
                data.put("role", OIDC_CONFIG.defaultRole);
                data.put("department_id", departmentId);
                data.put("last_login", DateTimeUtils.utcNowNaive());
                User newUser = userRepository.create(data);
                log.info("Created OIDC user: {} ({})", newUser.getUsername(), uid);

                // use_raw_username 模式下，创建占位用户记录绑定关系
                if (OIDC_CONFIG.useRawUsername) {
                    createOidcBindingPlaceholder(sub, newUser);
                }

                return newUser;
            } catch (DataIntegrityViolationException exc) {
                User existingUser = findUserByOidcSub(sub);
                if (existingUser != null) {
                    return existingUser;
                }
                username = buildUniqueOidcUsername(preferredUsername + "-" + (retryIndex + 2), sub);
            }
        }

        throw new BizException(500, "创建 OIDC 用户失败，请重试");
    }

    /** 恢复已注销的 OIDC 用户并返回可登录用户。 */
    public User restoreDeletedOidcUser(User deletedUser, Map<String, Object> userInfo) {
        String preferredUsername =
                str(userInfo.get("name")).isEmpty() ? str(userInfo.get("username")) : str(userInfo.get("name"));

        deletedUser.setIsDeleted(0);
        deletedUser.setDeletedAt(null);
        deletedUser.setLastLogin(DateTimeUtils.utcNowNaive());
        deletedUser.setPhoneNumber(null);
        deletedUser.setAvatar(null);

        String username = deletedUser.getUsername();
        if (username.startsWith("已注销用户-")) {
            username = buildUniqueOidcUsername(preferredUsername, String.valueOf(userInfo.get("sub")));
        }

        String passwordHash = deletedUser.getPasswordHash();
        if ("DELETED".equals(passwordHash)) {
            String randomPassword = AuthUtils.tokenUrlSafe(32);
            passwordHash = AuthUtils.hashPassword(randomPassword);
        }

        // 恢复语义含置空列（deleted_at/phone_number/avatar），必须显式 SET（不能用 updateById）
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<User>()
                .eq(User::getId, deletedUser.getId())
                .set(User::getIsDeleted, 0)
                .set(User::getDeletedAt, null)
                .set(User::getLastLogin, deletedUser.getLastLogin())
                .set(User::getPhoneNumber, null)
                .set(User::getAvatar, null)
                .set(User::getUsername, username)
                .set(User::getPasswordHash, passwordHash);
        userMapper.update(null, wrapper);
        deletedUser.setUsername(username);
        deletedUser.setPasswordHash(passwordHash);
        log.info("Restored deleted OIDC user: {} ({})", deletedUser.getUsername(), deletedUser.getUid());
        return deletedUser;
    }

    /** 更新 OIDC 用户登录时间。 */
    public void updateOidcUserLogin(User user) {
        user.setLastLogin(DateTimeUtils.utcNowNaive());
        userMapper.update(
                null,
                new LambdaUpdateWrapper<User>()
                        .eq(User::getId, user.getId())
                        .set(User::getLastLogin, user.getLastLogin()));
    }

    // ==================== 重定向与用例 ====================

    /** 成功后重定向到前端 OIDC 回调页面，仅携带一次性 code。 */
    private static RedirectResponse redirectToCallback(String exchangeCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", exchangeCode);
        String url = FRONTEND_CALLBACK_PATH + "?" + OIDCUtils.urlencode(params);
        return new RedirectResponse(url, 302);
    }

    /** 失败时重定向到登录页并携带错误信息。 */
    private static RedirectResponse redirectToLoginWithError(String errorMessage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("oidc_error", errorMessage);
        String url = FRONTEND_LOGIN_PATH + "?" + OIDCUtils.urlencode(params);
        return new RedirectResponse(url, 302);
    }

    /** 302 重定向的数据形状（参考实现 fastapi RedirectResponse；Controller 接入时转响应）。 */
    public record RedirectResponse(String url, int statusCode) {}

    /** 获取 OIDC 配置（供前端使用）。 */
    public Map<String, Object> getOidcConfig() {
        if (!OIDC_CONFIG.enabled || !OIDC_CONFIG.isConfigured()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", false);
            return result;
        }

        String providerName = OIDC_CONFIG.providerName;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("provider_name", providerName);
        return result;
    }

    /** 处理 OIDC 回调 - 重定向到前端 Vue 路由。 */
    public RedirectResponse oidcCallback(String code, String state, HttpServletRequest request) {
        if (!OIDC_CONFIG.isTokenExchangeConfigured()) {
            return redirectToLoginWithError("OIDC 配置不完整，请联系管理员");
        }

        if (OIDCUtils.verifyState(state) == null) {
            return redirectToLoginWithError("登录会话已过期，请返回登录页重试");
        }

        Map<String, Object> tokenResponse = OIDCUtils.exchangeCodeForToken(code);
        if (tokenResponse == null) {
            return redirectToLoginWithError("无法获取访问令牌，请返回登录页重试");
        }

        String accessToken = str(tokenResponse.get("access_token"));
        if (accessToken.isEmpty()) {
            return redirectToLoginWithError("无法获取访问令牌，请返回登录页重试");
        }

        Map<String, Object> userinfo = OIDCUtils.getUserinfo(accessToken);
        if (userinfo == null) {
            return redirectToLoginWithError("无法获取用户信息，请返回登录页重试");
        }

        Map<String, Object> extractedInfo = OIDCUtils.extractUserInfo(userinfo);
        String sub = str(extractedInfo.get("sub"));

        if (sub.isEmpty()) {
            return redirectToLoginWithError("无法获取用户标识，请返回登录页重试");
        }

        // 查找用户：总是先通过 sub 查找，保证绑定关系可验证
        User userBySub = findUserByOidcSub(sub);

        User user;
        if (OIDC_CONFIG.useRawUsername) {
            // 使用原始用户名模式
            String username = str(extractedInfo.get("username"));
            user = null;
            if (!username.isEmpty()) {
                User userByName = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getUid, username)
                        .eq(User::getIsDeleted, 0));

                if (userBySub != null) {
                    // sub 已经绑定到一个用户
                    if (userByName != null && userBySub.getId().equals(userByName.getId())) {
                        // sub 绑定的用户就是找到的用户名用户 -> 验证通过
                        user = userByName;
                        log.info("OIDC user logged in with raw username: {} (sub: {})", username, sub);
                    } else {
                        // sub 已经绑定到另一个用户，存在冲突，拒绝登录
                        String conflictName = userByName == null ? userBySub.getUsername() : userByName.getUsername();
                        log.warn(
                                "OIDC sub {} is already bound to a different user, "
                                        + "login rejected to prevent account hijacking (conflict: {})",
                                sub,
                                conflictName);
                        return redirectToLoginWithError("OIDC标识已绑定到其他账号，请联系管理员处理绑定冲突");
                    }
                } else {
                    // sub 尚未绑定到任何用户
                    if (userByName != null) {
                        // 用户名存在，且 sub 没有绑定 -> 允许登录，并创建绑定记录
                        // 在不修改表结构的情况下，我们创建一个占位用户 oidc:{sub} 来记录绑定关系
                        // 这个占位用户不会被用来登录，仅用于存储sub -> 用户的绑定关系
                        user = userByName;
                        log.info("Binding new OIDC sub {} to existing user with raw username: {}", sub, username);
                        // 创建绑定占位用户（后台静默创建，不影响现有用户）
                        createOidcBindingPlaceholder(sub, userByName);
                    } else {
                        // 用户名不存在，需要创建新用户
                        if (OIDC_CONFIG.autoCreateUser) {
                            user = null; // 让后续逻辑创建
                        } else {
                            return redirectToLoginWithError("用户不存在，请联系管理员开通账号");
                        }
                    }
                }
            } else {
                // 没有获取到 username，回退到按sub查找
                user = userBySub;
            }
        } else {
            // 标准 OIDC 模式，通过 sub 查找
            user = userBySub;
        }

        if (user != null) {
            updateOidcUserLogin(user);
            log.info("OIDC user logged in: {}", user.getUsername());
        } else if (OIDC_CONFIG.autoCreateUser) {
            User deletedUser = findDeletedOidcUserBySub(sub);
            if (deletedUser != null) {
                user = restoreDeletedOidcUser(deletedUser, extractedInfo);
                log.info("OIDC deleted user restored and logged in: {}", user.getUsername());
            } else {
                // 从用户信息中获取部门信息
                String deptName = strOrNull(extractedInfo.get("department_name"));
                String deptDesc = strOrNull(extractedInfo.get("department_description"));
                Department dept = getOrCreateOidcDepartment(deptName, deptDesc);
                Integer departmentId = dept != null ? dept.getId() : null;
                user = createOidcUser(extractedInfo, departmentId);
            }
        } else {
            return redirectToLoginWithError("用户未注册，请联系管理员开通账号");
        }

        if (user.getIsDeleted() != null && user.getIsDeleted() != 0) {
            return redirectToLoginWithError("该账户已注销");
        }

        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("sub", String.valueOf(user.getId()));
        String jwtToken = AuthUtils.createAccessToken(tokenData, null);

        operationLogService.logOperation(user.getId(), "OIDC 登录", null, request);

        String departmentName = null;
        if (user.getDepartmentId() != null) {
            Department department = departmentMapper.selectById(user.getDepartmentId());
            departmentName = department == null ? null : department.getName();
        }

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("access_token", jwtToken);
        responseData.put("token_type", "bearer");
        responseData.put("user_id", user.getId());
        responseData.put("username", user.getUsername());
        responseData.put("uid", user.getUid());
        responseData.put("phone_number", user.getPhoneNumber());
        responseData.put("avatar", user.getAvatar());
        responseData.put("role", user.getRole());
        responseData.put("department_id", user.getDepartmentId());
        responseData.put("department_name", departmentName);

        String exchangeCode = OIDCUtils.generateLoginCode(responseData);
        return redirectToCallback(exchangeCode);
    }

    /** 用一次性 code 交换登录响应数据。 */
    public Map<String, Object> oidcExchangeCode(String code) {
        Map<String, Object> tokenData = OIDCUtils.consumeLoginCode(code);
        if (tokenData == null) {
            throw new BizException(400, "登录 code 无效或已过期，请重新登录");
        }
        return tokenData;
    }

    /** 获取 OIDC 登录 URL。 */
    public Map<String, Object> oidcLoginUrl(String redirectPath) {
        if (!OIDC_CONFIG.enabled || !OIDC_CONFIG.isConfigured()) {
            throw new BizException(503, "OIDC 登录暂不可用，请联系管理员");
        }

        String loginUrl = OIDCUtils.buildAuthorizationUrl(redirectPath);
        if (loginUrl == null) {
            String metadataError = OIDCUtils.getLastMetadataError();
            if (metadataError != null) {
                throw new BizException(500, "生成登录链接失败：" + metadataError);
            }
            throw new BizException(500, "生成登录链接失败，请稍后重试或联系管理员");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("login_url", loginUrl);
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
