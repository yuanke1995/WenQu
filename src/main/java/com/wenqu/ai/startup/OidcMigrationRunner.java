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
 * OIDC 旧配置（{@code c_ai_config} 的 {@code oidc.*} 键）→ 旧认证源表 default 行的存量迁移
 * （幂等，可重复执行）：
 * <p>
 * 2026-10-26 收缩：只保留「组装 default 行」——{@code c_ai_user.oidc_sub/oidc_provider} 两列与
 * 唯一索引已随统一身份模型退役（{@link DropLegacyColumnsRunner} 负责删除），回填绑定与换索引
 * 两段随之移除；default 行组装出的旧表行由 {@link IdentityMigrationRunner}（@Order(2)）桥接进
 * 统一身份表。旧 {@code oidc.*} 行<b>原样保留、不删</b>——代码回滚后老实现直接可读，回滚零成本。
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
            log.warn("[OidcMigration] OIDC 存量迁移失败（不阻塞启动）：{}", e.getMessage());
        }
    }

    void migrate() {
        if (!tableExists("c_ai_oidc_provider")) {
            log.warn("[OidcMigration] 前置表不存在（SchemaMigrator 可能未执行），跳过迁移");
            return;
        }
        long providerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM c_ai_oidc_provider", Long.class);
        if (providerCount > 0) {
            log.info("[OidcMigration] c_ai_oidc_provider 已存在数据，跳过 default 行组装");
            return;
        }
        Map<String, String> cfg = loadOidcConfig();
        if (cfg.isEmpty()) {
            log.info("[OidcMigration] 无存量 oidc.* 配置，跳过 default 行组装（全新安装）");
            return;
        }
        assembleDefault(cfg);
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
