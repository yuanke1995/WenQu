package com.wenqu.ai.startup;

import com.wenqu.ai.service.DeploymentSecrets;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

/**
 * 旧密文格式的一次性读取器（仅供 {@link SecretRotationRunner} 把它搬到 JWE 用）。
 * <p>
 * 改造前敏感数据是「RSA/OAEP-SHA256 每 190 字节明文分块直接加密」，密文形如
 * {@code RSA:vN:<base64>}（带版本）或 {@code RSA:<base64>}（密钥环引入前，即 vN=1）；
 * 认证源 config 还曾误加过一层前缀（{@code RSA:RSA:…}，写侧已修）。
 * <p>
 * 这个类<b>故意不在运行路径里</b>：库里全部密文被重封成 JWE 之后，运行期只剩 JOSE 一条路。
 * 留着它只为了让存量数据能读出来再封一遍——重封完成后可以整类删除（判据：Runner 的
 * 「旧格式」计数归零）。
 *
 * @author yuanke
 */
final class LegacyRsaCipher {

    private static final String PREFIX = "RSA:";
    private static final int CIPHER_BLOCK = 256;
    private static final String TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    /** 密钥环引入前的密文没有版本标注，对应的就是导入后的那把（v1） */
    private static final String LEGACY_KID = "v1";

    private LegacyRsaCipher() {
    }

    /** 解出明文；解不开返回 null（调用方保留原值并计数，绝不把解不开的密文当明文重封） */
    static String decrypt(String stored, Function<String, DeploymentSecrets.RsaKey> keysByKid) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return null;
        }
        String body = stored.substring(PREFIX.length());
        // 历史上误把 encrypt() 的产物再拼一次前缀入库，剥掉多余那层
        for (int depth = 0; body.startsWith(PREFIX) && depth < 2; depth++) {
            body = body.substring(PREFIX.length());
        }
        if (body.startsWith(PREFIX)) {
            return null;
        }
        String kid = LEGACY_KID;
        if (body.matches("^v\\d+:.*")) {
            int split = body.indexOf(':');
            kid = body.substring(0, split);
            body = body.substring(split + 1);
        }
        DeploymentSecrets.RsaKey key = keysByKid.apply(kid);
        if (key == null) {
            return null;
        }
        try {
            byte[] all = Base64.getMimeDecoder().decode(body);
            if (all.length == 0 || all.length % CIPHER_BLOCK != 0) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int off = 0; off < all.length; off += CIPHER_BLOCK) {
                Cipher cipher = Cipher.getInstance(TRANSFORM);
                cipher.init(Cipher.DECRYPT_MODE, key.privateKey());
                out.write(cipher.doFinal(all, off, CIPHER_BLOCK));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isLegacy(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
