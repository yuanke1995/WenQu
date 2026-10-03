package com.wisesoft.ai.service;

import com.wisesoft.ai.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 使用统计（个人用量）：以助手消息的 tokens JSON（$.total）与 model 字段为数据源，
 * 按登录用户聚合出统计卡（累计/峰值 Token、最长聊天时长、连续天数）、Token 活动热力图、
 * 每日×模型趋势与模型用量占比。
 * <p>
 * 设计口径：
 * <ul>
 *   <li>统计卡为<b>全时段</b>口径；时间范围（近7日/近30日）只影响趋势图与模型用量；</li>
 *   <li>峰值 = 单日 token 总量的历史最大值；连续天数按「有助手回答的日期」计；</li>
 *   <li>热力图返回近 365 天的稀疏日清单（无 token 的日期不返回），每日/每周/累计三种视图由前端派生；</li>
 *   <li>存量消息 model 为 NULL（模型字段上线前），趋势/占比中归入「未知」。</li>
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

    /** 空结果（未登录匿名身份无可归属的个人用量，不展示匿名兼容池的数据） */
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
        List<Map<String, Object>> daily = messageMapper.statDailyTokens(userId, null);
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

        // ---- 连续天数（活跃日 = 当日有助手回答） ----
        Set<LocalDate> activeDays = messageMapper.statActiveDates(userId).stream()
                .map(LocalDate::parse)
                .collect(Collectors.toCollection(TreeSet::new));
        int longestStreak = longestStreak(activeDays);
        int currentStreak = currentStreak(activeDays, today);

        // ---- 时间范围窗口：每日×模型 → 趋势序列 + 模型占比 ----
        List<Map<String, Object>> modelDaily =
                messageMapper.statModelDaily(userId, today.minusDays(r - 1L).atStartOfDay());
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
                line.put("values", days.stream().map(d -> e.getValue().getOrDefault(d, 0L)).toList());
                series.add(line);
            } else {
                e.getValue().forEach((d, v) -> other.merge(d, v, Long::sum));
            }
        }
        if (!other.isEmpty()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("model", OTHER_LABEL);
            line.put("values", days.stream().map(d -> other.getOrDefault(d, 0L)).toList());
            series.add(line);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("days", dayKeys);
        trend.put("series", series);

        // 模型用量占比：与趋势同窗口（近 N 日总量降序；含「其他」与「未知」桶）
        List<Map<String, Object>> models = byModel.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", e.getKey());
                    m.put("tokens", e.getValue().values().stream().mapToLong(Long::longValue).sum());
                    return m;
                })
                .sorted(Comparator.<Map<String, Object>>comparingLong(
                        m -> ((Number) m.get("tokens")).longValue()).reversed())
                .toList();

        Long longestSeconds = messageMapper.statLongestSessionSeconds(userId);
        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("totalTokens", totalTokens);
        cards.put("peakDayTokens", peakDay);
        cards.put("longestChatSeconds", longestSeconds == null ? 0L : longestSeconds);
        cards.put("currentStreakDays", currentStreak);
        cards.put("longestStreakDays", longestStreak);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        out.put("heatmap", heatmap);
        out.put("trend", trend);
        out.put("models", models);
        return out;
    }

    /** 是否还有「具名模型」名额未用完（决定当前模型是合并进其他还是单独成线） */
    private boolean hasNamedModelLeft(List<Map.Entry<String, Map<LocalDate, Long>>> sorted,
                                      List<Map<String, Object>> series) {
        return sorted.stream().anyMatch(s -> !OTHER_LABEL.equals(s.getKey())
                && series.stream().noneMatch(l -> s.getKey().equals(l.get("model"))));
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
}
