package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.MinioUrls;
import com.wisesoft.wenqu.models.APIKey;
import com.wisesoft.wenqu.models.ScheduledAgentJob;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repository.port.APIKeyMapper;
import com.wisesoft.wenqu.repository.port.ScheduledAgentJobMapper;
import com.wisesoft.wenqu.repository.port.UserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户仓储。
 *
 * <p>由参考实现的 repositories/user_repository.py 逐方法翻译：查询条件（含 is_deleted=0 口径）、
 * 排序与分页、登录标识的"uid 优先、手机号兜底"、注销时的用户名改写与哈希后缀、
 * 软删除同时撤销 API Key 与删除定时任务。
 *
 * <p>必要替换：
 * <ul>
 *   <li>会话注入：参考实现支持"复用请求会话 / 未注入时新建会话"；本工程的 Mapper 调用本身
 *       即一个事务，需要多语句原子性的方法用 {@code @Transactional} 标注。
 *   <li>icontains（大小写不敏感包含）→ MySQL 的 {@code LOWER(col) LIKE LOWER(?) ESCAPE}，
 *       并沿用参考实现的转义（转义 %、_ 与反斜杠）。
 *   <li>{@code session.refresh()} 在 MyBatis-Plus 无对应语义（插入后主键已回填），省略。
 * </ul>
 */
@Repository
public class UserRepository {

    private final UserMapper userMapper;
    private final APIKeyMapper apiKeyMapper;
    private final ScheduledAgentJobMapper scheduledAgentJobMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public UserRepository(
            UserMapper userMapper,
            APIKeyMapper apiKeyMapper,
            ScheduledAgentJobMapper scheduledAgentJobMapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.scheduledAgentJobMapper = scheduledAgentJobMapper;
        this.jdbc = jdbc;
    }

    /** 使用 naive datetime 以匹配不带时区的时间列。 */
    static LocalDateTime utcNow() {
        return DateTimeUtils.utcNowNaive();
    }

    /** 根据 ID 获取用户。 */
    public User getById(Integer id) {
        return userMapper.selectById(id);
    }

    /** 检查系统是否尚未创建用户。 */
    public boolean isFirstRun() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<>());
        return count == null || count == 0;
    }

    /** 根据 ID 获取未删除用户（可选加行锁）。 */
    public User getActiveById(Integer id, boolean forUpdate) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().eq(User::getId, id).eq(User::getIsDeleted, 0);
        if (forUpdate) {
            wrapper.last("FOR UPDATE");
        }
        return userMapper.selectOne(wrapper);
    }

    /** 撤销用户的全部 API Key，并保留已有撤销时间。 */
    static void revokeApiKeys(APIKeyMapper apiKeyMapper, Integer userId, LocalDateTime revokedAt) {
        List<APIKey> keys = apiKeyMapper.selectList(new LambdaQueryWrapper<APIKey>().eq(APIKey::getUserId, userId));
        for (APIKey key : keys) {
            LambdaUpdateWrapper<APIKey> wrapper =
                    new LambdaUpdateWrapper<APIKey>().eq(APIKey::getId, key.getId()).set(APIKey::getIsEnabled, false);
            if (key.getRevokedAt() == null) {
                wrapper.set(APIKey::getRevokedAt, revokedAt);
            }
            apiKeyMapper.update(null, wrapper);
        }
    }

    /** 账号删除时移除任务定义，数据库级联清理调度历史。 */
    static void deleteScheduledJobs(ScheduledAgentJobMapper mapper, String uid) {
        mapper.delete(new LambdaQueryWrapper<ScheduledAgentJob>().eq(ScheduledAgentJob::getUid, String.valueOf(uid)));
    }

    /** 根据 ID 获取用户（指定 Mapper 之外的兼容签名）。 */
    public User getByIdWithDb(Integer id) {
        return userMapper.selectById(id);
    }

    /** 根据 uid 获取用户。 */
    public User getByUid(String uid) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUid, uid));
    }

    /** 批量获取指定 uid 的用户（去重排序后查询）。 */
    public List<User> listByUids(List<String> uids) {
        TreeSet<String> normalized = new TreeSet<>();
        if (uids != null) {
            for (String uid : uids) {
                if (uid != null && !uid.trim().isEmpty()) {
                    normalized.add(uid.trim());
                }
            }
        }
        if (normalized.isEmpty()) {
            return new ArrayList<>();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getUid, normalized));
    }

    /** 根据手机号获取用户。 */
    public User getByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhoneNumber, phone));
    }

    /** 按 uid 优先、手机号兜底查找登录用户。 */
    public User getByLoginIdentifier(String identifier) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUid, identifier));
        if (user != null) {
            return user;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhoneNumber, identifier));
    }

    /** 按用户名查找用户，可排除指定用户。 */
    public User getByUsername(String username, Integer excludeUserId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().eq(User::getUsername, username);
        if (excludeUserId != null) {
            wrapper.ne(User::getId, excludeUserId);
        }
        return userMapper.selectOne(wrapper);
    }

    /** 按手机号查找除指定用户外的用户。 */
    public User getByPhoneExcluding(String phone, Integer excludeUserId) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhoneNumber, phone).ne(User::getId, excludeUserId));
    }

    /** 获取用户列表。 */
    public List<User> listUsers(int skip, int limit, Integer departmentId, String role) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().eq(User::getIsDeleted, 0);
        if (departmentId != null) {
            wrapper.eq(User::getDepartmentId, departmentId);
        }
        if (role != null) {
            wrapper.eq(User::getRole, role);
        }
        wrapper.orderByAsc(User::getId);
        wrapper.last("LIMIT " + limit + " OFFSET " + skip);
        return userMapper.selectList(wrapper);
    }

    /** 获取用户列表，包含部门名称（部门名称为 null 时表示未分配部门）。 */
    public List<Map<String, Object>> listWithDepartment(int skip, int limit, Integer departmentId, String role) {
        return queryUsersWithDepartmentName(departmentId, role, null, skip, limit);
    }

    /** 分页查询有效用户，并返回过滤后的总数。 */
    public Map<String, Object> listPageWithDepartment(
            int offset, int limit, Integer departmentId, String role, String search) {
        List<Map<String, Object>> records = queryUsersWithDepartmentName(departmentId, role, search, offset, limit);
        LambdaQueryWrapper<User> countWrapper = baseFilters(departmentId, role, search);
        Long total = userMapper.selectCount(countWrapper);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0L : total);
        return result;
    }

    private List<Map<String, Object>> queryUsersWithDepartmentName(
            Integer departmentId, String role, String search, int offset, int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT u.*, d.name AS department_name FROM users u "
                                + "LEFT JOIN departments d ON u.department_id = d.id WHERE u.is_deleted = 0");
        List<Object> args = new ArrayList<>();
        if (departmentId != null) {
            sql.append(" AND u.department_id = ?");
            args.add(departmentId);
        }
        if (role != null) {
            sql.append(" AND u.role = ?");
            args.add(role);
        }
        if (search != null && !search.isEmpty()) {
            String escaped = escapeLike(search.toLowerCase());
            sql.append(
                    " AND (LOWER(u.username) LIKE ? ESCAPE '\\\\' OR LOWER(u.uid) LIKE ? ESCAPE '\\\\' "
                            + "OR LOWER(u.phone_number) LIKE ? ESCAPE '\\\\')");
            for (int i = 0; i < 3; i++) {
                args.add("%" + escaped + "%");
            }
        }
        sql.append(" ORDER BY u.id ASC LIMIT ").append(limit).append(" OFFSET ").append(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private LambdaQueryWrapper<User> baseFilters(Integer departmentId, String role, String search) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().eq(User::getIsDeleted, 0);
        if (departmentId != null) {
            wrapper.eq(User::getDepartmentId, departmentId);
        }
        if (role != null) {
            wrapper.eq(User::getRole, role);
        }
        if (search != null && !search.isEmpty()) {
            String escaped = escapeLike(search.toLowerCase());
            wrapper.and(
                    w ->
                            w.apply("LOWER(username) LIKE {0} ESCAPE '\\\\'", "%" + escaped + "%")
                                    .or()
                                    .apply("LOWER(uid) LIKE {0} ESCAPE '\\\\'", "%" + escaped + "%")
                                    .or()
                                    .apply("LOWER(phone_number) LIKE {0} ESCAPE '\\\\'", "%" + escaped + "%"));
        }
        return wrapper;
    }

    /** 创建用户。 */
    public User create(Map<String, Object> data) {
        User user = new User();
        applyUserFields(user, data);
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(utcNow());
        }
        userMapper.insert(user);
        return user;
    }

    /** 保存用户实体的当前变更（本工程对应一次 update）。 */
    public User save(User user) {
        userMapper.updateById(user);
        return user;
    }

    /** 更新用户。 */
    public User update(Integer id, Map<String, Object> data) {
        User user =
                userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, id).eq(User::getIsDeleted, 0));
        if (user == null) {
            return null;
        }
        Map<String, Object> withoutId = new LinkedHashMap<>(data);
        withoutId.remove("id");
        applyUserFields(user, withoutId);
        userMapper.updateById(user);
        return user;
    }

    /**
     * 软删除用户。
     *
     * <p>传入 username 时把用户名改写为"已注销用户-<uid 哈希前 4 位>"；传入 phoneNumber 时清空手机号。
     */
    @Transactional
    public boolean softDelete(Integer id, String username, String phoneNumber) {
        User user =
                userMapper.selectOne(
                        new LambdaQueryWrapper<User>().eq(User::getId, id).eq(User::getIsDeleted, 0).last("FOR UPDATE"));
        if (user == null) {
            return false;
        }
        LocalDateTime deletedAt = utcNow();
        user.setIsDeleted(1);
        user.setDeletedAt(deletedAt);
        if (username != null && !username.isEmpty()) {
            String hashSuffix = sha256Hex(user.getUid()).substring(0, 4);
            user.setUsername("已注销用户-" + hashSuffix);
        }
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            user.setPhoneNumber(null);
        }
        userMapper.update(null, buildFullUpdate(user));
        revokeApiKeys(apiKeyMapper, user.getId(), deletedAt);
        deleteScheduledJobs(scheduledAgentJobMapper, user.getUid());
        return true;
    }

    /** 软删除用户并在同一事务中不可恢复地撤销其 API Key。 */
    @Transactional
    public void deleteForAdmin(User user) {
        LocalDateTime deletedAt = utcNow();
        user.setIsDeleted(1);
        user.setDeletedAt(deletedAt);
        user.setUsername("已注销用户-" + user.getId());
        user.setPhoneNumber(null);
        user.setPasswordHash("DELETED");
        user.setAvatar(null);
        userMapper.update(null, buildFullUpdate(user));
        revokeApiKeys(apiKeyMapper, user.getId(), deletedAt);
        deleteScheduledJobs(scheduledAgentJobMapper, user.getUid());
    }

    /** 检查 uid 是否存在。 */
    public boolean existsByUid(String uid) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUid, uid));
        return count != null && count > 0;
    }

    /** 检查手机号是否存在。 */
    public boolean existsByPhone(String phone) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhoneNumber, phone));
        return count != null && count > 0;
    }

    /** 统计用户数量。 */
    public int count(Integer departmentId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().eq(User::getIsDeleted, 0);
        if (departmentId != null) {
            wrapper.eq(User::getDepartmentId, departmentId);
        }
        Long count = userMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /** 获取所有 uid。 */
    public List<String> getAllUids() {
        List<String> uids = new ArrayList<>();
        for (User user : userMapper.selectList(new LambdaQueryWrapper<User>().select(User::getUid))) {
            uids.add(user.getUid());
        }
        return uids;
    }

    /** 统计部门中管理员数量。 */
    public int getAdminCountInDepartment(Integer departmentId, Integer excludeUserId) {
        LambdaQueryWrapper<User> wrapper =
                new LambdaQueryWrapper<User>()
                        .eq(User::getDepartmentId, departmentId)
                        .eq(User::getRole, "admin")
                        .eq(User::getIsDeleted, 0);
        if (excludeUserId != null) {
            wrapper.ne(User::getId, excludeUserId);
        }
        Long count = userMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 以「整实体落库」语义保存用户（对应参考实现 {@code repositories/user_repository.save(user)} 的
     * {@code session.flush()}：SQLAlchemy 按变更跟踪把被置为 {@code None} 的列一并写为 {@code NULL}）。
     *
     * <p>本工程的 {@link #save(User)}（{@code updateById}）默认 NOT_NULL 策略会跳过 null 列，
     * 导致 {@code reset_failed_login()}、清空手机号/头像这类「清空」语义静默不生效，
     * 故登录/资料链路一律走本方法（与本仓库既有的显式 set 约定一致）。
     */
    public User saveAllColumns(User user) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getUsername, user.getUsername())
                .set(User::getUid, user.getUid())
                .set(User::getPhoneNumber, user.getPhoneNumber())
                .set(User::getAvatar, user.getAvatar())
                .set(User::getPasswordHash, user.getPasswordHash())
                .set(User::getRole, user.getRole())
                .set(User::getDepartmentId, user.getDepartmentId())
                .set(User::getCreatedAt, user.getCreatedAt())
                .set(User::getLastLogin, user.getLastLogin())
                .set(User::getLoginFailedCount, user.getLoginFailedCount())
                .set(User::getLastFailedLogin, user.getLastFailedLogin())
                .set(User::getLoginLockedUntil, user.getLoginLockedUntil())
                .set(User::getIsDeleted, user.getIsDeleted())
                .set(User::getDeletedAt, user.getDeletedAt()));
        return user;
    }

    // ==================== 模型行为（models_business.User 的方法） ====================

    /** 参考实现 models_business.MAX_LOGIN_FAILED_ATTEMPTS。 */
    public static final int MAX_LOGIN_FAILED_ATTEMPTS = 5;

    /** 参考实现 models_business.LOGIN_LOCK_DURATION_SECONDS。 */
    public static final int LOGIN_LOCK_DURATION_SECONDS = 300;

    /** User.is_login_locked()：是否处于登录锁定状态。 */
    public static boolean isLoginLocked(User user) {
        if (user.getLoginLockedUntil() == null) {
            return false;
        }
        return utcNow().isBefore(user.getLoginLockedUntil());
    }

    /** User.get_remaining_lock_time()：剩余锁定秒数（下限 0）。 */
    public static int getRemainingLockTime(User user) {
        if (user.getLoginLockedUntil() == null) {
            return 0;
        }
        long seconds = java.time.Duration.between(utcNow(), user.getLoginLockedUntil()).getSeconds();
        return (int) Math.max(0L, seconds);
    }

    /** User.increment_failed_login()：失败计数 +1，达到阈值即按失败时刻锁定。 */
    public static void incrementFailedLogin(User user) {
        int count = (user.getLoginFailedCount() == null ? 0 : user.getLoginFailedCount()) + 1;
        user.setLoginFailedCount(count);
        user.setLastFailedLogin(utcNow());
        if (count >= MAX_LOGIN_FAILED_ATTEMPTS) {
            user.setLoginLockedUntil(user.getLastFailedLogin().plusSeconds(LOGIN_LOCK_DURATION_SECONDS));
        }
    }

    /** User.reset_failed_login()：重置登录失败相关字段。 */
    public static void resetFailedLogin(User user) {
        user.setLoginFailedCount(0);
        user.setLastFailedLogin(null);
        user.setLoginLockedUntil(null);
    }

    // ==================== 内部工具 ====================

    /**
     * User.to_dict() 的键与时间格式照搬（参考实现 models_business.User.to_dict）。
     *
     * <p>includePassword 对应参考实现的 {@code to_dict(include_password=True)}；
     * avatar 经 {@link MinioUrls#normalizePublicMinioUrl} 规范化（normalize_public_minio_url）。
     */
    public static Map<String, Object> toDict(User user, boolean includePassword) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("uid", user.getUid());
        result.put("phone_number", user.getPhoneNumber());
        result.put("avatar", MinioUrls.normalizePublicMinioUrl(user.getAvatar()));
        result.put("role", user.getRole());
        result.put("department_id", user.getDepartmentId());
        result.put("created_at", DateTimeUtils.formatUtcDatetime(user.getCreatedAt()));
        result.put("last_login", DateTimeUtils.formatUtcDatetime(user.getLastLogin()));
        result.put("login_failed_count", user.getLoginFailedCount());
        result.put("last_failed_login", DateTimeUtils.formatUtcDatetime(user.getLastFailedLogin()));
        result.put("login_locked_until", DateTimeUtils.formatUtcDatetime(user.getLoginLockedUntil()));
        result.put("is_deleted", user.getIsDeleted());
        result.put("deleted_at", DateTimeUtils.formatUtcDatetime(user.getDeletedAt()));
        if (includePassword) {
            result.put("password_hash", user.getPasswordHash());
        }
        return result;
    }

    /** 把 Map 写入用户实体（与 Python 的 setattr 语义一致：只写入出现的键）。 */
    static void applyUserFields(User user, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "username" -> user.setUsername(RepoValues.asString(value));
                case "uid" -> user.setUid(RepoValues.asString(value));
                case "phone_number" -> user.setPhoneNumber(RepoValues.asString(value));
                case "avatar" -> user.setAvatar(RepoValues.asString(value));
                case "password_hash" -> user.setPasswordHash(RepoValues.asString(value));
                case "role" -> user.setRole(RepoValues.asString(value));
                case "department_id" -> user.setDepartmentId(RepoValues.toInt(value));
                case "created_at" -> user.setCreatedAt(RepoValues.toLocalDateTime(value));
                case "last_login" -> user.setLastLogin(RepoValues.toLocalDateTime(value));
                case "login_failed_count" -> user.setLoginFailedCount(RepoValues.toInt(value));
                case "last_failed_login" -> user.setLastFailedLogin(RepoValues.toLocalDateTime(value));
                case "login_locked_until" -> user.setLoginLockedUntil(RepoValues.toLocalDateTime(value));
                case "is_deleted" -> user.setIsDeleted(RepoValues.toInt(value));
                case "deleted_at" -> user.setDeletedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键忽略
                }
            }
        }
    }

    /** 构造完整字段更新（含置空），规避框架默认更新策略跳过 null 列。 */
    static LambdaUpdateWrapper<User> buildFullUpdate(User user) {
        return new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getIsDeleted, user.getIsDeleted())
                .set(User::getDeletedAt, user.getDeletedAt())
                .set(User::getUsername, user.getUsername())
                .set(User::getPhoneNumber, user.getPhoneNumber());
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
}
