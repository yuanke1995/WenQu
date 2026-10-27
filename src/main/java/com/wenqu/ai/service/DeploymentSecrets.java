package com.wenqu.ai.service;

import com.wenqu.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署级密钥材料：登录令牌签名密钥 + 配置加密 RSA 密钥环。
 *
 * <p><b>登录令牌密钥</b>走 Spring Boot 自己的配置层（env {@code AI_JWT_SECRET} / yml，K8s Secret、
 * docker --secret 注进 env 是同一件事）。判据只用框架的 profile 机制：{@code local}（仓库内的本机
 * 私有配置）允许临时随机生成并告警，其余任何 profile 缺密钥直接启动失败——随机密钥在多副本下
 * 各签各验（登录随机 401），重启还会作废全员登录态与图片签名地址，属企业级红线，不引入额外开关。
 *
 * <p><b>RSA 密钥环</b>是密钥目录里的一批标准 PEM 私钥（{@code {dir}/rsa/v3.key}，文件名里的数字就是版本）：
 * 解析交给 BouncyCastle 的 PEMParser（PKCS#1 / PKCS#8 / OpenSSL 3 的 v2 都认，JCA 自己会报
 * {@code algid parse error}），加密走 JCA/Nimbus，版本号进 JWE header 的 kid。轮换＝往目录里丢一把
 * 更高版本的 PEM 再重启：新写入用当前版本，老密文按自己 kid 里的版本取钥，启动时
 * SecretRotationRunner 把存量重封到当前版本，重封完旧文件即可撤——双密钥过渡期是这套机制自带的，
 * 不需要停机，也不存在读到解不开的路径。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DeploymentSecrets {

    /** 密钥环里的一把 RSA 密钥（public 由 private 的模数/公钥指数导出，只存私钥文件） */
    public record RsaKey(int version, RSAPrivateKey privateKey, RSAPublicKey publicKey) {
    }

    private static final Pattern KEY_FILE = Pattern.compile("^v(\\d+)\\.key$");
    private static final String LEGACY_SINGLE_KEY = "config-rsa.key";
    private static final int KEY_SIZE = 2048;
    /** local profile＝仓库内的本机私有配置，允许临时密钥 */
    private static final String DEV_PROFILE = "local";

    private final AppProperties properties;
    private final Environment environment;

    private volatile String jwtSecret;
    /** 版本降序，第 0 把是当前加密密钥 */
    private volatile List<RsaKey> rsaRing = List.of();
    /** 目录里有解析不了的密钥文件时置真：禁止自动生成新密钥覆盖它们（覆盖＝存量密文永久失效） */
    private volatile boolean unreadableKeyPresent;

    public DeploymentSecrets(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        this.jwtSecret = resolveJwtSecret();
        this.rsaRing = loadRsaRing();
        log.info("[Secrets] 密钥目录={} 登录令牌密钥={} RSA 密钥环={}（当前版本 v{}）",
                secretsDir(), "已解析", describeRing(), currentRsaVersion());
    }

    /** 密钥目录：env/YAML {@code ai-app.secrets.dir} 优先，默认落盘数据目录下的 secret/ */
    public Path secretsDir() {
        String custom = properties.getSecrets().getDir();
        if (custom != null && !custom.isBlank()) {
            return Paths.get(custom.trim());
        }
        return Paths.get(properties.getImages().getDir(), "secret");
    }

    /** 登录令牌签名密钥（恒非空：非 local profile 缺失即启动失败） */
    public String jwtSecret() {
        return jwtSecret;
    }

    /** 当前用于加密的 RSA 密钥版本（无密钥时为 0） */
    public int currentRsaVersion() {
        return rsaRing.isEmpty() ? 0 : rsaRing.get(0).version();
    }

    /** JWE header 里的 kid（密钥版本的标址名，轮换与解密都按它取钥） */
    public String currentKid() {
        return kidOf(currentRsaVersion());
    }

    public String kidOf(int version) {
        return "v" + version;
    }

    /** kid → 密钥；找不到返回 null（历史密文对应已撤下的密钥） */
    public RsaKey byKid(String kid) {
        if (kid == null) {
            return null;
        }
        for (RsaKey k : rsaRing) {
            if (kidOf(k.version()).equals(kid)) {
                return k;
            }
        }
        return null;
    }

    /** 当前加密密钥（未初始化返回 null） */
    public RsaKey currentRsaKey() {
        return rsaRing.isEmpty() ? null : rsaRing.get(0);
    }

    private String describeRing() {
        if (rsaRing.isEmpty()) return "空";
        StringBuilder sb = new StringBuilder();
        for (RsaKey k : rsaRing) {
            if (sb.length() > 0) sb.append(',');
            sb.append('v').append(k.version());
        }
        return sb.toString();
    }

    // ==================== 登录令牌签名密钥 ====================

    private String resolveJwtSecret() {
        String fromConfig = properties.getAuth().getJwtSecret();
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig.trim();
        }
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of(DEV_PROFILE))) {
            log.warn("[Secrets] 未配置 AI_JWT_SECRET，已按 local profile 生成临时随机密钥（重启后全员令牌失效，"
                    + "多副本无法互验）。非 local profile 缺该密钥会直接启动失败");
            return randomSecret();
        }
        throw new IllegalStateException("缺少登录令牌签名密钥 AI_JWT_SECRET（当前 profile 不是 " + DEV_PROFILE
                + "）：请注入 ≥32 位随机串且各副本一致。多副本用随机密钥会各签各验，登录会随机 401");
    }

    private static String randomSecret() {
        byte[] buf = new byte[48];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // ==================== RSA 密钥环 ====================

    private List<RsaKey> loadRsaRing() {
        Path dir = secretsDir().resolve("rsa");
        importLegacyKeys(dir);
        unreadableKeyPresent = false;
        List<RsaKey> ring = readRingDir(dir);
        if (ring.isEmpty() && unreadableKeyPresent) {
            log.error("[Secrets] 密钥目录 {} 里有解析不了的密钥文件，不会自动生成新密钥——"
                    + "覆盖它等于让存量密文永久失效。请人工修复该文件（或从备份放回）后重启", dir);
            return List.of();
        }
        if (ring.isEmpty()) {
            generateInto(dir, 1);
            ring = readRingDir(dir);
        }
        ring.sort(Comparator.comparingInt(RsaKey::version).reversed());
        return List.copyOf(ring);
    }

    private List<RsaKey> readRingDir(Path dir) {
        List<RsaKey> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) return found;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.sorted().toList()) {
                Matcher m = KEY_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int version = Integer.parseInt(m.group(1));
                try {
                    RsaKey key = readPemKeyFile(p, version);
                    if (key != null) {
                        found.add(key);
                    }
                } catch (Exception e) {
                    unreadableKeyPresent = true;
                    log.error("[Secrets] 密钥文件 {} 无法解析，已跳过（该 kid 的存量密文将无法解密）: {}",
                            p, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Secrets] 读取密钥环目录 {} 失败: {}", dir, e.getMessage());
        }
        return found;
    }

    /**
     * 旧密钥形态一次性迁入标准 PEM：本目录改造前的 {@code secret/config-rsa.key}（两行 base64）与
     * 中间态的 {@code secret/rsa/vN.key}（同格式）都转成 PKCS#8 PEM。环内文件就地替换
     * （临时文件 + ATOMIC_MOVE），且替换前回读校验——迁完即没有第二条读取路径，运行期只认 PEM，
     * 所以宁可留着旧文件报错，也不能写坏一把唯一还能解存量密文的密钥。
     */
    private void importLegacyKeys(Path dir) {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path p : stream.sorted().toList()) {
                    String name = p.getFileName().toString();
                    Matcher m = KEY_FILE.matcher(name);
                    if (!m.matches() || isPem(p)) {
                        continue;
                    }
                    int version = Integer.parseInt(m.group(1));
                    RsaKey key = readTwoLineKeyFileQuietly(p, version);
                    if (key == null || !rewriteAsPemVerified(p, key)) {
                        log.error("[Secrets] 旧格式密钥 {} 未能转成 PEM（保留原文件，请人工转换为 PKCS#8 PEM）", p);
                    } else {
                        log.info("[Secrets] 密钥 {} 已就地转成标准 PEM 私钥", p);
                    }
                }
            } catch (Exception e) {
                log.error("[Secrets] 扫描旧密钥文件失败: {}", e.getMessage());
            }
        }
        Path single = secretsDir().resolve(LEGACY_SINGLE_KEY);
        if (!Files.isReadable(single)) {
            return;
        }
        RsaKey key = readTwoLineKeyFileQuietly(single, 1);
        if (key == null) {
            log.error("[Secrets] 旧密钥 {} 无法解析，未迁入密钥环", single);
            return;
        }
        Path target = dir.resolve("v1.key");
        if (Files.exists(target)) {
            log.warn("[Secrets] 已有密钥 {}，旧文件 {} 保留不覆盖", target, single);
            return;
        }
        try {
            Files.createDirectories(dir);
            writePem(target, key);
            Files.delete(single);
            log.info("[Secrets] 旧密钥 {} 已迁入密钥环 v1（{}），旧文件已删除", single, target);
        } catch (Exception e) {
            log.error("[Secrets] 迁入旧密钥 {} 失败: {}", single, e.getMessage());
        }
    }

    private RsaKey readTwoLineKeyFileQuietly(Path file, int version) {
        try {
            return readTwoLineKeyFile(file, version);
        } catch (Exception e) {
            log.error("[Secrets] 解析旧格式密钥 {} 失败: {}", file, e.getMessage());
            return null;
        }
    }

    /** 就地转 PEM：写临时文件 → 回读校验能解出同一把模数 → 原子替换 */
    private boolean rewriteAsPemVerified(Path file, RsaKey key) {
        Path temp = file.resolveSibling(file.getFileName().toString() + ".converting");
        try {
            writePem(temp, key);
            RsaKey reread = readPemKeyFile(temp, key.version());
            if (reread == null || !java.util.Arrays.equals(reread.privateKey().getEncoded(),
                    key.privateKey().getEncoded())) {
                Files.deleteIfExists(temp);
                return false;
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            log.error("[Secrets] 转换密钥 {} 失败: {}", file, e.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // 临时文件清不掉不影响结论
            }
            return false;
        }
    }

    private boolean isPem(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).contains("-----BEGIN");
        } catch (Exception e) {
            return false;
        }
    }

    private void generateInto(Path dir, int version) {
        Path target = dir.resolve("v" + version + ".key");
        if (Files.exists(target)) {
            log.error("[Secrets] {} 已存在但不合法，且不覆盖——请人工检查该文件", target);
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            java.security.KeyPair pair = generator.generateKeyPair();
            RsaKey key = new RsaKey(version, (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic());
            Files.createDirectories(dir);
            writePem(target, key);
            log.warn("[Secrets] 密钥目录内没有 RSA 密钥，已生成 v{} 写入 {}——多副本必须挂载同一密钥目录，"
                    + "否则各副本密钥不同，A 副本加密的凭据在 B 副本无法解密", version, dir);
        } catch (Exception e) {
            log.error("[Secrets] RSA 密钥生成失败，敏感配置将以明文存储: {}", e.getMessage());
        }
    }

    /** 标准 PKCS#8 PEM 私钥（openssl / keytool / Vault 导出的形态都能被 PEMParser 读回） */
    private void writePem(Path file, RsaKey key) throws Exception {
        StringWriter out = new StringWriter();
        try (var writer = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(out)) {
            writer.writeObject(key.privateKey());
            writer.flush();
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // 非 POSIX 文件系统设不了权限，不影响功能
        }
    }

    /** PEM 私钥 → RSA 密钥对（公钥从私钥模数导出，不落第二份文件） */
    private RsaKey readPemKeyFile(Path file, int version) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(file, StandardCharsets.UTF_8)))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            java.security.PrivateKey privateKey;
            if (object instanceof PEMKeyPair pair) {
                privateKey = converter.getKeyPair(pair).getPrivate();
            } else if (object instanceof PrivateKeyInfo info) {
                privateKey = converter.getPrivateKey(info);
            } else {
                throw new IllegalStateException("文件里没有私钥对象（读到 " + object + "）");
            }
            return toRsaKey(version, privateKey);
        }
    }

    /** 改造前的两行 base64 格式：public=X509 / private=PKCS8（前缀长度 public= 7、private= 8，写错会丢首字符） */
    private RsaKey readTwoLineKeyFile(Path file, int version) throws Exception {
        String privateB64 = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("private=")) {
                privateB64 = line.substring(8).trim();
            }
        }
        if (privateB64 == null) {
            return null;
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        java.security.PrivateKey privateKey = factory.generatePrivate(
                new java.security.spec.PKCS8EncodedKeySpec(
                        java.util.Base64.getMimeDecoder().decode(privateB64)));
        return toRsaKey(version, privateKey);
    }

    private RsaKey toRsaKey(int version, java.security.PrivateKey privateKey) throws Exception {
        if (!(privateKey instanceof RSAPrivateKey rsaPrivate)) {
            throw new IllegalStateException("不是 RSA 私钥（" + privateKey.getAlgorithm() + "）");
        }
        // 公钥从私钥自带的模数+公钥指数导出（CRT 形态才有 publicExponent），省得再存一份公钥文件
        if (!(rsaPrivate instanceof java.security.interfaces.RSAPrivateCrtKey crt)) {
            throw new IllegalStateException("不是 RSA CRT 私钥（无法导出公钥，JWE 加密需要公钥）");
        }
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new RsaKey(version, rsaPrivate, (RSAPublicKey) publicKey);
    }

}
