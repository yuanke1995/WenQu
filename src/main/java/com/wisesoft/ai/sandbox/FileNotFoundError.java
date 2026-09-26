package com.wisesoft.ai.sandbox;

/** 对应 Python 内建 {@code FileNotFoundError}（沙盒安全脚本判定目标不存在时）。 */
public class FileNotFoundError extends RuntimeException {
    public FileNotFoundError(String message) {
        super(message);
    }
}
