package com.wisesoft.wenqu.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 哈希标识工具。
 *
 * <p>由参考实现的 utils/hash_utils.py 逐函数翻译：hashstr（SHA-256 可选截断/加盐）、
 * hash_id（前缀 + 定长摘要）、subagent_child_thread_id（同步 task 子线程确定性派生）。
 */
public final class HashUtils {

    private HashUtils() {}

    /** 生成字符串的 SHA-256 哈希值，可选截断和加盐。 */
    public static String hashstr(Object inputString, Integer length, boolean withSalt, String salt) {
        String text = String.valueOf(inputString);
        byte[] encodedString = text.getBytes(StandardCharsets.UTF_8);

        if (withSalt) {
            String effectiveSalt = salt;
            if (effectiveSalt == null || effectiveSalt.isEmpty()) {
                effectiveSalt = System.currentTimeMillis() / 1000 + "_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            }
            String combined = new String(encodedString, StandardCharsets.UTF_8) + effectiveSalt;
            encodedString = combined.getBytes(StandardCharsets.UTF_8);
        }

        String digest = sha256Hex(encodedString);
        if (length != null) {
            return digest.substring(0, Math.min(length, digest.length()));
        }
        return digest;
    }

    /** 前缀 + 定长摘要的稳定 ID（length 不足前缀长度时摘要为空串）。 */
    public static String hashId(String prefix, Object value, int length) {
        int digestLength = Math.max(0, length - prefix.length());
        String digest = digestLength > 0 ? hashstr(value, digestLength, false, null) : "";
        return prefix + digest;
    }

    /**
     * 同步 task 子智能体线程 ID：由父线程、子智能体、工具调用确定性派生
     * （须与事件路由保持一致）。
     */
    public static String subagentChildThreadId(String parentThreadId, String agentSlug, String toolCallId) {
        return hashId("subagent_", parentThreadId + ":" + agentSlug + ":" + toolCallId, 64);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }
}
