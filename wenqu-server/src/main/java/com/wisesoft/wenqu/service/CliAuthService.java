package com.wisesoft.wenqu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.APIKey;
import com.wisesoft.wenqu.models.CLIAuthSession;
import com.wisesoft.wenqu.models.Department;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.APIKeyRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.repository.port.CLIAuthSessionMapper;
import com.wisesoft.wenqu.repository.port.DepartmentMapper;
import com.wisesoft.wenqu.repository.port.UserMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CLI 浏览器授权会话。
 *
 * <p>由参考实现的 services/auth_service.py 逐函数翻译：CLI 授权会话的创建、浏览器批准、
 * device_code 换取 API Key（含 consumed 会话的确定性重放）。
 *
 * <p>命名与必要替换：
 * <ul>
 *   <li>类名 {@code CliAuthService}：内容全部是 CLI 授权（参考实现亦如此）；
 *       因本工程 wenqu-server 内已有早期登录脚手架占用 {@code AuthService} 类名
 *       （后续接入参考实现认证栈时归并），此处类名带 Cli 前缀区分。
 *   <li>产品命名：默认 Key 名按本产品命名（WenQu CLI）、device_code 前缀
 *       {@code wqcli_}（参考实现用其产品前缀）；其余键名/状态机照搬。
 *   <li>ORM 会话 → MyBatis-Plus Mapper；{@code with_for_update()} →
 *       {@code FOR UPDATE}（方法级事务内生效）。参考实现在抛出前先 commit「expired」
 *       状态再抛 410；本实现在事务方法内抛错会一并回滚该状态落库，但过期判定按
 *       expires_at 时间推导、每次读取重算，行为等价。
 *   <li>{@code secrets.token_urlsafe(32)} → {@link AuthUtils#tokenUrlSafe}(32)。
 * </ul>
 */
@Service
public class CliAuthService {

    public static final int CLI_AUTH_SESSION_TTL_SECONDS = 10 * 60;
    public static final int CLI_AUTH_POLL_INTERVAL_SECONDS = 2;
    public static final String CLI_AUTH_DEFAULT_KEY_NAME = "WenQu CLI";
    /** 大写字母 + 数字，去除易混淆的 0O1I。 */
    public static final String CLI_AUTH_USER_CODE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".replaceAll("[0O1I]", "");

    public static final String CLI_AUTH_STATUS_PENDING = "pending";
    public static final String CLI_AUTH_STATUS_APPROVED = "approved";
    public static final String CLI_AUTH_STATUS_CONSUMED = "consumed";
    public static final String CLI_AUTH_STATUS_EXPIRED = "expired";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CLIAuthSessionMapper cliAuthSessionMapper;
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final com.wisesoft.wenqu.repository.port.APIKeyMapper apiKeyMapper;
    private final APIKeyRepository apiKeyRepository;

    public CliAuthService(
            CLIAuthSessionMapper cliAuthSessionMapper,
            UserMapper userMapper,
            DepartmentMapper departmentMapper,
            com.wisesoft.wenqu.repository.port.APIKeyMapper apiKeyMapper,
            APIKeyRepository apiKeyRepository) {
        this.cliAuthSessionMapper = cliAuthSessionMapper;
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.apiKeyRepository = apiKeyRepository;
    }

    /** CLI 授权链路的业务异常（code + 用户可读消息 + HTTP 状态码）。 */
    @Getter
    public static class CliAuthError extends RuntimeException {
        private final String code;
        private final int statusCode;

        public CliAuthError(String code, String message, int statusCode) {
            super(message);
            this.code = code;
            this.statusCode = statusCode;
        }

        public CliAuthError(String code, String message) {
            this(code, message, 400);
        }
    }

    /** createCliAuthSession 的返回：持久化会话 + 仅本次可见的 device_code。 */
    public record CreatedSession(CLIAuthSession session, String deviceCode) {}

    private static String hashSecret(String value) {
        return AuthUtils.sha256Hex(value);
    }

    private static String generateDeviceCode() {
        return "wqcli_" + AuthUtils.tokenUrlSafe(32);
    }

    private static String generateUserCode() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            raw.append(CLI_AUTH_USER_CODE_ALPHABET.charAt(RANDOM.nextInt(CLI_AUTH_USER_CODE_ALPHABET.length())));
        }
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    private String generateUniqueUserCode() {
        for (int i = 0; i < 10; i++) {
            String userCode = generateUserCode();
            Long count = cliAuthSessionMapper.selectCount(
                    new LambdaQueryWrapper<CLIAuthSession>().eq(CLIAuthSession::getUserCode, userCode));
            if (count == null || count == 0) {
                return userCode;
            }
        }
        throw new IllegalStateException("无法生成唯一 CLI 授权码");
    }

    private boolean expireIfNeeded(CLIAuthSession session, LocalDateTime now) {
        LocalDateTime current = now != null ? now : DateTimeUtils.utcNowNaive();
        boolean expirable = CLI_AUTH_STATUS_PENDING.equals(session.getStatus())
                || CLI_AUTH_STATUS_APPROVED.equals(session.getStatus());
        if (expirable && !session.getExpiresAt().isAfter(current)) {
            session.setStatus(CLI_AUTH_STATUS_EXPIRED);
            return true;
        }
        return false;
    }

    /** 创建 CLI 授权会话，返回 (session, device_code)。 */
    public CreatedSession createCliAuthSession(String keyName) {
        String deviceCode = generateDeviceCode();
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        CLIAuthSession session = new CLIAuthSession();
        session.setDeviceCodeHash(hashSecret(deviceCode));
        session.setUserCode(generateUniqueUserCode());
        session.setStatus(CLI_AUTH_STATUS_PENDING);
        String trimmedName = keyName == null ? "" : keyName.strip();
        session.setKeyName(trimmedName.isEmpty() ? CLI_AUTH_DEFAULT_KEY_NAME : trimmedName);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(CLI_AUTH_SESSION_TTL_SECONDS, ChronoUnit.SECONDS));
        cliAuthSessionMapper.insert(session);
        return new CreatedSession(session, deviceCode);
    }

    /** 按 user_code 读取会话（可加行锁）；过期时收敛状态并抛 410。 */
    public CLIAuthSession getCliAuthSessionForUser(String userCode, boolean forUpdate) {
        LambdaQueryWrapper<CLIAuthSession> wrapper =
                new LambdaQueryWrapper<CLIAuthSession>().eq(CLIAuthSession::getUserCode, userCode.strip().toUpperCase());
        if (forUpdate) {
            wrapper.last("FOR UPDATE");
        }
        CLIAuthSession session = cliAuthSessionMapper.selectOne(wrapper);
        if (session == null) {
            throw new CliAuthError("not_found", "授权会话不存在", 404);
        }
        if (expireIfNeeded(session, null)) {
            cliAuthSessionMapper.updateById(session);
            throw new CliAuthError("expired_token", "授权会话已过期", 410);
        }
        return session;
    }

    /** 浏览器端批准授权会话。 */
    @Transactional
    public CLIAuthSession approveCliAuthSession(String userCode, User user) {
        CLIAuthSession session = getCliAuthSessionForUser(userCode, true);
        if (CLI_AUTH_STATUS_CONSUMED.equals(session.getStatus())) {
            throw new CliAuthError("already_consumed", "授权会话已完成", 409);
        }
        if (CLI_AUTH_STATUS_APPROVED.equals(session.getStatus())) {
            throw new CliAuthError("already_approved", "授权会话已批准", 409);
        }
        if (!CLI_AUTH_STATUS_PENDING.equals(session.getStatus())) {
            throw new CliAuthError("invalid_state", "授权会话状态无效", 409);
        }

        session.setStatus(CLI_AUTH_STATUS_APPROVED);
        session.setApprovedUserId(user.getId());
        session.setApprovedAt(DateTimeUtils.utcNowNaive());
        cliAuthSessionMapper.updateById(session);
        return session;
    }

    /** 从 consumed 会话确定性重放同一密钥响应，不读取或保存明文。 */
    private Map<String, Object> buildCliExchangeResult(CLIAuthSession session) {
        if (session.getApprovedUserId() == null || session.getApiKeyId() == null) {
            throw new CliAuthError("invalid_state", "授权会话缺少已提交的密钥事实", 409);
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, session.getApprovedUserId())
                .eq(User::getIsDeleted, 0));
        if (user == null) {
            throw new CliAuthError("invalid_user", "授权用户或 API Key 不存在", 409);
        }
        Department department =
                user.getDepartmentId() == null ? null : departmentMapper.selectById(user.getDepartmentId());
        APIKey apiKey = apiKeyMapper.selectById(session.getApiKeyId());
        if (apiKey == null) {
            throw new CliAuthError("invalid_user", "授权用户或 API Key 不存在", 409);
        }
        if (!APIKeyRepository.isValid(apiKey)) {
            throw new CliAuthError("api_key_revoked", "授权会话对应的 API Key 已失效", 409);
        }
        String[] derived = AuthUtils.deriveApiKey("cli-session:" + session.getDeviceCodeHash(), user.getId());
        String fullKey = derived[0];
        String keyHash = derived[1];
        if (!keyHash.equals(apiKey.getKeyHash())) {
            throw new CliAuthError("secret_replay_unavailable", "历史授权密钥无法安全重放", 409);
        }

        Map<String, Object> userData = UserRepository.toDict(user, false);
        userData.put("department_name", department == null ? null : department.getName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_key", APIKeyRepository.toDict(apiKey));
        result.put("secret", fullKey);
        result.put("user", userData);
        return result;
    }

    /** 按 device_code 换取 API Key；consumed 会话在重放窗口内确定性重放。 */
    @Transactional
    public Map<String, Object> exchangeCliAuthToken(String deviceCode) {
        CLIAuthSession session =
                cliAuthSessionMapper.selectOne(new LambdaQueryWrapper<CLIAuthSession>()
                        .eq(CLIAuthSession::getDeviceCodeHash, hashSecret(deviceCode))
                        .last("FOR UPDATE"));
        if (session == null) {
            throw new CliAuthError("invalid_request", "授权会话不存在", 404);
        }
        if (expireIfNeeded(session, null)) {
            cliAuthSessionMapper.updateById(session);
            throw new CliAuthError("expired_token", "授权会话已过期", 410);
        }
        if (CLI_AUTH_STATUS_PENDING.equals(session.getStatus())) {
            throw new CliAuthError("authorization_pending", "等待浏览器授权", 400);
        }
        if (CLI_AUTH_STATUS_CONSUMED.equals(session.getStatus())) {
            if (!session.getExpiresAt().isAfter(DateTimeUtils.utcNowNaive())) {
                throw new CliAuthError("expired_token", "授权结果重放窗口已过期", 410);
            }
            return buildCliExchangeResult(session);
        }
        if (!CLI_AUTH_STATUS_APPROVED.equals(session.getStatus()) || session.getApprovedUserId() == null) {
            throw new CliAuthError("invalid_state", "授权会话状态无效", 409);
        }

        String[] derived =
                AuthUtils.deriveApiKey("cli-session:" + session.getDeviceCodeHash(), session.getApprovedUserId());
        String fullKey = derived[0];
        String keyHash = derived[1];
        String keyPrefix = derived[2];
        APIKey apiKey;
        try {
            apiKey = apiKeyRepository.create(
                    keyHash,
                    keyPrefix,
                    "c:" + session.getDeviceCodeHash().substring(0, Math.min(62, session.getDeviceCodeHash().length())),
                    session.getKeyName(),
                    session.getApprovedUserId(),
                    null,
                    null,
                    String.valueOf(session.getApprovedUserId()));
        } catch (APIKeyRepository.SubjectUnavailableException exc) {
            throw new CliAuthError("invalid_user", "授权用户不存在", 409);
        } catch (APIKeyRepository.IdempotencyConflictException | APIKeyRepository.DepartmentConflictException exc) {
            throw new CliAuthError("invalid_state", "授权会话无法创建 API Key", 409);
        }

        session.setStatus(CLI_AUTH_STATUS_CONSUMED);
        session.setApiKeyId(apiKey.getId());
        session.setConsumedAt(DateTimeUtils.utcNowNaive());
        cliAuthSessionMapper.updateById(session);
        return buildCliExchangeResult(session);
    }

    /**
     * CLIAuthSession.to_dict() 的键与时间格式照搬（参考实现 models_business.CLIAuthSession）。
     *
     * <p>注意：参考实现的路由用 {@code response_model} 过滤响应，实际下发字段由各响应模型决定，
     * 调用方需按响应模型投影。
     */
    public static Map<String, Object> toDict(CLIAuthSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.getId());
        result.put("user_code", session.getUserCode());
        result.put("status", session.getStatus());
        result.put("key_name", session.getKeyName());
        result.put("approved_user_id", session.getApprovedUserId());
        result.put("api_key_id", session.getApiKeyId());
        result.put("created_at", DateTimeUtils.formatUtcDatetime(session.getCreatedAt()));
        result.put("expires_at", DateTimeUtils.formatUtcDatetime(session.getExpiresAt()));
        result.put("approved_at", DateTimeUtils.formatUtcDatetime(session.getApprovedAt()));
        result.put("consumed_at", DateTimeUtils.formatUtcDatetime(session.getConsumedAt()));
        return result;
    }
}
