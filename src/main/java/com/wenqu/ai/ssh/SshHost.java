package com.wenqu.ai.ssh;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SSH 远程主机：工作区的「远端执行后端」。
 *
 * <p>与本机执行器（{@code c_ai_executor}）是两类个人资产，互不混表：
 * <ul>
 *   <li>本机执行器是用户电脑上的一个进程，主动长轮询；</li>
 *   <li>SSH 主机是服务端主动连接的远端 POSIX 机器（SFTP + exec + PTY）。</li>
 * </ul>
 *
 * <p><b>凭据安全</b>：密码/私钥/口令一律以 {@link com.wenqu.ai.service.ConfigCryptoService} 加密后落库
 * （{@code *_cipher} 列），实体本身只在服务端内部流转；任何出参都必须经 {@code SshHostService#toDto}
 * 脱敏为 {@code hasPassword/hasPrivateKey}。明文只在建连瞬间解密，不写日志、不进审计。
 *
 * <p><b>指纹</b>：首次连接记录 {@code SHA256:...}（TOFU），此后严格比对，不一致 fail-closed。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_ssh_host")
public class SshHost {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户（个人资产，不共享） */
    private String uid;

    /** 显示名 */
    private String name;

    /** 域名或 IP */
    private String host;

    /** SSH 端口 */
    private Integer port;

    /** 登录用户 */
    private String username;

    /** 认证方式：password / private_key */
    private String authType;

    /** 密码（JWE 密文） */
    private String passwordCipher;

    /** 私钥（JWE 密文） */
    private String privateKeyCipher;

    /** 私钥口令（JWE 密文） */
    private String passphraseCipher;

    /** 主机公钥指纹（SHA256:...） */
    private String hostKeyFingerprint;

    /** 主机公钥算法 */
    private String hostKeyAlgorithm;

    /** active / disabled */
    private String status;

    private LocalDateTime lastProbeAt;

    /** 1=通 0=失败；null=从未探测 */
    private Integer lastProbeOk;

    private String lastError;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---------- 常量 ----------
    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_PRIVATE_KEY = "private_key";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    /** 虚拟执行器 id 前缀：ExecutorRegistry 据此把调用分发给本进程的 SSH 执行器。 */
    public static final String EXECUTOR_PREFIX = "ssh:";

    public static String executorIdOf(String hostId) {
        return EXECUTOR_PREFIX + hostId;
    }

    /** 从虚拟执行器 id 还原 hostId；不是 ssh: 前缀返回 null。 */
    public static String hostIdOf(String executorId) {
        if (executorId == null || !executorId.startsWith(EXECUTOR_PREFIX)) return null;
        String id = executorId.substring(EXECUTOR_PREFIX.length());
        return id.isBlank() ? null : id;
    }
}
