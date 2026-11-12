package com.wenqu.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 用户改动对账的 diff 排版：执行器给结构化 hunk，服务端只负责变成模型可读的 unified diff。 */
class ProjectTurnChangesServiceTest {

    @Test
    @DisplayName("hunk 列表排版成 unified diff，空/非法输入返回空串")
    void formatDriftDiff() {
        List<Map<String, Object>> hunks = List.of(Map.of(
                "oldStart", 3, "oldLines", 2, "newStart", 3, "newLines", 3,
                "lines", List.of(" 上下文", "-旧行", "+新行", "+新增")));
        String out = ProjectTurnChangesService.formatDriftDiff(List.of(
                Map.of("path", "src/a.md", "hunks", hunks)));
        assertThat(out).contains("--- src/a.md");
        assertThat(out).contains("@@ -3,2 +3,3 @@");
        assertThat(out).contains("-旧行");
        assertThat(out).contains("+新增");
        assertThat(ProjectTurnChangesService.formatDriftDiff(null)).isEmpty();
        assertThat(ProjectTurnChangesService.formatDriftDiff(List.of())).isEmpty();
    }

    @Test
    @DisplayName("对账上下文：列出文件与 diff，连续两轮出现的文件才提示沉淀工作区记忆")
    void driftNoteFormatsAndDetectsRepeats() {
        ProjectTurnChangesService svc = new ProjectTurnChangesService(null, null, null, null);
        Map<String, Object> drift = Map.of(
                "supported", true,
                "total", 1,
                "files", List.of(Map.of("path", "src/a.md", "added", 2, "deleted", 1)),
                "diffs", List.of(Map.of("path", "src/a.md", "hunks", List.of(Map.of(
                        "oldStart", 1, "oldLines", 1, "newStart", 1, "newLines", 2,
                        "lines", List.of("-旧", "+新"))))));
        Map<String, Object> result = Map.of("drift", drift);

        // Start 只封装执行器的进行中快照；对账基线由执行器按工作区自行选取，不再由服务端下发
        String first = svc.driftNote("s1", new ProjectTurnChangesService.Start(
                CompletableFuture.completedFuture(result)), 1000);
        assertThat(first).contains("用户手动改过", "- src/a.md（+2 -1）", "用户改动的差异");
        assertThat(first).contains("<untrusted-data", "-旧", "+新");
        assertThat(first).doesNotContain("连续多轮被用户修正");

        String second = svc.driftNote("s1", new ProjectTurnChangesService.Start(
                CompletableFuture.completedFuture(result)), 1000);
        assertThat(second).contains("连续多轮被用户修正").contains(".wenqu/MEMORY.md");
    }

    @Test
    @DisplayName("对账上下文：没有快照/空结果/没有文件都不注入")
    void driftNoteSkipsWhenNoDrift() {
        ProjectTurnChangesService svc = new ProjectTurnChangesService(null, null, null, null);
        assertThat(svc.driftNote("s2", null, 1000)).isEmpty();
        assertThat(svc.driftNote("s2", new ProjectTurnChangesService.Start(
                CompletableFuture.completedFuture(Map.of())), 1000)).isEmpty();
        assertThat(svc.driftNote("s2", new ProjectTurnChangesService.Start(
                CompletableFuture.completedFuture(Map.of("drift", Map.of("supported", true,
                        "files", List.of())))), 1000)).isEmpty();
    }
}
