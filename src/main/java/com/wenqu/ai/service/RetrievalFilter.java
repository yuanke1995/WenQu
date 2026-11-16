package com.wenqu.ai.service;

import com.wenqu.ai.model.AiDocument;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 检索期元数据过滤（文档级收窄）：知识库、文档范围、标签、文件类型、更新时间、上传人。
 *
 * <p><b>为什么是查询期判定而不是写进向量 metadata</b>：文档标签/类型/更新时间都是运营可改的数据，
 * 烘焙进索引就必须同步重写，写漏一处就是「过滤结果和文档列表不一致」。这里始终以 MySQL
 * {@code c_ai_document} 为唯一真相，改标签下一次检索即生效、无需重建索引。
 *
 * <p><b>与权限的分工</b>：本类只表达「检索条件」，不表达「能不能看」。可见性仍由
 * {@link ResourceVisibilityService} 单独判定，两者在
 * {@link HybridRetrievalService#loadEligibleDocIdsOfHits} 里统一收口：先判可见，再判条件。
 *
 * <p><b>空集合语义</b>：{@code kbIds}/{@code docIds} 为 {@code null}=不限，
 * 空集=无结果（fail-closed）。这是检索范围类字段的既有语义，不能把「空集」当成「不限」，
 * 否则配置错误会静默放宽范围。
 *
 * @author yuanke
 */
public record RetrievalFilter(
        /** 限定知识库（null=不限；空集=无结果）。@ 知识库收窄与智能体库界都编译到这里 */
        Set<String> kbIds,
        /** 限定文档（null=不限；空集=无结果） */
        Set<String> docIds,
        /** 文档标签任一命中（null/空=不限；文档无标签在收窄语义下不放行） */
        Set<String> tags,
        /** 文件类型任一命中（null/空=不限；小写比较） */
        Set<String> fileTypes,
        /** 文档更新时间下界（含） */
        LocalDateTime updatedAfter,
        /** 文档更新时间上界（不含） */
        LocalDateTime updatedBefore,
        /** 上传人（登录用户 uid） */
        String createdBy) {

    /** 无任何条件：走短路，保证存量检索行为逐条不变 */
    public static final RetrievalFilter NONE =
            new RetrievalFilter(null, null, null, null, null, null, null);

    public RetrievalFilter {
        kbIds = copySet(kbIds);
        docIds = copySet(docIds);
        tags = normalizeTags(tags);
        fileTypes = normalizeTypes(fileTypes);
        createdBy = createdBy == null || createdBy.isBlank() ? null : createdBy.trim();
    }

    public boolean isEmpty() {
        return kbIds == null && docIds == null
                && (tags == null || tags.isEmpty())
                && (fileTypes == null || fileTypes.isEmpty())
                && updatedAfter == null && updatedBefore == null && createdBy == null;
    }

    /** 是否存在需要「文档行」才能判定的条件（含文档范围与库范围） */
    public boolean requiresDocument() {
        return kbIds != null || hasDocConditions();
    }

    /** 是否存在需要按文档行判定的条件（不含库范围；用于向量路 docId 下推的必要性判断） */
    public boolean hasDocConditions() {
        return docIds != null
                || (tags != null && !tags.isEmpty())
                || (fileTypes != null && !fileTypes.isEmpty())
                || updatedAfter != null || updatedBefore != null
                || createdBy != null;
    }

    /**
     * 文档是否满足全部条件。
     *
     * @param doc 命中所属文档；手动知识块（无文档行）传 null
     * @param kbId 命中块的实际库归属；doc 非空时可传 null 由文档行补齐
     */
    public boolean accepts(AiDocument doc, String kbId) {
        if (isEmpty()) return true;
        String effectiveKb = kbId == null || kbId.isBlank()
                ? (doc == null ? null : doc.getKbId()) : kbId;

        if (kbIds != null) {
            if (kbIds.isEmpty()) return false;
            if (effectiveKb == null || effectiveKb.isBlank() || !kbIds.contains(effectiveKb)) return false;
        }
        if (docIds != null) {
            if (docIds.isEmpty() || doc == null || doc.getId() == null) return false;
            if (!docIds.contains(String.valueOf(doc.getId()))) return false;
        }
        if (tags != null && !tags.isEmpty()) {
            if (!anyTagMatches(doc, tags)) return false;
        }
        if (fileTypes != null && !fileTypes.isEmpty()) {
            if (doc == null || doc.getFileType() == null) return false;
            String type = doc.getFileType().trim().toLowerCase(Locale.ROOT);
            if (!fileTypes.contains(type)) return false;
        }
        if (updatedAfter != null || updatedBefore != null) {
            if (doc == null || doc.getUpdateTime() == null) return false;
            if (updatedAfter != null && doc.getUpdateTime().isBefore(updatedAfter)) return false;
            if (updatedBefore != null && !doc.getUpdateTime().isBefore(updatedBefore)) return false;
        }
        if (createdBy != null) {
            if (doc == null || doc.getCreatedBy() == null || !createdBy.equals(doc.getCreatedBy())) return false;
        }
        return true;
    }

    /** 按文档行自身库归属判定（调用方没有额外库信息时用） */
    public boolean accepts(AiDocument doc) {
        return accepts(doc, doc == null ? null : doc.getKbId());
    }

    /** 覆盖库范围（@ 知识库收窄编译进来；null/空保持原样语义） */
    public RetrievalFilter withKbIds(Set<String> newKbIds) {
        return new RetrievalFilter(newKbIds, docIds, tags, fileTypes,
                updatedAfter, updatedBefore, createdBy);
    }

    private static boolean anyTagMatches(AiDocument doc, Set<String> wanted) {
        if (doc == null || doc.getTags() == null || doc.getTags().isBlank()) return false;
        for (String part : splitTags(doc.getTags())) {
            if (wanted.contains(part)) return true;
        }
        return false;
    }

    /** 标签归一：逗号/中文逗号分隔、去空白、去空项 */
    static Set<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,，]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Set<String> copySet(Set<String> raw) {
        if (raw == null) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String v : raw) {
            if (v != null && !v.isBlank()) out.add(v.trim());
        }
        return Set.copyOf(out);
    }

    private static Set<String> normalizeTags(Set<String> raw) {
        Set<String> copied = copySet(raw);
        return copied == null ? null : Set.copyOf(copied);
    }

    private static Set<String> normalizeTypes(Set<String> raw) {
        Set<String> copied = copySet(raw);
        if (copied == null) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String v : copied) out.add(v.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }
}
