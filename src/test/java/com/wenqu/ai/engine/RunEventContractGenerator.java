package com.wenqu.ai.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端事件表生成器/校验器。
 *
 * <p>唯一真源是 {@link RunEventType}。正常测试只校验生成文件是否最新；显式加
 * {@code -Dwenqu.generateEvents=true} 时重写文件：
 * <pre>
 * mvn -q -Dtest=RunEventContractGenerator -Dwenqu.generateEvents=true test
 * </pre>
 */
class RunEventContractGenerator {

    private static final Path TARGET = Path.of("web", "src", "chat", "eventContract.generated.js");

    @Test
    void generatedContractIsUpToDate() throws IOException {
        String expected = render();
        if (Boolean.getBoolean("wenqu.generateEvents")) {
            Files.createDirectories(TARGET.getParent());
            Files.writeString(TARGET, expected, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(TARGET), "缺少前端事件表，先运行生成器");
        assertEquals(expected, Files.readString(TARGET, StandardCharsets.UTF_8),
                "前端事件表已过期，先运行生成器");
    }

    private static String render() {
        StringBuilder js = new StringBuilder();
        js.append("// AUTO-GENERATED from com.wenqu.ai.engine.RunEventType. Do not edit by hand.\n");
        js.append("// Regenerate: mvn -q -Dtest=RunEventContractGenerator -Dwenqu.generateEvents=true test\n\n");

        js.append("export const CHAT_EVENT_CB = Object.freeze({\n");
        for (RunEventType t : RunEventType.values()) {
            if (t.callback() == null) continue;
            js.append("  ").append(t.sseName()).append(": '").append(js(t.callback())).append("',\n");
        }
        js.append("})\n\n");

        js.append("export const SSE_LABELS = Object.freeze({\n");
        for (RunEventType t : RunEventType.values()) {
            if (t.label() == null) continue;
            js.append("  ").append(t.sseName()).append(": '").append(js(t.label())).append("',\n");
        }
        js.append("})\n\n");

        js.append("export const SSE_TONES = Object.freeze({\n");
        for (RunEventType t : RunEventType.values()) {
            if (t.tone() == null) continue;
            js.append("  ").append(t.sseName()).append(": '").append(js(t.tone())).append("',\n");
        }
        js.append("})\n\n");

        js.append("export const RUN_EVENT_TYPES = Object.freeze([\n");
        for (RunEventType t : RunEventType.values()) {
            js.append("  '").append(t.sseName()).append("',\n");
        }
        js.append("])\n");
        return js.toString();
    }

    private static String js(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
