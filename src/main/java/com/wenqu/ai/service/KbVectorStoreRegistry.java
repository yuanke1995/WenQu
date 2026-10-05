package com.wenqu.ai.service;

import com.wenqu.ai.model.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Service;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库向量存储注册中心：按知识库路由 VectorStore（per-KB 向量模型绑定的核心）。
 * <p>
 * 每个库必须绑定自己的向量模型（必须是可解析的供应商引用）：懒创建独立
 * RedisVectorStore——独立索引 {@code ai-doc-kb-{kbId}}、独立 key 前缀 {@code ai:chunkkb-{kbId}:}、
 * 独立维度 schema、向量化客户端为该库绑定的模型（{@link DynamicEmbeddingModel#forRef}）。
 * 全局共享索引 ai-doc-index 已退役（spring.ai.vectorstore.type=none 不再装配全局向量库 bean）。
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
     * 按知识库路由向量库：每个库都按自己的绑定模型路由独立索引。
     * kbId 空 → fail-loud：文档归属必填，走到这里说明上游传了空库，
     * 悄悄落到「某个默认库」会写错索引（默认库已每用户化，此处也无从解析归属）。
     */
    public VectorStore storeForKb(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalStateException("文档未归属任何知识库（kbId 为空），无法路由向量库；请检查该文档的 kb_id 数据");
        }
        String effective = kbId.trim();
        RedisVectorStore custom = byKb.get(effective);
        if (custom != null) return custom;
        KnowledgeBase kb = kbService.get(effective);
        if (kb == null || kb.getEmbeddingRef() == null || kb.getEmbeddingRef().isBlank()) {
            // 历史空绑定不再自动回填；走到这里说明向量模型未绑定，fail-loud 暴露而不是悄悄写错索引
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
                // 可过滤 metadata（全部 TAG）：docId/kbId + 三个 ACL 标签。
                // 不声明这些字段则 RedisFilterExpressionConverter.doKey() 会抛
                // "Not allowed filter identifier name"，且索引层无字段可过滤（ACL 下推无从谈起）。
                .metadataFields(DocumentAclTags.METADATA_FIELDS)
                .initializeSchema(true)
                .build();
        // 立即按该模型维度建索引（beforeAnyWrite/Search 均可用；维度探测在 forRef 委托内部完成）
        store.afterPropertiesSet();
        // 新建索引已含 ACL 字段；存量索引 afterPropertiesSet 会因已存在而跳过建schema，
        // 必须显式热补（FT.ALTER 不动向量数据，秒级完成）
        ensureAclSchema(kbId);
        return store;
    }

    /**
     * 热补存量索引的 ACL 可过滤字段（FT.ALTER SCHEMA ADD）。
     *
     * <p><b>为什么必须显式补</b>：{@link RedisVectorStore#afterPropertiesSet()} 在索引已存在时
     * 直接 return（源码：{@code if (jedis.ftList().contains(indexName)) return;}），
     * 也就是说 builder 里声明的 metadataFields <b>只对新建索引生效</b>。存量索引若不补，
     * 检索期{@code @aclGlobal:{1}} 会报「字段不存在」而整个向量路失败。
     *
     * <p><b>存量向量补不出 ACL 值（这是预期中间态，不是 bug）</b>：FT.ALTER 只改schema，
     * 不会回填历史 JSON 里的字段值。老向量没有 acl* 字段 → 检索期 ACL 条件对它们一律不命中 →
     * <b>漏召回（安全方向：宁可少召回也绝不越权）</b>。要恢复完整召回需重建该库向量
     * （{@code DocumentService.reembedKbAsync}），重建后新向量即带 ACL 标签。
     *
     * <p><b>不幂等，需按字段逐个补</b>：{@code ftAlter} 只要遇到一个已存在的字段就<b>整条命令失败</b>
     * （{@code Duplicate field in schema}），所以必须先探测哪些字段缺失、只补缺失的那些。
     * 上面 {@link #hasAllAclFields} 正是为此存在——它同时校验字段名与 SEPARATOR，
     * 任何一个不满足就重新探测一遍补全（逐个补，已有字段自动被跳过）。
     *
     * <p><b>改不了已有字段的属性（重要限制）</b>：RediSearch 不支持修改已存在字段的 SEPARATOR，
     * 只能 DROP 索引后重建（<b>不带 DD 时 JSON 向量数据不丢</b>，重建后按新 schema 自动重索引）。
     * 本项目所有ACL 字段都是一次性以正确 SEPARATOR 声明的，不会出现需要改属性的历史索引；
     * 若真遇到（人工改坏过），本方法补字段不会修好它的 SEPARATOR，需运维 DROP 重建该库索引。
     */
    private void ensureAclSchema(String kbId) {
        String index = kbIndexName(kbId);
        try {
            // 先查字段是否齐备：已齐备直接返回，避免每次访问都打一次 ALTER（ALTER 会触发索引重建，代价不小）
            if (hasAllAclFields(index)) return;
            // ftAlter 遇到已存在字段会整条失败 → 必须逐个字段补：成功的跳过，Duplicate 的忽略，
            // 这样「部分字段已存在」的索引也能补齐剩余字段
            Set<String> existing = existingAclFieldNames(index);
            for (RedisVectorStore.MetadataField mf : DocumentAclTags.METADATA_FIELDS) {
                if (existing.contains(mf.name())) continue;
                try {
                    // JSON 文档字段路径固定为 $.<字段名>（与 RedisVectorStore.jsonPath() 同口径）
                    // SEPARATOR "," 是<b>多值 ACL 标签的必要条件</b>：不设则整个字段值当字面量，
                    // 写 "deptA,deptB" 只能整体匹配（实测：不设分隔符时查单个值恒 0 命中）。
                    // 单值字段（kbId/docId/aclGlobal）设了也无副作用。
                    sharedJedis().ftAlter(index, List.of(TagField.of("$." + mf.name())
                            .as(mf.name())
                            .separator(DocumentAclTags.MULTI_VALUE_SEPARATOR.charAt(0))));
                    log.info("[KB-VEC] 索引 {} 已热补 ACL 字段 {}", index, mf.name());
                } catch (Exception e) {
                    // 单字段失败不阻断其余字段（并发的另一实例可能刚补上同一字段）
                    log.warn("[KB-VEC] 索引 {} 补字段 {} 失败（可能已被其它实例补上）: {}",
                            index, mf.name(), e.getMessage());
                }
            }
            log.info("[KB-VEC] 索引 {} ACL 可过滤字段已就绪（存量向量待重建后才参与 ACL 下推）", index);
        } catch (Exception e) {
            // fail-loud：schema 补失败不静默——检索期下推会拿不到字段，召回会掉。
            // 注意这里<b>不抛异常中断</b>：索引仍可正常读写（无过滤检索），
            // 越权风险由 HybridRetrievalService 的事后过滤兜底，属安全方向。
            log.error("[FAIL-LOUD] 索引 {} 热补 ACL 字段失败，本库将只走事后过滤（召回率下降但不会越权）: {}",
                    index, e.getMessage());
        }
    }

    /** 索引中已存在的 ACL 字段名集合（读不到时返回空集 → 逐个补，补已存在的会报错但被忽略） */
    private Set<String> existingAclFieldNames(String index) {
        Set<String> names = new HashSet<>();
        try {
            Map<String, Object> info = sharedJedis().ftInfo(index);
            if (info == null || !(info.get("attributes") instanceof List<?> attrs)) return names;
            for (Object o : attrs) {
                if (!(o instanceof List<?> pairs)) continue;
                for (int i = 0; i + 1 < pairs.size(); i += 2) {
                    if ("attribute".equals(String.valueOf(pairs.get(i)))) {
                        names.add(String.valueOf(pairs.get(i + 1)));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[KB-VEC] 读取索引 {} schema 失败，按「无既有字段」处理: {}", index, e.getMessage());
        }
        return names;
    }

    /**
     * 索引 schema 是否已含全部 ACL 可过滤字段<b>且 SEPARATOR 正确</b>（齐备则无需 ALTER）。
     *
     * <p><b>{@code ftInfo} 的返回结构（Jedis 6.0 实测）</b>：{@code Map<String,Object>} 里
     * {@code attributes} 节点是 {@code List<Object>}，<b>每个元素是扁平键值对 List</b>
     * （{@code [identifier, $.content, attribute, content, type, TEXT, SEPARATOR, N/A, ...]}），
     * <b>不是</b> {@code TextField}/{@code TagField} 对象——那几个类也没有公开 getter。
     * 故这里按键名对解析，不做类型强转（否则 index 定义变化即抛 ClassCastException）。
     */
    private boolean hasAllAclFields(String index) {
        try {
            Map<String, Object> info = sharedJedis().ftInfo(index);
            if (info == null || !(info.get("attributes") instanceof List<?> attrs)) return false;
            // 字段名 → 该字段的 SEPARATOR（未声明时 Redis 返回字符串 "N/A"）
            Map<String, String> separators = new HashMap<>();
            for (Object o : attrs) {
                if (!(o instanceof List<?> pairs)) continue;
                String attrName = null;
                String separator = null;
                for (int i = 0; i + 1 < pairs.size(); i += 2) {
                    String key = String.valueOf(pairs.get(i));
                    if ("attribute".equals(key)) attrName = String.valueOf(pairs.get(i + 1));
                    else if ("SEPARATOR".equals(key)) separator = String.valueOf(pairs.get(i + 1));
                }
                if (attrName != null) separators.put(attrName, separator);
            }
            for (RedisVectorStore.MetadataField mf : DocumentAclTags.METADATA_FIELDS) {
                // 字段缺失 → 需补；SEPARATOR 不是逗号 → 多值标签无法按值命中，同样视为需补
                //（注：已存在字段的 SEPARATOR 改不了，只能 DROP 重建索引，见 ensureAclSchema 注释）
                if (!DocumentAclTags.MULTI_VALUE_SEPARATOR.equals(separators.get(mf.name()))) return false;
            }
            return true;
        } catch (Exception e) {
            log.debug("[KB-VEC] 读取索引 {} schema 失败，按「需补字段」处理: {}", index, e.getMessage());
            return false;
        }
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
