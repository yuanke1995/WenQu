package com.wisesoft.ai.service;

import com.wisesoft.ai.model.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Service;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库向量存储注册中心：按知识库路由 VectorStore（per-KB 向量模型绑定的核心）。
 * <p>
 * 每个库必须绑定自己的向量模型（历史空绑定已由启动迁移回填）：懒创建独立 RedisVectorStore——
 * 独立索引 {@code ai-doc-kb-{kbId}}、独立 key 前缀 {@code ai:chunkkb-{kbId}:}、
 * 独立维度 schema、向量化客户端为该库绑定的模型（{@link DynamicEmbeddingModel#forRef}）。
 * 全局共享索引 ai-doc-index 仅保留 Spring 自动配置 bean 作回滚缓冲，不再参与路由。
 * <p>
 * 维度一致性约束因此从"全库"收缩到"单库"：同库所有块同一向量模型；换模型触发本库重嵌入
 * （{@code DocumentService.reembedKbAsync}）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbVectorStoreRegistry {

    private final KnowledgeBaseService kbService;
    private final DynamicEmbeddingModel embeddingModel;
    private final RedisProperties redisProperties;

    /** kbId → 独立向量库实例（懒创建；KB 切换向量模型时由 evict 移除重建） */
    private final Map<String, RedisVectorStore> byKb = new ConcurrentHashMap<>();

    private volatile JedisPooled sharedJedis;

    /**
     * 按知识库路由向量库：每个库都按自己的绑定模型路由独立索引；
     * kbId 空（防御：迁移后不应出现）或库行查不到（已删）→ 默认库的独立索引。
     */
    public VectorStore storeForKb(String kbId) {
        String effective = kbId == null || kbId.isBlank() ? kbService.defaultId() : kbId.trim();
        RedisVectorStore custom = byKb.get(effective);
        if (custom != null) return custom;
        KnowledgeBase kb = kbService.get(effective);
        if (kb == null || kb.getEmbeddingRef() == null || kb.getEmbeddingRef().isBlank()) {
            // 启动迁移会把历史空绑定回填；走到这里说明向量模型不可用，fail-loud 暴露而不是悄悄写错索引
            throw new IllegalStateException("知识库 " + effective + " 未绑定向量模型，无法路由向量库（请在知识库管理中绑定）");
        }
        return byKb.computeIfAbsent(effective, id -> build(kb));
    }

    /** KB 切换/清空向量模型后调用：移除旧实例（下次访问按新 ref 重建）；返回被移除的实例（可能 null） */
    public RedisVectorStore remove(String kbId) {
        return kbId == null ? null : byKb.remove(kbId);
    }

    /** KB 独立索引名（与 build 一致；DROP 旧索引用） */
    public static String kbIndexName(String kbId) {
        return "ai-doc-kb-" + kbId;
    }

    /** DROP 该 KB 的独立索引（连数据删除——旧模型向量全部作废，用于按库重嵌/清空绑定）；索引不存在时忽略 */
    public void dropKbIndex(String kbId) {
        if (kbId == null || kbId.isBlank()) return;
        remove(kbId);
        try {
            sharedJedis().ftDropIndexDD(kbIndexName(kbId));
            log.info("[KB-VEC] 知识库 {} 独立向量索引已删除（含旧向量数据）", kbId);
        } catch (Exception e) {
            log.info("[KB-VEC] 知识库 {} 独立向量索引不存在或删除失败（可忽略）: {}", kbId, e.getMessage());
        }
    }

    /** 当前全部独立（自定义向量模型）向量库——无 scope 的全库检索按此展开；全局库由调用方并入 */
    public List<KnowledgeBase> customKbs() {
        return kbService.listCustomEmbedding();
    }

    private RedisVectorStore build(KnowledgeBase kb) {
        String kbId = kb.getId();
        log.info("[KB-VEC] 为知识库「{}」构建独立向量索引: index=ai-doc-kb-{}, prefix=ai:chunkkb-{}:, 模型={}",
                kb.getName(), kbId, kbId, kb.getEmbeddingRef());
        RedisVectorStore store = RedisVectorStore
                .builder(sharedJedis(), embeddingModel.forRef(kb.getEmbeddingRef()))
                .indexName(kbIndexName(kbId))
                .prefix("ai:chunkkb-" + kbId + ":")
                .initializeSchema(true)
                .build();
        // 立即按该模型维度建索引（beforeAnyWrite/Search 均可用；维度探测在 forRef 委托内部完成）
        store.afterPropertiesSet();
        return store;
    }

    /** 全局共享的 Jedis 连接池（各 per-KB 索引共用一个池，连接配置与全局 Redis 一致） */
    private synchronized JedisPooled sharedJedis() {
        if (sharedJedis == null) {
            String host = redisProperties.getHost();
            int port = redisProperties.getPort();
            DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(5000)
                    .socketTimeoutMillis(5000);
            if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
                cfg.password(redisProperties.getPassword());
            }
            sharedJedis = new JedisPooled(new HostAndPort(host, port), cfg.build());
        }
        return sharedJedis;
    }

    /** 全部知识库的独立向量库（供无 scope 的全库检索展开；顺序无关） */
    public List<VectorStore> allStores() {
        List<VectorStore> out = new ArrayList<>();
        for (KnowledgeBase kb : customKbs()) {
            try {
                out.add(storeForKb(kb.getId()));
            } catch (Exception e) {
                log.warn("[KB-VEC] 知识库 {} 向量库构建失败（跳过该库）: {}", kb.getId(), e.getMessage());
            }
        }
        return out;
    }
}
