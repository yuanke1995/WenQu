package com.wisesoft.wenqu.agents.backends.sandbox;

/**
 * 沙盒 provisioner 客户端 / 后端不可用或调用失败时抛出。
 *
 * <p>对应参考实现 provisioner_client.py 的 {@code RuntimeError} 分支；本工程未部署
 * 沙盒 provisioner（见 dify/notion/skill_remote_install 同一口径），相关调用统一抛此异常，
 * 绝不静默成功。
 */
public class SandboxProvisionerException extends RuntimeException {

    public SandboxProvisionerException(String message) {
        super(message);
    }

    public SandboxProvisionerException(String message, Throwable cause) {
        super(message, cause);
    }
}
