package com.wenqu.ai.startup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OIDC 单认证源 → 多认证源的存量迁移（幂等，可重复执行）：
 * <ol>
 *   <li><b>组装 default 行</b>：{@code c_ai_oidc_provider} 为空 且 {@code c_ai_config} 存在任一 {@code oidc.*}
 *       行时，把 20 个旧键原样（client_secret 已是 RSA 密文，直接搬）组装成 {@code id='default'} 一行；
 *       旧 {@code oidc.*} 行<b>原样保留、不删</b>——代码回滚后老实现直接可读，回滚零成本。</li>
 *   <li><b>回填绑定</b>：{@code oidc_sub} 非空的存量账号 → {@code oidc_provider='default'}（仅当 default 行已建）。</li>
 *   <li><b>换唯一索引</b>：{@code uk_oidc_sub} 单列唯一 → {@code uk_oidc_provider_sub(provider_id, oidc_sub)} 复合唯一；
 *       先在 information_schema 判存在性，失败仅告警（功能按复合键判定，冲突时 resolveUser 的 DuplicateKeyException 分支给明确错误）。</li>
 * </ol>
 * <p>须在 {@link SchemaMigrator}（@Order(0)）之后跑：本类依赖它补齐的 {@code c_ai_oidc_provider} 新表
 * 与 {@code c_ai_user.oidc_provider} 新列。</p>
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(1)
public class OidcMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public OidcMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[OidcMigration] OIDC 多认证源迁移失败（不阻塞启动）：{}", e.getMessage());
        }
    }

    void migrate() {
        if (!tableExists("c_ai_oidc_provider") || !columnExists("c_ai_user", "oidc_provider")) {
            log.warn("[OidcMigration] 前置表/列不存在（SchemaMigrator 可能未执行），跳过迁移");
            return;
        }
        long providerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM c_ai_oidc_provider", Long.class);
        if (providerCount == 0) {
            Map<String, String> cfg = loadOidcConfig();
            if (!cfg.isEmpty()) {
                assembleDefault(cfg);
                backfillBindings();
            } else {
                log.info("[OidcMigration] 无存量 oidc.* 配置，跳过 default 行组装（全新安装）");
            }
        } else {
            log.info("[OidcMigration] c_ai_oidc_provider 已存在数据，跳过 default 行组装");
        }
        swapUniqueIndex();
    }

    /** 把旧 c_ai_config 的 oidc.* 行读出（raw 值，client_secret 已是 RSA 密文，原样搬入即可） */
    private Map<String, String> loadOidcConfig() {
        Map<String, String> cfg = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT config_key, config_value FROM c_ai_config WHERE config_key LIKE 'oidc.%'");
            for (Map<String, Object> row : rows) {
                String k = String.valueOf(row.get("config_key"));
                String v = row.get("config_value") == null ? "" : String.valueOf(row.get("config_value"));
                cfg.put(k, v);
            }
        } catch (Exception e) {
            log.warn("[OidcMigration] 读取旧 oidc.* 配置失败：{}", e.getMessage());
        }
        return cfg;
    }

    private void assembleDefault(Map<String, String> cfg) {
        String name = blankToNull(cfg.get("oidc.providerName"));
        name = name == null ? "默认认证源" : name;
        int enabled = "true".equalsIgnoreCase(cfg.getOrDefault("oidc.enabled", "false")) ? 1 : 0;
        String sql = "INSERT INTO c_ai_oidc_provider (id, name, enabled, issuer_url, client_id, client_secret, "
                + "redirect_uri, frontend_base_url, authorization_endpoint, token_endpoint, userinfo_endpoint, "
                + "scopes, force_prompt_login, username_claim, name_claim, use_raw_username, auto_create_user, "
                + "default_role, default_department, fetch_department_info, department_claim, sort_order) VALUES ("
                + "'default', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        jdbcTemplate.update(sql,
                name, enabled,
                toNull(cfg.get("oidc.issuerUrl")),
                toNull(cfg.get("oidc.clientId")),
                toNull(cfg.get("oidc.clientSecret")),
                toNull(cfg.get("oidc.redirectUri")),
                toNull(cfg.get("oidc.frontendBaseUrl")),
                toNull(cfg.get("oidc.authorizationEndpoint")),
                toNull(cfg.get("oidc.tokenEndpoint")),
                toNull(cfg.get("oidc.userinfoEndpoint")),
                toNull(cfg.get("oidc.scopes")),
                bool(cfg.get("oidc.forcePromptLogin")),
                toNull(cfg.get("oidc.usernameClaim")),
                toNull(cfg.get("oidc.nameClaim")),
                bool(cfg.get("oidc.useRawUsername")),
                bool(cfg.get("oidc.autoCreateUser")),
                toNull(cfg.get("oidc.defaultRole")),
                toNull(cfg.get("oidc.defaultDepartment")),
                bool(cfg.get("oidc.fetchDepartmentInfo")),
                toNull(cfg.get("oidc.departmentClaim")));
        log.info("[OidcMigration] 已组装默认认证源 id=default name={} enabled={}", name, enabled);
    }

    private void backfillBindings() {
        try {
            int n = jdbcTemplate.update(
                    "UPDATE c_ai_user SET oidc_provider='default' WHERE oidc_sub IS NOT NULL "
                            + "AND oidc_sub <> '' AND (oidc_provider IS NULL OR oidc_provider = '')");
            if (n > 0) log.info("[OidcMigration] 回填 {} 个存量账号的 oidc_provider='default'", n);
        } catch (Exception e) {
            log.warn("[OidcMigration] 回填 oidc_provider 失败：{}", e.getMessage());
        }
    }

    private void swapUniqueIndex() {
        boolean hasOld = indexExists("c_ai_user", "uk_oidc_sub");
        boolean hasNew = indexExists("c_ai_user", "uk_oidc_provider_sub");
        if (hasNew) {
            if (hasOld) {
                try {
                    jdbcTemplate.execute("ALTER TABLE c_ai_user DROP INDEX uk_oidc_sub");
                    log.info("[OidcMigration] 已删除旧单列唯一索引 uk_oidc_sub");
                } catch (Exception e) {
                    log.warn("[OidcMigration] 删除 uk_oidc_sub 失败（复合唯一已生效，功能不受影响）：{}", e.getMessage());
                }
            }
            return;
        }
        // 复合索引缺失（如 SchemaMigrator 索引补齐被关闭）：补建
        try {
            jdbcTemplate.execute("ALTER TABLE c_ai_user ADD UNIQUE INDEX uk_oidc_provider_sub (oidc_provider, oidc_sub)");
            log.info("[OidcMigration] 已补建复合唯一索引 uk_oidc_provider_sub");
        } catch (Exception e) {
            log.warn("[OidcMigration] 补建 uk_oidc_provider_sub 失败（oidc_provider 列可能未就绪）：{}", e.getMessage());
            return;
        }
        if (hasOld) {
            try {
                jdbcTemplate.execute("ALTER TABLE c_ai_user DROP INDEX uk_oidc_sub");
                log.info("[OidcMigration] 已删除旧单列唯一索引 uk_oidc_sub");
            } catch (Exception e) {
                log.warn("[OidcMigration] 删除 uk_oidc_sub 失败：{}", e.getMessage());
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

    private static String toNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    private static Integer bool(String v) {
        return "true".equalsIgnoreCase(v) || "1".equals(v) ? 1 : 0;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
