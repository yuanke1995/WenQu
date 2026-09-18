package com.wisesoft.wenqu.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 进程路径与环境变量配置。
 *
 * <p>由参考实现的 config/__init__.py 逐函数翻译：有下界整数环境变量读取、
 * 运行目录、历史存储目录、Skill 源/投影目录、用户数据目录。
 *
 * <p>必要替换：环境变量按本产品命名（{@code WENQU_LEGACY_STORAGE_DIR}、{@code WENQU_RUNTIME_DIR}、
 * {@code WENQU_SKILL_DATA_DIR}、{@code WENQU_SKILL_PROJECTION_DIR}、{@code WENQU_USER_DATA_DIR}），
 * 默认值与目录拼装逻辑照搬；Python Path → {@link Path}。
 */
public final class RuntimePaths {

    private RuntimePaths() {}

    /** 读取有下界的整数环境变量，配置非法时显式失败。 */
    public static int getIntEnv(String name, int defaultValue, int minimum) {
        String rawValue = System.getenv().getOrDefault(name, String.valueOf(defaultValue)).strip();
        int value;
        try {
            value = Integer.parseInt(rawValue);
        } catch (NumberFormatException exc) {
            throw new IllegalStateException(name + " must be an integer, got '" + rawValue + "'");
        }
        if (value < minimum) {
            throw new IllegalStateException(name + " must be >= " + minimum + ", got " + value);
        }
        return value;
    }

    /** 读取仅供一次性迁移使用的历史广域存储目录。 */
    public static Path getLegacyStorageDir() {
        return Paths.get(System.getenv().getOrDefault("WENQU_LEGACY_STORAGE_DIR", "legacy-saves"));
    }

    /** 读取可丢弃日志与缓存使用的当前进程运行目录。 */
    public static Path getRuntimeDir() {
        String configured = System.getenv("WENQU_RUNTIME_DIR");
        if (configured != null && !configured.isEmpty()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("wenqu-runtime-" + ProcessHandle.current().pid());
    }

    /** 读取共享与个人 Skill 持久源目录。 */
    public static Path getSkillDataDir() {
        String configured = System.getenv("WENQU_SKILL_DATA_DIR");
        return configured != null && !configured.isEmpty() ? Paths.get(configured) : Paths.get("skill-sources");
    }

    /** 读取用户授权 Skill 只读投影目录。 */
    public static Path getSkillProjectionDir() {
        String configured = System.getenv("WENQU_SKILL_PROJECTION_DIR");
        return configured != null && !configured.isEmpty() ? Paths.get(configured) : Paths.get("skill-projections");
    }

    /** 读取用户级实时文件持久目录。 */
    public static Path getUserDataDir() {
        return Paths.get(System.getenv().getOrDefault("WENQU_USER_DATA_DIR", "user-data"));
    }
}
