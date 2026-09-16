package com.wisesoft.ai.util;

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

    /** 签发访问令牌（有效期 ttlSeconds） */
    public static String issueToken(String secret, String issuer, String audience,
                                    String uid, String role, long ttlSeconds) {
        long now = System.currentTimeMillis() / 1000L;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", uid);
        payload.put("role", role);
        payload.put("iss", issuer);
        payload.put("aud", audience);
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

    /** 随机密钥（32 字节 hex，64 字符）；开发期未配置 jwt-secret 时自动生成 */
    public static String randomSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
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
