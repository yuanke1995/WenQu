package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.APIKey;
import com.wisesoft.wenqu.models.Department;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repository.port.APIKeyMapper;
import com.wisesoft.wenqu.repository.port.DepartmentMapper;
import com.wisesoft.wenqu.repository.port.UserMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部门仓储。
 *
 * <p>由参考实现的 repositories/department_repository.py 逐方法翻译：查询与排序口径
 * （按创建时间倒序）、用户计数口径（仅未删除用户）、删除部门时把用户迁移到默认部门并
 * 撤销该部门下 API Key 的语义。
 *
 * <p>必要替换：
 * <ul>
 *   <li>{@code Department.to_dict()} 的键与时间格式照搬（created_at 输出 UTC ISO8601，
 *       参考实现用 format_utc_datetime，naive 值按 UTC 处理）。
 *   <li>删除部门依赖数据库外键级联清理 API Key 关联；本工程不建物理外键，故显式更新
 *       API Key（与参考实现同一条 UPDATE：置 is_enabled=false、写撤销时间、清空部门）。
 * </ul>
 */
@Repository
public class DepartmentRepository {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final APIKeyMapper apiKeyMapper;

    public DepartmentRepository(
            DepartmentMapper departmentMapper, UserMapper userMapper, APIKeyMapper apiKeyMapper) {
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.apiKeyMapper = apiKeyMapper;
    }

    /** 部门删除结果。 */
    public record DepartmentDeletionResult(String name, int migratedUserCount) {}

    /** 根据 ID 获取部门。 */
    public Department getById(Integer id) {
        return departmentMapper.selectById(id);
    }

    /** 根据 ID 获取部门名称。 */
    public String getNameById(Integer id) {
        Department department = departmentMapper.selectById(id);
        return department == null ? null : department.getName();
    }

    /** 获取部门及其未删除用户数量。 */
    public Map<String, Object> getWithUserCount(Integer id) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            return null;
        }
        Map<String, Object> result = toDict(department);
        result.put("user_count", countUsers(id));
        return result;
    }

    /** 根据名称获取部门。 */
    public Department getByName(String name) {
        return departmentMapper.selectOne(new LambdaQueryWrapper<Department>().eq(Department::getName, name));
    }

    /** 获取所有部门列表（按创建时间倒序）。 */
    public List<Department> listDepartments() {
        return departmentMapper.selectList(new LambdaQueryWrapper<Department>().orderByDesc(Department::getCreatedAt));
    }

    /** 获取所有部门列表，包含用户数量。 */
    public List<Map<String, Object>> listWithUserCount() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Department department : listDepartments()) {
            Map<String, Object> item = toDict(department);
            item.put("user_count", countUsers(department.getId()));
            result.add(item);
        }
        return result;
    }

    /** 创建部门。 */
    public Department create(Map<String, Object> data) {
        Department department = new Department();
        applyDepartmentFields(department, data);
        if (department.getCreatedAt() == null) {
            department.setCreatedAt(DateTimeUtils.utcNowNaive());
        }
        departmentMapper.insert(department);
        return department;
    }

    /** 更新部门。 */
    public Department update(Integer id, Map<String, Object> data) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            return null;
        }
        Map<String, Object> withoutId = new LinkedHashMap<>(data);
        withoutId.remove("id");
        applyDepartmentFields(department, withoutId);
        departmentMapper.updateById(department);
        return department;
    }

    /** 删除部门。 */
    public boolean delete(Integer id) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            return false;
        }
        departmentMapper.deleteById(id);
        return true;
    }

    /** 迁移部门用户、撤销关联 API Key，并删除部门。 */
    @Transactional
    public DepartmentDeletionResult deleteAndMigrateUsers(Integer id, int defaultDepartmentId) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            return null;
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getDepartmentId, id));
        for (User user : users) {
            userMapper.update(
                    null,
                    new LambdaUpdateWrapper<User>()
                            .eq(User::getId, user.getId())
                            .set(User::getDepartmentId, defaultDepartmentId));
        }

        apiKeyMapper.update(
                null,
                new LambdaUpdateWrapper<APIKey>()
                        .eq(APIKey::getDepartmentId, id)
                        .set(APIKey::getIsEnabled, false)
                        .set(APIKey::getRevokedAt, DateTimeUtils.utcNowNaive())
                        .set(APIKey::getDepartmentId, null));

        departmentMapper.deleteById(id);
        return new DepartmentDeletionResult(department.getName(), users.size());
    }

    /** 统计部门用户数量。 */
    public int countUsers(Integer id) {
        Long count =
                userMapper.selectCount(
                        new LambdaQueryWrapper<User>().eq(User::getDepartmentId, id).eq(User::getIsDeleted, 0));
        return count == null ? 0 : count.intValue();
    }

    /** 检查部门名称是否存在。 */
    public boolean existsByName(String name) {
        Long count = departmentMapper.selectCount(new LambdaQueryWrapper<Department>().eq(Department::getName, name));
        return count != null && count > 0;
    }

    /** Department.to_dict() 的键与时间格式照搬。 */
    // 可见性调整（平台差异）：参考实现的路由层直接调用该序列化函数组装响应，
    // Spring 侧路由在 controller 包，故由包内私有放宽为 public；逻辑不变。
    public static Map<String, Object> toDict(Department department) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", department.getId());
        result.put("name", department.getName());
        result.put("description", department.getDescription());
        result.put("created_at", formatUtcDatetime(department.getCreatedAt()));
        return result;
    }

    /** naive 时间按 UTC 处理并输出带 Z 的 ISO8601 字符串（与参考实现 format_utc_datetime 一致）。 */
    static String formatUtcDatetime(LocalDateTime value) {
        return DateTimeUtils.formatUtcDatetime(value);
    }

    static void applyDepartmentFields(Department department, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "name" -> department.setName(RepoValues.asString(value));
                case "description" -> department.setDescription(RepoValues.asString(value));
                case "created_at" -> department.setCreatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键忽略
                }
            }
        }
    }
}
