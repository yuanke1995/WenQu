package com.wenqu.ai.service;

import com.wenqu.ai.model.AiDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索元数据过滤判定核单测：空集合语义、标签归一、类型大小写、时间边界、手动块剔除。
 * 纯函数，不起 Spring 上下文。
 */
class RetrievalFilterTest {

    private static AiDocument doc(String id, String kbId, String tags, String fileType, String by,
                                  LocalDateTime updateTime) {
        AiDocument d = new AiDocument();
        d.setId(id);
        d.setKbId(kbId);
        d.setTags(tags);
        d.setFileType(fileType);
        d.setCreatedBy(by);
        d.setUpdateTime(updateTime);
        return d;
    }

    @Test
    @DisplayName("空过滤器：任何文档与手动块都放行")
    void noneAcceptsEverything() {
        assertThat(RetrievalFilter.NONE.isEmpty()).isTrue();
        assertThat(RetrievalFilter.NONE.accepts(null, null)).isTrue();
        assertThat(RetrievalFilter.NONE.accepts(doc("d1", "kb", "合同", "pdf", "u", LocalDateTime.now()))).isTrue();
    }

    @Test
    @DisplayName("空集合 = 无结果，不能当不限")
    void emptySetsFailClosed() {
        RetrievalFilter f = new RetrievalFilter(Set.of(), Set.of(), null, null, null, null, null);
        assertThat(f.isEmpty()).isFalse();
        assertThat(f.accepts(doc("d1", "kb", null, "pdf", "u", LocalDateTime.now()))).isFalse();
        assertThat(f.requiresDocument()).isTrue();
    }

    @Test
    @DisplayName("标签归一：空格、中文逗号、子串不误命中")
    void tagNormalization() {
        LocalDateTime now = LocalDateTime.now();
        RetrievalFilter f = new RetrievalFilter(null, null, Set.of("合同"), null, null, null, null);
        // 存量数据常见「部门A, 合同」：空格不能导致漏命中
        assertThat(f.accepts(doc("d1", "kb", "部门A, 合同", "pdf", "u", now))).isTrue();
        assertThat(f.accepts(doc("d2", "kb", "部门A，合同", "pdf", "u", now))).isTrue();
        // 子串不算标签命中
        assertThat(f.accepts(doc("d3", "kb", "劳动合同", "pdf", "u", now))).isFalse();
        assertThat(f.accepts(doc("d4", "kb", null, "pdf", "u", now))).isFalse();
    }

    @Test
    @DisplayName("文件类型：大小写不敏感，任一命中")
    void fileTypes() {
        LocalDateTime now = LocalDateTime.now();
        RetrievalFilter f = new RetrievalFilter(null, null, null, Set.of("pdf", "DOCX"), null, null, null);
        assertThat(f.accepts(doc("d1", "kb", null, "PDF", "u", now))).isTrue();
        assertThat(f.accepts(doc("d2", "kb", null, "docx", "u", now))).isTrue();
        assertThat(f.accepts(doc("d3", "kb", null, "xlsx", "u", now))).isFalse();
    }

    @Test
    @DisplayName("更新时间：下界含、上界不含")
    void timeBounds() {
        LocalDateTime after = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime before = LocalDateTime.of(2026, 2, 1, 0, 0);
        RetrievalFilter f = new RetrievalFilter(null, null, null, null, after, before, null);
        assertThat(f.accepts(doc("d1", "kb", null, "pdf", "u", after))).isTrue();
        assertThat(f.accepts(doc("d2", "kb", null, "pdf", "u", before))).isFalse();
        assertThat(f.accepts(doc("d3", "kb", null, "pdf", "u", after.minusSeconds(1)))).isFalse();
    }

    @Test
    @DisplayName("文档范围与库范围：都必须命中；手动块在有文档级条件时剔除")
    void scopeAndManualBlocks() {
        LocalDateTime now = LocalDateTime.now();
        RetrievalFilter f = new RetrievalFilter(Set.of("kb1"), Set.of("d1"), null, null, null, null, null);
        assertThat(f.accepts(doc("d1", "kb1", null, "pdf", "u", now))).isTrue();
        assertThat(f.accepts(doc("d2", "kb1", null, "pdf", "u", now))).isFalse();
        assertThat(f.accepts(doc("d1", "kb2", null, "pdf", "u", now))).isFalse();
        assertThat(f.accepts(null, null)).isFalse();
    }

    @Test
    @DisplayName("withKbIds 只覆盖库范围，其余条件保留")
    void withKbIdsKeepsOtherConditions() {
        RetrievalFilter base = new RetrievalFilter(null, null, Set.of("合同"), null, null, null, null);
        RetrievalFilter narrowed = base.withKbIds(new LinkedHashSet<>(List.of("kb1")));
        assertThat(narrowed.kbIds()).containsExactly("kb1");
        assertThat(narrowed.tags()).containsExactly("合同");
    }
}
