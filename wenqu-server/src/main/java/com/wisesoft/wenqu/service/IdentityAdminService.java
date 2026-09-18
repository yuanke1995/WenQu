package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.MinioUrls;
import com.wisesoft.wenqu.models.Department;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.DepartmentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织与初始身份管理用例的事务 Owner。
 *
 * <p>由参考实现的 services/identity_admin_service.py 逐函数翻译：管理员可见范围内的用户分页、
 * 原子创建部门+首位管理员、系统初始化（默认部门 + 超级管理员 + 初始化审计）。
 *
 * <p>必要替换：
 * <ul>
 *   <li>初始化串行锁：参考实现用 PostgreSQL 事务级建议锁
 *       {@code pg_advisory_xact_lock(0x59555849)}；MySQL 无事务级建议锁，改用命名锁
 *       {@code GET_LOCK(锁名, 10)}（会话级，方法事务内成对释放）。
 *   <li>{@code IntegrityError} → Spring 的 {@link DataIntegrityViolationException}。
 *   <li>参考实现显式 commit/rollback → Spring {@code @Transactional}（抛错即回滚，
 *       返回即提交，语义一致）。
 *   <li>分页行（User, department_name）→ 仓储返回的联表行 Map，此处按
 *       {@code User.to_dict()} 键集映射（不泄露 password_hash）。
 * </ul>
 */
@Service
public class IdentityAdminService {

    /** 参考实现的建议锁数值键（0x59555849）；MySQL 侧以其十六进制文本作命名锁名。 */
    private static final String INITIALIZATION_LOCK_KEY = "0x59555849";

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final OperationLogService operationLogService;
    private final JdbcTemplate jdbc;

    public IdentityAdminService(
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            OperationLogService operationLogService,
            JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.operationLogService = operationLogService;
        this.jdbc = jdbc;
    }

    /** 唯一身份或部门事实在提交时发生冲突。 */
    public static class IdentityConflictError extends RuntimeException {
        public IdentityConflictError(String message) {
            super(message);
        }
    }

    /** 系统初始化已由当前或并发请求完成。 */
    public static class SystemAlreadyInitializedError extends RuntimeException {
        public SystemAlreadyInitializedError(String message) {
            super(message);
        }
    }

    /** 同一事务创建的部门和管理员。 */
    public record DepartmentAdminCreation(Department department, User admin) {}

    /** 返回管理员可见范围内的用户分页。 */
    public Map<String, Object> listManagedUsersPage(
            int offset,
            int limit,
            boolean isSuperadmin,
            Integer visibleDepartmentId,
            Integer departmentId,
            String role,
            String search) {
        Integer effectiveDepartmentId = isSuperadmin ? departmentId : visibleDepartmentId;
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isSuperadmin && effectiveDepartmentId == null) {
            result.put("items", new ArrayList<>());
            result.put("total", 0);
            result.put("limit", limit);
            result.put("offset", offset);
            return result;
        }
        Map<String, Object> page =
                userRepository.listPageWithDepartment(offset, limit, effectiveDepartmentId, role, search);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("records");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = userDictFromRow(row);
            item.put("department_name", row.get("department_name"));
            items.add(item);
        }
        result.put("items", items);
        result.put("total", page.get("total"));
        result.put("limit", limit);
        result.put("offset", offset);
        return result;
    }

    /**
     * 原子创建部门、首位管理员和强制审计事实。
     *
     * <p>request 允许为 null（对应参考实现的可选 Request）；审计日志在创建之后、
     * 事务提交之前写入，失败会整体回滚（参考实现 log_operation 后才 commit，语义一致）。
     */
    @Transactional
    public DepartmentAdminCreation createDepartmentWithAdmin(
            String name,
            String description,
            String adminUid,
            String adminPassword,
            String adminPhone,
            int actorUserId,
            HttpServletRequest request) {
        Department department;
        User admin;
        try {
            Map<String, Object> departmentData = new LinkedHashMap<>();
            departmentData.put("name", name);
            departmentData.put("description", description);
            department = departmentRepository.create(departmentData);

            String passwordHash = AuthUtils.hashPassword(adminPassword);
            Map<String, Object> adminData = new LinkedHashMap<>();
            adminData.put("username", adminUid);
            adminData.put("uid", adminUid);
            adminData.put("phone_number", adminPhone);
            adminData.put("password_hash", passwordHash);
            adminData.put("role", "admin");
            adminData.put("department_id", department.getId());
            admin = userRepository.create(adminData);
        } catch (DataIntegrityViolationException exc) {
            throw new IdentityConflictError("部门名称、管理员用户ID、用户名或手机号已存在");
        }
        operationLogService.logOperation(
                actorUserId, "创建部门", "创建部门: " + name + "，并创建管理员: " + adminUid, request);
        return new DepartmentAdminCreation(department, admin);
    }

    /** 串行、原子地创建默认部门、超级管理员和初始化审计。 */
    @Transactional
    public DepartmentAdminCreation initializeSystemAdmin(String uid, String password, String phoneNumber) {
        String lockName = "wenqu:identity-init:" + INITIALIZATION_LOCK_KEY;
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, lockName);
        boolean held = acquired != null && acquired == 1;
        try {
            if (!userRepository.isFirstRun()) {
                throw new SystemAlreadyInitializedError("系统已经初始化，无法再次创建初始管理员");
            }

            String passwordHash = AuthUtils.hashPassword(password);
            Map<String, Object> departmentData = new LinkedHashMap<>();
            departmentData.put("name", "默认部门");
            departmentData.put("description", "系统初始化时创建的默认部门");
            Department department = departmentRepository.create(departmentData);

            Map<String, Object> adminData = new LinkedHashMap<>();
            adminData.put("username", uid);
            adminData.put("uid", uid);
            adminData.put("phone_number", phoneNumber);
            adminData.put("avatar", null);
            adminData.put("password_hash", passwordHash);
            adminData.put("role", "superadmin");
            adminData.put("department_id", department.getId());
            adminData.put("last_login", DateTimeUtils.utcNowNaive());
            User admin = userRepository.create(adminData);

            operationLogService.logOperation(admin.getId(), "系统初始化", "创建超级管理员账户", null);
            return new DepartmentAdminCreation(department, admin);
        } catch (DataIntegrityViolationException exc) {
            throw new IdentityConflictError("初始化身份事实与现有数据库约束冲突");
        } finally {
            if (held) {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
            }
        }
    }

    /**
     * 把联表行 Map 按 User.to_dict() 的键集映射（参考实现逐行 {@code user.to_dict()}）。
     *
     * <p>queryForList 的列名为 snake_case，与 to_dict 键名一致；时间列由 JDBC 映射为
     * LocalDateTime，此处统一转参考实现的 UTC ISO 字符串；avatar 经 MinioUrls 规范化。
     */
    static Map<String, Object> userDictFromRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("username", row.get("username"));
        result.put("uid", row.get("uid"));
        result.put("phone_number", row.get("phone_number"));
        result.put("avatar", MinioUrls.normalizePublicMinioUrl(asString(row.get("avatar"))));
        result.put("role", row.get("role"));
        result.put("department_id", row.get("department_id"));
        result.put("created_at", formatDatetime(row.get("created_at")));
        result.put("last_login", formatDatetime(row.get("last_login")));
        result.put("login_failed_count", row.get("login_failed_count"));
        result.put("last_failed_login", formatDatetime(row.get("last_failed_login")));
        result.put("login_locked_until", formatDatetime(row.get("login_locked_until")));
        result.put("is_deleted", row.get("is_deleted"));
        result.put("deleted_at", formatDatetime(row.get("deleted_at")));
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String formatDatetime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return DateTimeUtils.formatUtcDatetime(localDateTime);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return DateTimeUtils.formatUtcDatetime(timestamp.toLocalDateTime());
        }
        return String.valueOf(value);
    }
}
