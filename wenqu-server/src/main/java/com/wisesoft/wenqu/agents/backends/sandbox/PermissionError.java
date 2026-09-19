package com.wisesoft.wenqu.agents.backends.sandbox;

/** 对应 Python 内建 {@code PermissionError}（沙盒安全脚本的权限/符号链接边界失败）。 */
public class PermissionError extends RuntimeException {
    public PermissionError(String message) {
        super(message);
    }
}
