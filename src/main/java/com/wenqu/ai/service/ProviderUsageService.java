package com.wenqu.ai.service;

import com.wenqu.ai.mapper.UsageLogMapper;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.model.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商维度的用量与跨供应商矩阵（模型供应商页二期）。
 *
 * <h3>为什么单独一个服务</h3>
 * 「近 7 天调用/预估费用」与「模型矩阵」都要拿台账 × 登记单价现算，与费用报表
 * （{@link UsageStatsService}）同一口径：单价随改随算、不落快照，历史费用不跟着
 * 现在的价格漂移。两处共用这一份聚合，避免列表与矩阵两个界面各算各的、
 * 出现「卡片说 ¥3.4、矩阵说 ¥3.5」这种对不上账的尴尬。
 *
 * <h3>费用只算有价部分</h3>
 * 未登记单价的模型照常计入调用数与 token，但费用记 null（前端显示「未计价」）——
 * 与费用报表的 unpricedTokens 同一语义：宁可缺一块，不把「没配价」读成「免费」。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderUsageService {

    /** 列表卡片与矩阵的用量窗口（天） */
    public static final int WINDOW_DAYS = 7;

    private final UsageLogMapper usageLogMapper;
    private final ModelRegistryService registry;

    /** ref（providerId/modelId）→ 近 7 天 {calls, prompt, completion}；一次查询全供应商共用 */
    private record RefUsage(long calls, long prompt, long completion) {
    }

    /**
     * 近 7 天按模型引用的用量（台账不可读时返回空表：用量列显示为空，而不是整页报错——
     * 供应商管理不依赖统计侧）。
     */
    private Map<String, RefUsage> usageByRef() {
        Map<String, RefUsage> out = new HashMap<>();
        try {
            for (Map<String, Object> row : usageLogMapper.statModelUsageSince(
                    LocalDateTime.now().minusDays(WINDOW_DAYS))) {
                String model = row.get("model") == null ? "" : String.valueOf(row.get("model"));
                if (model.isBlank()) continue;
                out.put(model, new RefUsage(num(row.get("calls")), num(row.get("p")), num(row.get("c"))));
            }
        } catch (Exception e) {
            log.warn("[ProviderUsage] 台账聚合失败（用量列按空处理）: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 给供应商列表行追加 {@code usage7d:{calls, cost}}（cost=null=全部未计价）。
     * 按行的 providerId 归组其下模型引用，单价取登记表现值。
     */
    public void enrich(List<Map<String, Object>> rows) {
        Map<String, RefUsage> usage = usageByRef();
        Map<String, ModelInfo> priceByRef = priceByRef();
        for (Map<String, Object> row : rows) {
            String pid = String.valueOf(row.get("id"));
            long calls = 0;
            double cost = 0;
            boolean anyPriced = false;
            for (Map.Entry<String, ModelInfo> e : priceByRef.entrySet()) {
                if (!e.getKey().startsWith(pid + "/")) continue;
                RefUsage u = usage.get(e.getKey());
                if (u == null) continue;
                calls += u.calls();
                cost += costOf(u, e.getValue());
                anyPriced = true;
            }
            // 有调用但一家价都没配：cost=null（未计价），而不是 0——0 会被读成「没花钱」
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls);
            m.put("cost", anyPriced ? round2(cost) : null);
            row.put("usage7d", m);
        }
    }

    /**
     * 模型矩阵：同一裸模型名跨供应商的同源登记分组（请求者可见范围内）。
     * 返回按「同源家数由多到少」排序的分组清单，每组带逐家对比行。
     */
    public List<Map<String, Object>> matrix(String uid, String role) {
        Map<String, RefUsage> usage = usageByRef();
        Map<String, ModelInfo> priceByRef = priceByRef();
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Provider p : registry.visibleProviders(uid, role)) {
            for (ModelInfo mi : registry.modelsOf(p.getId())) {
                String ref = p.getId() + "/" + mi.getModelId();
                RefUsage u = usage.get(ref);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("providerId", p.getId());
                entry.put("providerName", p.getName());
                entry.put("ref", ref);
                entry.put("modelType", mi.getModelType());
                entry.put("displayName", mi.getDisplayName());
                entry.put("inputPrice", mi.getInputPrice());
                entry.put("outputPrice", mi.getOutputPrice());
                entry.put("contextWindow", mi.getContextWindow());
                entry.put("modelEnabled", !Integer.valueOf(0).equals(mi.getEnabled()));
                entry.put("providerEnabled", !Integer.valueOf(0).equals(p.getEnabled()));
                entry.put("quotaBlocked", ModelQuotaService.isBlocked(p));
                entry.put("verifyStatus", p.getVerifyStatus());
                entry.put("lastVerifyMs", p.getLastVerifyMs());
                entry.put("calls7d", u == null ? 0 : u.calls());
                entry.put("cost7d", u == null ? null : round2(costOf(u, mi)));
                groups.computeIfAbsent(mi.getModelId(), k -> new ArrayList<>()).add(entry);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("modelId", e.getKey());
            g.put("entries", e.getValue());
            out.add(g);
        }
        // 同源家数多的在前（矩阵的主价值是比价与备份缺口），同数按模型名
        out.sort(Comparator
                .comparingInt((Map<String, Object> g) -> ((List<?>) g.get("entries")).size()).reversed()
                .thenComparing(g -> String.valueOf(g.get("modelId"))));
        return out;
    }

    // ==================== 内部 ====================

    /** ref → 登记行（单价现值的读取源） */
    private Map<String, ModelInfo> priceByRef() {
        Map<String, ModelInfo> out = new HashMap<>();
        for (Provider p : registry.allProvidersForUsage()) {
            for (ModelInfo mi : registry.modelsOf(p.getId())) {
                out.put(p.getId() + "/" + mi.getModelId(), mi);
            }
        }
        return out;
    }

    /** 单笔费用（元）：输入×输入价 + 输出×输出价，除以百万；两价都未配置返回 0（调用方按 anyPriced 区分） */
    private static double costOf(RefUsage u, ModelInfo mi) {
        double in = mi.getInputPrice() == null ? 0 : mi.getInputPrice().doubleValue();
        double outp = mi.getOutputPrice() == null ? 0 : mi.getOutputPrice().doubleValue();
        return u.prompt() / 1_000_000.0 * in + u.completion() / 1_000_000.0 * outp;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double round2(double v) {
        return new BigDecimal(String.valueOf(v)).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
