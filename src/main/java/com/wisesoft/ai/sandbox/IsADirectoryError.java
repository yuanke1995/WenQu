package com.wisesoft.ai.sandbox;

/** 对应 Python 内建 {@code IsADirectoryError}（沙盒安全脚本判定目标是目录时）。 */
public class IsADirectoryError extends RuntimeException {
    public IsADirectoryError(String message) {
        super(message);
    }
}
