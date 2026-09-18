package com.wisesoft.wenqu.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析纯文本 Channel 的最小 slash command。
 *
 * <p>由参考实现的 services/channel_command_service.py 逐函数翻译。
 *
 * <p>必要替换：Python {@code shlex.split}（POSIX 模式）移植为 {@link #shlexSplit}——
 * 空白分隔（shlex 默认空白集为空格/制表/回车/换行）；单引号内无转义；双引号内反斜杠
 * 只转义 {@code " \ \` $}（其余字符保留反斜杠原样，与 shlex 一致）；引号外反斜杠转义
 * 任意字符；引号/转义不闭合抛 {@link IllegalArgumentException}（对应 shlex 的 ValueError）。
 */
public final class ChannelCommandService {

    private ChannelCommandService() {}

    /** 一个已规范化的 slash command。 */
    public record SlashCommand(String name, List<String> args) {}

    /** 解析以 {@code /} 开头的命令；普通文本返回 {@code null}。 */
    public static SlashCommand parseSlashCommand(String text) {
        String normalized = text == null ? "" : String.valueOf(text).strip();
        if (!normalized.startsWith("/")) {
            return null;
        }

        List<String> parts;
        try {
            parts = shlexSplit(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("slash command 格式无效");
        }
        if (parts.isEmpty() || !parts.get(0).startsWith("/")) {
            return null;
        }

        String name = parts.get(0).substring(1).strip().toLowerCase();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("slash command 不能为空");
        }
        return new SlashCommand(name, List.copyOf(parts.subList(1, parts.size())));
    }

    /** {@code shlex.split}（POSIX 模式）的移植，见类注释的语义说明。 */
    static List<String> shlexSplit(String source) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean hasToken = false;
        int i = 0;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (isWhitespace(ch)) {
                if (hasToken) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    hasToken = false;
                }
                i++;
            } else if (ch == '\'') {
                // 单引号内无转义，直到下一个单引号
                hasToken = true;
                int closing = source.indexOf('\'', i + 1);
                if (closing < 0) {
                    throw new IllegalArgumentException("No closing quotation");
                }
                token.append(source, i + 1, closing);
                i = closing + 1;
            } else if (ch == '"') {
                // 双引号内：反斜杠仅转义 " 与 \（shlex 的 escapedquotes+escape），其余保留原样
                hasToken = true;
                i++;
                boolean closed = false;
                while (i < source.length()) {
                    char c = source.charAt(i);
                    if (c == '\\' && i + 1 < source.length()) {
                        char next = source.charAt(i + 1);
                        if (next == '"' || next == '\\') {
                            token.append(next);
                            i += 2;
                            continue;
                        }
                    }
                    if (c == '"') {
                        closed = true;
                        i++;
                        break;
                    }
                    token.append(c);
                    i++;
                }
                if (!closed) {
                    throw new IllegalArgumentException("No closing quotation");
                }
            } else if (ch == '\\') {
                // 引号外：转义任意字符；行尾无字符可转义 → 与 shlex 一致报错
                if (i + 1 >= source.length()) {
                    throw new IllegalArgumentException("No escaped character");
                }
                hasToken = true;
                token.append(source.charAt(i + 1));
                i += 2;
            } else {
                hasToken = true;
                token.append(ch);
                i++;
            }
        }
        if (hasToken) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    /** shlex 默认空白集：空格、制表、回车、换行。 */
    private static boolean isWhitespace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
    }
}
