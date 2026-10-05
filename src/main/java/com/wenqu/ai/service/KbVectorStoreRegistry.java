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
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /**
     * 索引诊断（运维用，只读）：某库向量的「schema 是否就绪 + 存量向量是否已带 ACL 标签」。
     *
     * <p>回答运维最常问的两个问题，<b>两者含义不同、必须分开看</b>：
     * <ul>
     *   <li><b>schemaReady</b>：索引有没有 ACL 可过滤字段 → 决定检索期下推能不能用
     *       （缺字段时 {@code ensureAclSchema} 会自动热补，失败则下推退回无过滤检索）；</li>
     *   <li><b>docsWithAclTag</b>：存量向量里有多少条真的带 ACL 标签 ——<b>schema 就绪 ≠ 标签已回填</b>：
     *       {@code FT.ALTER} 只改 schema 不动历史 JSON，老向量没有 acl* 字段，检索期仍会被
     *       下推过滤掉（漏召回，不是越权）。</li>
     * </ul>
     * 不区分这两个会误判成「字段齐了=召回就正常」。
     */
    public Map<String, Object> diagnose(String kbId) {
        String index = kbIndexName(kbId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", kbId);
        out.put("index", index);
        try {
            boolean fieldsReady = hasAllAclFields(index);
            out.put("schemaReady", fieldsReady);
            out.put("separatorReady", aclSeparatorOk(index));
            out.put("indexedDocs", countDocs(index, "*"));
            // 带标签计数：TAG 字段不支持 {*} 通配（实测 @docId:* 直接 Syntax error），
            // 故抽样读 JSON 键判断，而不是靠 FT.SEARCH 聚合——抽样足以区分
            // 「全部已回填」与「一条都没回填」这两种需要处置的状态。
            out.put("docsWithAclTagSampled", sampleAclTagged(index));
            long tagged = out.get("docsWithAclTagSampled") instanceof Long v ? v : -1L;
            out.put("aclPushdownUsable", fieldsReady && tagged > 0);
            out.put("hint", !fieldsReady
                    ? "索引缺 ACL 可过滤字段：访问该库时会自动 FT.ALTER 热补；持续失败请检查 Redis 权限"
                    : (!aclSeparatorOk(index)
                        ? "ACL 字段缺 SEPARATOR（多值标签无法按值命中）：访问时会自动 DROP 重建 schema"
                        : (tagged > 0
                            ? "ACL 下推已生效"
                            : "schema 已就绪但存量向量无 ACL 标签，需重建：POST /api/ai/document/rebuild-kb")));
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 索引内满足条件的文档数（索引不存在或查询失败返回 -1，用 limit(0,0) 只取 total 不取文档） */
    private long countDocs(String index, String queryText) {
        try {
            var r = sharedJedis().ftSearch(index,
                    new redis.clients.jedis.search.Query(queryText).limit(0, 0).dialect(2));
            return r.getTotalResults();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 抽样统计带 ACL 标签的向量数（最多抽 {@value} 条）。
     * <p><b>为什么抽样而不是 FT.SEARCH 聚合</b>：TAG 字段不支持 {@code @field:{*}} 通配
     * （实测 {@code @docId:*} 直接报 Syntax error），跨字段 OR 通配也不被解析器接受，
     * 拿不到「任一 acl 字段有值」的总数。抽样读 JSON 键已足够区分
     * 「已回填」与「未回填」两种需处置状态，成本也更低（不必扫全索引）。
     *
     * @return 抽样中带标签的条数；索引不可读返回 -1
     */
    private long sampleAclTagged(String index) {
        String prefix = index.replace("ai-doc-kb-", "ai:chunkkb-") + ":";
        long tagged = 0;
        int sampled = 0;
        try {
            // SCAN 游标 + COUNT 限流，避免 KEYS 阻塞主线程（Jedis 6 用 String cursor + isCompleteIteration）
            String cursor = "0";
            do {
                redis.clients.jedis.resps.ScanResult<String> res = sharedJedis().scan(cursor,
                        new redis.clients.jedis.params.ScanParams().match(prefix + "*").count(200));
                for (String k : res.getResult()) {
                    if (sampled++ >= SAMPLE_LIMIT) return tagged;
                    try {
                        Object v = sharedJedis().jsonGet(k,
                                redis.clients.jedis.json.Path2.of("$." + DocumentAclTags.FIELD_ACL_GLOBAL));
                        if (v != null) tagged++;
                    } catch (Exception ignored) {
                        // 单条读失败跳过
                    }
                }
                cursor = res.getCursor();
                if (res.isCompleteIteration()) break;
            } while (sampled < SAMPLE_LIMIT);
        } catch (Exception e) {
            return -1L;
        }
        return tagged;
    }

    /** 抽样上限：够判断「有无标签」，又不至于把诊断拖慢 */
    private static final int SAMPLE_LIMIT = 200;

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
     * <p><b>Spring AI 建索引时不带 SEPARATOR（2026-10-05 实测，重要）</b>：
     * {@link RedisVectorStore} 的 {@code schemaField(MetadataField)} 只按字段类型生成
     * {@code TextField}/{@code TagField}/{@code NumericField}，<b>不设置 SEPARATOR</b>；
     * builder 的 {@code metadataFields(...)} 也无法表达分隔符。
     * 于是<b>所有由 Spring AI 新建的索引，ACL 字段的 SEPARATOR 都是空的</b>，
     * 而多值标签（部门/用户级共享）在无分隔符时只能整体匹配、查单个值恒 0 命中
     * → 单值（aclGlobal/kbId）正常，多值（aclDept/aclUser）静默失效。
     * <p>本方法把「字段缺失」与「SEPARARATOR 不对」都视为需处理：
     * <ul>
     *   <li><b>字段缺失</b> → 逐个 {@code ftAlter} 补（带正确 SEPARATOR）；</li>
     *   <li><b>字段在但 SEPARATOR 不对</b> → RediSearch <b>不支持改已有字段属性</b>，
     *       {@code ftAlter} 只会报 Duplicate 而无效果。此时只能 <b>DROP 索引重建</b>
     *       （不带 DD 时 JSON 向量数据不丢），并<b>告警要求运维重建该库向量</b>——
     *       但注意 DROP 后 Spring AI 仍会建成无 SEPARATOR 的 schema，所以
     *       <b>必须在 DROP 之后由本类自己用带 SEPARATOR 的 schema 重建</b>，见 {@link #recreateIndexWithAclSchema}。</li>
     * </ul>
     */
    private void ensureAclSchema(String kbId) {
        String index = kbIndexName(kbId);
        try {
            // 先查字段是否齐备：已齐备直接返回，避免每次访问都打一次 ALTER（ALTER 会触发索引重建，代价不小）
            if (hasAllAclFields(index)) return;
            Set<String> existing = existingAclFieldNames(index);
            // 分支1：字段已存在但 SEPARATOR 不对 —— ftAlter 改不了已有字段属性（报 Duplicate 无效果），
            //        只能 DROP 后由本类用带 SEPARATOR 的 schema 重建（JSON 数据不丢）
            boolean separatorWrong = !existing.isEmpty() && !aclSeparatorOk(index);
            if (separatorWrong) {
                log.warn("[KB-VEC] 索引 {} 的 ACL 字段缺 SEPARATOR（Spring AI 建索引时不会设置），"
                        + "多值 ACL 标签（部门/用户级共享）将无法按值命中 → 重建 schema", index);
                if (recreateIndexWithAclSchema(kbId, index)) return;
                // 重建失败：继续走补字段（至少让字段存在，单值标签可用）
            }
            // 分支2：字段缺失 —— ftAlter 遇到已存在字段会整条失败，必须逐个补
            for (RedisVectorStore.MetadataField mf : DocumentAclTags.METADATA_FIELDS) {
                if (existing.contains(mf.name())) continue;
                try {
                    sharedJedis().ftAlter(index, List.of(aclTagField(mf.name())));
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

    /**
     * ACL 字段的 TagField 定义：{@code $.name AS name TAG SEPARATOR ","}。
     * <p>SEPARATOR 是多值标签生效的<b>必要条件</b>，缺它则整个字段值当字面量
     * （实测：写 {@code deptA,deptB} 后查 {@code deptA} 恒 0 命中）。单值字段设了无副作用。
     */
    private SchemaField aclTagField(String name) {
        return TagField.of("$." + name)
                .as(name)
                .separator(DocumentAclTags.MULTI_VALUE_SEPARATOR.charAt(0));
    }

    /**
     * DROP 索引后用<b>带 SEPARATOR 的 schema</b> 重建（JSON 向量数据保留，仅重建 schema 与倒排）。
     *
     * <p><b>为什么必须由本类重建而不是交给 Spring AI</b>：{@link RedisVectorStore#afterPropertiesSet()}
     * 建的 schema <b>不带 SEPARATOR</b>（其 {@code schemaField()} 不支持该属性），
     * 所以「DROP 掉等它自动重建」只会得到同样有问题的 schema。
     *
     * <p><b>DROP 用不带 DD 的形式</b>：只丢 schema 与倒排，{@code ai:chunkkb-* 的 JSON（含向量）} 保留，
     * 重建后自动重新入索引 → 无需重新调嵌入模型。
     *
     * @return true=重建成功；false=失败（调用方退回补字段路径）
     */
    private boolean recreateIndexWithAclSchema(String kbId, String index) {
        try {
            // 维度取自当前模型绑定（重建 schema 必须与向量实际维度一致，否则倒排建不起来）
            KnowledgeBase kb = kbService.get(kbId);
            if (kb == null || kb.getEmbeddingRef() == null || kb.getEmbeddingRef().isBlank()) {
                log.warn("[KB-VEC] 知识库 {} 无向量模型绑定，跳过 schema 重建", kbId);
                return false;
            }
            int dim = embeddingModel.forRef(kb.getEmbeddingRef()).dimensions();
            if (dim <= 0) {
                log.warn("[KB-VEC] 知识库 {} 向量模型维度探测失败（{}），跳过 schema 重建", kbId, dim);
                return false;
            }
            sharedJedis().ftDropIndex(index); // 不带 DD：保留 JSON 数据
            List<SchemaField> fields = new ArrayList<>();
            fields.add(TextField.of("$.content").as("content").weight(1.0));
            Map<String, Object> vecAttrs = new HashMap<>();
            vecAttrs.put("DIM", dim);
            vecAttrs.put("DISTANCE_METRIC", "COSINE");
            vecAttrs.put("TYPE", "FLOAT32");
            fields.add(VectorField.builder()
                    .fieldName("$.embedding")
                    .algorithm(VectorField.VectorAlgorithm.HNSW)
                    .attributes(vecAttrs)
                    .as("embedding")
                    .build());
            for (RedisVectorStore.MetadataField mf : DocumentAclTags.METADATA_FIELDS) {
                fields.add(aclTagField(mf.name()));
            }
            String resp = sharedJedis().ftCreate(index,
                    FTCreateParams.createParams()
                            .on(IndexDataType.JSON)
                            .addPrefix("ai:chunkkb-" + kbId + ":"),
                    fields);
            if (!"OK".equals(resp)) {
                log.error("[FAIL-LOUD] 索引 {} 重建 schema 返回 {}", index, resp);
                return false;
            }
            log.info("[KB-VEC] 索引 {} 已用带 SEPARATOR 的 schema 重建（JSON 向量数据保留，无需重新嵌入）", index);
            return true;
        } catch (Exception e) {
            log.error("[FAIL-LOUD] 索引 {} 重建 schema 失败: {}", index, e.getMessage());
            return false;
        }
    }

        /**
     * 索引中<b>已存在的 ACL 字段</b>是否都带正确 SEPARATOR。
     * <p>与 {@link #hasAllAclFields} 分工：那个判「字段齐不齐」，这个判「字段在但属性对不对」——
     * 后者是 Spring AI 建索引的必然缺口（{@code schemaField()} 不设 SEPARATOR），
     * 且 RediSearch 不允许事后改属性，只能 DROP 重建。
     */
    private boolean aclSeparatorOk(String index) {
        try {
            Map<String, Object> info = sharedJedis().ftInfo(index);
            if (info == null || !(info.get("attributes") instanceof List<?> attrs)) return true;
            for (Object o : attrs) {
                if (!(o instanceof List<?> pairs)) continue;
                String attrName = null;
                String separator = null;
                for (int i = 0; i + 1 < pairs.size(); i += 2) {
                    String key = String.valueOf(pairs.get(i));
                    if ("attribute".equals(key)) attrName = String.valueOf(pairs.get(i + 1));
                    else if ("SEPARATOR".equals(key)) separator = String.valueOf(pairs.get(i + 1));
                }
                if (attrName == null) continue;
                boolean isAclField = false;
                for (RedisVectorStore.MetadataField mf : DocumentAclTags.METADATA_FIELDS) {
                    if (mf.name().equals(attrName)) { isAclField = true; break; }
                }
                if (isAclField && !DocumentAclTags.MULTI_VALUE_SEPARATOR.equals(separator)) return false;
            }
            return true;
        } catch (Exception e) {
            return true; // 读不到就不动 schema（ftAlter 补字段路径更保守）
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
