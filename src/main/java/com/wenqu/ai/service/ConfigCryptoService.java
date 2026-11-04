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
 *       轮换＝放一把更高版本的 PEM 私钥并重启，此后新写入用新版本，历史密文照旧按自己的 kid 解密
 *       ——<b>存量密文不会被批量重封</b>，对应版本的密钥文件必须一直留着，撤下即解不开。</li>
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

    /**
     * 旧版密文标记：JWE 改造前的分块 RSA 形态（{@code RSA:v2…}）。那一代的读取器已退役，
     * 当前版本<b>解不出真值</b>——本常量只用于「识别这仍是密文」，不是「能读它」。
     */
    public static final String LEGACY_PREFIX = "RSA:";

    private final DeploymentSecrets secrets;

    public ConfigCryptoService(DeploymentSecrets secrets) {
        this.secrets = secrets;
    }

    /** 是否已加密 */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 这个值是否仍是密文形态（{@value #PREFIX} 或旧版 {@value #LEGACY_PREFIX} 开头）。
     * <p><b>用途是「识别读不了」，不是「能读」</b>：旧 RSA 密文被明文迁移再封一层 JWE 后，
     * 当前版本永远解不出真值，拿去调网关只会 401。用它把这类行挡在「明文待迁移」之外，
     * 否则 token 本体会被套进两层密文——v2 私钥退场后彻底解不开
     * （2026-10-27 真机：sandbox.token 被套成 {@code RSA:v2} 套 JWE，运行时 401）。
     */
    public boolean looksLikeCiphertext(String value) {
        return value != null && (isEncrypted(value) || value.startsWith(LEGACY_PREFIX));
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

    /** 解出明文；解不开返回 null（不写日志，由调用方决定怎么暴露这个故障） */
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
