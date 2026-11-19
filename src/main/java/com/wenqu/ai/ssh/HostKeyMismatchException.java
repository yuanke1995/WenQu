package com.wenqu.ai.ssh;

/**
 * SSH 主机指纹与已保存值不一致：fail-closed，绝不自动覆盖（可能是中间人攻击或主机重装）。
 *
 * @author yuanke
 */
public class HostKeyMismatchException extends SshOpException {

    public HostKeyMismatchException(String message) {
        super("E_HOST_KEY_MISMATCH", message, false);
    }
}
