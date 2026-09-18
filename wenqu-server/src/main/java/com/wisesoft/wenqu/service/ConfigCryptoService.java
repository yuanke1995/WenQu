package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 敏感配置项 RSA 加解密（*.apiKey 入库保护）
 * <p>
 * - RSA-2048 + OAEP(SHA-256)：公钥加密、私钥解密；密文带 {@link #PREFIX} 前缀，无前缀视为历史明文（读取原样返回，兼容存量）
 * - 密钥文件默认在数据目录 secret/config-rsa.key（随数据卷持久化；删除后已加密配置无法解密），
 *   可用环境变量 AI_CONFIG_RSA_KEY 覆盖路径——多实例部署共享同一 DB 时必须挂载同一密钥文件，否则跨实例无法互解
 * - 加解密失败均不抛异常：加密失败明文入库（fail-loud 日志）、解密失败返回原值（调用网关 401 快速暴露密钥不一致）
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigCryptoService {

    /** 密文前缀（入库值以此开头表示已加密；无前缀视为历史明文） */
    public static final String PREFIX = "RSA:";
    /** RSA-2048 + OAEP(SHA-256) 单块明文上限 190 字节；密文块固定 256 字节（API Key 通常几十字节，一块即容纳） */
    private static final int MAX_PLAIN_BLOCK = 190;
    private static final int CIPHER_BLOCK = 256;
    private static final String TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String ALGORITHM = "RSA";

    private final AppProperties properties;
    private final Environment environment;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    public ConfigCryptoService(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        Path keyFile = keyFile();
        try {
            if (Files.exists(keyFile)) {
                loadKeys(keyFile);
                log.info("[Crypto] RSA 密钥已加载: {}", keyFile);
            } else {
                generateKeys(keyFile);
                log.info("[Crypto] 已生成新 RSA 密钥对并写入 {}（删除该文件将导致已加密配置无法解密）", keyFile);
            }
        } catch (Exception e) {
            log.error("[Crypto] RSA 密钥初始化失败，敏感配置将以明文存储: {}", e.getMessage());
        }
    }

    /** 密钥文件路径：env AI_CONFIG_RSA_KEY 优先，默认 {ai-app.images.dir}/secret/config-rsa.key */
    private Path keyFile() {
        String custom = environment.getProperty("AI_CONFIG_RSA_KEY");
        if (custom != null && !custom.isBlank()) {
            return Paths.get(custom.trim());
        }
        return Paths.get(properties.getImages().getDir(), "secret", "config-rsa.key");
    }

    /** 文件格式两行：public=X509 Base64 / private=PKCS8 Base64。
     *  注意前缀长度：public= 7 字符、private= 8 字符——substring 长度写错会丢 base64 首字符，
     *  导致密钥文件永远无法解码（此前 bug：重启后密钥失效，已加密配置无法解密） */
    private void loadKeys(Path keyFile) throws Exception {
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        for (String line : Files.readAllLines(keyFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("public=")) {
                publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(line.substring(7))));
            } else if (line.startsWith("private=")) {
                privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(line.substring(8))));
            }
        }
        if (publicKey == null || privateKey == null) {
            throw new IllegalStateException("密钥文件内容不完整（缺少 public/private 段）");
        }
    }

    private void generateKeys(Path keyFile) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.createDirectories(keyFile.getParent());
        String content = "public=" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n"
                + "private=" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n";
        Files.writeString(keyFile, content, StandardCharsets.UTF_8);
        publicKey = pair.getPublic();
        privateKey = pair.getPrivate();
    }

    /** 是否已加密（RSA: 前缀） */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密（空值/已加密值原样返回；密钥未初始化或加密异常时明文返回并 fail-loud 告警，不阻塞保存流程）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain)) {
            return plain;
        }
        if (publicKey == null || privateKey == null) {
            log.error("[Crypto] 密钥未初始化，敏感配置将以明文存储（请检查密钥文件初始化日志）");
            return plain;
        }
        try {
            byte[] data = plain.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int off = 0; off < data.length; off += MAX_PLAIN_BLOCK) {
                int len = Math.min(MAX_PLAIN_BLOCK, data.length - off);
                Cipher cipher = Cipher.getInstance(TRANSFORM);
                cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                out.write(cipher.doFinal(data, off, len));
            }
            return PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.error("[Crypto] 加密失败，敏感配置将以明文存储: {}", e.getMessage());
            return plain;
        }
    }

    /**
     * 解密（null/明文原样返回，兼容存量数据；密文非法或密钥不匹配时返回原值并 fail-loud 告警，
     * 后续网关调用 401 快速暴露密钥不一致，避免静默错误）
     */
    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        if (privateKey == null) {
            log.error("[Crypto] 密钥未初始化，无法解密配置（密文将以原值参与调用，预期网关 401）");
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length == 0 || all.length % CIPHER_BLOCK != 0) {
                log.error("[Crypto] 密文长度非法（{} 字节，应为 {} 的倍数），按原值返回", all.length, CIPHER_BLOCK);
                return stored;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int off = 0; off < all.length; off += CIPHER_BLOCK) {
                Cipher cipher = Cipher.getInstance(TRANSFORM);
                cipher.init(Cipher.DECRYPT_MODE, privateKey);
                out.write(cipher.doFinal(all, off, CIPHER_BLOCK));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[Crypto] 解密失败（密钥文件可能已更换/损坏），按原值返回: {}", e.getMessage());
            return stored;
        }
    }
}
