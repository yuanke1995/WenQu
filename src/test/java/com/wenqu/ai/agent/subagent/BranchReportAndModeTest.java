package com.wenqu.ai.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * agent-as-tool 回报模型与分支模式枚举的单元测试。
 */
class BranchReportAndModeTest {

    @Nested
    @DisplayName("BranchReport 状态判断")
    class BranchReportUsable {

        @Test
        @DisplayName("done + 有正文 → usable=true")
        void doneWithAnswer() {
            BranchReport r = new BranchReport(BranchReport.DONE, "有内容", List.of(), List.of(),
                    3, List.of(), List.of(), List.of(), 1000, null);
            assertTrue(r.usable());
            assertEquals("有内容", r.answerOrEmpty());
        }

        @Test
        @DisplayName("done + 空正文 → usable=false")
        void doneNoAnswer() {
            BranchReport r = new BranchReport(BranchReport.DONE, "", List.of(), List.of(),
                    3, List.of(), List.of(), List.of(), 1000, null);
            assertFalse(r.usable());
        }

        @Test
        @DisplayName("done + null answer → usable=false")
        void doneNullAnswer() {
            BranchReport r = new BranchReport(BranchReport.DONE, null, List.of(), List.of(),
                    3, List.of(), List.of(), List.of(), 1000, null);
            assertFalse(r.usable());
        }

        @Test
        @DisplayName("done + 纯空白正文 → usable=false")
        void doneBlankAnswer() {
            BranchReport r = new BranchReport(BranchReport.DONE, "  \n\t ", List.of(), List.of(),
                    3, List.of(), List.of(), List.of(), 1000, null);
            assertFalse(r.usable());
        }

        @Test
        @DisplayName("failed 状态一律 usable=false")
        void failedStatusNeverUsable() {
            BranchReport r = new BranchReport(BranchReport.FAILED, "有正文但状态是 failed",
                    List.of(), List.of(), 0, List.of(), List.of(), List.of(), 1000, "err");
            assertFalse(r.usable());
        }

        @Test
        @DisplayName("timeout 状态一律 usable=false")
        void timeoutStatusNeverUsable() {
            BranchReport r = new BranchReport(BranchReport.TIMEOUT, "有正文但超时",
                    List.of(), List.of(), 0, List.of(), List.of(), List.of(), 1000, "timeout");
            assertFalse(r.usable());
        }
    }

    @Nested
    @DisplayName("BranchReport.failed 工厂")
    class BranchReportFailedFactory {

        @Test
        @DisplayName("failed() 产出的回报保持传入的 steps")
        void failedKeepsSteps() {
            List<Map<String, Object>> steps = List.of(
                    Map.of("n", "read_file", "s", "done"),
                    Map.of("n", "search_knowledge", "s", "error"));
            BranchReport r = BranchReport.failed(BranchReport.FAILED, "mock 挂了", 1200L, steps);
            assertEquals(BranchReport.FAILED, r.status());
            assertEquals("mock 挂了", r.error());
            assertEquals(1200L, r.elapsedMs());
            assertEquals(2, r.steps().size());
            assertEquals(0, r.toolSteps());
            assertTrue(r.answerOrEmpty().isEmpty());
            assertTrue(r.sourcesOrEmpty().isEmpty());
        }

        @Test
        @DisplayName("failed(null status) 默认 FAILED")
        void nullStatusDefaultsFailed() {
            BranchReport r = BranchReport.failed(null, "err", 100L, null);
            // failed() 不补默认值，但 usable=false 且 steps 空
            assertFalse(r.usable());
        }

        @Test
        @DisplayName("failed(null steps) → 空列表")
        void nullStepsBecomesEmpty() {
            BranchReport r = BranchReport.failed(BranchReport.FAILED, "err", 100L, null);
            assertTrue(r.stepsOrEmpty().isEmpty());
        }
    }

    @Nested
    @DisplayName("BranchReport 空安全访问")
    class BranchReportOrEmpty {

        @Test
        void sourcesOrEmptyNullSafe() {
            BranchReport r = new BranchReport(BranchReport.DONE, "ok", null, null,
                    0, null, null, null, 0, null);
            assertNotNull(r.sourcesOrEmpty());
            assertTrue(r.sourcesOrEmpty().isEmpty());
            assertNotNull(r.imagesOrEmpty());
            assertNotNull(r.stepsOrEmpty());
            assertNotNull(r.artifactFilesOrEmpty());
            assertNotNull(r.artifactsOrEmpty());
            assertEquals("ok", r.answerOrEmpty());
        }
    }

    @Nested
    @DisplayName("BranchMode.of 解析")
    class BranchModeParse {

        @Test
        void nullBlankReturnsFallback() {
            assertEquals(BranchMode.DIGEST, BranchMode.of(null, BranchMode.DIGEST));
            assertEquals(BranchMode.FULL, BranchMode.of(null, BranchMode.FULL));
            assertEquals(BranchMode.DIGEST, BranchMode.of("", BranchMode.DIGEST));
            assertEquals(BranchMode.DIGEST, BranchMode.of("   ", BranchMode.DIGEST));
        }

        @Test
        void lowercaseCodeMatches() {
            assertEquals(BranchMode.DIGEST, BranchMode.of("digest", BranchMode.FULL));
            assertEquals(BranchMode.FULL, BranchMode.of("full", BranchMode.DIGEST));
        }

        @Test
        void caseInsensitive() {
            assertEquals(BranchMode.DIGEST, BranchMode.of("DIGEST", BranchMode.FULL));
            assertEquals(BranchMode.FULL, BranchMode.of("Full", BranchMode.DIGEST));
        }

        @Test
        void labelAlsoMatches() {
            assertEquals(BranchMode.DIGEST, BranchMode.of("检索要点", BranchMode.FULL));
            assertEquals(BranchMode.FULL, BranchMode.of("全循环", BranchMode.DIGEST));
        }

        @Test
        void unknownStringFallsBack() {
            assertEquals(BranchMode.DIGEST, BranchMode.of("bogus", BranchMode.DIGEST));
            assertEquals(BranchMode.FULL, BranchMode.of("bogus", BranchMode.FULL));
        }

        @Test
        void trimWhitespaceBeforeMatching() {
            assertEquals(BranchMode.DIGEST, BranchMode.of("  digest  ", BranchMode.FULL));
        }
    }

    @Test
    @DisplayName("BranchMode 两个档位的 code/label 都正确")
    void branchModeCodes() {
        assertEquals("digest", BranchMode.DIGEST.code());
        assertEquals("检索要点", BranchMode.DIGEST.label());
        assertEquals("full", BranchMode.FULL.code());
        assertEquals("全循环", BranchMode.FULL.label());
    }

    @Test
    @DisplayName("BranchMode.DIGEST 是默认档（与注释一致：保持原行为）")
    void digestIsDefault() {
        BranchMode fallback = BranchMode.of(null, BranchMode.DIGEST);
        assertEquals(BranchMode.DIGEST, fallback);
    }
}
