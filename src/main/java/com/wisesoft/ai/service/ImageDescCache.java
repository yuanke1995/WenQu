package com.wisesoft.ai.service;

import com.wisesoft.ai.mapper.ImageDescMapper;
import com.wisesoft.ai.model.ImageDesc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图片描述缓存（内容寻址，MySQL 持久化）
 * key = v{版本}_{sha256(model + prompt + 图片字节)}
 * <p>
 * 生产级策略：
 * - 持久化：DB 表存储，重启不丢 → 重解析描述文本稳定 → 块级 diff 才能复用（跳过重新 embedding）
 * - 新鲜度：TTL 内命中直接返回并刷新命中时间；超 TTL 视为过期重新描述（自动换新，避免坏描述永久锁定）
 * - 重生成：bump vision.descCacheVersion → key 前缀变化 → 全部 miss 重新描述；旧版本行由 prune 回收
 * - 并发：ON DUPLICATE KEY UPDATE 原子 upsert，无重复行/无写撕裂
 * - 运维：GET/DELETE /api/ai/desc-cache 可查可清；prune 按写入节流自动执行
 */
@Slf4j
@Component
public class ImageDescCache {

    private final ConfigService configService;
    private final ImageDescMapper descMapper;
    /** prune 节流：每 N 次写入才执行一次清理（避免每次写都扫表） */
    private static final int PRUNE_INTERVAL = 100;
    private final AtomicInteger writeCount = new AtomicInteger();

    public ImageDescCache(ConfigService configService, ImageDescMapper descMapper) {
        this.configService = configService;
        this.descMapper = descMapper;
    }

    private int version() {
        return configService.getInt("vision.descCacheVersion", 1);
    }

    private int ttlDays() {
        return configService.getInt("vision.descCacheTtlDays", 180);
    }

    /** 缓存 key：v{版本}_{sha256(model+prompt+bytes)}；版本变化 → 前缀变化 → 全部 miss（强制重生成） */
    public String key(byte[] imageBytes, String prompt, String model) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salt = (model == null ? "" : model) + "\n" + (prompt == null ? "" : prompt) + "\n";
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest(imageBytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return "v" + version() + "_" + sb;
        } catch (Exception e) {
            log.warn("[ImageDescCache] key 计算失败: {}", e.getMessage());
            return null;
        }
    }

    /** 命中且未过期返回描述；未命中/过期/读取失败返回 null（调用方重新描述） */
    public String get(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            ImageDesc row = descMapper.selectById(key);
            if (row == null || row.getDescription() == null || row.getDescription().isBlank()) return null;
            if (ttlExpired(row.getUpdateTime())) return null; // 超 TTL：视为过期，重新描述并刷新
            try {
                descMapper.touch(key); // 刷新"最近命中"（TTL 基准）
            } catch (Exception e) {
                log.debug("[ImageDescCache] touch 失败: {}", e.getMessage());
            }
            return row.getDescription();
        } catch (Exception e) {
            log.warn("[ImageDescCache] 读取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 写入缓存（原子 upsert；失败不阻断主流程）。按写入节流自动执行过期/旧版本清理 */
    public void put(String key, String description, String model) {
        if (key == null || key.isBlank() || description == null || description.isBlank()) return;
        try {
            descMapper.upsert(key, description, model);
        } catch (Exception e) {
            log.warn("[ImageDescCache] 写入失败: {}", e.getMessage());
        }
        if (writeCount.incrementAndGet() % PRUNE_INTERVAL == 0) {
            prune();
        }
    }

    /** 清理超 TTL 过期行 + 旧版本（key 前缀不匹配当前版本）行；ttl<=0 时只清理旧版本 */
    public void prune() {
        int ttl = ttlDays();
        try {
            int n;
            if (ttl > 0) {
                n = descMapper.pruneExpired(LocalDateTime.now().minusDays(ttl), "v" + version() + "_%");
            } else {
                n = descMapper.pruneOldVersion("v" + version() + "_%");
            }
            log.info("[ImageDescCache] 缓存清理 {} 行", n);
        } catch (Exception e) {
            log.warn("[ImageDescCache] 清理失败: {}", e.getMessage());
        }
    }

    /** 清空全部缓存（运维：全量重新描述） */
    public void clear() {
        try {
            descMapper.delete(null);
            log.info("[ImageDescCache] 缓存已清空");
        } catch (Exception e) {
            log.warn("[ImageDescCache] 清空失败: {}", e.getMessage());
        }
    }

    /** 缓存统计（总条数/模型数/最新/最旧命中时间） */
    public Map<String, Object> stats() {
        try {
            return descMapper.stats();
        } catch (Exception e) {
            log.warn("[ImageDescCache] 统计失败: {}", e.getMessage());
            return Map.of("total", 0, "models", 0, "newest", null, "oldest", null);
        }
    }

    private boolean ttlExpired(LocalDateTime updateTime) {
        int ttl = ttlDays();
        if (ttl <= 0 || updateTime == null) return false; // 0=永不过期
        return updateTime.isBefore(LocalDateTime.now().minusDays(ttl));
    }
}
