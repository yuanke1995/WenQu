package com.wenqu.ai.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本读写唯一入口：显式 UTF-8。
 *
 * <p>Java 17 的 {@code Files.readString(Path)} / {@code writeString(Path, CharSequence)} 使用
 * {@code Charset.defaultCharset()}。Windows 中文系统默认 GBK，精简 Linux 容器在 LANG=C 时默认
 * US-ASCII，同样的中文技能统计 / MEMORY.md 会乱码甚至抛 MalformedInputException。
 * 项目锁定 Java 17（JEP 400 默认 UTF-8 从 Java 18 才有），因此不能依赖平台默认。
 *
 * <p>约定：服务端所有文本落盘/读回都走这里；禁止裸用单参 readString/writeString。
 */
public final class TextIo {
    private TextIo() {
    }

    public static String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public static void writeString(Path path, CharSequence text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    public static byte[] utf8(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }
}
