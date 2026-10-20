package com.wenqu.ai.service;

import com.wenqu.ai.model.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 库级检索参数的解析与生效（“召回池”那一层）。
 *
 * <p>取值链：<b>显式线程覆盖（评估扫参）&gt; 库级 JSON &gt; 全局设置</b>。
 * 显式覆盖排在最前，是为了让 {@code RetrievalEvaluationService} 的参数扫描仍然能压过库级配置——
 * 否则带自定义阈值的库会让评估扫参静默失效（正是 2026-09 那批“配了不生效”的同款坑）。
 *
 * <p>库级参数是<b>逐库</b>的：{@link #forScope} 一次解析出本轮涉及的所有库，
 * 由检索层按命中块所属库取用，而不是在回合入口压成一个标量。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbRetrievalParamService {

    /**
     * 一个库的生效检索参数。
     * <p>{@code rerankModel} 只用于展示/诊断：真正生效时由 {@link #withRerankModel} 收敛成
     * 本轮唯一的重排模型（一轮只有一个重排器）。
     */
    public record Params(double vecThreshold, double vectorWeight, double keywordWeight, String rerankModel) {
        public boolean hasRerankModel() {
            return rerankModel != null && !rerankModel.isBlank();
        }
    }

    private static final double DEFAULT_VEC_THRESHOLD = 0.3;
    private static final double DEFAULT_VECTOR_WEIGHT = 0.6;
    private static final double DEFAULT_KEYWORD_WEIGHT = 0.4;
    /** 归一化分母 1-vt 不能为 0：阈值上限钳到 0.99（配置层同样校验 0~1） */
    private static final double MAX_VEC_THRESHOLD = 0.99;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ConfigService configService;

    /** 全局口径（不含任何库覆盖）：手动知识块/无库场景的兜底 */
    public Params defaults() {
        return resolve(null);
    }

    /** 单库生效参数（库不存在按全局处理） */
    public Params forKb(String kbId) {
        return resolve(kbId == null || kbId.isBlank() ? null : knowledgeBaseService.get(kbId));
    }

    /**
     * 本轮范围 → 每库生效参数。{@code kbIds} 为空表示“不限库”，按库列表（默认库优先）解析。
     * <p>只解析范围内的库：检索层按命中块所属库取值，范围外的库不会产生命中。
     */
    public Map<String, Params> forScope(Collection<String> kbIds) {
        Map<String, Params> out = new LinkedHashMap<>();
        for (KnowledgeBase kb : scope(kbIds)) {
            out.put(kb.getId(), resolve(kb));
        }
        return out;
    }

    /**
     * 本轮收敛后的重排模型：在<b>实际命中</b>的库里，按范围顺序取第一个配了 {@code rerank.model} 的库。
     *
     * <p>为什么按“命中库”而不是“范围”收敛：范围可以为空（=全部库），若按范围取，
     * 多租户下会选中别人的默认库模型（代打他人额度），也会给只命中手册库的一轮套上法律库的重排模型。
     * 命中块已经过可见性判定，按它收敛既准确又不越权。
     *
     * <p>返回 null 有三种含义——本轮没有命中、命中的库都没配（走平台默认）、
     * 或本轮已有显式覆盖（评估/调用方指定，优先级更高）。
     */
    public String rerankModelFor(Collection<String> kbIds, Collection<String> hitKbIds) {
        if (hitKbIds == null || hitKbIds.isEmpty()) return null;
        if (configService.currentOverrides().containsKey(KbParamCodec.RERANK_MODEL)) return null;
        Set<String> hit = new LinkedHashSet<>(hitKbIds);
        for (KnowledgeBase kb : scope(kbIds)) {
            if (!hit.contains(kb.getId())) continue;
            String ref = KbParamCodec.parse(kb.getQueryParams()).get(KbParamCodec.RERANK_MODEL);
            if (ref != null && !ref.isBlank()) return ref;
        }
        return null;
    }

    /**
     * 在“本轮重排模型”作用域内执行一次重排调用。
     * <p>重排器是整轮共享资源：多库各配一个模型时无法同时成立，故按 {@link #rerankModelFor}
     * 收敛成一个值，再以线程局部覆盖交给 {@code RerankService}（它按 rerank.model 解析路由）。
     * 调用结束恢复原覆盖层——池化线程复用，残留会让下一轮继承本轮的模型。
     */
    public <T> T withRerankModel(Collection<String> kbIds, Collection<String> hitKbIds, Supplier<T> call) {
        String ref = rerankModelFor(kbIds, hitKbIds);
        if (ref == null) return call.get();
        Map<String, String> prev = configService.currentOverrides();
        configService.putOverrides(Map.of(KbParamCodec.RERANK_MODEL, ref));
        try {
            return call.get();
        } finally {
            configService.clearOverride();
            if (!prev.isEmpty()) configService.putOverrides(prev);
        }
    }

    /** 写入前归一（知识库新建/编辑用）：只落白名单键，清空后返回 null = 全部继承 */
    public String normalizeForStore(String json) {
        return KbParamCodec.normalize(json);
    }

    // ==================== 解析 ====================

    private Params resolve(KnowledgeBase kb) {
        Map<String, String> kbParams = kb == null ? Map.of() : KbParamCodec.parse(kb.getQueryParams());
        double vt = number(KbParamCodec.VEC_THRESHOLD, kbParams,
                DEFAULT_VEC_THRESHOLD, 0d, MAX_VEC_THRESHOLD);
        double vw = number(KbParamCodec.VECTOR_WEIGHT, kbParams,
                DEFAULT_VECTOR_WEIGHT, 0d, 1d);
        double kw = number(KbParamCodec.KEYWORD_WEIGHT, kbParams,
                DEFAULT_KEYWORD_WEIGHT, 0d, 1d);
        String rw = kbParams.get(KbParamCodec.RERANK_MODEL);
        String rerankModel = rw == null || rw.isBlank() ? null : rw.trim();
        return new Params(vt, vw, kw, rerankModel);
    }

    private double number(String key, Map<String, String> kbParams, double def, double min, double max) {
        Map<String, String> explicit = configService.currentOverrides();
        String raw = explicit.containsKey(key) ? explicit.get(key) : kbParams.get(key);
        Double v = parseDouble(raw);
        // 显式覆盖/库级值非法时回落全局；全局也非法时 getDouble 自己会兜底到声明默认
        if (v == null) v = configService.getDouble(key, def);
        return clamp(v, min, max);
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 本轮涉及的库：kbIds 非空按给定顺序（智能体绑定顺序），为空取全部未删除库（默认库优先） */
    private List<KnowledgeBase> scope(Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return knowledgeBaseService.list();
        List<KnowledgeBase> out = new ArrayList<>(kbIds.size());
        for (String id : kbIds) {
            if (id == null || id.isBlank()) continue;
            KnowledgeBase kb = knowledgeBaseService.get(id);
            if (kb != null) out.add(kb);
        }
        return out;
    }
}
