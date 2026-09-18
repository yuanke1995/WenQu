package com.wisesoft.wenqu.permissions;

/**
 * 权限解析的请求者视图。
 *
 * <p>对应参考实现 permissions/resource_permission.py 中通过 {@code _value(user, key)} 读取的
 * 三个字段（role / uid / department_id）。参考实现用 {@code hasattr/getattr} 兼容字典与对象，
 * Java 侧改为显式接口 + 实体适配方法（语言差异，不改变解析逻辑）。
 */
public interface PermissionSubject {

    /** 角色：user / admin / superadmin。 */
    String role();

    /** 用户 uid。 */
    String uid();

    /** 所属部门 id，可为 null。 */
    Integer departmentId();

    /** 从用户实体适配。 */
    static PermissionSubject of(com.wisesoft.wenqu.models.User user) {
        return new PermissionSubject() {
            @Override
            public String role() {
                return user.getRole();
            }

            @Override
            public String uid() {
                return user.getUid();
            }

            @Override
            public Integer departmentId() {
                return user.getDepartmentId();
            }
        };
    }

    /** 从 Map 适配（参考实现支持字典入参）。 */
    static PermissionSubject ofMap(java.util.Map<String, Object> source) {
        return new PermissionSubject() {
            @Override
            public String role() {
                Object value = source.get("role");
                return value == null ? null : String.valueOf(value);
            }

            @Override
            public String uid() {
                Object value = source.get("uid");
                return value == null ? null : String.valueOf(value);
            }

            @Override
            public Integer departmentId() {
                return com.wisesoft.wenqu.repositories.RepoValues.toInt(source.get("department_id"));
            }
        };
    }
}
