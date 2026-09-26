package com.wisesoft.ai.sandbox;

/**
 * Sandbox runtime 的持久挂载身份与请求不一致（对应参考实现 provider.py 的
 * {@code SandboxIdentityMismatchError(RuntimeError)}）。
 *
 * <p>参考实现继承 RuntimeError；本工程统一以 RuntimeException 派生。
 */
public class SandboxIdentityMismatchError extends RuntimeException {

    public SandboxIdentityMismatchError(String message) {
        super(message);
    }
}
