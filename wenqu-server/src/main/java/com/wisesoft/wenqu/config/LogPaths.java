package com.wisesoft.wenqu.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 日志文件定位：参考实现 {@code utils/logging_config.py} 中 {@code LOG_FILE} 的等价常量。
 *
 * <p>参考实现为 {@code LOG_DIR / f"{品牌名}-{DATETIME}.log"}（{@code DATETIME} 取上海时区的当天），
 * 这里保持「目录 + {品牌名}-{日期}.log」的形状与按天切分语义。
 *
 * <p>必要替换（显式标注）：文件名为本产品标识 {@code wenqu-}；目录改由环境变量
 * {@code WENQU_LOG_DIR} 指定（缺省 {@code ./logs}）——该值与
 * {@code src/main/resources/logback-spring.xml} 中的 {@code LOG_DIR} 必须一致，
 * 否则 {@code /api/system/logs} 读到的不是实际写入的文件。
 */
public final class LogPaths {

    /** 日志目录环境变量名。 */
    public static final String LOG_DIR_ENV = "WENQU_LOG_DIR";

    /** 缺省日志目录（与 logback-spring.xml 的缺省值一致）。 */
    public static final String DEFAULT_LOG_DIR = "./logs";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private LogPaths() {}

    /** 日志目录。 */
    public static Path logDir() {
        String configured = System.getenv(LOG_DIR_ENV);
        return Paths.get(configured == null || configured.isBlank() ? DEFAULT_LOG_DIR : configured);
    }

    /** 当天日志文件（对应参考实现的 LOG_FILE）。 */
    public static String logFile() {
        return logDir().resolve("wenqu-" + LocalDate.now().format(DATE_FORMAT) + ".log").toString();
    }
}
