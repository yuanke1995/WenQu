package com.wisesoft.wenqu.common;

/**
 * 通用字符串处理工具。
 *
 * <p>由参考实现的 utils/string_utils.py 逐函数翻译。
 */
public final class StringUtils {

    /** 截断标记。 */
    public static final String TRUNCATION_MARKER = "\n[内容已截断]";

    private StringUtils() {}

    /**
     * Python 内置 {@code repr(list[str])} 的等价输出（单引号包裹、逗号加空格）。
     *
     * <p>平台差异载体：参考实现有若干处把列表直接插值进错误/提示文案
     * （如 {@code f"...不在 provider 能力 {sorted(capabilities)} 内"}），
     * Python 会渲染成 {@code ['chat']}。Java 无此机制，故显式实现同一渲染规则，
     * 保证这些用户可见文案与参考实现逐字一致。
     */
    public static String pythonListRepr(java.util.Collection<String> values) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                builder.append(", ");
            }
            builder.append('\'').append(value).append('\'');
            first = false;
        }
        return builder.append(']').toString();
    }

    /** 截断结果：文本与是否发生截断。 */
    public record Truncated(String text, boolean truncated) {}

    /** 在 UTF-8 字节预算内截断文本并返回是否发生截断。 */
    public static Truncated truncateUtf8(Object value, int maxBytes) {
        String textValue = value == null ? "" : String.valueOf(value);
        byte[] encoded = textValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length <= maxBytes) {
            return new Truncated(textValue, false);
        }

        byte[] markerBytes = TRUNCATION_MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (maxBytes < markerBytes.length) {
            return new Truncated("", true);
        }

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(
                encoded, 0, maxBytes - markerBytes.length);
        java.nio.charset.CodingErrorAction ignore = java.nio.charset.CodingErrorAction.IGNORE;
        String prefix;
        try {
            prefix = java.nio.charset.StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(ignore)
                    .onUnmappableCharacter(ignore)
                    .decode(buffer)
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            // IGNORE 策略下不会抛出；兜底按 ASCII 前缀截断
            prefix = new String(encoded, 0, maxBytes - markerBytes.length, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (prefix.isEmpty()) {
            return new Truncated(TRUNCATION_MARKER.strip(), true);
        }
        return new Truncated(prefix + TRUNCATION_MARKER, true);
    }
}
