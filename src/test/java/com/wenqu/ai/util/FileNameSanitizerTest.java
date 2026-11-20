package com.wenqu.ai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileNameSanitizerTest {
    @Test
    void replacesIllegalCharacters() {
        assertEquals("a_b_c_d_e", FileNameSanitizer.sanitize("a/b\\c:d*e"));
    }

    @Test
    void prefixesWindowsReservedDeviceNames() {
        assertEquals("_CON.txt", FileNameSanitizer.sanitize("CON.txt"));
        assertEquals("_com1.md", FileNameSanitizer.sanitize("com1.md"));
        assertNotEquals("_NUL", FileNameSanitizer.sanitize("NULL.txt"));
    }

    @Test
    void stripsTrailingDotsAndSpaces() {
        assertEquals("report", FileNameSanitizer.sanitize("report. . "));
        assertEquals("report", FileNameSanitizer.sanitize("report..."));
    }

    @Test
    void handlesBlankAndDotNames() {
        assertEquals("unnamed", FileNameSanitizer.sanitize(null));
        assertEquals("unnamed", FileNameSanitizer.sanitize("   "));
        assertEquals("unnamed", FileNameSanitizer.sanitize(".."));
    }

    @Test
    void truncatesByCharsAndByUtf8Bytes() {
        assertEquals("abcdef", FileNameSanitizer.sanitize("abcdefgh", 6));
        String longName = "中文文件名".repeat(20);
        String bytes = FileNameSanitizer.sanitizeBytes(longName, 30);
        assertTrue(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 30);
        assertFalse(bytes.endsWith("."));
    }
}
