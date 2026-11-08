package com.wenqu.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索相关度门（单一实现）：主检索工具、工作流检索节点、子智能体咨询来源、联网搜索
 * 此前各写一遍过滤流，口径一漂就会出现「同一条块在工具里被门掉、在引用面板里却保留」。
 *
 * <p>两档分域是既有语义，不能合并：重排分走 {@code retrieval.minContextScore}，
 * 未重排（关闭/失败回退）的融合分走 {@code retrieval.minFusionScore}——两个分值域分布不同。
 * 重排已实际执行时，未拿到重排分的尾部候选不得借融合门入场（单侧约束）。
 *
 * <p>低分保底（{@code retrieval.lowConfidencePolicy=top1}，默认）解决的是「隐性问答」被
 * 绝对门全杀：问题问的是块里的隐性信息（如「引用的诗词是什么」），重排只给部分匹配分
 * （实测 0.192），绝对门 0.6 会把唯一正确块也丢掉。保底只在**全灭**时启用，且只留 1 条，
 * 由调用方显形「低置信」——不按比例放开一批，避免把噪声一起带进引用面板。
 *
 * <p>重排没跑成（rerankActive=false）时不保底：那说明重排链路异常，不能让「重排坏了」
 * 演成「什么都搜到」，继续按融合门处理。
 *
 * @author yuanke
 */
public final class RetrievalGate {

    /** kept=实际保留；dropped=被门掉的数量；lowConfidence=是否走了 top-1 保底 */
    public record Result(List<HybridRetrievalService.Hit> kept, int dropped, boolean lowConfidence) {
    }

    private RetrievalGate() {
    }

    /**
     * @param hits         候选（重排执行过时已按重排分排序；未排序也安全，best 按分值取）
     * @param rerankGate   重排分门（≤0=该域关门）
     * @param fusionGate   融合分门（≤0=该域关门）
     * @param rerankActive 本轮是否实际拿到重排分
     * @param policy       低分策略：top1=全灭时保底 1 条；off/空=不保底
     * @param top1Floor    保底最低分（低于它视为没有相关内容）
     */
    public static Result apply(List<HybridRetrievalService.Hit> hits, double rerankGate, double fusionGate,
                               boolean rerankActive, String policy, double top1Floor) {
        if (hits == null || hits.isEmpty()) return new Result(List.of(), 0, false);

        List<HybridRetrievalService.Hit> passed = new ArrayList<>(hits.size());
        for (HybridRetrievalService.Hit h : hits) {
            if (rerankActive && h.rerankScore() == null) continue;
            double score = h.rerankScore() != null ? h.rerankScore() : h.score();
            double gate = h.rerankScore() != null ? rerankGate : fusionGate;
            if (gate <= 0 || score >= gate) passed.add(h);
        }
        if (!passed.isEmpty()) return new Result(passed, hits.size() - passed.size(), false);

        if (rerankActive && top1Enabled(policy)) {
            HybridRetrievalService.Hit best = null;
            for (HybridRetrievalService.Hit h : hits) {
                if (h.rerankScore() == null) continue;
                if (best == null || h.rerankScore() > best.rerankScore()) best = h;
            }
            if (best != null && best.rerankScore() >= Math.max(0, top1Floor)) {
                return new Result(List.of(best), hits.size() - 1, true);
            }
        }
        return new Result(List.of(), hits.size(), false);
    }

    /** 是否启用 top-1 保底（默认 top1；空/off 视为关闭） */
    public static boolean top1Enabled(String policy) {
        return policy != null && "top1".equalsIgnoreCase(policy.trim());
    }
}
