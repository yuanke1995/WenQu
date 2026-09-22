package com.wisesoft.wenqu.config;

import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;

/**
 * 知识库向量库装配：在 Spring AI 自动配置之上**补上 chunk 元数据字段**。
 *
 * <h3>为什么必须自己声明这个 Bean</h3>
 * {@link RedisVectorStore} 检索时只把 <b>schema 里声明过的</b> metadata 字段读回
 * （{@code doSimilaritySearch → toDocument} 用 {@code this.metadataFields} 过滤
 * {@code doc::hasProperty}）。而 Spring AI 自动配置的 {@code RedisVectorStore} 从不设置
 * {@code metadataFields}（其 {@code RedisVectorStoreProperties} 里没有该属性），于是索引只声明
 * {@code $.content} + {@code $.embedding} 两个字段 ⇒ 检索回来的 {@code Document.metadata} 里
 * <b>没有 kb_id / file_id / chunk_id</b> ⇒ {@code KnowledgeBaseRuntime.vectorRecall} 的
 * 「过采样后按 metadata kb_id 过滤」把所有命中全部丢掉 ⇒ <b>检索恒为空</b>
 * （2026-09-22 实测：向量已按 spec 正确写入、KNN 也能召回，但过滤后结果恒为 {@code []}）。
 *
 * <p>声明的四个字段与消费点一一对应：
 * <ul>
 *   <li>{@code kb_id} —— {@code vectorRecall} 的作用域过滤（本工程向量库是全局单索引，
 *       只能召回后按该字段筛）；</li>
 *   <li>{@code file_id} / {@code chunk_id} / {@code chunk_index} ——
 *       {@code fromDocument} 构造返回块时读取（对齐参考实现 {@code _build_chunk_from_hit}）。</li>
 * </ul>
 *
 * <p><b>注意</b>：索引 schema 在创建时固定。改字段后必须让索引重建
 * （`FT.DROPINDEX <index-name>` 保留 JSON 文档、下次启动由
 * {@code initializeSchema=true} 按新 schema 重建；不 DD 则文档不丢）。
 *
 * <p>除 metadata 字段外，其余选项与自动配置逐项同构（index 名 / 前缀 / 初始化开关 / 观测 /
 * 批处理策略），Jedis 连接构造亦按其 private 实现复刻。
 */
@Configuration
public class KnowledgeVectorStoreConfig {

    /** 检索侧要读回的 chunk 元数据字段（tag 便于等值过滤，chunk_index 用于排序展示）。 */
    static final List<RedisVectorStore.MetadataField> METADATA_FIELDS = List.of(
            RedisVectorStore.MetadataField.tag("kb_id"),
            RedisVectorStore.MetadataField.tag("file_id"),
            RedisVectorStore.MetadataField.tag("chunk_id"),
            RedisVectorStore.MetadataField.numeric("chunk_index"));

    /**
     * 替换自动配置的向量库（`@ConditionalOnMissingBean` 会因此退让），其余口径保持一致。
     */
    @Bean
    public RedisVectorStore vectorStore(
            EmbeddingModel embeddingModel,
            RedisVectorStoreProperties properties,
            JedisConnectionFactory jedisConnectionFactory,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<VectorStoreObservationConvention> customObservationConvention,
            BatchingStrategy batchingStrategy) {
        return RedisVectorStore.builder(jedisPooled(jedisConnectionFactory), embeddingModel)
                .initializeSchema(properties.isInitializeSchema())
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .customObservationConvention(customObservationConvention.getIfAvailable(() -> null))
                .batchingStrategy(batchingStrategy)
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .metadataFields(METADATA_FIELDS)
                .build();
    }

    /** 等价于 Spring AI 自动配置里的 private {@code jedisPooled(...)}（本工程需自行构造）。 */
    private static JedisPooled jedisPooled(JedisConnectionFactory factory) {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(factory.isUseSsl())
                .clientName(factory.getClientName())
                .timeoutMillis(factory.getTimeout())
                .password(factory.getPassword())
                .build();
        return new JedisPooled(new HostAndPort(factory.getHostName(), factory.getPort()), clientConfig);
    }
}
