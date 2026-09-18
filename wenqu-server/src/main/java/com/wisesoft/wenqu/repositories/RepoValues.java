package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 仓储层的取值辅助。
 *
 * <p>参考实现的仓储方法普遍以 {@code dict[str, Any]} 作为写入参数、以 ORM 对象作为返回，
 * 依赖 Python 的动态类型完成赋值。翻译到 Java 后参数仍是 {@code Map<String, Object>}，
 * 但需要显式转换，故把转换集中在此处，避免各仓储各写一份。
 *
 * <p>转换规则与参考实现落库时的隐式转换一致：
 * <ul>
 *   <li>JSON 列：字典/列表序列化为字符串（本工程对应列类型为文本），已是字符串则原样保留
 *   <li>布尔列：Java 侧实体使用 0/1 或 Boolean，按目标字段的实际类型转换
 *   <li>时间列：接受 LocalDateTime/OffsetDateTime/ISO 字符串
 * </ul>
 */
public final class RepoValues {

    private RepoValues() {}

    /** 取字符串值；null 原样返回。 */
    public static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 取整数值；布尔按 1/0 处理。 */
    public static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(text)) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 取布尔值；0/1 与 true/false 均可。 */
    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if ("1".equals(text)) {
            return true;
        }
        if ("0".equals(text)) {
            return false;
        }
        return Boolean.parseBoolean(text);
    }

    /** 取长整数值。 */
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 取时间值；接受 LocalDateTime/OffsetDateTime/ISO 字符串。 */
    public static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value));
    }

    /** 取 JSON 文本；非字符串值序列化后返回。 */
    public static String toJsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        return JSON.toJSONString(value);
    }

    /** 解析 JSON 对象文本；空值或解析失败返回空对象（与参考实现的 {@code dict(x or {})} 一致）。 */
    public static JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSON.parseObject(json);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** 解析 JSON 对象；对象形式入参直接返回其副本。 */
    public static JSONObject toJsonObject(Object value) {
        if (value == null) {
            return new JSONObject();
        }
        if (value instanceof JSONObject jsonObject) {
            return new JSONObject(jsonObject);
        }
        if (value instanceof Map<?, ?> map) {
            JSONObject result = new JSONObject();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return parseObject(String.valueOf(value));
    }

    /**
     * 为插入行补齐时间列。
     *
     * <p>必要替换：参考实现的时间默认值由 ORM 在插入时填充（模型声明 {@code default=utc_now}），
     * 本工程不经 ORM，故在写入前显式补上同一个 UTC 时刻；调用方已提供的值不覆盖。
     */
    public static void fillTimestamps(Map<String, Object> row) {
        Object now = com.wisesoft.wenqu.common.DateTimeUtils.utcNowNaive();
        row.putIfAbsent("created_at", now);
        row.putIfAbsent("updated_at", now);
    }
}
