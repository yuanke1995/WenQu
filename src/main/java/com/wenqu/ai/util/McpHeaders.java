package com.wenqu.ai.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 自定义请求头的解析、校验与摘要。
 * <p>
 * 存储形态是「整块 JSON 的 RSA 密文」，本类只处理明文 JSON 这一层：保存前严格校验
 * （非法名会让 {@code HttpRequest} 构建时抛 IllegalArgumentException，用户只会看到一句莫名的
 * 连接失败），建连时宽松解析（解不开就按无凭据连接，连接状态会如实变红）。
 * <p>
 * 危险键在保存与注入两处都丢弃/拒绝：这些头由传输层自己管理，用户覆盖只会把会话打坏。
 *
 * @author yuanke
 */
public final class McpHeaders {

    /** 由传输层管理的头（小写比对）：用户覆盖会让会话/分帧出错，保存时直接拒绝 */
    private static final Set<String> FORBIDDEN = Set.of(
            "host", "content-length", "connection", "transfer-encoding",
            "mcp-session-id", "mcp-protocol-version");
    /** RFC 7230 token 字符集 */
    private static final Pattern NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
    private static final int MAX_COUNT = 32;
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_JSON_LENGTH = 8192;

    private McpHeaders() {
    }

    /**
     * 严格校验并规范化保存值：返回可落库的 JSON 字符串；空输入返回 null（= 无请求头）。
     * 接受 JSON 对象或 JSON 字符串（字符串形态用于 curl/导入）。
     */
    public static String normalize(Object raw) {
        if (raw == null) return null;
        Map<?, ?> map;
        if (raw instanceof Map<?, ?> m) {
            map = m;
        } else if (raw instanceof String s) {
            if (s.isBlank()) return null;
            try {
                Object parsed = JSON.parse(s.trim());
                if (!(parsed instanceof Map<?, ?> pm)) {
                    throw new BizException("请求头格式不正确：应为 JSON 对象");
                }
                map = pm;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("请求头格式不正确：应为 JSON 对象");
            }
        } else {
            throw new BizException("请求头格式不正确：应为 JSON 对象");
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
            if (key.isEmpty()) continue;
            if (e.getValue() == null) continue;
            String value = String.valueOf(e.getValue());
            validate(key, value);
            if (out.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(key))) {
                throw new BizException("请求头「" + key + "」重复（键名不区分大小写）");
            }
            out.put(key, value);
        }
        if (out.isEmpty()) return null;
        if (out.size() > MAX_COUNT) {
            throw new BizException("请求头最多 " + MAX_COUNT + " 条");
        }
        String json = JSON.toJSONString(out);
        if (json.length() > MAX_JSON_LENGTH) {
            throw new BizException("请求头总长度过大（上限 " + MAX_JSON_LENGTH + " 字符）");
        }
        return json;
    }

    /** 宽松解析（建连用）：解不开/格式不对一律返回空 map，绝不抛异常打断建连 */
    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JSONObject obj = JSON.parseObject(json);
            if (obj == null) return Map.of();
            Map<String, String> out = new LinkedHashMap<>();
            for (String key : obj.keySet()) {
                if (key == null || key.isBlank() || isForbidden(key)) continue;
                String value = obj.getString(key);
                if (value != null) out.put(key, value);
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 已保存的键名（回显脱敏用；永不返回值） */
    public static List<String> keys(String json) {
        return new ArrayList<>(parse(json).keySet());
    }

    /** 配置指纹用摘要：密文变化即视为配置变化，避免把整段凭据塞进指纹串 */
    public static String digest(String stored) {
        if (stored == null || stored.isBlank()) return "-";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(stored.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(stored.hashCode());
        }
    }

    public static boolean isForbidden(String key) {
        return key != null && FORBIDDEN.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    private static void validate(String key, String value) {
        if (!NAME.matcher(key).matches()) {
            throw new BizException("请求头名称「" + key + "」非法（只允许字母、数字与 !#$%&'*+-.^_`|~）");
        }
        if (isForbidden(key)) {
            throw new BizException("请求头「" + key + "」由平台管理，不能自定义");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new BizException("请求头「" + key + "」的值不能包含换行");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new BizException("请求头「" + key + "」的值过长（上限 " + MAX_VALUE_LENGTH + " 字符）");
        }
    }
}
