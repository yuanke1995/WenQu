package com.wenqu.ai.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能使用统计：7/30 天汇总、30 天序列与最近触发。 */
class SkillStatsStoreTest {

    @TempDir
    Path tmp;

    private SkillStatsStore store;

    /** 收尾关掉后台落盘线程：目录还在，close 会把内存数据同步刷完，不留给 @TempDir 清理后的残留写。 */
    @AfterEach
    void closeStore() {
        if (store != null) store.close();
    }

    @Test
    @DisplayName("记录读取后 7/30 天汇总与 sparkline 序列就位")
    void recordAndSummarize() {
        store = new SkillStatsStore(tmp.toString());
        store.recordRead("u1", "form-subtable", "s1");
        store.recordRead("u1", "form-subtable", "s1");
        store.recordRead("u1", "form-patch", "s2");

        // 契约：recordRead 返回时内存已更新，读路径不需要等异步队列，也不靠轮询猜落定时间。
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
        store = new SkillStatsStore(tmp.toString());
        store.recordRead("u2", "demo", "sA");
        store.recordRead("u2", "demo", "sB");

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
        store = new SkillStatsStore(tmp.toString());
        store.recordRead(null, "x", "s");
        store.recordRead("u3", "", "s");
        store.recordRead("u3", "x", null);   // sessionId 可为空，仍记录
        assertThat(store.summary("u3")).containsKey("x");
    }

    @Test
    @DisplayName("关闭后数据已落盘，新建 store 从文件恢复同一份统计")
    void survivesRestart() {
        store = new SkillStatsStore(tmp.toString());
        store.recordRead("u4", "restarted", "s1");
        store.recordRead("u4", "restarted", "s1");
        store.close();
        store = null;

        SkillStatsStore reopened = new SkillStatsStore(tmp.toString());
        store = reopened;
        Map<String, Object> row = reopened.summary("u4").get("restarted");
        assertThat(row).isNotNull();
        assertThat(((Number) row.get("reads7d")).intValue()).isEqualTo(2);
    }
}
