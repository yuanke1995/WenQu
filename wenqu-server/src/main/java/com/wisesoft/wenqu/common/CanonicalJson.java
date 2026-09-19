package com.wisesoft.wenqu.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范化 JSON 序列化（参考实现 {@code json.dumps(..., sort_keys=True, separators=(",", ":"), default=str)}
 * 的 Java 载体）。
 *
 * <p>用于需要"同一份语义得到同一根字符串"的场合：运行清单指纹、恢复请求的 request_id 派生等。
 * 键排序是这类用法的**语义前提**——同样的载荷换个键序必须得到同一结果，否则幂等性失效。
 *
 * <h3>两个入口的差异（对应参考实现里两处 ensure_ascii 取值不同）</h3>
 * <ul>
 *   <li>{@link #dumps(Object)} —— {@code ensure_ascii=False}：非 ASCII 字符原样输出。
 *       参考实现用于 {@code resume} 载荷派生 request_id。</li>
 *   <li>{@link #dumpsAscii(Object)} —— {@code ensure_ascii=True}：非 ASCII 转 {@code \\uXXXX}，
 *       且 BMP 外字符按 UTF-16 代理对输出两个转义。参考实现用于运行清单
 *       （{@code agent_run_manifest_service.canonical_json}）。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非等价）</h3>
 * <ul>
 *   <li><b>浮点格式</b>：参考实现走 Python {@code repr} 的最短往返表示。本实现对**整数值浮点**
 *       （如 {@code 1.0}）输出与之相同（{@code "1.0"}）；对其它浮点沿用 JDK
 *       {@code Double.toString}，其指数形式（如 {@code 1.0E-5}）与 Python（{@code 1e-05}）
 *       字面不同。实际载荷（决策数组、资源键、limit 整数）不含此类值，故未实现完整 repr
 *       算法；若将来有浮点进入指纹，需补该算法并重算历史指纹。</li>
 *   <li><b>非有限浮点</b>：参考实现输出 {@code NaN} / {@code Infinity} / {@code -Infinity}
 *       （非合法 JSON）；本实现同样输出这三个字面量，以保持字符级一致。</li>
 *   <li><b>default=str</b>：不可直接序列化的对象走 {@code String.valueOf}，再按字符串转义
 *       （与 Python {@code default=str} 后整体转义一致）。</li>
 * </ul>
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** {@code ensure_ascii=False}：非 ASCII 原样保留。 */
    public static String dumps(Object payload) {
        StringBuilder buffer = new StringBuilder();
        write(payload, buffer, false);
        return buffer.toString();
    }

    /** {@code ensure_ascii=True}：非 ASCII 转 {@code \\uXXXX}。 */
    public static String dumpsAscii(Object payload) {
        StringBuilder buffer = new StringBuilder();
        write(payload, buffer, true);
        return buffer.toString();
    }

    private static void write(Object value, StringBuilder buffer, boolean ensureAscii) {
        if (value == null) {
            buffer.append("null");
            return;
        }
        if (value instanceof Boolean bool) {
            buffer.append(bool ? "true" : "false");
            return;
        }
        if (value instanceof String text) {
            writeString(text, buffer, ensureAscii);
            return;
        }
        if (value instanceof Character character) {
            writeString(String.valueOf(character), buffer, ensureAscii);
            return;
        }
        if (value instanceof Number number) {
            writeNumber(number, buffer);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeMap(map, buffer, ensureAscii);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            writeIterable(iterable, buffer, ensureAscii);
            return;
        }
        if (value instanceof Object[] array) {
            List<Object> items = new ArrayList<>(array.length);
            for (Object item : array) {
                items.add(item);
            }
            writeIterable(items, buffer, ensureAscii);
            return;
        }
        // default=str：先转字符串，再作为 JSON 字符串输出
        writeString(String.valueOf(value), buffer, ensureAscii);
    }

    private static void writeNumber(Number number, StringBuilder buffer) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value)) {
                buffer.append("NaN");
                return;
            }
            if (Double.isInfinite(value)) {
                buffer.append(value > 0 ? "Infinity" : "-Infinity");
                return;
            }
            // 整数值浮点：Python repr(1.0) == "1.0"，JDK Double.toString(1.0) 亦为 "1.0"
            if (value == Math.rint(value) && Math.abs(value) < 1e16d) {
                buffer.append(Double.toString(value));
                return;
            }
            if (number instanceof Float) {
                buffer.append(Float.toString(number.floatValue()));
                return;
            }
            buffer.append(Double.toString(value));
            return;
        }
        if (number instanceof java.math.BigDecimal decimal) {
            buffer.append(decimal.toPlainString());
            return;
        }
        buffer.append(number.toString());
    }

    private static void writeMap(Map<?, ?> map, StringBuilder buffer, boolean ensureAscii) {
        // sort_keys=True：Python 按字符串键排序（本类所有实际键均为字符串）
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        buffer.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                buffer.append(',');
            }
            first = false;
            writeString(entry.getKey(), buffer, ensureAscii);
            buffer.append(':');
            write(entry.getValue(), buffer, ensureAscii);
        }
        buffer.append('}');
    }

    private static void writeIterable(Iterable<?> iterable, StringBuilder buffer, boolean ensureAscii) {
        buffer.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) {
                buffer.append(',');
            }
            first = false;
            write(item, buffer, ensureAscii);
        }
        buffer.append(']');
    }

    /** 字符串转义：对齐 Python {@code json} 编码器（不转义 {@code /} 与 {@code '}）。 */
    private static void writeString(String text, StringBuilder buffer, boolean ensureAscii) {
        buffer.append('"');
        int length = text.length();
        for (int index = 0; index < length; index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> buffer.append("\\\"");
                case '\\' -> buffer.append("\\\\");
                case '\b' -> buffer.append("\\b");
                case '\f' -> buffer.append("\\f");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                case '\t' -> buffer.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        appendUnicodeEscape(buffer, ch);
                    } else if (ensureAscii && ch > 0x7E) {
                        // BMP 外字符由 UTF-16 代理对天然承载，逐 char 转义即等价于 Python 的代理对输出
                        appendUnicodeEscape(buffer, ch);
                    } else {
                        buffer.append(ch);
                    }
                }
            }
        }
        buffer.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder buffer, char ch) {
        buffer.append("\\u");
        String hex = Integer.toHexString(ch);
        for (int pad = hex.length(); pad < 4; pad++) {
            buffer.append('0');
        }
        buffer.append(hex);
    }
}
