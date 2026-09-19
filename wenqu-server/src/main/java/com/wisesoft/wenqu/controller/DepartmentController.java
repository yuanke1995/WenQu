package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.Department;
import com.wisesoft.wenqu.repositories.DepartmentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.IdentityAdminService;
import com.wisesoft.wenqu.service.OperationLogService;
import com.wisesoft.wenqu.service.UserIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 部门管理路由，逐端点对齐参考实现 {@code server/routers/auth_dept_router.py}。
 *
 * <p>错误语义照搬：部门不存在 404 {@code 部门不存在}；名称/用户ID/手机号冲突 400（文案逐字）；
 * 默认部门（id=1）不可删除 400 {@code 默认部门不允许删除}；创建成功 201。
 *
 * <p>平台差异（必要替换）：{@code Depends(get_admin_user/get_superadmin_user)} →
 * {@link AuthGuards#requireAdmin()} / {@link AuthGuards#requireSuperadmin()}；
 * pydantic 约束（admin_password min_length=8）→ 方法内显式校验（422）；
 * {@code default_department_id} 默认参数 1 → 调用点显式传 1。
 */
@Slf4j
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
@Tag(name = "department", description = "部门管理")
public class DepartmentController {

    /** 参考实现的用户ID格式约束。 */
    private static final Pattern ADMIN_UID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /** 默认部门 ID（参考实现 delete_and_migrate_users 的 default_department_id 默认值）。 */
    private static final int DEFAULT_DEPARTMENT_ID = 1;

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final IdentityAdminService identityAdminService;
    private final OperationLogService operationLogService;

    @Operation(summary = "部门列表（含用户数）")
    @GetMapping({"", "/"})
    public List<Map<String, Object>> getDepartments() {
        AuthGuards.requireAdmin();
        return departmentRepository.listWithUserCount();
    }

    @Operation(summary = "部门详情")
    @GetMapping("/{department_id}")
    public Map<String, Object> getDepartment(@PathVariable("department_id") int departmentId) {
        AuthGuards.requireSuperadmin();
        Map<String, Object> department = departmentRepository.getWithUserCount(departmentId);
        if (department == null) {
            throw new ApiHttpException(404, "部门不存在");
        }
        return department;
    }

    @Operation(summary = "创建部门（同时创建该部门管理员）")
    @PostMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> createDepartment(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String actorUid = AuthGuards.requireSuperadmin();

        String name = requireString(body.get("name"), "name");
        String adminUid = requireString(body.get("admin_uid"), "admin_uid");
        String adminPassword = requireString(body.get("admin_password"), "admin_password");
        if (adminPassword.length() < 8) {
            throw new ApiHttpException(422, "admin_password 长度不能少于 8 个字符");
        }
        String description = optionalString(body.get("description"));
        String adminPhone = optionalString(body.get("admin_phone"));

        if (departmentRepository.existsByName(name)) {
            throw new ApiHttpException(400, "部门名称已存在");
        }
        if (!ADMIN_UID_PATTERN.matcher(adminUid).matches()) {
            throw new ApiHttpException(400, "用户ID只能包含字母、数字和下划线");
        }
        if (adminUid.length() < 3 || adminUid.length() > 20) {
            throw new ApiHttpException(400, "用户ID长度必须在3-20个字符之间");
        }
        if (userRepository.existsByUid(adminUid)) {
            throw new ApiHttpException(400, "用户ID已存在");
        }
        if (adminPhone != null && !adminPhone.isEmpty()) {
            if (!UserIdentityService.isValidPhoneNumber(adminPhone)) {
                throw new ApiHttpException(400, "手机号格式不正确");
            }
            if (userRepository.existsByPhone(adminPhone)) {
                throw new ApiHttpException(400, "手机号已存在");
            }
        }

        IdentityAdminService.DepartmentAdminCreation created;
        try {
            created = identityAdminService.createDepartmentWithAdmin(
                    name, description, adminUid, adminPassword,
                    (adminPhone == null || adminPhone.isEmpty()) ? null : adminPhone,
                    actorUserId(actorUid), request);
        } catch (IdentityAdminService.IdentityConflictError exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>(DepartmentRepository.toDict(created.department()));
        result.put("user_count", 1);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Operation(summary = "更新部门")
    @PutMapping("/{department_id}")
    public Map<String, Object> updateDepartment(
            @PathVariable("department_id") int departmentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String actorUid = AuthGuards.requireSuperadmin();

        Department department = departmentRepository.getById(departmentId);
        if (department == null) {
            throw new ApiHttpException(404, "部门不存在");
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        String name = optionalString(body.get("name"));
        if (name != null && !name.isEmpty() && !name.equals(department.getName())) {
            if (departmentRepository.getByName(name) != null) {
                throw new ApiHttpException(400, "部门名称已存在");
            }
            updates.put("name", name);
        }
        if (body.get("description") != null) {
            updates.put("description", body.get("description"));
        }

        Department updated = departmentRepository.update(departmentId, updates);
        if (updated == null) {
            throw new ApiHttpException(404, "部门不存在");
        }

        operationLogService.logOperation(
                actorUserId(actorUid),
                "更新部门",
                "更新部门: " + updated.getName(),
                request);

        Map<String, Object> result = new LinkedHashMap<>(DepartmentRepository.toDict(updated));
        result.put("user_count", departmentRepository.countUsers(departmentId));
        return result;
    }

    @Operation(summary = "删除部门（用户迁移到默认部门）")
    @DeleteMapping("/{department_id}")
    public Map<String, Object> deleteDepartment(
            @PathVariable("department_id") int departmentId, HttpServletRequest request) {
        String actorUid = AuthGuards.requireSuperadmin();

        Department department = departmentRepository.getById(departmentId);
        if (department == null) {
            throw new ApiHttpException(404, "部门不存在");
        }
        if (department.getId() != null && department.getId() == DEFAULT_DEPARTMENT_ID) {
            throw new ApiHttpException(400, "默认部门不允许删除");
        }

        DepartmentRepository.DepartmentDeletionResult deletion =
                departmentRepository.deleteAndMigrateUsers(departmentId, DEFAULT_DEPARTMENT_ID);
        if (deletion == null) {
            throw new ApiHttpException(404, "部门不存在");
        }

        String detail;
        if (deletion.migratedUserCount() > 0) {
            detail = "删除部门: " + deletion.name() + "，迁移 " + deletion.migratedUserCount() + " 个用户到默认部门";
        } else {
            detail = "删除部门: " + deletion.name();
        }
        operationLogService.logOperation(
                actorUserId(actorUid), "删除部门", detail, request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "部门已删除");
        return result;
    }

    // ==================== 内部辅助 ====================

    /** 当前用户行的数值主键（参考实现的 current_user.id）。 */
    private int actorUserId(String actorUid) {
        var user = userRepository.getByUid(actorUid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user.getId();
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new ApiHttpException(422, field + " 字段必填");
        }
        return text;
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : null;
    }
}
