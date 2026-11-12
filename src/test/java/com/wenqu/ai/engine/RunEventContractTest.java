package com.wenqu.ai.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件名契约：RunEventType、生成的前端事件表与服务端按字面量发的事件名三处对齐。
 * <p>done/error 刻意不在前端事件表里（两条链路收尾语义不同），
 * 其余事件名多一条、少一条、拼错都判红；服务端字面量拼错会发成前端不认识的名字，同样判红。
 */
class RunEventContractTest {

    private static final Path CONTRACT_JS = Path.of("web", "src", "chat", "eventContract.generated.js");
    private static final Path SRC_ROOT = Path.of("src", "main", "java");
    private static final Pattern ENTRY = Pattern.compile("([a-z][a-z0-9_]*)\\s*:\\s*'(on[A-Za-z]+)'");
    /** 按字面量发事件的调用：第二个实参是字符串常量（走枚举的那类不在扫描范围内）。 */
    private static final Pattern SEND_LITERAL =
            Pattern.compile("(?:sendSseEvent|sendFrame)\\(\\s*[^,]+,\\s*\"([a-z][a-z0-9_]*)\"", Pattern.DOTALL);
    /** 直接搭 SseEmitter 帧的写法：事件名同样是字面量。 */
    private static final Pattern BUILDER_LITERAL =
            Pattern.compile("SseEmitter\\.event\\(\\)\\.name\\(\\s*\"([a-z][a-z0-9_]*)\"");

    @Test
    @DisplayName("枚举里的事件名与前端 CHAT_EVENT_CB 完全一致")
    void enumMatchesFrontendTable() throws IOException {
        Set<String> frontend = frontendEventNames();

        Set<String> expected = new LinkedHashSet<>();
        for (RunEventType type : RunEventType.values()) {
            if (type != RunEventType.DONE && type != RunEventType.ERROR) {
                expected.add(type.sseName());
            }
        }

        assertThat(frontend)
                .as("RunEventType 与生成的前端事件表对账")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("账本/镜像标记保持既有语义")
    void metadataFlagsAreStable() {
        assertThat(RunEventType.sseLedgerNames())
                .contains("tool_status", "plan", "usage", "skill_hit", "error")
                .doesNotContain("token", "deep_state", "compact_state");
        assertThat(RunEventType.mirrorNames())
                .contains("plan", "todo", "deep_state", "compact_state", "runcode_progress")
                .doesNotContain("tool_status", "token");
    }

    @Test
    @DisplayName("回调名唯一，且只有 done/error 不派发")
    void callbacksAreUniqueAndOnlyTerminalAreNull() {
        Set<String> callbacks = new LinkedHashSet<>();
        for (RunEventType type : RunEventType.values()) {
            if (type == RunEventType.DONE || type == RunEventType.ERROR) {
                assertThat(type.callback()).as("%s 不应有前端回调", type.sseName()).isNull();
                continue;
            }
            assertThat(type.callback()).as("%s 缺少前端回调", type.sseName()).isNotBlank();
            assertThat(callbacks.add(type.callback())).as("重复回调: %s", type.callback()).isTrue();
        }
    }

    @Test
    @DisplayName("枚举名唯一，且全是小写下划线")
    void enumNamesAreUniqueAndStable() {
        Set<String> names = new LinkedHashSet<>();
        for (RunEventType type : RunEventType.values()) {
            assertThat(type.sseName())
                    .as("SSE 事件名")
                    .isNotBlank()
                    .matches("[a-z][a-z0-9_]*");
            assertThat(names.add(type.sseName()))
                    .as("重复的 SSE 事件名: %s", type.sseName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("服务端字面量事件名必须来自枚举：契约第三腿")
    void literalEventNamesAreDeclared() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        for (RunEventType type : RunEventType.values()) declared.add(type.sseName());

        List<String> offenders = new ArrayList<>();
        List<String> literals = new ArrayList<>();
        try (var files = Files.walk(SRC_ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                collect(f, src, SEND_LITERAL, declared, literals, offenders);
                collect(f, src, BUILDER_LITERAL, declared, literals, offenders);
            }
        }

        assertThat(literals).as("服务端确有按字面量发的事件").isNotEmpty();
        // 两条采集腿都得活着：snapshot 只由 sendFrame 发，runcode_progress 只由 SseEmitter 构造器发
        assertThat(literals).as("字面量采集腿覆盖 sendFrame 与 SseEmitter.event().name")
                .contains("snapshot", "runcode_progress");
        assertThat(offenders).as("未在 RunEventType 里声明的字面量事件名").isEmpty();
    }

    private static void collect(Path file, String src, Pattern pattern, Set<String> declared,
                                List<String> literals, List<String> offenders) {
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            literals.add(m.group(1));
            if (!declared.contains(m.group(1))) {
                offenders.add(file.getFileName() + " → " + m.group(1));
            }
        }
    }

    private Set<String> frontendEventNames() throws IOException {
        String js = Files.readString(CONTRACT_JS);
        int start = js.indexOf("const CHAT_EVENT_CB =");
        assertThat(start).as("生成的前端事件表里找不到 CHAT_EVENT_CB").isGreaterThanOrEqualTo(0);
        int end = js.indexOf("\n})", start);
        assertThat(end).as("CHAT_EVENT_CB 没有闭合").isGreaterThan(start);
        String block = js.substring(start, end);

        List<String> keys = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        Matcher m = ENTRY.matcher(block);
        while (m.find()) {
            keys.add(m.group(1));
            callbacks.add(m.group(2));
        }
        assertThat(keys).as("CHAT_EVENT_CB 至少应有一条").isNotEmpty();
        assertThat(callbacks).as("回调名不应重复").doesNotHaveDuplicates();
        return new LinkedHashSet<>(keys);
    }
}
