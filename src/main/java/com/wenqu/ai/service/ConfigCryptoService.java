package com.wenqu.ai.service;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 敏感数据加密（模型 API Key、凭据、认证源 config、MCP 请求头、设置页密钥项）。
 * <p>
 * 密文是标准 <b>JWE compact</b>（{@value #PREFIX} 标记 + RSA-OAEP-256 包裹 A256-GCM 数据密钥）：
 * <ul>
 *   <li>信封加密由 JOSE 完成，取代原来「RSA 每 190 字节分块直接加密」——分块 RSA 没有完整性保护，
 *       GCM 认证标签是自带的；长 JSON 也不再受 RSA 分组上限约束。</li>
 *   <li>密钥版本走 JWE header 的 {@code kid}，按 kid 从 {@link DeploymentSecrets} 的密钥环取钥解密；
 *       轮换＝放一把更高版本的 PEM 私钥并重启，启动时 SecretRotationRunner 把存量重封到新 kid。</li>
 *   <li>无 {@value #PREFIX} 前缀的值视为历史明文，原样返回（明文列不批量改写）。</li>
 * </ul>
 * 加密失败明文入库并 fail-loud；解密失败返回原值（调用方网关 401 快速暴露密钥不一致），不静默吞。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigCryptoService {

    /** 密文标记：以此开头表示 JWE 密文；无前缀视为历史明文 */
    public static final String PREFIX = "JWE:";

    private final DeploymentSecrets secrets;

    public ConfigCryptoService(DeploymentSecrets secrets) {
        this.secrets = secrets;
    }

    /** 当前加密所用密钥版本（密钥环为空时 0） */
    public int currentVersion() {
        return secrets.currentRsaVersion();
    }

    /** 是否已加密 */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 是否已由当前密钥保护——SecretRotationRunner 的判据，false 就要重封。
     * 旧的分块 RSA 形态（{@code RSA:…}）不属于 JWE，由 Runner 里的旧格式读取器负责搬到 JWE。
     */
    public boolean onCurrentKey(String stored) {
        if (!isEncrypted(stored)) {
            return false;
        }
        return secrets.currentKid().equals(kidOf(stored));
    }

    /** 加密（空值/已加密值原样返回；密钥缺失或异常时明文返回并 fail-loud，不阻塞保存流程） */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain)) {
            return plain;
        }
        DeploymentSecrets.RsaKey key = secrets.currentRsaKey();
        if (key == null) {
            log.error("[Crypto] RSA 密钥环为空，敏感配置将以明文存储（请检查密钥目录初始化日志）");
            return plain;
        }
        try {
            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                    .keyID(secrets.kidOf(key.version()))
                    .build();
            JWEObject jwe = new JWEObject(header, new Payload(plain));
            jwe.encrypt(new RSAEncrypter(key.publicKey()));
            return PREFIX + jwe.serialize();
        } catch (Exception e) {
            log.error("[Crypto] 加密失败，敏感配置将以明文存储: {}", e.getMessage());
            return plain;
        }
    }

    /** 解密（null/明文原样返回；kid 不在密钥环内或密文损坏时返回原值并 fail-loud） */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        String plain = tryDecrypt(stored);
        if (plain == null) {
            log.error("[Crypto] 密文无法解密（密钥环里没有 kid={} 的密钥，或密文已损坏），按原值返回", kidOf(stored));
            return stored;
        }
        return plain;
    }

    /**
     * 用当前密钥重封存量密文（轮换收尾）。
     * <p>只在确实解得开时产出新密文，解不开返回 null 让调用方跳过——把解不开的密文当明文再封一层，
     * 等于把「密钥不一致」这个可诊断故障洗成不可逆的数据损坏。
     */
    public String reencrypt(String stored) {
        if (!isEncrypted(stored)) {
            return null;
        }
        String plain = tryDecrypt(stored);
        if (plain == null || plain.isBlank()) {
            return null;
        }
        return encrypt(plain);
    }

    /** 解出明文；解不开返回 null（不写日志，由调用方决定原值返回还是跳过重封） */
    private String tryDecrypt(String stored) {
        DeploymentSecrets.RsaKey key = secrets.byKid(kidOf(stored));
        if (key == null) {
            return null;
        }
        try {
            JWEObject jwe = JWEObject.parse(stored.substring(PREFIX.length()));
            jwe.decrypt(new RSADecrypter(key.privateKey()));
            return jwe.getPayload().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 密文 header 里的 kid（只读第一个 base64url 段，不做解密）；读不到返回 null */
    private String kidOf(String stored) {
        try {
            return JWEObject.parse(stored.substring(PREFIX.length())).getHeader().getKeyID();
        } catch (Exception e) {
            return null;
        }
    }
}
