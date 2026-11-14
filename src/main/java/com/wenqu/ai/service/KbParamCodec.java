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
 * <p><b>为什么库只拥有这些键</b>：检索参数分两道门——库级定“召回池”（阈值、两路权重、重排模型、
 * 重排窗口），智能体级定“出水”（是否重排、给模型几条）。同一个键只允许一层拥有，两层都写必然有一层
 * 被静默压掉；而跨库的一轮检索里，库级参数只能逐库生效，不能塞进线程级单标量。
 *
 * <p>重排触发即“库绑了重排模型才重排”：平台总开关与平台/个人默认模型已退役（2026-11-14），
 * {@code rerank.enabled} 仅剩智能体级「强制关」一种合法取值（显式 {@code false}），
 * {@code retrieval.vectorTopK} / {@code retrieval.keywordLimit} / {@code rerank.baseUrl} 已退役；
 * 存量 JSON 里残留这些键时读取静默忽略、保存时清除，避免“填了不生效”再次出现。
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

    /** 库级可写键（顺序即前端展示顺序；只读集合防调用方误改） */
    public static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            VEC_THRESHOLD, VECTOR_WEIGHT, KEYWORD_WEIGHT, RERANK_MODEL, RERANK_MIN_HITS, RERANK_MAX_HITS)));

    /** 已迁出库级的键：读取忽略、保存清除 */
    public static final Set<String> RETIRED = Set.of(
            "retrieval.vectorTopK", "retrieval.keywordLimit", "rerank.enabled", "rerank.baseUrl");

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

    /** 是否为“已迁出库级”的键（供写入侧区分“退役”与“非法”，日志口径不同） */
    public static boolean isRetired(String key) {
        return key != null && RETIRED.contains(key);
    }
}
