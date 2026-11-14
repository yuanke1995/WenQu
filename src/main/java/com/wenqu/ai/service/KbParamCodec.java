package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库检索参数的键面（库级唯一真源）。
 *
 * <p><b>为什么库只拥有这些键</b>：检索参数分两道门——库级定“召回池”（阈值、两路权重、召回量、
 * 相关度门、重排模型、重排窗口），智能体级定“出水”（是否重排、给模型几条）。同一个键只允许一层拥有，
 * 两层都写必然有一层被静默压掉；而跨库的一轮检索里，库级参数只能逐库生效，不能塞进线程级单标量。
 *
 * <p>重排触发即“库绑了重排模型才重排”：平台总开关与平台/个人默认模型已退役（2026-11-14），
 * {@code rerank.enabled} 仅剩智能体级「强制关」一种合法取值（显式 {@code false}），
 * {@code rerank.baseUrl} 已退役；检索全局默认值层已整体退役（2026-11-14，系统设置不再有
 * 「检索设置/文档解析默认模板」），{@code retrieval.vectorTopK} / {@code retrieval.keywordLimit}
 * 反向回流为库级键。存量 JSON 里残留退役键时读取静默忽略、保存时清除，避免“填了不生效”再次出现。
 *
 * @author yuanke
 */
public final class KbParamCodec {

    /** 向量相似度归一化基准 / 下限（逐库） */
    public static final String VEC_THRESHOLD = "retrieval.vecThreshold";
    /** 向量路在融合分中的权重（逐库） */
    public static final String VECTOR_WEIGHT = "retrieval.vectorWeight";
    /** 关键词路在融合分中的权重（逐库） */
    public static final String KEYWORD_WEIGHT = "retrieval.keywordWeight";
    /** 本库绑定的重排模型（资源归属：谁建库谁付费；一轮只有一个重排器，多库按绑定顺序收敛） */
    public static final String RERANK_MODEL = "rerank.model";
    /** 本库重排触发下限（块）：融合候选少于该数跳过 cross-encoder；空=内置默认（ConfigDefaults.RERANK_MIN_HITS） */
    public static final String RERANK_MIN_HITS = "rerank.minHits";
    /** 本库重排窗口（块）：只把融合分前 N 块送重排；空=内置默认（ConfigDefaults.RERANK_MAX_HITS） */
    public static final String RERANK_MAX_HITS = "rerank.maxHits";
    /** 本库向量召回条数（多库轮取参与库最大值）；空=内置默认（ConfigDefaults.RETRIEVAL_VECTOR_TOP_K） */
    public static final String VECTOR_TOP_K = "retrieval.vectorTopK";
    /** 本库关键词召回条数上限（多库轮取参与库最大值）；空=内置默认（ConfigDefaults.RETRIEVAL_KEYWORD_LIMIT） */
    public static final String KEYWORD_LIMIT = "retrieval.keywordLimit";
    /** 本库重排分门（仅对重排分 0~1 分域生效）；空=内置默认（ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE） */
    public static final String MIN_CONTEXT_SCORE = "retrieval.minContextScore";
    /** 本库融合分门（未启用重排时生效）；空=内置默认（ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE） */
    public static final String MIN_FUSION_SCORE = "retrieval.minFusionScore";
    /** 本库低分全灭策略（top1=只保底最相关的 1 条；off=不保底）；空=内置默认（ConfigDefaults.RETRIEVAL_LOW_CONFIDENCE_POLICY） */
    public static final String LOW_CONFIDENCE_POLICY = "retrieval.lowConfidencePolicy";
    /** 本库保底条目的最低分；空=内置默认（ConfigDefaults.RETRIEVAL_TOP1_FLOOR） */
    public static final String TOP1_FLOOR = "retrieval.top1Floor";
    /** 本库检索图扩展上限（块）：0=不扩展；空=内置默认（ConfigDefaults.GRAPH_EXPAND_TOP_K）。
     *  只对本库开了图谱（graph_enabled=1）时生效，多库轮取参与库最大值（与召回量两键同口径） */
    public static final String GRAPH_EXPAND_TOP_K = "graphrag.expandTopK";

    /** 库级可写键（顺序即前端展示顺序；只读集合防调用方误改） */
    public static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            VEC_THRESHOLD, VECTOR_WEIGHT, KEYWORD_WEIGHT, RERANK_MODEL, RERANK_MIN_HITS, RERANK_MAX_HITS,
            VECTOR_TOP_K, KEYWORD_LIMIT, MIN_CONTEXT_SCORE, MIN_FUSION_SCORE, LOW_CONFIDENCE_POLICY, TOP1_FLOOR,
            GRAPH_EXPAND_TOP_K)));

    private KbParamCodec() {}

    /**
     * 解析库级参数 JSON，只保留 {@link #KEYS} 内的键；空值/非法 JSON 一律当“未配置”。
     * <p>不抛异常：存量脏数据不能让一次检索整体失败，最坏是这一项回落全局。
     */
    public static Map<String, String> parse(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        JSONObject obj;
        try {
            obj = JSON.parseObject(json);
        } catch (Exception e) {
            return out;
        }
        if (obj == null) return out;
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (e.getKey() == null || !KEYS.contains(e.getKey())) continue;
            Object v = e.getValue();
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) out.put(e.getKey(), s);
        }
        return out;
    }

    /**
     * 写库前归一：只落白名单内的非空项；一项都不剩时返回 {@code null}（= 全部继承，清空该列）。
     */
    public static String normalize(String json) {
        Map<String, String> kept = parse(json);
        return kept.isEmpty() ? null : JSON.toJSONString(kept);
    }

}
