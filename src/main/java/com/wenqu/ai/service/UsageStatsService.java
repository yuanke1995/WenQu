package com.wenqu.ai.service;

import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.ModelInfoMapper;
import com.wenqu.ai.mapper.UsageLogMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 使用统计（个人用量）：以推理用量台账 <b>c_ai_usage_log</b> 为唯一数据源，
 * 按登录用户聚合出统计卡（累计/峰值 Token、最长聊天时长、连续天数）、Token 活动热力图、
 * 每日×模型趋势与模型用量占比。
 * <p>
 * 数据源在变动：此前直接读助手消息的 tokens JSON，只覆盖主链路最后那一轮用量，与供应商
 * 账单差数倍（工具调用循环每轮都是真实请求，记忆提取/智能体分派等旁路调用同样烧 token）。
 * 现在所有推理请求在 {@link DynamicOpenAiChatModel} 出口统一记账，这里只读台账——
 * 于是「页面看到的数字 = 供应商账单里属于你的部分」。
 * <p>
 * 设计口径：
 * <ul>
 *   <li>统计卡为<b>全时段</b>口径；时间范围（近7日/近30日）只影响趋势图与模型用量；</li>
 *   <li>峰值 = 单日 token 总量的历史最大值；连续天数按「当天有用量」计；</li>
 *   <li>热力图返回近 365 天的稀疏日清单（无 token 的日期不返回），每日/每周/累计三种视图由前端派生；</li>
 *   <li>台账无模型（模型字段上线前回补的历史行）归入「未记录」桶。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageStatsService {

    /** 趋势图/占比里单独成线的最大模型数，超出者合并为「其他」 */
    private static final int MAX_MODELS = 6;
    private static final String OTHER_LABEL = "其他";
    /** 模型字段上线前的存量消息无模型记录，聚合时归入该桶（非异常数据） */
    private static final String UNKNOWN_LABEL = "未记录";
    /** 热力图窗口：近 365 天（含今天） */
    private static final int HEATMAP_DAYS = 365;

    private final MessageMapper messageMapper;
    private final UsageLogMapper usageLogMapper;
    private final ModelInfoMapper modelInfoMapper;
    private final UserMapper userMapper;

    /** 空结果（未登录匿名身份无可归属的个人用量，不展示匿名兼容池数据） */
    public static Map<String, Object> empty() {
        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("totalTokens", 0L);
        cards.put("peakDayTokens", 0L);
        cards.put("longestChatSeconds", 0L);
        cards.put("currentStreakDays", 0);
        cards.put("longestStreakDays", 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        out.put("heatmap", List.of());
        out.put("trend", Map.of("days", List.of(), "series", List.of()));
        out.put("models", List.of());
        return out;
    }

    /**
     * 个人用量统计。
     *
     * @param userId 登录用户 uid
     * @param range  时间范围（天）：7 或 30，其他值归一为 30
     */
    public Map<String, Object> usage(String userId, int range) {
        int r = range == 7 ? 7 : 30;
        LocalDate today = LocalDate.now();

        // ---- 全量日清单：一次扫描派生 累计/峰值/热力图 三个指标 ----
        List<Map<String, Object>> daily = usageLogMapper.statDailyTokens(userId, null);
        LocalDate heatStart = today.minusDays(HEATMAP_DAYS - 1L);
        List<Map<String, Object>> heatmap = new ArrayList<>();
        long totalTokens = 0;
        long peakDay = 0;
        for (Map<String, Object> row : daily) {
            long t = num(row.get("t"));
            long c = num(row.get("c"));
            totalTokens += t;
            peakDay = Math.max(peakDay, t);
            LocalDate d = LocalDate.parse(String.valueOf(row.get("d")));
            if (!d.isBefore(heatStart)) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("date", String.valueOf(row.get("d")));
                cell.put("tokens", t);
                cell.put("count", c);
                heatmap.add(cell);
            }
        }

        // ---- 连续天数（活跃日 = 当天有推理用量） ----
        Set<LocalDate> activeDays = usageLogMapper.statActiveDates(userId).stream()
                .map(LocalDate::parse)
                .collect(Collectors.toCollection(TreeSet::new));
        int longestStreak = longestStreak(activeDays);
        int currentStreak = currentStreak(activeDays, today);

        // ---- 时间范围窗口：每日×模型 → 趋势序列 + 模型占比 ----
        List<Map<String, Object>> modelDaily =
                usageLogMapper.statModelDaily(userId, today.minusDays(r - 1L).atStartOfDay());
        List<LocalDate> days = new ArrayList<>();
        for (int i = r - 1; i >= 0; i--) days.add(today.minusDays(i));
        List<String> dayKeys = days.stream().map(LocalDate::toString).toList();
        Map<String, Map<LocalDate, Long>> byModel = new LinkedHashMap<>();
        for (Map<String, Object> row : modelDaily) {
            String model = row.get("model") == null ? UNKNOWN_LABEL : String.valueOf(row.get("model"));
            LocalDate d = LocalDate.parse(String.valueOf(row.get("d")));
            byModel.computeIfAbsent(model, k -> new LinkedHashMap<>())
                    .merge(d, num(row.get("t")), Long::sum);
        }
        // 模型按区间总量降序；单独成线的最多 MAX_MODELS 个，超出者（与既有「其他」行）合并为「其他」排最后
        Map<String, Map<LocalDate, Long>> named = new LinkedHashMap<>();
        Map<LocalDate, Long> other = new LinkedHashMap<>();
        for (Map.Entry<String, Map<LocalDate, Long>> e : byModel.entrySet()) {
            if (OTHER_LABEL.equals(e.getKey())) {
                e.getValue().forEach((d, v) -> other.merge(d, v, Long::sum));
            } else {
                named.put(e.getKey(), e.getValue());
            }
        }
        List<Map.Entry<String, Map<LocalDate, Long>>> ranked = named.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Map<LocalDate, Long>> e) ->
                        e.getValue().values().stream().mapToLong(Long::longValue).sum()).reversed())
                .toList();
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Map.Entry<String, Map<LocalDate, Long>> e = ranked.get(i);
            if (i < MAX_MODELS) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("model", e.getKey());
                line.put("label", labelOf(e.getKey()));
                line.put("values", days.stream().map(d -> e.getValue().getOrDefault(d, 0L)).toList());
                series.add(line);
            } else {
                e.getValue().forEach((d, v) -> other.merge(d, v, Long::sum));
            }
        }
        if (!other.isEmpty()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("model", OTHER_LABEL);
            line.put("label", OTHER_LABEL);
            line.put("values", days.stream().map(d -> other.getOrDefault(d, 0L)).toList());
            series.add(line);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("days", dayKeys);
        trend.put("series", series);

        // 模型用量占比：与趋势同窗口（近 N 日总量降序；含「其他」与「未记录」桶）
        List<Map<String, Object>> models = byModel.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", e.getKey());
                    m.put("label", labelOf(e.getKey()));
                    m.put("tokens", e.getValue().values().stream().mapToLong(Long::longValue).sum());
                    return m;
                })
                .sorted(Comparator.<Map<String, Object>>comparingLong(
                        m -> ((Number) m.get("tokens")).longValue()).reversed())
                .toList();

        Long longestSeconds = messageMapper.statLongestSessionSeconds(userId);

        // ---- 费用（§13 Token 成本报表）：台账 tokens × 模型登记单价，Java 侧换算 ----
        // 全时段费用（统计卡口径）+ 区间内按模型费用（模型列表同窗口）；未登记单价的模型
        // 不计入费用，其 token 数单列（unpricedTokens）让「费用数字不完整」可见，而不是静默少算。
        Map<String, BigDecimal[]> prices = priceMap();
        List<Map<String, Object>> allCost = usageLogMapper.statCostDetail(userId, null);
        double totalCost = 0;
        long totalUnpriced = 0;
        for (Map<String, Object> row : allCost) {
            BigDecimal[] p = prices.get(String.valueOf(row.get("model")));
            long t = num(row.get("t"));
            if (p == null) { totalUnpriced += t; continue; }
            totalCost += num(row.get("p")) / 1_000_000.0 * p[0].doubleValue()
                    + num(row.get("c")) / 1_000_000.0 * p[1].doubleValue();
        }
        Map<String, Object> rangeCostByModel = rangeCostByModel(userId, today.minusDays(r - 1L).atStartOfDay(), prices);
        for (Map<String, Object> m : models) {
            Object cost = rangeCostByModel.get(m.get("model"));
            m.put("cost", cost);   // null=该模型未登记单价（前端显示「未计价」）
        }

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("totalTokens", totalTokens);
        cards.put("peakDayTokens", peakDay);
        cards.put("longestChatSeconds", longestSeconds == null ? 0L : longestSeconds);
        cards.put("currentStreakDays", currentStreak);
        cards.put("longestStreakDays", longestStreak);
        cards.put("totalCost", round2(totalCost));
        cards.put("unpricedTokens", totalUnpriced);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        out.put("heatmap", heatmap);
        out.put("trend", trend);
        out.put("models", models);
        return out;
    }

    /**
     * 管理侧费用报表（§13）：全员台账 × 模型登记单价，按用户 / 按模型两个维度聚合。
     * 单价未配置的模型照常出现在维度里但费用记 null（token 数照实），费用合计只含有价部分——
     * 未计价 token 总量单列，避免「总费用」被误读成全部消耗的真实成本。
     *
     * @param range 时间范围（天）：7 或 30，其他值归一为 30
     */
    public Map<String, Object> adminCost(int range) {
        int r = range == 7 ? 7 : 30;
        LocalDate today = LocalDate.now();
        Map<String, BigDecimal[]> prices = priceMap();
        List<Map<String, Object>> rows = usageLogMapper.statCostDetail(null, today.minusDays(r - 1L).atStartOfDay());

        double total = 0;
        long unpricedTokens = 0;
        // uid -> {tokens, cost}；model -> {tokens, cost, priced}
        Map<String, long[]> userTok = new LinkedHashMap<>();
        Map<String, double[]> userCost = new HashMap<>();
        Map<String, long[]> modelTok = new LinkedHashMap<>();
        Map<String, double[]> modelCost = new HashMap<>();
        Set<String> unpricedModels = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            String model = row.get("model") == null ? UNKNOWN_LABEL : String.valueOf(row.get("model"));
            String uid = row.get("uid") == null ? "" : String.valueOf(row.get("uid"));
            long p = num(row.get("p")), c = num(row.get("c")), t = num(row.get("t"));
            BigDecimal[] price = prices.get(model);
            Double cost = null;
            if (price != null) {
                cost = p / 1_000_000.0 * price[0].doubleValue() + c / 1_000_000.0 * price[1].doubleValue();
                total += cost;
            } else {
                unpricedTokens += t;
                unpricedModels.add(model);
            }
            userTok.computeIfAbsent(uid, k -> new long[2])[0] += p;
            userTok.get(uid)[1] += c;
            if (cost != null) userCost.merge(uid, new double[]{cost}, (a, b) -> new double[]{a[0] + b[0]});
            modelTok.computeIfAbsent(model, k -> new long[2])[0] += p;
            modelTok.get(model)[1] += c;
            if (cost != null) modelCost.merge(model, new double[]{cost}, (a, b) -> new double[]{a[0] + b[0]});
        }

        // 用户名回填（看板展示 uid 没有人味；量级=成员数，一次全量可接受）
        Map<String, String> names = new HashMap<>();
        try {
            for (User u : userMapper.selectList(null)) names.put(u.getUid(), u.getUsername());
        } catch (Exception e) {
            log.warn("[Cost] 用户名回填失败，按 uid 展示: {}", e.getMessage());
        }
        List<Map<String, Object>> byUser = userTok.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    double[] cost = userCost.get(e.getKey());
                    m.put("uid", e.getKey());
                    m.put("name", names.getOrDefault(e.getKey(), e.getKey()));
                    m.put("promptTokens", e.getValue()[0]);
                    m.put("completionTokens", e.getValue()[1]);
                    m.put("tokens", e.getValue()[0] + e.getValue()[1]);
                    m.put("cost", cost == null ? null : round2(cost[0]));
                    return m;
                })
                .sorted(Comparator.comparingDouble((Map<String, Object> m) ->
                        m.get("cost") == null ? -1 : ((Number) m.get("cost")).doubleValue()).reversed())
                .toList();
        List<Map<String, Object>> byModel = modelTok.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    double[] cost = modelCost.get(e.getKey());
                    m.put("model", e.getKey());
                    m.put("label", labelOf(e.getKey()));
                    m.put("promptTokens", e.getValue()[0]);
                    m.put("completionTokens", e.getValue()[1]);
                    m.put("tokens", e.getValue()[0] + e.getValue()[1]);
                    m.put("cost", cost == null ? null : round2(cost[0]));
                    m.put("priced", cost != null);
                    return m;
                })
                .sorted(Comparator.comparingDouble((Map<String, Object> m) ->
                        m.get("cost") == null ? -1 : ((Number) m.get("cost")).doubleValue()).reversed())
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r);
        out.put("total", round2(total));
        out.put("unpricedTokens", unpricedTokens);
        out.put("unpricedModels", List.copyOf(unpricedModels));
        out.put("byUser", byUser);
        out.put("byModel", byModel);
        return out;
    }

    /** 模型引用 → [输入单价, 输出单价]（元/百万 tokens）；ref 规则与台账 model 列一致（providerId/modelId） */
    private Map<String, BigDecimal[]> priceMap() {
        Map<String, BigDecimal[]> out = new HashMap<>();
        try {
            for (ModelInfo mi : modelInfoMapper.selectList(null)) {
                if (mi.getInputPrice() == null && mi.getOutputPrice() == null) continue;
                out.put(mi.getProviderId() + "/" + mi.getModelId(),
                        new BigDecimal[]{mi.getInputPrice() == null ? BigDecimal.ZERO : mi.getInputPrice(),
                                         mi.getOutputPrice() == null ? BigDecimal.ZERO : mi.getOutputPrice()});
            }
        } catch (Exception e) {
            log.warn("[Cost] 模型单价读取失败，本轮费用按未计价处理: {}", e.getMessage());
        }
        return out;
    }

    /** 区间内按模型的费用：model → 费用（元，2 位小数）；未登记单价的模型不在返回里 */
    private Map<String, Object> rangeCostByModel(String userId, LocalDateTime since, Map<String, BigDecimal[]> prices) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : usageLogMapper.statCostDetail(userId, since)) {
            String model = row.get("model") == null ? UNKNOWN_LABEL : String.valueOf(row.get("model"));
            BigDecimal[] p = prices.get(model);
            if (p == null) continue;
            double cost = num(row.get("p")) / 1_000_000.0 * p[0].doubleValue()
                    + num(row.get("c")) / 1_000_000.0 * p[1].doubleValue();
            if (out.containsKey(model)) {
                cost += ((Number) out.get(model)).doubleValue();
            }
            out.put(model, round2(cost));
        }
        return out;
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 最长连续天数：排序后相邻日期差 1 天即延续 */
    private int longestStreak(Set<LocalDate> days) {
        int best = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : days) {
            run = (prev != null && prev.plusDays(1).equals(d)) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        return best;
    }

    /**
     * 当前连续天数：从今天起回数；今天尚无活跃则从昨天起回数（今天的连续性还没被打破，GitHub 口径）。
     */
    private int currentStreak(Set<LocalDate> days, LocalDate today) {
        if (!days.contains(today) && !days.contains(today.minusDays(1))) return 0;
        int run = 0;
        LocalDate cur = days.contains(today) ? today : today.minusDays(1);
        while (days.contains(cur)) {
            run++;
            cur = cur.minusDays(1);
        }
        return run;
    }

    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 模型引用的展示名：`providerId/modelId` 取「/」后的模型名（不展示供应商 UUID）；
     * 遗留纯模型名原样。聚合 key 仍为完整引用——不同供应商的同名模型是不同配置，tokens 分开统计。
     */
    private String labelOf(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) return UNKNOWN_LABEL;
        return modelRef.contains("/") ? modelRef.substring(modelRef.lastIndexOf('/') + 1) : modelRef;
    }
}
