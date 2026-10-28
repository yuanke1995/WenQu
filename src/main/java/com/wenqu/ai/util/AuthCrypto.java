package com.wenqu.ai.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地登录的密码哈希与访问令牌——**纯 JDK 实现，不引入第三方依赖**。
 * <ul>
 *   <li>密码：PBKDF2WithHmacSHA256，存储格式 {@code pbkdf2$<iterations>$<saltB64url>$<hashB64url>}；
 *       校验用 {@link MessageDigest#isEqual} 恒定时间比较</li>
 *   <li>令牌：标准 JWT（HS256），载荷含 {@code sub/role/iss/aud/iat/exp}；
 *       签名、有效期、iss/aud 均由 {@link #parseToken} 校验，任一不符返回 null</li>
 * </ul>
 *
 * @author yuanke
 */
public final class AuthCrypto {

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2";
    private static final String HMAC_ALGO = "HmacSHA256";

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final ObjectMapper OM = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthCrypto() {
    }

    // ==================== 密码 ====================

    /** 生成密码哈希（每次随机盐） */
    public static String hashPassword(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(raw.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$" + B64E.encodeToString(salt) + "$" + B64E.encodeToString(dk);
    }

    /** 校验密码（恒定时间比较；存储格式不符一律 false） */
    public static boolean verifyPassword(String stored, String raw) {
        if (stored == null || raw == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !PREFIX.equals(p[0])) return false;
        try {
            int iter = Integer.parseInt(p[1]);
            byte[] salt = B64D.decode(p[2]);
            byte[] expected = B64D.decode(p[3]);
            if (expected.length == 0) return false;
            byte[] actual = pbkdf2(raw.toCharArray(), salt, iter, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否为本类生成的密码哈希（用于判断「该用户是否已设置密码」） */
    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX + "$");
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            try {
                return SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    // ==================== 令牌（JWT HS256） ====================

    /**
     * 签发访问令牌（有效期 ttlSeconds；epoch=签发时的会话代数，写进 ep 声明）。
     * <p>会话治理判据在 {@code UserContextInterceptor}：令牌 ep 与用户当前代数不符即失效——
     * 改密 / 强制下线把代数 +1，此人所有已签发令牌（含其他设备）当场作废。
     * 存量令牌没有 ep 声明，按代数 0 兼容：升级不踢人，安全动作才踢。
     */
    public static String issueToken(String secret, String issuer, String audience,
                                    String uid, String role, long ttlSeconds, long epoch) {
        long now = System.currentTimeMillis() / 1000L;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", uid);
        payload.put("role", role);
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("ep", epoch);
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);
        String signingInput = b64(json(header)) + "." + b64(json(payload));
        return signingInput + "." + b64(hmac(secret, signingInput));
    }

    /**
     * 校验令牌并返回载荷；签名不符 / 结构非法 / 已过期 / iss-与 aud 不匹配 → null。
     */
    public static Map<String, Object> parseToken(String secret, String issuer, String audience, String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(secret, signingInput);
        byte[] actual;
        try {
            actual = B64D.decode(parts[2]);
        } catch (Exception e) {
            return null;
        }
        if (!MessageDigest.isEqual(expected, actual)) return null;
        Map<String, Object> claims;
        try {
            claims = OM.readValue(B64D.decode(parts[1]), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
        Object sub = claims.get("sub");
        Object exp = claims.get("exp");
        if (sub == null || String.valueOf(sub).isBlank() || exp == null) return null;
        if (!issuer.equals(String.valueOf(claims.get("iss")))) return null;
        if (!audience.equals(String.valueOf(claims.get("aud")))) return null;
        try {
            if (Long.parseLong(String.valueOf(exp)) < System.currentTimeMillis() / 1000L) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return claims;
    }

    private static byte[] hmac(String secret, String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private static String b64(byte[] bytes) {
        return B64E.encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(Map<String, Object> map) {
        try {
            return OM.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("令牌载荷序列化失败", e);
        }
    }
}
