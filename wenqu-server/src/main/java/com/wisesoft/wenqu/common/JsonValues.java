package com.wisesoft.wenqu.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求体取值小工具：null 安全、隐式类型转换、Python 真值语义。
 *
 * <p>参考实现的控制器直接以 pydantic 模型为形参，字段是"带类型和默认值"的属性；本工程控制器用
 * {@code Map<String, Object>} 接原始 JSON，因此取值时要逐处还原 Python 的 <b>{@code or} 真值</b>与
 * <b>{@code str()}</b> 转换语义——这两处最容易写成 Java 直觉（{@code == null} 判定、
 * {@code String.valueOf}）而偏离参考实现。
 *
 * <p><b>已知重复（显式标注，待收敛）</b>：{@code controller.AgentController} 与
 * {@code controller.ScheduledAgentController} 各有一份等价的私有 {@code text/asMap/truthy}。
 * 三者语义一致，尚未合并的原因是这两处属于已交付批次，收敛属独立改动——勿再新增第四份。
 *
 * @author yuanke
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** 对应 Python {@code str(value)}；{@code null} 保持 {@code null}（区别于 Python 的 {@code "None"}）。 */
    public static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 对应 Python 的真值判定（{@code if value:}）。 */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        String text = String.valueOf(value).strip().toLowerCase();
        return !text.isEmpty() && !"false".equals(text) && !"0".equals(text) && !"none".equals(text);
    }

    /** 对应 Python {@code value or fallback}（真值语义，非 null 判定）。 */
    public static Object or(Object value, Object fallback) {
        return truthy(value) ? value : fallback;
    }

    /** 对应 {@code dict(value or {})}：非 Map 值退化为空表。 */
    public static Map<String, Object> asMap(Object value) {
        Map<String, Object> map = asMapOrNull(value);
        return map == null ? new LinkedHashMap<>() : map;
    }

    /** 与 {@link #asMap} 同义，但区分「不是 Map」（返回 {@code null}）。 */
    public static Map<String, Object> asMapOrNull(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
