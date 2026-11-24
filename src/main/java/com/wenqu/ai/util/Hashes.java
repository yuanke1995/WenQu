package com.wenqu.ai.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 摘要工具唯一入口：SHA-256 与其定长十六进制前缀。
 *
 * <p><b>为什么要有这个类</b>：摘要散在各处时，空值语义与失败语义必然各不相同——有的返回完整 64 位、
 * 有的取前 8 字节、有的失败后 {@code return Integer.toHexString(x.hashCode())} 悄悄降级成 8 位 32 位哈希。
 * 后者最危险：它让「去重键 / 幂等键 / 邀请令牌指纹」在摘要不可用时<b>静默换了一套口子</b>，碰撞概率从
 * 2^-128 掉到 2^-32，而且调用方完全无感。
 *
 * <p><b>纪律</b>：
 * <ul>
 *   <li>摘要失败一律抛 {@link IllegalStateException}，<b>禁止降级为 {@code hashCode()}</b>。
 *       SHA-256 是 JDK 必须提供的算法，正常永不失败；若真失败，硬失败远好过静默弱化安全语义。</li>
 *   <li>取短前缀一律走 {@link #sha256HexSuffix(String, int)}，让「取几位」成为显式参数，
 *       而不是每个调用点手抄一个 for 循环。</li>
 *   <li>需要「先拼盐再摘要」的场景自行 {@code update}，见 {@code ImageDescCache}。</li>
 * </ul>
 */
public final class Hashes {
    private Hashes() {
    }

    /** SHA-256 全量小写十六进制（64 字符），输入按 UTF-8 编码；{@code null} 视作空串。 */
    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    }

    /** SHA-256 全量小写十六进制（64 字符），输入按 UTF-8 编码。 */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest(bytes == null ? new byte[0] : bytes));
    }

    /**
     * SHA-256 前 {@code hexChars} 位小写十六进制。
     *
     * <p>{@code hexChars} 必须是偶数且 &gt; 0，否则抛 {@link IllegalArgumentException}——半字节的前缀
     * 没有意义，且这种错误应当尽早暴露在一次调用上，而不是被 {@code substring} 带出来。
     */
    public static String sha256HexPrefix(String value, int hexChars) {
        if (hexChars <= 0 || hexChars % 2 != 0) {
            throw new IllegalArgumentException("hexChars 必须是正偶数: " + hexChars);
        }
        String full = sha256Hex(value);
        return hexChars >= full.length() ? full : full.substring(0, hexChars);
    }

    /** 摘要 {@code salt} 与 {@code payload} 的拼接（含 \n 分隔），小写十六进制。 */
    public static String sha256HexSalted(String salt, String payload) {
        MessageDigest md = newDigest();
        md.update((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
        md.update((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    /** 摘要 {@code salt}（UTF-8）后接 {@code payload} 字节，小写十六进制。 */
    public static String sha256HexSalted(String salt, byte[] payload) {
        MessageDigest md = newDigest();
        if (salt != null && !salt.isEmpty()) {
            md.update(salt.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest(payload == null ? new byte[0] : payload));
    }

    /** 小写十六进制字符串转回字节（校验用，格式非法抛 {@link IllegalArgumentException}）。 */
    public static byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex.toLowerCase(Locale.ROOT));
    }

    private static byte[] digest(byte[] bytes) {
        return newDigest().digest(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exc) {
            // 不降级：见类注释。SHA-256 缺失说明运行环境已被破坏，继续跑只会产生弱指纹。
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }
}