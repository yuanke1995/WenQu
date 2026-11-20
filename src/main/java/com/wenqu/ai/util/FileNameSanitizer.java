package com.wenqu.ai.util;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跨平台文件名净化：非法字符、Windows 保留设备名、尾部点/空格、长度上限。
 *
 * <p>过去几处落盘/导出各自写一份正则，只处理非法字符。Windows 上还有三条额外规则：
 * 保留设备名（CON/PRN/AUX/NUL/COM1..9/LPT1..9，含带扩展名形式）、不能以点或空格结尾、
 * MAX_PATH 260。Linux/macOS 上这些名字都能创建，所以只在 macOS 开发测不出来。
 *
 * <p>统一在这里做一次，所有写盘与下载文件名入口共用，避免各写一份产生口径漂移。
 */
public final class FileNameSanitizer {
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNameSanitizer() {
    }

    /** 默认上限：255 个 UTF-8 字节（ext4/NTFS 的常见上限），字符数另有限制时用重载。 */
    public static String sanitize(String name) {
        return sanitize(name, 255);
    }

    /** 按字符数截断的重载（用于 ZIP 条目名等需要更短上限的场景）。 */
    public static String sanitize(String name, int maxChars) {
        if (name == null || name.isBlank()) return "unnamed";
        String cleaned = ILLEGAL.matcher(name.trim()).replaceAll("_");
        cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);

        // Windows 不允许以点或空格结尾（包括 "a. ." 这种混合形式）。
        while (!cleaned.isEmpty() && (cleaned.endsWith(".") || cleaned.endsWith(" "))) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) return "unnamed";

        // 保留名按「主名」判定：CON.txt、con.md 同样命中。
        int dot = cleaned.indexOf('.');
        String stem = dot > 0 ? cleaned.substring(0, dot) : cleaned;
        if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) cleaned = "_" + cleaned;

        int limit = Math.max(1, maxChars);
        if (cleaned.length() > limit) cleaned = cleaned.substring(0, limit);
        // 截断可能再次留下尾部点/空格。
        while (!cleaned.isEmpty() && (cleaned.endsWith(".") || cleaned.endsWith(" "))) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    /** 按 UTF-8 字节上限截断，不切断代理对以外的多字节字符。 */
    public static String sanitizeBytes(String name, int maxBytes) {
        String cleaned = sanitize(name, Integer.MAX_VALUE);
        int limit = Math.max(1, maxBytes);
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < cleaned.length(); ) {
            int cp = cleaned.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > limit) break;
            out.appendCodePoint(cp);
            bytes += n;
            i += Character.charCount(cp);
        }
        String result = out.toString();
        while (!result.isEmpty() && (result.endsWith(".") || result.endsWith(" "))) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "unnamed" : result;
    }
}
