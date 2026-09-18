package com.wisesoft.wenqu.permissions;

/**
 * 资源权限等级，数值顺序用于判断权限是否足够。
 *
 * <p>由参考实现的 permissions/resource_permission.py 中 ResourcePermission 逐项翻译。
 */
public enum ResourcePermission {
    /** 无权限。 */
    NONE("none", 0),
    /** 只读。 */
    READ("read", 1),
    /** 可管理。 */
    MANAGE("manage", 2);

    private final String value;
    private final int order;

    ResourcePermission(String value, int order) {
        this.value = value;
        this.order = order;
    }

    /** 权限的字符串值（对外输出用）。 */
    public String value() {
        return value;
    }

    /** 权限等级序号：NONE=0 &lt; READ=1 &lt; MANAGE=2。 */
    public int order() {
        return order;
    }

    /** 按等级序号返回两者中更低（更小）的权限。 */
    public static ResourcePermission minimum(ResourcePermission left, ResourcePermission right) {
        return left.order <= right.order ? left : right;
    }
}
