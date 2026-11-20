package com.wenqu.ai.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TextIoTest {
    @TempDir
    Path dir;

    @Test
    void readsUtf8RegardlessOfPlatformDefaultCharset() throws Exception {
        Path file = dir.resolve("memory.md");
        Files.write(file, "跨会话记忆：你好，问渠".getBytes(StandardCharsets.UTF_8));
        assertEquals("跨会话记忆：你好，问渠", TextIo.readString(file));
    }

    @Test
    void writesExplicitUtf8Bytes() throws Exception {
        Path file = dir.resolve("stats.json");
        TextIo.writeString(file, "{\"name\":\"技能\"}");
        assertArrayEquals("{\"name\":\"技能\"}".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    }
}
