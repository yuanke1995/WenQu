package com.wenqu.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能使用统计：异步记录、7/30 天汇总、30 天序列与最近触发。 */
class SkillStatsStoreTest {

    @TempDir
    Path tmp;

    /** recordRead 走单线程 executor：轮询等待落内存（上限 2s），避免依赖 sleep 常量。 */
    private static void awaitFlush(SkillStatsStore store, String uid, String name, int expectReads) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = store.summary(uid).get(name);
            if (row != null && ((Number) row.get("reads7d")).intValue() >= expectReads) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("记录读取后 7/30 天汇总与 sparkline 序列就位")
    void recordAndSummarize() {
        SkillStatsStore store = new SkillStatsStore(tmp.toString());
        store.recordRead("u1", "form-subtable", "s1");
        store.recordRead("u1", "form-subtable", "s1");
        store.recordRead("u1", "form-patch", "s2");
        awaitFlush(store, "u1", "form-subtable", 2);

        Map<String, Object> row = store.summary("u1").get("form-subtable");
        assertThat(row).isNotNull();
        assertThat(((Number) row.get("reads7d")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("reads30d")).intValue()).isEqualTo(2);
        assertThat(row.get("lastUsedAt")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Integer> series = (List<Integer>) row.get("series7d");
        assertThat(series).hasSize(7);
        assertThat(series.get(6)).isEqualTo(2);   // 今天那格

        Map<String, Object> patch = store.summary("u1").get("form-patch");
        assertThat(((Number) patch.get("reads7d")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("detail 给 30 天序列与最近触发，缺技能返回空")
    void detailShape() {
        SkillStatsStore store = new SkillStatsStore(tmp.toString());
        store.recordRead("u2", "demo", "sA");
        store.recordRead("u2", "demo", "sB");
        awaitFlush(store, "u2", "demo", 2);

        Map<String, Object> d = store.detail("u2", "demo");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) d.get("series30d");
        assertThat(series).hasSize(30);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) d.get("recent");
        // 同 sessionId 只留最新一条
        assertThat(recent).hasSize(2);

        assertThat(store.detail("u2", "not-exist")).isEmpty();
        assertThat(store.summary("nobody")).isEmpty();
    }

    @Test
    @DisplayName("空 uid / 空技能名不记录也不报错")
    void ignoresBlank() {
        SkillStatsStore store = new SkillStatsStore(tmp.toString());
        store.recordRead(null, "x", "s");
        store.recordRead("u3", "", "s");
        store.recordRead("u3", "x", null);   // sessionId 可为空，仍记录
        awaitFlush(store, "u3", "x", 1);
        assertThat(store.summary("u3")).containsKey("x");
    }
}
