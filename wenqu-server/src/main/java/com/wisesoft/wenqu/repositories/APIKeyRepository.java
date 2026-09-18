package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.APIKey;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repository.port.APIKeyMapper;
import com.wisesoft.wenqu.repository.port.UserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * API Key 仓储。
 *
 * <p>由参考实现的 repositories/api_key_repository.py 逐方法翻译：可见性查询（超管可见全部、
 * 其余只看自己）、区分"不存在"与"无权访问"、按幂等请求 ID 创建或重放同一事实（含意图哈希
 * 与历史行的向后兼容判定）、撤销时保留幂等墓碑。
 *
 * <p>必要替换：
 * <ul>
 *   <li>幂等锁：参考实现用 PostgreSQL 的事务级建议锁 {@code pg_advisory_xact_lock(hashtext(scope))}；
 *       MySQL 无事务级建议锁，改用命名锁 {@code GET_LOCK}/{@code RELEASE_LOCK}（会话级），
 *       在本方法事务内成对释放。
 *   <li>意图哈希的 JSON 序列化：参考实现用 {@code json.dumps(..., sort_keys=True, separators=(",",":"))}
 *       再 SHA-256 —— 本层按同样的键序与紧凑分隔符构造（TreeMap + 手写紧凑序列化），
 *       保证同一意图在不同实现上得到相同哈希。
 *   <li>行锁 {@code with_for_update()} → {@code FOR UPDATE}，方法加 {@code @Transactional}。
 * </ul>
 *
 * <p>三类冲突以专用异常表达（对应参考实现的三个异常类）：
 * {@link IdempotencyConflictException}、{@link SubjectUnavailableException}、
 * {@link DepartmentConflictException}。
 */
@Repository
public class APIKeyRepository {

    /** 与 Python datetime.isoformat() 对齐的时间格式。 */
    private static final DateTimeFormatter EXPIRES_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final APIKeyMapper apiKeyMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbc;

    public APIKeyRepository(APIKeyMapper apiKeyMapper, UserMapper userMapper, JdbcTemplate jdbc) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
        this.jdbc = jdbc;
    }

    /** 同一幂等请求 ID 被用于不同的 API Key 创建意图。 */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    /** API Key 关联用户不存在或已删除。 */
    public static class SubjectUnavailableException extends RuntimeException {
        public SubjectUnavailableException(String message) {
            super(message);
        }
    }

    /** API Key 部门与关联用户当前部门不一致。 */
    public static class DepartmentConflictException extends RuntimeException {
        public DepartmentConflictException(String message) {
            super(message);
        }
    }

    /** 带存在性信息的可见性查询结果。 */
    public record AccessResult(APIKey apiKey, boolean exists) {}

    /** 稳定标识原始创建意图，不受资源后续可变字段影响。 */
    static String intentHash(
            String name, Integer userId, Integer departmentId, LocalDateTime expiresAt, String createdBy) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("name", name);
        payload.put("user_id", userId);
        payload.put("department_id", departmentId);
        // 与 Python datetime.isoformat() 对齐：固定到秒（Java 的 ISO_LOCAL_DATE_TIME 会在秒为 0 时省略秒）
        payload.put("expires_at", expiresAt == null ? null : expiresAt.format(EXPIRES_AT_FORMAT));
        payload.put("created_by", createdBy);
        return sha256Hex(compactJson(payload));
    }

    /** 按参考实现的 dumps 参数（排序键 + 无空格分隔符）生成紧凑 JSON。 */
    static String compactJson(Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(JSON.toJSONString(entry.getKey())).append(':');
            builder.append(entry.getValue() == null ? "null" : JSON.toJSONString(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    /**
     * APIKey.to_dict() 的键与时间格式照搬（参考实现 models_business.APIKey.to_dict）。
     *
     * <p>注意：不包含 key_hash 等机密列，与参考实现一致。
     */
    public static Map<String, Object> toDict(APIKey apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", apiKey.getId());
        result.put("key_prefix", apiKey.getKeyPrefix());
        result.put("name", apiKey.getName());
        result.put("user_id", apiKey.getUserId());
        result.put("department_id", apiKey.getDepartmentId());
        result.put("expires_at", DateTimeUtils.formatUtcDatetime(apiKey.getExpiresAt()));
        result.put("is_enabled", Boolean.TRUE.equals(apiKey.getIsEnabled()));
        result.put("last_used_at", DateTimeUtils.formatUtcDatetime(apiKey.getLastUsedAt()));
        result.put("created_by", apiKey.getCreatedBy());
        result.put("created_at", DateTimeUtils.formatUtcDatetime(apiKey.getCreatedAt()));
        return result;
    }

    /** APIKey.is_valid()：启用、未撤销、未过期。 */
    public static boolean isValid(APIKey apiKey) {
        if (!Boolean.TRUE.equals(apiKey.getIsEnabled())) {
            return false;
        }
        if (apiKey.getRevokedAt() != null) {
            return false;
        }
        if (apiKey.getExpiresAt() != null && DateTimeUtils.utcNowNaive().isAfter(apiKey.getExpiresAt())) {
            return false;
        }
        return true;
    }

    /** 列出请求者可见的 API Key 并返回总数（已撤销的不列出）。 */    public Map<String, Object> listVisible(int requesterUserId, boolean isSuperadmin, int skip, int limit) {
        LambdaQueryWrapper<APIKey> wrapper = new LambdaQueryWrapper<APIKey>().isNull(APIKey::getRevokedAt);
        if (!isSuperadmin) {
            wrapper.eq(APIKey::getUserId, requesterUserId);
        }
        Long total = apiKeyMapper.selectCount(wrapper);
        wrapper.orderByDesc(APIKey::getCreatedAt).last("LIMIT " + limit + " OFFSET " + skip);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", apiKeyMapper.selectList(wrapper));
        result.put("total", total == null ? 0L : total);
        return result;
    }

    /** 按请求者可见性读取 API Key，并区分不存在与无权访问。 */
    public AccessResult getAccessible(int apiKeyId, int requesterUserId, boolean isSuperadmin) {
        LambdaQueryWrapper<APIKey> wrapper =
                new LambdaQueryWrapper<APIKey>().eq(APIKey::getId, apiKeyId).isNull(APIKey::getRevokedAt);
        if (!isSuperadmin) {
            wrapper.eq(APIKey::getUserId, requesterUserId);
        }
        APIKey apiKey = apiKeyMapper.selectOne(wrapper);
        if (apiKey != null) {
            return new AccessResult(apiKey, true);
        }
        Long exists =
                apiKeyMapper.selectCount(
                        new LambdaQueryWrapper<APIKey>().eq(APIKey::getId, apiKeyId).isNull(APIKey::getRevokedAt));
        return new AccessResult(null, exists != null && exists > 0);
    }

    /** 在幂等锁内创建或重放同一 API Key 事实。 */
    @Transactional
    public APIKey create(
            String keyHash,
            String keyPrefix,
            String requestId,
            String name,
            Integer userId,
            Integer departmentId,
            LocalDateTime expiresAt,
            String createdBy) {
        String lockName = "wenqu:api-key:" + requestId;
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, lockName);
        boolean held = acquired != null && acquired == 1;
        try {
            User subject =
                    userMapper.selectOne(
                            new LambdaQueryWrapper<User>()
                                    .eq(User::getId, userId)
                                    .eq(User::getIsDeleted, 0)
                                    .last("FOR UPDATE"));
            if (subject == null) {
                throw new SubjectUnavailableException("关联的用户不存在");
            }
            if (departmentId != null && !departmentId.equals(subject.getDepartmentId())) {
                throw new DepartmentConflictException("API Key 部门必须与关联用户部门一致");
            }

            String intentHash = intentHash(name, userId, departmentId, expiresAt, createdBy);
            APIKey existing =
                    apiKeyMapper.selectOne(new LambdaQueryWrapper<APIKey>().eq(APIKey::getRequestId, requestId));
            if (existing != null) {
                boolean expected = intentHash.equals(existing.getIntentHash());
                if (existing.getIntentHash() == null) {
                    expected =
                            java.util.Objects.equals(existing.getKeyHash(), keyHash)
                                    && java.util.Objects.equals(existing.getName(), name)
                                    && java.util.Objects.equals(existing.getUserId(), userId)
                                    && java.util.Objects.equals(existing.getDepartmentId(), departmentId)
                                    && java.util.Objects.equals(existing.getExpiresAt(), expiresAt)
                                    && java.util.Objects.equals(existing.getCreatedBy(), createdBy);
                    if (expected) {
                        apiKeyMapper.update(
                                null,
                                new LambdaUpdateWrapper<APIKey>()
                                        .eq(APIKey::getId, existing.getId())
                                        .set(APIKey::getIntentHash, intentHash));
                        existing.setIntentHash(intentHash);
                    }
                }
                if (!expected) {
                    throw new IdempotencyConflictException("幂等请求 ID 已绑定另一项 API Key 创建意图");
                }
                if (existing.getRevokedAt() != null) {
                    throw new IdempotencyConflictException("该幂等创建请求对应的 API Key 已撤销，不能重放");
                }
                if (!java.util.Objects.equals(existing.getKeyHash(), keyHash)) {
                    throw new IdempotencyConflictException("历史 API Key 无法使用当前主密钥安全重放");
                }
                return existing;
            }

            LocalDateTime now = DateTimeUtils.utcNowNaive();
            APIKey apiKey = new APIKey();
            apiKey.setKeyHash(keyHash);
            apiKey.setKeyPrefix(keyPrefix);
            apiKey.setRequestId(requestId);
            apiKey.setIntentHash(intentHash);
            apiKey.setName(name);
            apiKey.setUserId(userId);
            apiKey.setDepartmentId(departmentId);
            apiKey.setExpiresAt(expiresAt);
            apiKey.setCreatedBy(createdBy);
            apiKey.setIsEnabled(true);
            apiKey.setCreatedAt(now);
            apiKeyMapper.insert(apiKey);
            return apiKey;
        } finally {
            if (held) {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
            }
        }
    }

    /** 更新 API Key（仅写入出现的键，未提及的保持原值）。 */
    @Transactional
    public APIKey update(APIKey apiKey, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            switch (entry.getKey()) {
                case "name" -> apiKey.setName(RepoValues.asString(entry.getValue()));
                case "is_enabled" -> apiKey.setIsEnabled(RepoValues.toBoolean(entry.getValue()));
                case "expires_at" -> apiKey.setExpiresAt(RepoValues.toLocalDateTime(entry.getValue()));
                case "department_id" -> apiKey.setDepartmentId(RepoValues.toInt(entry.getValue()));
                case "revoked_at" -> apiKey.setRevokedAt(RepoValues.toLocalDateTime(entry.getValue()));
                case "last_used_at" -> apiKey.setLastUsedAt(RepoValues.toLocalDateTime(entry.getValue()));
                default -> {
                    // 未知键忽略
                }
            }
        }
        apiKeyMapper.update(
                null,
                new LambdaUpdateWrapper<APIKey>()
                        .eq(APIKey::getId, apiKey.getId())
                        .set(APIKey::getName, apiKey.getName())
                        .set(APIKey::getIsEnabled, apiKey.getIsEnabled())
                        .set(APIKey::getExpiresAt, apiKey.getExpiresAt())
                        .set(APIKey::getDepartmentId, apiKey.getDepartmentId())
                        .set(APIKey::getRevokedAt, apiKey.getRevokedAt())
                        .set(APIKey::getLastUsedAt, apiKey.getLastUsedAt()));
        return apiKey;
    }

    /** 撤销 API Key 并保留幂等墓碑，阻止同一请求复活凭据。 */
    @Transactional
    public void delete(APIKey apiKey) {
        apiKeyMapper.update(
                null,
                new LambdaUpdateWrapper<APIKey>()
                        .eq(APIKey::getId, apiKey.getId())
                        .set(APIKey::getIsEnabled, false)
                        .set(APIKey::getRevokedAt, DateTimeUtils.utcNowNaive()));
    }
}
