package com.wenqu.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * 带<b>本地维度来源</b>的向量模型视图：{@link #dimensions()} 优先读本地（索引 schema），
 * 读不到才委托真实模型远程探测。
 *
 * <p><b>为什么必须存在（不是优化，是修 bug）</b>：{@code RedisVectorStore} 的
 * {@code createObservationContextBuilder} 里有 {@code .dimensions(embeddingModel.dimensions())}，
 * 而 {@code AbstractObservationVectorStore.delete} 在执行真正的删除前<b>必定先建埋点上下文</b>。
 * 于是「删向量」这个纯 Redis 操作（{@code doDelete} 只做 {@code pipeline.jsonDel()}，不碰向量）
 * 被绑定到了远程 embedding 服务的可用性上：{@code OpenAiEmbeddingModel.dimensions()} 在模型名
 * 不在 Spring AI 内置维度表（只有老的 ada 系列）时会真发一次 HTTP embedding 请求数维度，
 * 供应商额度耗尽 → 403 → 删除失败 → 文档删不掉。
 *
 * <p><b>本地维度为什么可信</b>：索引 schema 里 vector 字段的 {@code DIM} 是<b>已写入向量的事实维度</b>，
 * 比远程再探一次更权威（远程探的是「模型现在返回多少维」，索引记的是「这批向量实际是多少维」，
 * 换模型未重嵌时两者会不一致——此时以索引为准才是能写得进去的那个）。
 *
 * <p><b>何时才允许回落远程</b>：仅当索引还不存在（首次建库要建 schema，{@code DIM} 只能从模型那问）
 * 或 schema 读不出来。回落结果<b>不缓存</b>到本类——远程值由 delegate 自己缓存，
 * 本类只缓存读到的本地值，避免用一次远程探测结果盖过本地真值。
 *
 * <p><b>缓存与生命周期</b>：缓存随 {@code RedisVectorStore} 实例走，而 DROP 索引必然伴随
 * {@code KbVectorStoreRegistry.remove(kbId)} 丢弃实例，故同一实例存活期内索引维度不会变，缓存安全。
 * 也正因如此，检索这类高频路径不会每次都多一次 FT.INFO 往返。
 *
 * <p><b>转发不能省</b>：{@code getEmbeddingContent} 必须转发给 delegate——它带着
 * {@code MetadataMode.EMBED} 的格式化逻辑，若落到接口默认实现（{@code document.getText()}），
 * 送进模型的文本会和原来不同，<b>向量内容静默改变</b>且不报错。
 *
 * @author yuanke
 */
class LocalDimensionEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;

    /** 本地维度来源（读索引 schema）；返回 <=0 表示本地没有真值 */
    private final IntSupplier localDimensions;

    /**
     * 库级登记维度（{@code kb.embedding_dimensions}）；返回 <=0 表示未登记。
     *
     * <p><b>与 {@link #localDimensions} 的分工</b>：索引 schema 是「这批已写入向量的实际维度」，
     * 权威性最高，但<b>索引还不存在时读不到</b>——而应用启动那一刻恰恰索引尚未建立
     * （{@code build} 里 {@code afterPropertiesSet} 之前就要先取维度建 schema）。
     * 这时用知识库表登记的维度顶上：换模型重嵌成功时
     * {@code DocumentService.reembedKbAsync} 已把它回写进表，索引重建即可零调用完成。
     * 两级都拿不到才回落远程探测（首次建库/历史库未登记，属唯一合理场景）。
     */
    private final IntSupplier registeredDimensions;

    /** 本地维度缓存，-1=尚未读到 */
    private final AtomicInteger cachedLocalDim = new AtomicInteger(-1);

    LocalDimensionEmbeddingModel(EmbeddingModel delegate, IntSupplier localDimensions) {
        this(delegate, localDimensions, () -> -1);
    }

    LocalDimensionEmbeddingModel(EmbeddingModel delegate, IntSupplier localDimensions,
                                 IntSupplier registeredDimensions) {
        this.delegate = delegate;
        this.localDimensions = localDimensions;
        this.registeredDimensions = registeredDimensions;
    }

    @Override
    public int dimensions() {
        int cached = cachedLocalDim.get();
        if (cached > 0) return cached;
        int local = readLocal();
        if (local > 0) {
            cachedLocalDim.set(local);
            return local;
        }
        // 索引尚无真值 → 用库级登记维度（换模型重嵌成功即已回写，无需远程探测）
        int registered = readRegistered();
        if (registered > 0) {
            cachedLocalDim.set(registered);
            return registered;
        }
        // 两级本地都无真值（首次建库/历史库未登记）→ 委托远程探测，这是唯一合理路径
        return delegate.dimensions();
    }

    private int readLocal() {
        try {
            return localDimensions.getAsInt();
        } catch (Exception e) {
            // 本地维度读不到不是致命问题，退回库级登记值/远程探测即可（不静默吞：调用方日志会体现探测行为）
            return -1;
        }
    }

    private int readRegistered() {
        try {
            return registeredDimensions.getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }
}
