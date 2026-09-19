package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.MinioUrls;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.CLIAuthSession;
import com.wisesoft.wenqu.models.Department;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.DepartmentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.CliAuthService;
import com.wisesoft.wenqu.service.IdentityAdminService;
import com.wisesoft.wenqu.service.LoginRateLimitService;
import com.wisesoft.wenqu.service.OidcService;
import com.wisesoft.wenqu.service.OperationLogService;
import com.wisesoft.wenqu.service.UserIdentityService;
import com.wisesoft.wenqu.storage.MinioUploads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 认证与用户管理路由，逐端点对齐参考实现 {@code server/routers/auth_router.py}（前缀 {@code /api/auth}）。
 *
 * <p>与既有 {@code AuthController}（{@code /api/ai/auth}，本产品早期登录脚手架契约）并存，互不影响。
 *
 * <h3>响应契约照搬</h3>
 * 参考实现给每个端点声明了 {@code response_model}，FastAPI 会按该模型**投影**响应体
 * （多余键被丢弃、缺失键按默认值补 {@code null}）。本类用显式投影方法复刻这一行为：
 * <ul>
 *   <li>{@link #USER_RESPONSE_KEYS}（UserResponse）：{@code /me}、{@code /users*} 返回 10 个键，
 *       即 {@code to_dict()} 中的登录失败/软删除字段被过滤掉；</li>
 *   <li>{@link #TOKEN_KEYS}（Token / OIDCLoginResponse）：10 个键；</li>
 *   <li>{@link #CLI_SESSION_KEYS}（CLIAuthSessionResponse）6 键、{@link #CLI_APPROVE_KEYS} 3 键。</li>
 * </ul>
 * {@code POST /users} 与 {@code POST /initialize} 返回体缺 {@code department_name} / {@code department_id}，
 * 按参考实现补 {@code null}；{@code POST /initialize} 的 {@code avatar} 参考实现返回**未规范化**的原值
 * （与 {@code /token}、{@code /impersonate} 显式调用 {@code normalize_public_minio_url} 不同），此处照搬。
 *
 * <h3>平台差异（必要替换，均不影响状态码与文案）</h3>
 * <ul>
 *   <li>{@code Depends(get_current_user/get_required_user/get_admin_user/get_superadmin_user)} →
 *       {@link AuthGuards} 的方法开头显式调用；随后按 uid 载入 {@link User}（对应参考实现把 ORM 实体
 *       注入路由）。</li>
 *   <li>{@code request.app...}/ORM 会话 → 构造注入 Mapper/Repository；不再有 {@code db.commit()}，
 *       由 MyBatis-Plus 的单语句事务 + {@code @Transactional} 承担。</li>
 *   <li>pydantic 字段约束（{@code min_length}、{@code ge/le}、{@code max_length}、
 *       {@code extra="forbid"}、{@code Literal}）→ 显式校验并抛 422。参考实现的 422 响应体是结构化
 *       {@code detail} 列表，本工程为文本 detail（平台差异，与既有 GlobalExceptionHandler 一致）。</li>
 *   <li>{@code OAuth2PasswordRequestForm}（form 字段）→ {@code @RequestParam}（username/password）。</li>
 *   <li>密码哈希：参考实现用 argon2，本工程用既有 PBKDF2（见 {@code common/AuthUtils} 的类注解）。
 *       注意：本工程早期脚手架 {@code AuthCrypto} 的存储串前缀不同，两套哈希互不识别。</li>
 *   <li>{@code RedirectResponse} → {@link ResponseEntity} + {@code Location} 头。</li>
 *   <li>{@code extract_client_ip} 已在 {@link LoginRateLimitService} 中移植。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "auth", description = "认证与用户管理")
public class AuthRouterController {

    /** 参考实现 UserCreate/InitializeAdmin 的 uid 规则。 */
    private static final Pattern UID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /** 参考实现 pydantic {@code Field(min_length=8)}。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final Set<String> VALID_ROLES = Set.of("superadmin", "admin", "user");
    private static final Set<String> ACCESS_OPTION_ROLES = Set.of("superadmin", "admin", "user");

    /** UserResponse 的字段集（FastAPI response_model 投影）。 */
    private static final List<String> USER_RESPONSE_KEYS = List.of(
            "id", "username", "uid", "phone_number", "avatar", "role",
            "department_id", "department_name", "created_at", "last_login");

    /** Token / OIDCLoginResponse 的字段集。 */
    private static final List<String> TOKEN_KEYS = List.of(
            "access_token", "token_type", "user_id", "username", "uid",
            "phone_number", "avatar", "role", "department_id", "department_name");

    /** CLIAuthSessionResponse 的字段集。 */
    private static final List<String> CLI_SESSION_KEYS =
            List.of("user_code", "status", "key_name", "created_at", "expires_at", "approved_at");

    /** CLIAuthApproveResponse 的字段集。 */
    private static final List<String> CLI_APPROVE_KEYS = List.of("user_code", "status", "approved_at");

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final LoginRateLimitService loginRateLimitService;
    private final IdentityAdminService identityAdminService;
    private final OperationLogService operationLogService;
    private final CliAuthService cliAuthService;
    private final OidcService oidcService;

    // =========================================================================
    // === 认证分组 ===
    // =========================================================================

    /**
     * 登录获取令牌（参考实现 login_for_access_token）。
     *
     * <p>{@code OAuth2PasswordRequestForm} 的 username 字段即登录标识（uid 优先、手机号兜底）。
     */
    @Operation(summary = "登录获取令牌", description = "form: username(登录标识) / password")
    @PostMapping("/token")
    public Map<String, Object> loginForAccessToken(
            HttpServletRequest request,
            @RequestParam("username") String username,
            @RequestParam("password") String password) {
        String loginIdentifier = username;
        String clientIp = LoginRateLimitService.extractClientIp(request);

        // IP+账号 与 IP 全局滑动窗口失败限速，与账号级锁定叠加
        LoginRateLimitService.RateLimitResult limited =
                loginRateLimitService.checkLoginRateLimit(clientIp, loginIdentifier);
        if (!limited.allowed()) {
            throw new ApiHttpException(
                    429,
                    "登录尝试过于频繁，请稍后再试",
                    Map.of("Retry-After", String.valueOf(limited.retryAfterSeconds())));
        }

        User user = userRepository.getByLoginIdentifier(loginIdentifier);

        // 用户不存在时返回通用错误信息，防止用户名枚举
        if (user == null) {
            loginRateLimitService.recordLoginFailure(clientIp, loginIdentifier);
            throw new ApiHttpException(
                    401, "登录标识或密码错误", Map.of("WWW-Authenticate", "Bearer"));
        }

        if (user.getIsDeleted() != null && user.getIsDeleted() == 1) {
            throw new ApiHttpException(403, "该账户已注销", Map.of("WWW-Authenticate", "Bearer"));
        }

        if (UserRepository.isLoginLocked(user)) {
            int remaining = UserRepository.getRemainingLockTime(user);
            throw new ApiHttpException(
                    423,
                    "登录被锁定，请等待 " + remaining + " 秒后再试",
                    Map.of("WWW-Authenticate", "Bearer", "X-Lock-Remaining", String.valueOf(remaining)));
        }

        // 锁定已过期：清零失败计数，避免解锁后首次失败又立即再次锁定
        if (user.getLoginLockedUntil() != null) {
            UserRepository.resetFailedLogin(user);
            userRepository.saveAllColumns(user);
        }

        if (!AuthUtils.verifyPassword(user.getPasswordHash(), password)) {
            loginRateLimitService.recordLoginFailure(clientIp, loginIdentifier);
            UserRepository.incrementFailedLogin(user);
            userRepository.saveAllColumns(user);

            operationLogService.logOperation(
                    user.getId(),
                    "登录失败",
                    "密码错误，失败次数: " + user.getLoginFailedCount(),
                    request);

            if (UserRepository.isLoginLocked(user)) {
                int remaining = UserRepository.getRemainingLockTime(user);
                throw new ApiHttpException(
                        423,
                        "由于多次登录失败，账户已被锁定 " + remaining + " 秒",
                        Map.of("WWW-Authenticate", "Bearer", "X-Lock-Remaining", String.valueOf(remaining)));
            }
            throw new ApiHttpException(
                    401, "用户名或密码错误", Map.of("WWW-Authenticate", "Bearer"));
        }

        // 登录成功
        UserRepository.resetFailedLogin(user);
        user.setLastLogin(DateTimeUtils.utcNowNaive());
        userRepository.saveAllColumns(user);
        loginRateLimitService.clearLoginFailures(clientIp, loginIdentifier);

        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("sub", String.valueOf(user.getId()));
        String accessToken = AuthUtils.createAccessToken(tokenData, null);

        operationLogService.logOperation(user.getId(), "登录", null, request);

        String departmentName = null;
        if (user.getDepartmentId() != null) {
            departmentName = departmentRepository.getNameById(user.getDepartmentId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", accessToken);
        result.put("token_type", "bearer");
        result.put("user_id", user.getId());
        result.put("username", user.getUsername());
        result.put("uid", user.getUid());
        result.put("phone_number", user.getPhoneNumber());
        result.put("avatar", MinioUrls.normalizePublicMinioUrl(user.getAvatar()));
        result.put("role", user.getRole());
        result.put("department_id", user.getDepartmentId());
        result.put("department_name", departmentName);
        return project(result, TOKEN_KEYS);
    }

    // =========================================================================
    // === CLI 浏览器登录授权分组 ===
    // =========================================================================

    /** 创建 CLI 授权会话（参考实现 create_cli_session）。 */
    @Operation(summary = "创建 CLI 授权会话", description = "body: key_name?")
    @PostMapping("/cli/sessions")
    public Map<String, Object> createCliSession(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String keyName = asString(payload.get("key_name"));
        if (keyName != null && keyName.length() > 100) {
            throw new ApiHttpException(422, "key_name 长度不能超过 100");
        }
        CliAuthService.CreatedSession created = cliAuthService.createCliAuthSession(keyName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device_code", created.deviceCode());
        result.put("user_code", created.session().getUserCode());
        result.put("verification_uri", "/auth/cli/authorize");
        result.put("expires_in", CliAuthService.CLI_AUTH_SESSION_TTL_SECONDS);
        result.put("interval", CliAuthService.CLI_AUTH_POLL_INTERVAL_SECONDS);
        return result;
    }

    /** 查询 CLI 授权会话（参考实现 get_cli_session）。 */
    @Operation(summary = "查询 CLI 授权会话")
    @GetMapping("/cli/sessions/{user_code}")
    public Map<String, Object> getCliSession(@PathVariable("user_code") String userCode) {
        AuthGuards.requireUser();
        CLIAuthSession session;
        try {
            session = cliAuthService.getCliAuthSessionForUser(userCode, false);
        } catch (CliAuthService.CliAuthError exc) {
            throw cliAuthError(exc);
        }
        return project(CliAuthService.toDict(session), CLI_SESSION_KEYS);
    }

    /** 批准 CLI 授权会话（参考实现 approve_cli_session）。 */
    @Operation(summary = "批准 CLI 授权会话")
    @PostMapping("/cli/sessions/{user_code}/approve")
    public Map<String, Object> approveCliSession(@PathVariable("user_code") String userCode) {
        User currentUser = currentUser();
        CLIAuthSession session;
        try {
            session = cliAuthService.approveCliAuthSession(userCode, currentUser);
        } catch (CliAuthService.CliAuthError exc) {
            throw cliAuthError(exc);
        }
        return project(CliAuthService.toDict(session), CLI_APPROVE_KEYS);
    }

    /** 用 device_code 换取 API Key（参考实现 exchange_cli_session_token）。 */
    @Operation(summary = "CLI 换取 API Key", description = "body: device_code")
    @PostMapping("/cli/sessions/token")
    public Map<String, Object> exchangeCliSessionToken(@RequestBody Map<String, Object> body) {
        String deviceCode = asString(body == null ? null : body.get("device_code"));
        if (deviceCode == null) {
            throw new ApiHttpException(422, "device_code 不能为空");
        }
        try {
            return cliAuthService.exchangeCliAuthToken(deviceCode);
        } catch (CliAuthService.CliAuthError exc) {
            throw cliAuthError(exc);
        }
    }

    /** 校验是否需要初始化管理员（参考实现 check_first_run）。 */
    @Operation(summary = "是否首次运行")
    @GetMapping("/check-first-run")
    public Map<String, Object> checkFirstRun() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("first_run", userRepository.isFirstRun());
        return result;
    }

    /** 初始化管理员账户（参考实现 initialize_admin）。 */
    @Operation(summary = "初始化管理员", description = "body: uid / password / phone_number?")
    @PostMapping("/initialize")
    public Map<String, Object> initializeAdmin(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String uid = asString(payload.get("uid"));
        String password = asString(payload.get("password"));
        String phoneNumber = asString(payload.get("phone_number"));

        if (uid == null || !UID_PATTERN.matcher(uid).matches()) {
            throw new ApiHttpException(400, "用户ID只能包含字母、数字和下划线");
        }
        if (uid.length() < 3 || uid.length() > 20) {
            throw new ApiHttpException(400, "用户ID长度必须在3-20个字符之间");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiHttpException(422, "password 长度不能少于 8 个字符");
        }
        if (phoneNumber != null && !phoneNumber.isEmpty()
                && !UserIdentityService.isValidPhoneNumber(phoneNumber)) {
            throw new ApiHttpException(400, "手机号格式不正确");
        }

        IdentityAdminService.DepartmentAdminCreation created;
        try {
            created = identityAdminService.initializeSystemAdmin(uid, password, phoneNumber);
        } catch (IdentityAdminService.SystemAlreadyInitializedError exc) {
            throw new ApiHttpException(403, exc.getMessage());
        } catch (IdentityAdminService.IdentityConflictError exc) {
            throw new ApiHttpException(409, exc.getMessage());
        }

        User newAdmin = created.admin();
        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("sub", String.valueOf(newAdmin.getId()));
        String accessToken = AuthUtils.createAccessToken(tokenData, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", accessToken);
        result.put("token_type", "bearer");
        result.put("user_id", newAdmin.getId());
        result.put("username", newAdmin.getUsername());
        result.put("uid", newAdmin.getUid());
        result.put("phone_number", newAdmin.getPhoneNumber());
        // 参考实现此处返回未规范化的 avatar 原值
        result.put("avatar", newAdmin.getAvatar());
        result.put("role", newAdmin.getRole());
        return project(result, TOKEN_KEYS);
    }

    // =========================================================================
    // === 用户信息分组 ===
    // =========================================================================

    /** 获取当前登录用户信息（参考实现 read_users_me）。 */
    @Operation(summary = "当前用户信息")
    @GetMapping("/me")
    public Map<String, Object> readUsersMe() {
        User currentUser = currentUser();
        Map<String, Object> userDict = UserRepository.toDict(currentUser, false);
        if (currentUser.getDepartmentId() != null) {
            userDict.put("department_name",
                    departmentRepository.getNameById(currentUser.getDepartmentId()));
        }
        return project(userDict, USER_RESPONSE_KEYS);
    }

    /** 更新当前用户个人资料（参考实现 update_profile）。 */
    @Operation(summary = "更新个人资料", description = "body: username? / phone_number?")
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        User currentUser = currentUser();
        List<String> updateDetails = new ArrayList<>();

        if (payload.get("username") != null) {
            String username = asString(payload.get("username"));
            UserIdentityService.UsernameValidation validation =
                    UserIdentityService.validateUsername(username);
            if (!validation.valid()) {
                throw new ApiHttpException(400, validation.message());
            }
            if (userRepository.getByUsername(username, currentUser.getId()) != null) {
                throw new ApiHttpException(400, "用户名已存在");
            }
            currentUser.setUsername(username);
            updateDetails.add("用户名: " + username);
        }

        if (payload.get("phone_number") != null) {
            String phoneNumber = asString(payload.get("phone_number"));
            if (phoneNumber != null && !phoneNumber.isEmpty()
                    && !UserIdentityService.isValidPhoneNumber(phoneNumber)) {
                throw new ApiHttpException(400, "手机号格式不正确");
            }
            if (phoneNumber != null && !phoneNumber.isEmpty()
                    && userRepository.getByPhoneExcluding(phoneNumber, currentUser.getId()) != null) {
                throw new ApiHttpException(400, "手机号已被其他用户使用");
            }
            currentUser.setPhoneNumber(phoneNumber);
            updateDetails.add("手机号: " + (phoneNumber == null || phoneNumber.isEmpty() ? "已清空" : phoneNumber));
        }

        userRepository.saveAllColumns(currentUser);

        if (!updateDetails.isEmpty()) {
            operationLogService.logOperation(
                    currentUser.getId(),
                    "更新个人资料",
                    "更新个人资料: " + String.join(", ", updateDetails),
                    request);
        }
        return project(UserRepository.toDict(currentUser, false), USER_RESPONSE_KEYS);
    }

    // =========================================================================
    // === 用户管理分组 ===
    // =========================================================================

    /** 创建新用户（参考实现 create_user，管理员权限）。 */
    @Operation(summary = "创建用户（管理员）", description = "body: username / password / role? / phone_number? / department_id?")
    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        User currentUser = currentUserAdmin();

        String username = asString(payload.get("username"));
        String password = asString(payload.get("password"));
        String role = payload.get("role") == null ? "user" : rawString(payload.get("role"));
        String phoneNumber = asString(payload.get("phone_number"));
        Integer departmentIdInput = asInteger(payload.get("department_id"));

        if (username == null) {
            throw new ApiHttpException(422, "username 不能为空");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiHttpException(422, "password 长度不能少于 8 个字符");
        }

        UserIdentityService.UsernameValidation validation = UserIdentityService.validateUsername(username);
        if (!validation.valid()) {
            throw new ApiHttpException(400, validation.message());
        }

        List<User> users = userRepository.listUsers(0, 100, null, null);
        for (User candidate : users) {
            if (username.equals(candidate.getUsername())) {
                throw new ApiHttpException(400, "用户名已存在");
            }
        }

        if (phoneNumber != null && !phoneNumber.isEmpty() && userRepository.existsByPhone(phoneNumber)) {
            throw new ApiHttpException(400, "手机号已存在");
        }

        String uid = UserIdentityService.generateUniqueUid(
                username, new LinkedHashSet<>(userRepository.getAllUids()));
        String hashedPassword = AuthUtils.hashPassword(password);

        if ("superadmin".equals(role)) {
            throw new ApiHttpException(400, "不能创建超级管理员账户");
        }
        if ("admin".equals(currentUser.getRole()) && !"user".equals(role)) {
            throw new ApiHttpException(403, "管理员只能创建普通用户账户");
        }

        Integer departmentId;
        if ("superadmin".equals(currentUser.getRole())) {
            departmentId = departmentIdInput;
            if (departmentId == null) {
                departmentId = defaultDepartmentId();
            }
        } else {
            departmentId = currentUser.getDepartmentId();
            if (departmentId == null) {
                throw new ApiHttpException(400, "管理员必须属于部门才能创建用户");
            }
            if (departmentIdInput != null) {
                throw new ApiHttpException(403, "普通管理员不能指定部门");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        data.put("uid", uid);
        data.put("phone_number", phoneNumber);
        data.put("password_hash", hashedPassword);
        data.put("role", role);
        data.put("department_id", departmentId);
        User newUser = userRepository.create(data);

        operationLogService.logOperation(
                currentUser.getId(), "创建用户", "创建用户: " + username + ", 角色: " + role, request);

        return project(UserRepository.toDict(newUser, false), USER_RESPONSE_KEYS);
    }

    /** 分页查询当前管理员可管理的有效用户（参考实现 read_users_page）。 */
    @Operation(summary = "用户分页（管理员）")
    @GetMapping("/users/page")
    public Map<String, Object> readUsersPage(
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "department_id", required = false) Integer departmentId,
            @RequestParam(value = "role", required = false) String role) {
        User currentUser = currentUserAdmin();

        if (offset < 0) {
            throw new ApiHttpException(422, "offset 不能小于 0");
        }
        if (limit < 1 || limit > 100) {
            throw new ApiHttpException(422, "limit 必须在 1-100 之间");
        }
        if (search != null && search.length() > 100) {
            throw new ApiHttpException(422, "search 长度不能超过 100");
        }
        if (departmentId != null && departmentId < 1) {
            throw new ApiHttpException(422, "department_id 不能小于 1");
        }
        if (role != null && !VALID_ROLES.contains(role)) {
            throw new ApiHttpException(422, "role 取值必须是 superadmin/admin/user 之一");
        }

        Map<String, Object> page = identityAdminService.listManagedUsersPage(
                offset,
                limit,
                "superadmin".equals(currentUser.getRole()),
                currentUser.getDepartmentId(),
                departmentId,
                role,
                search == null ? null : search.strip());

        // 响应模型（UserPageResponse.items: list[UserResponse]）逐项投影
        Object itemsRaw = page.get("items");
        if (itemsRaw instanceof List<?> items) {
            List<Map<String, Object>> projected = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) map;
                    projected.add(project(row, USER_RESPONSE_KEYS));
                }
            }
            page.put("items", projected);
        }
        return page;
    }

    /** 获取用户列表（参考实现 read_users，管理员权限）。 */
    @Operation(summary = "用户列表（管理员）")
    @GetMapping("/users")
    public List<Map<String, Object>> readUsers(
            @RequestParam(value = "skip", defaultValue = "0") int skip,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        User currentUser = currentUserAdmin();
        List<Map<String, Object>> rows = "superadmin".equals(currentUser.getRole())
                ? userRepository.listWithDepartment(skip, limit, null, null)
                : userRepository.listWithDepartment(skip, limit, currentUser.getDepartmentId(), null);
        return projectUserRows(rows);
    }

    /** 获取用户访问选项（参考实现 read_user_access_options，管理员权限）。 */
    @Operation(summary = "用户访问选项（管理员）")
    @GetMapping("/users/access-options")
    public List<Map<String, Object>> readUserAccessOptions(
            @RequestParam(value = "skip", defaultValue = "0") int skip,
            @RequestParam(value = "limit", defaultValue = "1000") int limit) {
        User currentUser = currentUserAdmin();
        List<Map<String, Object>> rows = "superadmin".equals(currentUser.getRole())
                ? userRepository.listWithDepartment(skip, limit, null, null)
                : userRepository.listWithDepartment(skip, limit, currentUser.getDepartmentId(), null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("uid", row.get("uid"));
            option.put("username", row.get("username"));
            option.put("role", row.get("role"));
            option.put("department_id", row.get("department_id"));
            option.put("department_name", row.get("department_name"));
            result.add(option);
        }
        return result;
    }

    /** 获取特定用户信息（参考实现 read_user，管理员权限）。 */
    @Operation(summary = "用户详情（管理员）")
    @GetMapping("/users/{user_id}")
    public Map<String, Object> readUser(@PathVariable("user_id") Integer userId) {
        User currentUser = currentUserAdmin();
        User user = userRepository.getActiveById(userId, false);
        if (user == null) {
            throw new ApiHttpException(404, "用户不存在");
        }
        ensureUserInCurrentDepartment(currentUser, user);
        return project(UserRepository.toDict(user, false), USER_RESPONSE_KEYS);
    }

    /** 更新用户信息（参考实现 update_user，管理员权限）。 */
    @Operation(summary = "更新用户（管理员）", description = "body: username? / password? / phone_number? / avatar? / department_id?")
    @PutMapping("/users/{user_id}")
    public Map<String, Object> updateUser(
            @PathVariable("user_id") Integer userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        forbidExtraKeys(payload,
                Set.of("username", "password", "phone_number", "avatar", "department_id"), "UserUpdate");
        User currentUser = currentUserAdmin();

        User user = userRepository.getActiveById(userId, false);
        if (user == null) {
            throw new ApiHttpException(404, "用户不存在");
        }

        ensureUserInCurrentDepartment(currentUser, user);

        if ("superadmin".equals(user.getRole()) && !"superadmin".equals(currentUser.getRole())) {
            throw new ApiHttpException(403, "只有超级管理员才能修改超级管理员账户");
        }
        if ("admin".equals(currentUser.getRole()) && !"user".equals(user.getRole())) {
            throw new ApiHttpException(403, "管理员只能修改普通用户账户");
        }

        List<String> updateDetails = new ArrayList<>();

        if (payload.get("username") != null) {
            String username = asString(payload.get("username"));
            if (userRepository.getByUsername(username, userId) != null) {
                throw new ApiHttpException(400, "用户名已存在");
            }
            user.setUsername(username);
            updateDetails.add("用户名: " + username);
        }

        if (payload.get("password") != null) {
            String password = asString(payload.get("password"));
            if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
                throw new ApiHttpException(422, "password 长度不能少于 8 个字符");
            }
            user.setPasswordHash(AuthUtils.hashPassword(password));
            updateDetails.add("密码已更新");
        }

        if (payload.get("phone_number") != null) {
            String phoneNumber = rawString(payload.get("phone_number"));
            user.setPhoneNumber(phoneNumber);
            updateDetails.add("手机号: " + (phoneNumber == null || phoneNumber.isEmpty() ? "已清空" : phoneNumber));
        }

        if (payload.get("avatar") != null) {
            String avatar = rawString(payload.get("avatar"));
            user.setAvatar(avatar);
            updateDetails.add("头像: " + (avatar == null || avatar.isEmpty() ? "已清空" : avatar));
        }

        Integer departmentIdInput = asInteger(payload.get("department_id"));
        if (departmentIdInput != null
                && !departmentIdInput.equals(user.getDepartmentId())) {
            if (!"superadmin".equals(currentUser.getRole())) {
                throw new ApiHttpException(403, "只有超级管理员才能修改用户部门");
            }
            if ("admin".equals(user.getRole()) && user.getDepartmentId() != null) {
                int adminCount =
                        userRepository.getAdminCountInDepartment(user.getDepartmentId(), userId);
                if (adminCount <= 1) {
                    throw new ApiHttpException(400, "不能修改该用户的部门，因为该用户是当前部门的唯一管理员");
                }
            }
            user.setDepartmentId(departmentIdInput);
            updateDetails.add("部门ID: " + departmentIdInput);
        }

        userRepository.saveAllColumns(user);

        operationLogService.logOperation(
                currentUser.getId(),
                "更新用户",
                "更新用户ID " + userId + ": " + String.join(", ", updateDetails),
                request);

        return project(UserRepository.toDict(user, false), USER_RESPONSE_KEYS);
    }

    /** 删除用户（参考实现 delete_user，管理员权限）。 */
    @Operation(summary = "删除用户（管理员）")
    @DeleteMapping("/users/{user_id}")
    public Map<String, Object> deleteUser(@PathVariable("user_id") Integer userId, HttpServletRequest request) {
        User currentUser = currentUserAdmin();
        User user = userRepository.getActiveById(userId, true);
        if (user == null) {
            throw new ApiHttpException(404, "用户不存在");
        }

        ensureUserInCurrentDepartment(currentUser, user);

        if ("superadmin".equals(user.getRole())) {
            throw new ApiHttpException(400, "不能删除超级管理员账户");
        }
        if ("admin".equals(currentUser.getRole()) && !"user".equals(user.getRole())) {
            throw new ApiHttpException(403, "管理员只能删除普通用户账户");
        }
        if ("admin".equals(user.getRole()) && !"superadmin".equals(currentUser.getRole())) {
            int adminCount = userRepository.getAdminCountInDepartment(user.getDepartmentId(), null);
            if (adminCount <= 1) {
                throw new ApiHttpException(400, "不能删除部门唯一的管理员");
            }
        }
        if (user.getId().equals(currentUser.getId())) {
            throw new ApiHttpException(400, "不能删除自己的账户");
        }
        if (user.getIsDeleted() != null && user.getIsDeleted() == 1) {
            throw new ApiHttpException(400, "该用户已经被删除");
        }

        String deletionDetail =
                "删除用户: " + user.getUsername() + ", ID: " + user.getId() + ", 角色: " + user.getRole();

        userRepository.deleteForAdmin(user);

        operationLogService.logOperation(currentUser.getId(), "删除用户", deletionDetail, request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "用户已删除");
        return result;
    }

    /** 验证用户名并生成 uid（参考实现 validate_username_and_generate_uid）。 */
    @Operation(summary = "校验用户名并生成 uid")
    @PostMapping("/validate-username")
    public Map<String, Object> validateUsernameAndGenerateUid(@RequestBody Map<String, Object> body) {
        currentUserAdmin();
        String username = asString(body == null ? null : body.get("username"));
        if (username == null) {
            throw new ApiHttpException(422, "username 不能为空");
        }

        UserIdentityService.UsernameValidation validation = UserIdentityService.validateUsername(username);
        if (!validation.valid()) {
            throw new ApiHttpException(400, validation.message());
        }

        if (userRepository.getByUsername(username, null) != null) {
            throw new ApiHttpException(400, "用户名已存在");
        }

        String uid = UserIdentityService.generateUniqueUid(
                username, new LinkedHashSet<>(userRepository.getAllUids()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("uid", uid);
        result.put("is_available", true);
        return result;
    }

    /** 检查 uid 是否可用（参考实现 check_uid_availability）。 */
    @Operation(summary = "检查 uid 是否可用")
    @GetMapping("/check-uid/{uid}")
    public Map<String, Object> checkUidAvailability(@PathVariable("uid") String uid) {
        currentUserAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid);
        result.put("is_available", !userRepository.existsByUid(uid));
        return result;
    }

    /** 上传用户头像（参考实现 upload_user_avatar）。 */
    @Operation(summary = "上传头像", description = "multipart: file")
    @PostMapping("/upload-avatar")
    public Map<String, Object> uploadUserAvatar(@RequestParam("file") MultipartFile file) {
        User currentUser = currentUser();
        try {
            String avatarUrl = MinioUploads.uploadImageToMinio(
                    file,
                    "avatar/" + currentUser.getId(),
                    5L * 1024 * 1024,
                    "文件大小不能超过5MB");

            currentUser.setAvatar(avatarUrl);
            userRepository.saveAllColumns(currentUser);
            operationLogService.logOperation(
                    currentUser.getId(), "上传头像", "更新头像: " + avatarUrl, null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("avatar_url", avatarUrl);
            result.put("message", "头像上传成功");
            return result;
        } catch (MinioUploads.ValueErrorException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (RuntimeException exc) {
            throw new ApiHttpException(500, "头像上传失败: " + exc.getMessage());
        }
    }

    /** 模拟用户登录（参考实现 impersonate_user，超级管理员专用）。 */
    @Operation(summary = "模拟用户登录（超级管理员）")
    @PostMapping("/impersonate/{user_id}")
    public Map<String, Object> impersonateUser(
            @PathVariable("user_id") Integer userId, HttpServletRequest request) {
        User currentUser = currentUserSuperadmin();
        User targetUser = userRepository.getActiveById(userId, false);
        if (targetUser == null) {
            throw new ApiHttpException(404, "用户不存在");
        }
        if ("superadmin".equals(targetUser.getRole())) {
            throw new ApiHttpException(403, "不能模拟超级管理员账户");
        }

        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("sub", String.valueOf(targetUser.getId()));
        String accessToken = AuthUtils.createAccessToken(tokenData, null);

        String departmentName = null;
        if (targetUser.getDepartmentId() != null) {
            departmentName = departmentRepository.getNameById(targetUser.getDepartmentId());
        }

        // 危险操作标记，审计不可省略
        operationLogService.logOperation(
                currentUser.getId(),
                "⚠️ 危险操作-模拟用户",
                "模拟用户: " + targetUser.getUsername(),
                request);
        log.warn("⚠️ [危险操作] 超级管理员 {} 模拟登录用户: {}",
                currentUser.getUsername(), targetUser.getUsername());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", accessToken);
        result.put("token_type", "bearer");
        result.put("user_id", targetUser.getId());
        result.put("username", targetUser.getUsername());
        result.put("uid", targetUser.getUid());
        result.put("phone_number", targetUser.getPhoneNumber());
        result.put("avatar", MinioUrls.normalizePublicMinioUrl(targetUser.getAvatar()));
        result.put("role", targetUser.getRole());
        result.put("department_id", targetUser.getDepartmentId());
        result.put("department_name", departmentName);
        return project(result, TOKEN_KEYS);
    }

    // =========================================================================
    // === OIDC 认证分组 ===
    // =========================================================================

    /** 获取 OIDC 配置（参考实现 get_oidc_config）。 */
    @Operation(summary = "OIDC 配置")
    @GetMapping("/oidc/config")
    public Map<String, Object> getOidcConfig() {
        Map<String, Object> raw = oidcService.getOidcConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", raw.get("enabled"));
        result.put("login_url", raw.get("login_url"));
        result.put("provider_name", raw.get("provider_name") == null ? "OIDC登录" : raw.get("provider_name"));
        return result;
    }

    /** 获取 OIDC 登录 URL（参考实现 get_oidc_login_url）。 */
    @Operation(summary = "OIDC 登录 URL")
    @GetMapping("/oidc/login-url")
    public Map<String, Object> getOidcLoginUrl(
            @RequestParam(value = "redirect_path", defaultValue = "/") String redirectPath) {
        return oidcService.oidcLoginUrl(redirectPath);
    }

    /** 处理 OIDC 回调并重定向到前端 Vue 路由（参考实现 oidc_callback）。 */
    @Operation(summary = "OIDC 回调")
    @GetMapping("/oidc/callback")
    public ResponseEntity<Void> oidcCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest request) {
        OidcService.RedirectResponse redirect = oidcService.oidcCallback(code, state, request);
        return ResponseEntity.status(redirect.statusCode())
                .header(HttpHeaders.LOCATION, redirect.url())
                .build();
    }

    /** 用一次性 code 交换 OIDC 登录数据（参考实现 oidc_exchange_code）。 */
    @Operation(summary = "OIDC code 换登录数据", description = "body: code")
    @PostMapping("/oidc/exchange-code")
    public Map<String, Object> oidcExchangeCode(@RequestBody Map<String, Object> body) {
        String code = asString(body == null ? null : body.get("code"));
        if (code == null) {
            throw new ApiHttpException(422, "code 不能为空");
        }
        return project(oidcService.oidcExchangeCode(code), TOKEN_KEYS);
    }

    // =========================================================================
    // === 内部工具 ===
    // =========================================================================

    private ApiHttpException cliAuthError(CliAuthService.CliAuthError exc) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("error", exc.getCode());
        detail.put("message", exc.getMessage());
        // 参考实现 detail 为 {"error": code, "message": message}
        return new ApiHttpException(exc.getStatusCode(), String.valueOf(detail));
    }

    /** 参考实现 get_required_user：当前用户实体。 */
    private User currentUser() {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** 参考实现 get_admin_user。 */
    private User currentUserAdmin() {
        String uid = AuthGuards.requireAdmin();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** 参考实现 get_superadmin_user。 */
    private User currentUserSuperadmin() {
        String uid = AuthGuards.requireSuperadmin();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** 参考实现 _ensure_user_in_current_department。 */
    private static void ensureUserInCurrentDepartment(User currentUser, User targetUser) {
        if ("superadmin".equals(currentUser.getRole())) {
            return;
        }
        if (!java.util.Objects.equals(targetUser.getDepartmentId(), currentUser.getDepartmentId())) {
            throw new ApiHttpException(403, "只能管理本部门用户");
        }
    }

    /** 参考实现 create_user 中「超级管理员未指定部门时落到默认部门」。 */
    private Integer defaultDepartmentId() {
        for (Department department : departmentRepository.listDepartments()) {
            if ("默认部门".equals(department.getName())) {
                return department.getId();
            }
        }
        return null;
    }

    /** 把 list_with_department 的联表行投影为 UserResponse 列表（逐行 {@code user.to_dict()} + department_name）。 */
    private static List<Map<String, Object>> projectUserRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> userDict = IdentityAdminService.userDictFromRow(row);
            userDict.put("department_name", row.get("department_name"));
            result.add(project(userDict, USER_RESPONSE_KEYS));
        }
        return result;
    }

    /**
     * FastAPI {@code response_model} 投影：按声明顺序取出允许的键，缺失键补 {@code null}，
     * 其余键丢弃。用于复刻参考实现「响应被响应模型裁剪」的行为。
     */
    private static Map<String, Object> project(Map<String, Object> source, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, source.get(key));
        }
        return result;
    }

    /** pydantic {@code extra="forbid"} 的对等实现。 */
    private static void forbidExtraKeys(Map<String, Object> body, Set<String> allowed, String modelName) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw new ApiHttpException(422, modelName + " 不允许多余字段: " + key);
            }
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    /**
     * 原样保留空串的字符串化。
     *
     * <p>参考实现把 {@code phone_number=""}/{@code avatar=""} 原样写入列（清空为**空串**而非 NULL），
     * 故这两类字段不能走 {@link #asString}（它把 "" 归一成 null）。
     */
    private static String rawString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException exc) {
            throw new ApiHttpException(422, "字段值必须是整数: " + value);
        }
    }
}
