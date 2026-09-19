package com.wisesoft.wenqu.common;

import java.nio.charset.StandardCharsets;

/**
 * urllib.parse.quote 的等价实现（UTF-8）。
 *
 * <p>注意 quote/quote_plus 语义不同：quote 不编码 {@code _.~-} 与 safe 字符、
 * 空格编码为 {@code %20}；quote_plus 把空格编码为 {@code +}。
 * oidc 所用的 quotePlus 在 {@code OidcService.OIDCUtils} 中。
 */
public final class UrlQuote {

    private UrlQuote() {}

    /** urllib.parse.quote(string, safe='')：非 ASCII 按 UTF-8 百分号编码。 */
    public static String quote(String value, String safe) {
        StringBuilder builder = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~'
                    || safe.indexOf(c) >= 0) {
                builder.append(c);
            } else {
                builder.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return builder.toString();
    }
}
