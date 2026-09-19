package com.wisesoft.wenqu.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Datetime helper utilities for consistent timezone handling.
 *
 * <p>The backend stores timestamps in UTC and exposes ISO 8601 strings with an explicit timezone
 * designator. For user-facing displays we typically convert to Asia/Shanghai.
 *
 * <p>由参考实现的 utils/datetime_utils.py 逐函数翻译：时区常量、UTC/上海转换、ISO 输出、
 * 异构时间戳解析与毫秒时长，语义与返回的字符串格式保持一致（UTC 输出以 Z 结尾）。
 */
public final class DateTimeUtils {

    /** UTC。 */
    public static final ZoneOffset UTC = ZoneOffset.UTC;

    /** Asia/Shanghai。 */
    public static final ZoneId SHANGHAI_TZ = ZoneId.of("Asia/Shanghai");

    private static final String ISO_Z_SUFFIX = "+00:00";
    private static final DateTimeFormatter BASE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private DateTimeUtils() {}

    /** Return the current UTC time as an aware datetime. */
    public static OffsetDateTime utcNow() {
        return OffsetDateTime.now(UTC);
    }

    /** Return the current UTC time as a naive datetime (for DB fields without timezone). */
    public static LocalDateTime utcNowNaive() {
        return LocalDateTime.now(UTC);
    }

    /** Return the current Asia/Shanghai time as an aware datetime. */
    public static ZonedDateTime shanghaiNow() {
        return utcNow().atZoneSameInstant(SHANGHAI_TZ);
    }

    /**
     * Convert a datetime to UTC.
     *
     * <p>与参考实现一致：带时区的值按其自身偏移换算到 UTC（Java 的 OffsetDateTime 始终携带偏移，
     * 因此无需"naive 值视为上海时间"的分支，该分支由下面的 LocalDateTime 重载承担）。
     */
    public static OffsetDateTime ensureUtc(OffsetDateTime value) {
        return value.withOffsetSameInstant(UTC);
    }

    /**
     * Convert a naive datetime to UTC, assuming Asia/Shanghai as its timezone.
     *
     * <p>Naive values are assumed to be in Asia/Shanghai.
     */
    public static OffsetDateTime ensureUtc(LocalDateTime value) {
        return value.atZone(SHANGHAI_TZ).withZoneSameInstant(UTC).toOffsetDateTime();
    }

    /**
     * Convert a datetime to Asia/Shanghai.
     *
     * <p>Naive values are assumed to be in Asia/Shanghai.
     */
    public static ZonedDateTime ensureShanghai(OffsetDateTime value) {
        return value.atZoneSameInstant(SHANGHAI_TZ);
    }

    /**
     * Convert a naive datetime to Asia/Shanghai, assuming Asia/Shanghai as its timezone.
     *
     * <p>Naive values are assumed to be in Asia/Shanghai.
     */
    public static ZonedDateTime ensureShanghai(LocalDateTime value) {
        return value.atZone(SHANGHAI_TZ);
    }

    /** Return an ISO 8601 string in UTC with a trailing Z suffix. */
    public static String utcIsoformat(OffsetDateTime value) {
        OffsetDateTime normalized = ensureUtc(value == null ? utcNow() : value);
        String isoString = normalized.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (isoString.endsWith(ISO_Z_SUFFIX)) {
            return isoString.substring(0, isoString.length() - ISO_Z_SUFFIX.length()) + "Z";
        }
        return isoString;
    }

    /** Return an ISO 8601 string in UTC with a trailing Z suffix (current time). */
    public static String utcIsoformat() {
        return utcIsoformat((OffsetDateTime) null);
    }

    /** Return an ISO 8601 string in Asia/Shanghai timezone. */
    public static String shanghaiIsoformat(OffsetDateTime value) {
        ZonedDateTime normalized = ensureShanghai(value == null ? utcNow() : value);
        return normalized.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** Return an ISO 8601 string in Asia/Shanghai timezone (current time). */
    public static String shanghaiIsoformat() {
        return shanghaiIsoformat((OffsetDateTime) null);
    }

    /** Normalize persisted datetimes to UTC, handling nulls gracefully. */
    public static OffsetDateTime coerceDatetime(OffsetDateTime value) {
        if (value == null) {
            return null;
        }
        return ensureUtc(value);
    }

    /**
     * Convert heterogeneous timestamp representations to an aware UTC datetime.
     *
     * <p>Supports aware or naive datetime objects, unix timestamps (seconds) and ISO 8601 strings.
     */
    public static OffsetDateTime coerceAnyToUtcDatetime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return ensureUtc(odt);
        }
        if (value instanceof ZonedDateTime zdt) {
            return zdt.withZoneSameInstant(UTC).toOffsetDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ensureUtc(ldt);
        }
        if (value instanceof Number number) {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(number.longValue()), UTC);
        }
        if (value instanceof String text) {
            try {
                return ensureUtc(OffsetDateTime.parse(text.replace("Z", ISO_Z_SUFFIX)));
            } catch (DateTimeParseException firstFailure) {
                try {
                    double asNumber = Double.parseDouble(text);
                    return OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) asNumber), UTC);
                } catch (NumberFormatException ignored) {
                    throw new IllegalArgumentException("Unsupported datetime string format: " + text);
                }
            }
        }
        throw new IllegalArgumentException("Unsupported datetime value: " + value);
    }

    /** Normalize each datetime in iterable to UTC. */
    public static List<OffsetDateTime> normalizeIterableToUtc(Collection<OffsetDateTime> values) {
        List<OffsetDateTime> result = new ArrayList<>();
        for (OffsetDateTime item : values) {
            if (item == null) {
                result.add(null);
            } else {
                result.add(coerceDatetime(item));
            }
        }
        return result;
    }

    /**
     * Format a datetime to UTC ISO 8601 string, handling naive datetimes.
     *
     * <p>Returns null for null input. Naive datetimes are assumed to be in UTC.
     */
    public static String formatUtcDatetime(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return utcIsoformat(value.atZone(UTC).toOffsetDateTime());
    }

    /** 把有效时间区间转换为非负毫秒。 */
    public static Long durationMs(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return null;
        }
        // 参考实现为 round((end-start).total_seconds()*1000)；epoch 毫秒之差即等价结果
        return end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli();
    }

    /** Format a Unix timestamp as an ISO 8601 UTC datetime string (参考实现 utc_isoformat_from_timestamp). */
    public static String utcIsoformatFromTimestamp(Number timestamp) {
        if (timestamp == null) {
            return null;
        }
        long seconds = timestamp.longValue();
        double fraction = timestamp.doubleValue() - seconds;
        int nanos = (int) Math.round(fraction * 1_000_000_000.0);
        Instant instant = Instant.ofEpochSecond(seconds, nanos);
        OffsetDateTime odt = instant.atOffset(ZoneOffset.UTC);
        String base = odt.toLocalDateTime().format(BASE_FORMATTER);
        if (nanos == 0) {
            return base + "+00:00";
        }
        return base + "." + String.format("%06d", nanos / 1000) + "+00:00";
    }

    /**
     * Python {@code datetime.isoformat()} 的等价输出（UTC，时区后缀为 {@code +00:00}）：
     * 秒总是输出，微秒非零时才带 6 位小数（不裁尾随零）。
     *
     * <p>必要替换：Java 的 {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME} 对 {@link ZoneOffset#UTC}
     * 输出 {@code Z}，而 Python 的 {@code isoformat()} 输出 {@code +00:00}；且 Java 会裁掉微秒的
     * 尾随零。两者差异会落到响应体（如思维导图的 {@code mindmap_metadata.generated_at}），故显式拼装。
     */
    public static String pythonIsoformatUtc(OffsetDateTime value) {
        OffsetDateTime normalized = ensureUtc(value == null ? utcNow() : value);
        LocalDateTime local = normalized.toLocalDateTime();
        int micro = local.getNano() / 1_000;
        StringBuilder builder = new StringBuilder(
                local.format(BASE_FORMATTER));
        if (micro != 0) {
            builder.append('.').append(String.format("%06d", micro));
        }
        return builder.append(ISO_Z_SUFFIX).toString();
    }

    /** {@link #pythonIsoformatUtc(OffsetDateTime)} 的当前时间入口（对应 {@code datetime.now(UTC).isoformat()}）。 */
    public static String pythonIsoformatUtc() {
        return pythonIsoformatUtc((OffsetDateTime) null);
    }

    /**
     * Python {@code datetime.isoformat()} 的等价输出（naive 本地时间，无时区后缀）：
     * 秒总是输出，微秒非零时才带小数（Java {@code LocalDateTime.toString()} 会省略秒，不能直接用）。
     */
    public static String localIsoformat(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        int micro = value.getNano() / 1_000;
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond()));
        if (micro != 0) {
            // Python isoformat(auto)：微秒非零时固定输出 6 位（不裁尾随零）
            builder.append('.').append(String.format("%06d", micro));
        }
        return builder.toString();
    }
}
