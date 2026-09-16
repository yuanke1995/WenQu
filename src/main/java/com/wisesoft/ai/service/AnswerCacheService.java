package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.AnswerCacheMapper;
import com.wisesoft.ai.model.AnswerCache;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 相似问题答案缓存（语义缓存）
 * <p>
 * - 问题 embedding 与知识块同模型；内存中与历史问题向量做余弦相似度，
 *   达到阈值（semanticCache.threshold，默认 0.96）即命中，跳过检索+LLM 直接返回历史答案
 * - 存储走 MySQL（c_ai_answer_cache），启动全量加载进内存（条数上限 semanticCache.maxEntries 默认 500，LRU 按时间淘汰）
 * - 知识库变更时整体失效（DocumentService 在解析成功/删除/回滚/启停用/补块处调 clearAll），
 *   宁可少缓存不可答错——缓存答案对应的只是"当时"的知识库快照
 * - embedding 调用失败：查询侧静默跳过（放行走正常流程），写入侧丢弃（不阻断回答）
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AnswerCacheService {

    private final AnswerCacheMapper cacheMapper;
    private final EmbeddingModel embeddingModel;
    private final ConfigService configService;

    /** 内存索引：全量缓存条目（volatile 整体替换，读多写少） */
    private volatile List<Entry> index = List.of();

    public AnswerCacheService(AnswerCacheMapper cacheMapper, EmbeddingModel embeddingModel, ConfigService configService) {
        this.cacheMapper = cacheMapper;
        this.embeddingModel = embeddingModel;
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从 DB 全量加载进内存（启动/清空后重建） */
    public void reload() {
        try {
            List<AnswerCache> all = cacheMapper.selectList(new LambdaQueryWrapper<AnswerCache>()
                    .orderByAsc(AnswerCache::getCreateTime));
            List<Entry> list = new ArrayList<>(all.size());
            for (AnswerCache c : all) {
                try {
                    list.add(new Entry(c.getId(), c.getQuestion(), parseVector(c.getEmbedding()), c));
                } catch (Exception ignored) {
                    // 单条向量解析失败跳过
                }
            }
            index = list;
            log.info("[ANSWER-CACHE] 加载 {} 条相似问题缓存", list.size());
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 缓存加载失败（查询将全部放行）: {}", e.getMessage());
        }
    }

    private boolean enabled() {
        return configService.getBoolean("semanticCache.enabled");
    }

    private double threshold() {
        return configService.getDouble("semanticCache.threshold", 0.96);
    }

    private int maxEntries() {
        return configService.getInt("semanticCache.maxEntries", 500);
    }

    /**
     * 查询相似问题命中。返回命中的缓存实体（含问题/答案/来源等），未命中或未启用返回 null。
     * 命中后做 DB 行存在性校验（原子自增命中数）：
     * 行不存在说明内存索引滞后（其它实例 clearAll/淘汰）→ 整体失效本地索引并按未命中放行，
     * 保证多副本部署下绝不会返回"知识库已变更"的过期答案。
     */
    public AnswerCache lookup(String question) {
        if (!enabled() || question == null || question.isBlank()) return null;
        if (index.isEmpty()) return null;
        float[] qv;
        try {
            qv = embeddingModel.embed(question);
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 查询向量化失败，放行: {}", e.getMessage());
            return null;
        }
        double thr = threshold();
        Entry best = null;
        double bestScore = 0;
        int dimMismatch = 0;
        for (Entry e : index) {
            double s = cosine(qv, e.vector);
            if (s < 0) {
                dimMismatch++;
                continue;
            }
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        // 维度护栏命中：存量条目出自旧 embedding 模型（向量模型切换后 clearAll 未跑或跑失败）。
        // 这些条目已永久不可能命中，提示运维手动重嵌入/清缓存以恢复语义缓存加速能力
        if (dimMismatch > 0) {
            log.warn("[ANSWER-CACHE] {} 条缓存向量维度与当前模型不一致（旧向量模型残留），已跳过；"
                    + "请在设置页触发全量重嵌入或清空语义缓存", dimMismatch);
        }
        if (best == null || bestScore < thr) {
            log.debug("[ANSWER-CACHE] 未命中 best={} thr={}", String.format("%.4f", bestScore), thr);
            return null;
        }
        AnswerCache hit = best.cache;
        log.info("[ANSWER-CACHE] 命中 similarity={} question=[{}] → cache=[{}]", String.format("%.4f", bestScore), question, best.question);
        // 命中入库校验（原子自增二合一）：DB 行不存在 = 本实例内存索引滞后于共享库
        // （其它实例已 clearAll——知识库变更，或已按 LRU 淘汰该行）。此时内存条目已失效，
        // 若直接返回会把"旧知识库快照的答案"当正式回答下发并写入会话历史（多副本答错级风险）。
        // 受影响 0 → 整体置空本地索引（与库对齐），本次按未命中放行走正常检索。
        int affected;
        try {
            affected = cacheMapper.incrHitCount(hit.getId());
        } catch (Exception e) {
            // DB 异常：无法确认缓存有效，宁放行（多花一次 RAG）也不返回可能失效的答案
            log.warn("[ANSWER-CACHE] 命中校验失败（DB 异常），按未命中放行: {}", e.getMessage());
            return null;
        }
        if (affected == 0) {
            index = List.of();
            log.info("[ANSWER-CACHE] 命中条目 id={} 已被其它实例清除/淘汰，本地缓存整体失效，本次放行", hit.getId());
            return null;
        }
        return hit;
    }

    /**
     * 异步写入缓存（回答完成后调用；启用判断/失败静默，不阻断回答）
     *
     * @param sources 引用来源 JSON（done payload 的 sources，可为 null）
     * @param images  关联图片 URL 列表（可为 null）
     * @param related 相关追问列表（可为 null）
     */
    public void storeAsync(String question, String answer, String sources,
                           List<String> images, List<String> related, String messageId) {
        if (!enabled() || question == null || question.isBlank() || answer == null || answer.isBlank()) return;
        ThreadPoolManager.execute(() -> {
            try {
                float[] v = embeddingModel.embed(question);
                AnswerCache c = new AnswerCache();
                c.setQuestion(question);
                c.setEmbedding(JSON.toJSONString(v));
                c.setAnswer(answer);
                c.setSources(sources);
                c.setImages((images == null || images.isEmpty()) ? null : JSON.toJSONString(images));
                c.setRelated((related == null || related.isEmpty()) ? null : JSON.toJSONString(related));
                c.setMessageId(messageId);
                c.setHitCount(0);
                cacheMapper.insert(c);
                // 内存同步追加；超上限淘汰最早条目（DB+内存一起删）
                List<Entry> cur = new ArrayList<>(index);
                cur.add(new Entry(c.getId(), question, v, c));
                int max = Math.max(10, maxEntries());
                while (cur.size() > max) {
                    Entry oldest = cur.remove(0);
                    cacheMapper.deleteById(oldest.id);
                }
                index = cur;
                log.info("[ANSWER-CACHE] 已缓存问题: {}（共 {} 条）", question, cur.size());
            } catch (Exception e) {
                log.warn("[ANSWER-CACHE] 缓存写入失败（不影响回答）: {}", e.getMessage());
            }
        });
    }

    /** 整体失效清空（知识库变更时调用：解析成功/删除/回滚/启停用/手动补块） */
    public void clearAll() {
        try {
            cacheMapper.delete(new LambdaQueryWrapper<>());
            index = List.of();
            log.info("[ANSWER-CACHE] 知识库变更，答案缓存已整体清空");
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 缓存清空失败: {}", e.getMessage());
        }
    }

    /** 统计信息（设置页展示） */
    public Map<String, Object> stats() {
        return Map.of(
                "enabled", enabled(),
                "threshold", threshold(),
                "maxEntries", maxEntries(),
                "count", index.size());
    }

    private float[] parseVector(String json) {
        List<Float> list = JSON.parseArray(json, Float.class);
        float[] v = new float[list.size()];
        for (int i = 0; i < list.size(); i++) v[i] = list.get(i);
        return v;
    }

    /**
     * 余弦相似度（embedding 服务返回归一化向量时退化为点积，此处按通用公式实现）。
     * <p>
     * <b>维度护栏</b>：长度不等说明两个向量出自不同 embedding 模型——向量模型热切换后，
     * 库里存量条目仍是旧模型产物。跨模型向量空间不可比：按较短长度截断前缀算出的相似度
     * 是无意义噪声，且完全可能越过 0.96 阈值，把一条语义无关的历史回答当成命中直接返回。
     * 故维度不一致一律判为不命中（返回 -1，任何合法阈值都不可能通过），
     * 存量脏条目由 {@link #clearAll()}（全量重嵌入编排的第一步）负责清理。
     */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        int n = a.length;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 内存索引条目 */
    private record Entry(String id, String question, float[] vector, AnswerCache cache) {
    }
}