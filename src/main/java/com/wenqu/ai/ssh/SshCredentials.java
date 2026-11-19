package com.wenqu.ai.ssh;

/**
 * 一次性解密后的 SSH 连接凭据（只在建连瞬间存在，不缓存字段、不进日志/审计）。
 *
 * @param hostId            主机 id
 * @param host              域名/IP
 * @param port              端口
 * @param username          登录用户
 * @param authType          password / private_key
 * @param password          明文密码（authType=password）
 * @param privateKey        明文私钥（authType=private_key，OpenSSH/PEM）
 * @param passphrase        私钥口令（可空）
 * @param pinnedFingerprint 已固定的主机指纹（可空=首次 TOFU）
 */
public record SshCredentials(String hostId, String host, int port, String username, String authType,
                             String password, String privateKey, String passphrase,
                             String pinnedFingerprint) {

    public boolean passwordAuth() {
        return SshHost.AUTH_PASSWORD.equals(authType);
    }

    public boolean keyAuth() {
        return SshHost.AUTH_PRIVATE_KEY.equals(authType);
    }

    /** 日志/审计里可安全使用的目标描述（不含凭据）。 */
    public String target() {
        return username + "@" + host + ":" + port;
    }
}
