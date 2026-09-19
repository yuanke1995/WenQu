package com.wisesoft.wenqu.common;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 5 段 cron 表达式解析与"下一次触发时间"计算。
 *
 * <p>载体：参考实现 {@code services/scheduled_agent_service.py} 依赖第三方库 {@code croniter}
 * （{@code croniter.is_valid} / {@code croniter(expr, start).get_next(datetime)}）。本工程不引
 * 第三方依赖，故按其**文档语义**自实现一个等价的 5 段实现。
 *
 * <h3>已对齐的语义</h3>
 * <ul>
 *   <li>字段顺序：{@code 分 时 日 月 周}；仅接受 5 段（多于/少于 5 段一律无效，与参考实现
 *       {@code len(expression.split()) != 5} 的判定一致）。</li>
 *   <li>字段语法：{@code *}、单值、{@code a-b} 区间、{@code /n} 步长（{@code *}{@code /n}、
 *       {@code a-b/n}、{@code a/n}）、逗号列表；月份与星期支持三字母英文名（大小写不敏感）。</li>
 *   <li>星期取值：{@code 0} = 周日 … {@code 6} = 周六，{@code 7} 亦为周日（croniter 与 Vixie cron
 *       同口径）。</li>
 *   <li>周与日同时受限时取"或"（Vixie cron 语义，croniter 文档明示）。</li>
 *   <li>搜索上界：{@code _max_years_between_matches} 的 50 年口径——超出即视为"没有可计算的
 *       下一次触发时间"（对应参考实现抛 {@code CroniterBadDateError} 后的 422 分支）。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li><b>不支持的扩展语法</b>：{@code L}（月末）、{@code W}（最近工作日）、{@code #}（第 n 个星期几）、
 *       {@code ?}、6 段（含秒）表达式、{@code @daily} 之类的别名宏。这些在 croniter 里属于扩展能力，
 *       本实现解析失败即判为无效表达式（与参考实现"必须是 5 段标准 cron"的校验方向一致，
 *       但拒绝范围略宽——例如 croniter 可能接受 {@code 0 0 L * *}，本实现拒绝）。</li>
 *   <li><b>夏令时</b>：本实现在目标时区的本地时间上推进，跨夏令时按 {@code ZonedDateTime} 的
 *       本地时间解析规则归一（春季跳过的本地时刻前移、秋季重复时刻取首次出现）；
 *       croniter 对 DST 有更细的补偿规则。参考实现侧同样的边界行为也依赖运行时时区数据库，
 *       故此处按"不崩、结果仍是该时区的合法本地时刻"为准则。</li>
 * </ul>
 */
public final class CronSchedule {

    /** 搜索上界（年），对齐 croniter 的 {@code _max_years_between_matches} 默认值。 */
    private static final int MAX_YEARS_BETWEEN_MATCHES = 50;

    /** 循环次数保护上界（远大于 50 年所需的天/时分推进次数）。 */
    private static final int MAX_ITERATIONS = 200_000;

    private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final Map<String, Integer> DOW_NAMES = Map.of(
            "sun", 0, "mon", 1, "tue", 2, "wed", 3, "thu", 4, "fri", 5, "sat", 6);

    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronSchedule(
            Set<Integer> minutes,
            Set<Integer> hours,
            Set<Integer> daysOfMonth,
            Set<Integer> months,
            Set<Integer> daysOfWeek,
            boolean dayOfMonthRestricted,
            boolean dayOfWeekRestricted) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
    }

    /** croniter 的 {@code is_valid}：5 段且每段可解析。 */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /** 解析表达式；不合法时抛 {@link IllegalArgumentException}。 */
    public static CronSchedule parse(String expression) {
        String normalized = expression == null ? "" : expression.strip();
        String[] fields = normalized.split("\\s+");
        if (fields.length != 5 || normalized.isEmpty()) {
            throw new IllegalArgumentException("cron_expression 不是有效的 5 段 cron 表达式");
        }
        String domToken = fields[2];
        String dowToken = fields[4];
        return new CronSchedule(
                parseField(fields[0], 0, 59, null),
                parseField(fields[1], 0, 23, null),
                parseField(domToken, 1, 31, null),
                parseField(fields[3], 1, 12, MONTH_NAMES),
                parseDayOfWeek(dowToken),
                !"*".equals(domToken.trim()),
                !"*".equals(dowToken.trim()));
    }

    /**
     * 计算严格晚于 {@code after} 的下一次触发时刻（在 {@code after} 自带时区的本地时间上推进）。
     *
     * @throws IllegalStateException 50 年内没有匹配时刻（对应参考实现 {@code CroniterBadDateError}）
     */
    public ZonedDateTime nextAfter(ZonedDateTime after) {
        // 参考实现：croniter(expr, local_after).get_next(datetime) 严格晚于 local_after
        ZonedDateTime candidate = after.plusMinutes(1).withSecond(0).withNano(0);
        LocalDate limit = candidate.toLocalDate().plusYears(MAX_YEARS_BETWEEN_MATCHES);
        int guard = 0;
        while (true) {
            if (++guard > MAX_ITERATIONS || candidate.toLocalDate().isAfter(limit)) {
                throw new IllegalStateException("cron_expression 没有可计算的下一次触发时间");
            }
            if (!months.contains(candidate.getMonthValue())) {
                candidate = startOfNextMonth(candidate);
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = startOfNextDay(candidate);
                continue;
            }
            if (!hours.contains(candidate.getHour())) {
                candidate = startOfNextHour(candidate);
                continue;
            }
            if (!minutes.contains(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return candidate;
        }
    }

    /** 便捷入口：按 IANA 时区名与"UTC 无时区时刻"起点计算下一次触发（返回 UTC 无时区时刻）。 */
    public static java.time.LocalDateTime nextRunAtUtc(String expression, String timezone, java.time.LocalDateTime afterUtc) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime localAfter = afterUtc.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone);
        ZonedDateTime nextLocal = parse(expression).nextAfter(localAfter);
        return nextLocal.withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    // ==================== 内部 ====================

    private boolean dayMatches(ZonedDateTime moment) {
        boolean domMatch = daysOfMonth.contains(moment.getDayOfMonth());
        int cronDow = moment.getDayOfWeek().getValue() % 7; // ISO 周一=1…周日=7 → cron 周日=0
        boolean dowMatch = daysOfWeek.contains(cronDow);
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            // Vixie cron：两者同时受限时取"或"
            return domMatch || dowMatch;
        }
        if (dayOfMonthRestricted) {
            return domMatch;
        }
        if (dayOfWeekRestricted) {
            return dowMatch;
        }
        return true;
    }

    private static ZonedDateTime startOfNextMonth(ZonedDateTime moment) {
        return moment.withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .plusMonths(1);
    }

    private static ZonedDateTime startOfNextDay(ZonedDateTime moment) {
        return moment.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    private static ZonedDateTime startOfNextHour(ZonedDateTime moment) {
        return moment.withMinute(0).withSecond(0).withNano(0).plusHours(1);
    }

    /** 星期字段：0-7，7 归一到 0（周日）。 */
    private static Set<Integer> parseDayOfWeek(String token) {
        Set<Integer> values = parseField(token, 0, 7, DOW_NAMES);
        Set<Integer> normalized = new TreeSet<>();
        for (Integer value : values) {
            normalized.add(value % 7);
        }
        return normalized;
    }

    /**
     * 解析单个字段：逗号列表，每项为 {@code *}、步长形式（{@code *}{@code /n}）、单值 {@code a}、
     * 区间 {@code a-b}、起点步长 {@code a/n} 或区间步长 {@code a-b/n}。
     */
    private static Set<Integer> parseField(String token, int min, int max, Map<String, Integer> names) {
        String raw = token == null ? "" : token.strip();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("cron 字段为空");
        }
        Set<Integer> values = new TreeSet<>();
        for (String part : raw.split(",", -1)) {
            String item = part.strip();
            if (item.isEmpty()) {
                throw new IllegalArgumentException("cron 字段含空项");
            }
            String base = item;
            int step = 1;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                base = item.substring(0, slash).strip();
                String stepText = item.substring(slash + 1).strip();
                try {
                    step = Integer.parseInt(stepText);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("cron 步长非法: " + item);
                }
                if (step <= 0) {
                    throw new IllegalArgumentException("cron 步长必须为正: " + item);
                }
            }
            if ("*".equals(base) || base.isEmpty()) {
                for (int value = min; value <= max; value += step) {
                    values.add(value);
                }
                continue;
            }
            int dash = base.indexOf('-');
            if (dash >= 0) {
                int start = parseValue(base.substring(0, dash).strip(), min, max, names);
                int end = parseValue(base.substring(dash + 1).strip(), min, max, names);
                if (start > end) {
                    throw new IllegalArgumentException("cron 区间起点大于终点: " + item);
                }
                for (int value = start; value <= end; value += step) {
                    values.add(value);
                }
                continue;
            }
            int single = parseValue(base, min, max, names);
            if (step == 1) {
                values.add(single);
            } else {
                for (int value = single; value <= max; value += step) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cron 字段没有有效取值: " + token);
        }
        return new LinkedHashSet<>(values);
    }

    private static int parseValue(String text, int min, int max, Map<String, Integer> names) {
        if (names != null) {
            Integer named = names.get(text.toLowerCase(java.util.Locale.ROOT));
            if (named != null) {
                return named;
            }
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("cron 字段取值非法: " + text);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("cron 字段取值越界: " + text);
        }
        return value;
    }
}
