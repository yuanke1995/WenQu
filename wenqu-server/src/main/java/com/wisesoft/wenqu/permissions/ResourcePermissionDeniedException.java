package com.wisesoft.wenqu.permissions;

/**
 * 当前用户的资源权限不足。
 *
 * <p>由参考实现的 permissions/resource_permission.py 中 ResourcePermissionDenied 翻译
 * （Python 侧继承 PermissionError，Java 侧对应 SecurityException 家族）。
 */
public class ResourcePermissionDeniedException extends SecurityException {

    public ResourcePermissionDeniedException(String message) {
        super(message);
    }
}
