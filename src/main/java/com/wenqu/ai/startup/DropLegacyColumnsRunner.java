package com.wenqu.ai.startup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 旧列下线（Phase 1 身份治理批次 1-5 的收尾，2026-10-26）：
 * 删除 {@code c_ai_user.role / oidc_sub / oidc_provider} 三列与 {@code uk_oidc_provider_sub} /
 * {@code uk_oidc_sub} 两个旧唯一索引——角色已由 {@code c_ai_user_role}（多角色）承载、
 * 外部身份已由 {@code c_ai_user_identity}（多认证源）承载，服务层自批次 2 起停读写旧列。
 * <p>
 * <b>顺序保证</b>：本类 {@code @Order(3)}，跑在 {@link IdentityMigrationRunner}（@Order(2)，
 * 旧列数据 → 统一身份表的一次性桥接）之后——先复制、后删列，同一启动周期内完成，无丢失窗口；
 * 新装库（schema.sql 已无这三列）三步守卫全部跳过，天然幂等。删列失败仅告警不阻塞启动
 * （列在也无害，业务代码已不触碰）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(3)
public class DropLegacyColumnsRunner implements ApplicationRunner {

    /** c_ai_user 上随统一身份模型退役的列 */
    private static final String[] LEGACY_COLUMNS = {"oidc_sub", "oidc_provider", "role"};
    /** 旧列上的索引（uk_oidc_provider_sub 复合唯一、uk_oidc_sub 历史单列唯一） */
    private static final String[] LEGACY_INDEXES = {"uk_oidc_provider_sub", "uk_oidc_sub"};

    private final JdbcTemplate jdbcTemplate;

    public DropLegacyColumnsRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            dropLegacy();
        } catch (Exception e) {
            log.warn("[DropLegacy] 旧列下线失败（不阻塞启动，业务已不读写旧列）：{}", e.getMessage());
        }
    }

    void dropLegacy() {
        // 前置守卫：桥接迁移必须已完成（旧表桥接跑过或无需跑），否则不给删——宁可留着无害列
        if (!tableExists("c_ai_user") || !tableExists("c_ai_user_role") || !tableExists("c_ai_user_identity")) {
            log.warn("[DropLegacy] 前置表未就绪（SchemaMigrator/IdentityMigration 可能未执行），跳过");
            return;
        }
        Long userRows = count("c_ai_user");
        // 数据安全守卫：迁移 Runner 失败不阻塞启动，若它失败了（新表空而用户表有行）就删列 = 丢存量授权。
        // 故删 role 列要求 c_ai_user_role 非空（或根本没有用户），删 oidc 列要求 c_ai_user_identity 非空；
        // 不满足则保留列，下轮启动 IdentityMigrationRunner 重试后自然满足。
        boolean rolesReady = count("c_ai_user_role") > 0 || userRows == 0;
        boolean identityReady = count("c_ai_user_identity") > 0 || userRows == 0;
        if (!rolesReady || !identityReady) {
            log.warn("[DropLegacy] 桥接迁移尚未完成（user_role/identity 表为空而用户表有行），保留旧列下轮重试");
            return;
        }
        for (String idx : LEGACY_INDEXES) {
            if (indexExists("c_ai_user", idx)) {
                try {
                    jdbcTemplate.execute("ALTER TABLE c_ai_user DROP INDEX " + idx);
                    log.info("[DropLegacy] 已删除旧索引 {}.{}", "c_ai_user", idx);
                } catch (Exception e) {
                    log.warn("[DropLegacy] 删除旧索引 {} 失败：{}", idx, e.getMessage());
                }
            }
        }
        for (String col : LEGACY_COLUMNS) {
            if (columnExists("c_ai_user", col)) {
                try {
                    jdbcTemplate.execute("ALTER TABLE c_ai_user DROP COLUMN " + col);
                    log.info("[DropLegacy] 已删除旧列 c_ai_user.{}（角色→c_ai_user_role，外部身份→c_ai_user_identity）", col);
                } catch (Exception e) {
                    log.warn("[DropLegacy] 删除旧列 {} 失败：{}", col, e.getMessage());
                }
            }
        }
    }

    private boolean tableExists(String table) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Long count(String table) {
        try {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean columnExists(String table, String column) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean indexExists(String table, String index) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, index);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
