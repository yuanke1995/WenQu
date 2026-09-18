package com.wisesoft.wenqu.common;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import com.alibaba.fastjson2.JSON;

/**
 * 认证工具集。
 *
 * <p>由参考实现的 utils/auth_utils.py 逐函数翻译：安全密钥校验（存在性/无首尾空白/≥32 字符/
 * 相互独立）、API Key 生成与确定性派生、密码哈希与校验、JWT 签发与解析。
 *
 * <p>必要替换（能力差异，均已注明）：
 * <ul>
 *   <li>JWT：参考实现用 PyJWT 库；本工程遵循「不引入任何 JWT 三方库」的既有约定，
 *       手写 HS256 签发/校验（header {alg:HS256,typ:JWT} + claims exp/iss/aud，
 *       语义与 PyJWT 的 require=["exp","sub","iss","aud"] 校验一致）。
 *   <li>密码哈希：参考实现用 argon2-cffi（{@code $argon2} 前缀）；JDK 无 argon2 实现，
 *       改用本工程既有的 PBKDF2WithHmacSHA256（{@code $pbkdf2$} 前缀）。
 *       hash/verify 成对出现在本类中，自洽；旧库中的 argon2 串会因前缀不符而校验失败
 *       （与参考实现校验非 argon2 串直接返回 False 的行为一致）。
 *   <li>产品命名：环境变量 {@code WENQU_ENV}、{@code WENQU_INSTANCE_ID}、
 *       JWT audience/issuer、派生 payload 前缀、Key 前缀同步替换；算法结构不变。
 * </ul>
 */
public final class AuthUtils {

    public static final String JWT_ALGORITHM = "HS256";
    public static final long JWT_EXPIRATION_SECONDS = 7L * 24 * 60 * 60;
    public static final String JWT_AUDIENCE = "wenqu-know-api";
    public static final String PUBLIC_DEFAULT_JWT_SECRET_KEY = "wenqu_secure_key";
    public static final Set<String> SECURITY_SECRET_NAMES =
            Set.of("JWT_SECRET_KEY", "API_KEY_DERIVATION_SECRET", "SANDBOX_PROVISIONER_TOKEN");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 120_000;

    /** 开发环境自动生成的临时值的进程内覆盖表（参考实现写回 os.environ；Java 不可写环境变量）。 */
    private static final Map<String, String> DEV_ENV_OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();

    private AuthUtils() {}

    // ==================== 环境与安全密钥 ====================

    private static boolean isProductionEnv() {
        String value = env("WENQU_ENV", "development").strip().toLowerCase();
        return value.equals("prod") || value.equals("production");
    }

    private static String env(String name, String defaultValue) {
        String override = DEV_ENV_OVERRIDES.get(name);
        if (override != null) {
            return override;
        }
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }

    private static String getOrCreateDevEnv(String name, java.util.function.Supplier<String> valueFactory) {
        String value = env(name, "").strip();
        if (!value.isEmpty()) {
            return value;
        }
        if (isProductionEnv()) {
            throw new IllegalStateException(name + " 未配置，请在生产环境的 .env.prod 中设置持久化随机值");
        }
        value = valueFactory.get();
        DEV_ENV_OVERRIDES.put(name, value);
        System.out.println(name + " 未配置，开发环境已自动生成临时随机值，服务重启后会重新生成。");
        return value;
    }

    /**
     * 校验进程实际收到的安全密钥，而不是 dotenv 原始文本。
     *
     * <p>参考实现返回命名的值字典；Java 侧以 Map 承载，键与参考实现一致。
     */
    private static Map<String, String> validateConfiguredSecuritySecrets(Set<String> requiredNames) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : SECURITY_SECRET_NAMES) {
            values.put(name, env(name, ""));
        }
        for (String name : requiredNames) {
            if (values.get(name).isEmpty()) {
                throw new IllegalStateException(name + " 未配置");
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.equals(value.strip())) {
                throw new IllegalStateException(entry.getKey() + " 不能包含首尾空白");
            }
            if (value.length() < 32) {
                throw new IllegalStateException(entry.getKey() + " 必须配置为至少 32 个字符的持久随机值");
            }
        }
        var populated = values.entrySet().stream().filter(e -> !e.getValue().isEmpty()).toList();
        for (int index = 0; index < populated.size(); index++) {
            var name = populated.get(index).getKey();
            var value = populated.get(index).getValue();
            for (int other = index + 1; other < populated.size(); other++) {
                var otherName = populated.get(other).getKey();
                var otherValue = populated.get(other).getValue();
                if (MessageDigest.isEqual(
                        value.getBytes(StandardCharsets.UTF_8), otherValue.getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalStateException("安全密钥必须相互独立: " + name + " 与 " + otherName + " 不得复用");
                }
            }
        }
        return values;
    }

    private static String getJwtSecretKey() {
        String secretKey = env("JWT_SECRET_KEY", "");
        if (secretKey.isEmpty()) {
            if (isProductionEnv()) {
                throw new IllegalStateException("JWT_SECRET_KEY 未配置，请在生产环境的 .env.prod 中设置持久化随机值");
            }
            secretKey = tokenHex(32);
            // 参考实现写回 os.environ 使同进程后续调用复用同一密钥；此处写入进程内覆盖表
            DEV_ENV_OVERRIDES.put("JWT_SECRET_KEY", secretKey);
            System.out.println("JWT_SECRET_KEY 未配置，开发环境已自动生成临时随机值，服务重启后会重新生成。");
        }
        if (isProductionEnv() && secretKey.equals(PUBLIC_DEFAULT_JWT_SECRET_KEY)) {
            throw new IllegalStateException("JWT_SECRET_KEY 不能使用公开默认密钥，请重新生成随机强密钥");
        }
        validateConfiguredSecuritySecrets(Set.of("JWT_SECRET_KEY"));
        return secretKey;
    }

    private static String getApiKeyDerivationSecret() {
        return validateConfiguredSecuritySecrets(Set.of("API_KEY_DERIVATION_SECRET")).get("API_KEY_DERIVATION_SECRET");
    }

    private static String getJwtIssuer() {
        String instanceId =
                getOrCreateDevEnv("WENQU_INSTANCE_ID", () -> "instance-" + tokenHex(8));
        return "wenqu-know:" + instanceId;
    }

    // ==================== API Key ====================

    /** 随机生成 API Key，返回 (full_key, key_hash, key_prefix)。 */
    public static String[] generateApiKey() {
        String randomPart = tokenHex(24);
        String fullKey = "wqkey_" + randomPart;
        String keyHash = sha256Hex(fullKey);
        String keyPrefix = fullKey.substring(0, Math.min(12, fullKey.length()));
        return new String[] {fullKey, keyHash, keyPrefix};
    }

    /**
     * 由稳定幂等域确定性派生可重放的 API Key，数据库仍只保存 hash。
     *
     * <p>返回 (full_key, key_hash, key_prefix)；派生 payload 前缀按产品命名替换
     * （参考实现使用其产品前缀），Key 前缀 {@code wqkey_}（参考实现亦为其产品前缀）。
     */
    public static String[] deriveApiKey(String idempotencyScope, Object subjectId) {
        String scope = idempotencyScope == null ? "" : idempotencyScope.strip();
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("API Key 幂等域不能为空");
        }
        String payload = "wenqu-api-key-v1:" + subjectId + ":" + scope;
        String digest = hmacSha256Hex(getApiKeyDerivationSecret(), payload);
        String fullKey = "wqkey_" + digest.substring(0, Math.min(48, digest.length()));
        return new String[] {fullKey, sha256Hex(fullKey), fullKey.substring(0, Math.min(12, fullKey.length()))};
    }

    /** 在服务发布前验证独立、持久的 API Key 派生主密钥。 */
    public static void requireApiKeyDerivationSecret() {
        getApiKeyDerivationSecret();
    }

    /** 在任何外部服务启动前校验三项真实运行时安全密钥。 */
    public static void requireSecuritySecrets() {
        getJwtSecretKey();
        validateConfiguredSecuritySecrets(SECURITY_SECRET_NAMES);
    }

    // ==================== 密码哈希 ====================

    /** PBKDF2WithHmacSHA256（参考实现为 argon2，见类注解的能力差异说明）。 */
    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return "$pbkdf2$" + PBKDF2_ITERATIONS + "$" + base64Url(salt) + "$" + base64Url(derived);
    }

    public static boolean verifyPassword(String storedPassword, String providedPassword) {
        if (storedPassword == null || !storedPassword.startsWith("$pbkdf2")) {
            return false;
        }
        try {
            String[] parts = storedPassword.split("\\$");
            // 形如 "", "pbkdf2", iterations, salt, hash
            if (parts.length != 5) {
                return false;
            }
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[4]);
            byte[] actual = pbkdf2(providedPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | java.security.spec.InvalidKeySpecException exc) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 不可用", exc);
        }
    }

    // ==================== JWT ====================

    /** 签发 HS256 JWT；expiresDelta 为 null 时使用默认 7 天有效期。 */
    public static String createAccessToken(Map<String, Object> data, java.time.Duration expiresDelta) {
        Map<String, Object> toEncode = new LinkedHashMap<>(data);
        java.time.OffsetDateTime expire = DateTimeUtils.utcNow()
                .plus(expiresDelta != null ? expiresDelta : java.time.Duration.ofSeconds(JWT_EXPIRATION_SECONDS));
        toEncode.put("exp", expire.toEpochSecond());
        toEncode.put("iss", getJwtIssuer());
        toEncode.put("aud", JWT_AUDIENCE);
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(JSON.toJSONString(toEncode).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + hmacSha256Base64Url(getJwtSecretKey(), signingInput);
    }

    /** 解析并完整校验 JWT；任何失败返回 null（对应 PyJWTError 分支）。 */
    public static Map<String, Object> decodeToken(String token) {
        try {
            return decodeAndValidate(token);
        } catch (RuntimeException | java.text.ParseException exception) {
            return null;
        }
    }

    /** 解析并校验 JWT；过期抛「令牌已过期」，其余失败抛「无效的令牌」。 */
    public static Map<String, Object> verifyAccessToken(String token) {
        try {
            return decodeAndValidate(token);
        } catch (TokenExpiredException exception) {
            throw new IllegalStateException("令牌已过期");
        } catch (RuntimeException | java.text.ParseException exception) {
            throw new IllegalStateException("无效的令牌");
        }
    }

    private static final class TokenExpiredException extends RuntimeException {}

    private static Map<String, Object> decodeAndValidate(String token)
            throws java.text.ParseException {
        if (token == null) {
            throw new IllegalArgumentException("token 不能为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("token 结构不合法");
        }
        String expectedSignature = hmacSha256Base64Url(getJwtSecretKey(), parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("签名不匹配");
        }
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        com.alibaba.fastjson2.JSONObject claims = JSON.parseObject(payloadJson);

        // options={"require": ["exp","sub","iss","aud"]}（PyJWT 语义）
        for (String required : new String[] {"exp", "sub", "iss", "aud"}) {
            if (!claims.containsKey(required)) {
                throw new IllegalArgumentException("缺少必需声明: " + required);
            }
        }
        long exp = claims.getLongValue("exp");
        long now = DateTimeUtils.utcNow().toEpochSecond();
        if (exp < now) {
            throw new TokenExpiredException();
        }
        if (!JWT_AUDIENCE.equals(claims.getString("aud"))) {
            throw new IllegalArgumentException("audience 不匹配");
        }
        if (!getJwtIssuer().equals(claims.getString("iss"))) {
            throw new IllegalArgumentException("issuer 不匹配");
        }
        return claims;
    }

    // ==================== 基础工具 ====================

    /** secrets.token_urlsafe(n)：n 字节随机数的 URL-safe Base64（无填充）。 */
    public static String tokenUrlSafe(int nbytes) {
        byte[] bytes = new byte[nbytes];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    /** secrets.token_hex(n)：n 字节随机数的十六进制串。 */
    public static String tokenHex(int nbytes) {
        byte[] bytes = new byte[nbytes];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    private static String hmacSha256Hex(String key, String value) {
        return toHex(hmacSha256(key, value));
    }

    private static String hmacSha256Base64Url(String key, String value) {
        return base64Url(hmacSha256(key, value));
    }

    private static byte[] hmacSha256(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exc) {
            throw new IllegalStateException("HmacSHA256 不可用", exc);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
