package com.wisesoft.ai.service;

import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.model.AiDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档元数据缓存（docId → fileName）
 * 供引用标注使用；upload/delete/status 变更时调用 invalidate 立即失效本实例。
 * <p>
 * 多副本一致性：缓存条目带 TTL（10 分钟，命中即刷新）。其它实例对改名/删除的感知
 * 最多延迟一个 TTL 后自动回源自愈（DB 查不到/不一致即刷新），无需跨实例失效广播——
 * 展示型数据，代价远低于引入广播链路。invalidate 仍即时清除本实例缓存。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentMetaCache {

    /** 缓存条目有效期（秒）：cache.docMetaTtlSeconds 可配（默认 600=10 分钟）；过期后回源 DB 自愈
     *  （覆盖其它实例的改名/删除）。TTL 越长跨副本感知越慢，越短回源越频繁 */
    private long entryTtlMs() {
        return Math.max(1, configService.getInt("cache.docMetaTtlSeconds", 600)) * 1000L;
    }

    private final AiDocumentMapper documentMapper;
    private final ConfigService configService;
    private final Map<String, Entry> fileNameCache = new ConcurrentHashMap<>();

    /** 缓存条目（带过期时间；命中时刷新过期线，冷条目持续活跃） */
    private static final class Entry {
        final String name;
        volatile long expireAt;

        Entry(String name, long expireAt) {
            this.name = name;
            this.expireAt = expireAt;
        }

        boolean fresh() {
            return expireAt > System.currentTimeMillis();
        }
    }

    /**
     * 获取文档文件名（含弃用文档也返回，引用需要）；未知返回 null
     */
    public String getFileName(String docId) {
        if (docId == null || docId.isBlank()) {
            log.warn("[DocumentMetaCache] docId 为空，无法查询文件名");
            return null;
        }
        Entry entry = fileNameCache.get(docId);
        if (entry != null && entry.fresh()) {
            entry.expireAt = System.currentTimeMillis() + entryTtlMs();
            return entry.name;
        }
        // 未命中或已过期：回源 DB（过期条目的旧名自愈为最新值/清除）
        try {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc != null && doc.getFileName() != null && !doc.getFileName().isBlank()) {
                fileNameCache.put(docId, new Entry(doc.getFileName(), System.currentTimeMillis() + entryTtlMs()));
                return doc.getFileName();
            }
            // DB 中已不存在/文件名为空：清除本地陈旧缓存（跨副本删除/改名自愈）
            fileNameCache.remove(docId);
            log.warn("[DocumentMetaCache] 文档不存在或文件名为空: docId={}", docId);
        } catch (Exception e) {
            log.warn("[DocumentMetaCache] 查询文档名失败: docId={} error={}", docId, e.getMessage());
        }
        return null;
    }

    /**
     * 批量获取文件名：先查缓存（含 TTL 刷新），未命中/过期的批量回源补齐
     * （检索上下文循环内避免逐 hit 查库，冷缓存时每轮问答最多一次批量查询）
     */
    public Map<String, String> getFileNames(Collection<String> docIds) {
        Map<String, String> result = new HashMap<>();
        if (docIds == null || docIds.isEmpty()) return result;
        long now = System.currentTimeMillis();
        List<String> missing = new ArrayList<>();
        for (String docId : docIds) {
            if (docId == null || docId.isBlank()) continue;
            Entry entry = fileNameCache.get(docId);
            if (entry != null && entry.fresh()) {
                entry.expireAt = now + entryTtlMs();
                result.put(docId, entry.name);
            } else {
                missing.add(docId);
            }
        }
        if (missing.isEmpty()) return result;
        try {
            List<AiDocument> docs = documentMapper.selectBatchIds(missing);
            for (AiDocument doc : docs) {
                if (doc != null && doc.getFileName() != null && !doc.getFileName().isBlank()) {
                    fileNameCache.put(doc.getId(), new Entry(doc.getFileName(), now + entryTtlMs()));
                    result.put(doc.getId(), doc.getFileName());
                }
            }
            // 批量回源后仍缺失的 docId：清陈旧缓存（跨副本删除/改名自愈）
            for (String docId : missing) {
                if (!result.containsKey(docId)) {
                    fileNameCache.remove(docId);
                }
            }
        } catch (Exception e) {
            log.warn("[DocumentMetaCache] 批量查询文档名失败: error={}", e.getMessage());
        }
        return result;
    }

    /**
     * 失效缓存（文档新增/删除/状态变更后调用；仅需清本实例——其它实例由 TTL 自愈）
     */
    public void invalidate(String docId) {
        if (docId != null) fileNameCache.remove(docId);
    }

    /**
     * 失效全部（批量操作后）
     */
    public void invalidateAll() {
        fileNameCache.clear();
    }
}
