package com.wenqu.ai.startup;

import com.wenqu.ai.service.OidcProviderService;
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
 * OIDC 旧配置（{@code c_ai_config} 的 {@code oidc.*} 键）→ 统一认证源表 default 行的存量迁移
 * （幂等，可重复执行）：
 * <p>
 * 2026-10-26（任务 #8）：直接落 {@code c_ai_identity_provider}(type='oidc') default 行
 * （旧表 c_ai_oidc_provider 已退役）；统一表已有 OIDC 行则跳过（多行存量由
 * {@link IdentityMigrationRunner} 按行幂等桥接）。旧 {@code oidc.*} 行<b>原样保留、不删</b>——
 * 代码回滚后老实现直接可读，回滚零成本。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(1)
public class OidcMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final OidcProviderService providerService;

    public OidcMigrationRunner(JdbcTemplate jdbcTemplate, OidcProviderService providerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.providerService = providerService;
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
        if (!tableExists("c_ai_identity_provider")) {
            log.warn("[OidcMigration] 统一认证源表不存在（SchemaMigrator 可能未执行），跳过迁移");
            return;
        }
        long oidcRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM c_ai_identity_provider WHERE type = 'oidc'", Long.class);
        if (oidcRows > 0) {
            log.info("[OidcMigration] 统一表已有 OIDC 认证源，跳过 default 行组装");
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
        // 组装成视图直接落统一表（旧表已退役）；client_secret 已是 RSA 密文原样搬
        com.wenqu.ai.model.OidcProvider src = new com.wenqu.ai.model.OidcProvider();
        src.setEnabled(enabled);
        src.setIssuerUrl(toNull(cfg.get("oidc.issuerUrl")));
        src.setClientId(toNull(cfg.get("oidc.clientId")));
        src.setClientSecret(toNull(cfg.get("oidc.clientSecret")));
        src.setRedirectUri(toNull(cfg.get("oidc.redirectUri")));
        src.setFrontendBaseUrl(toNull(cfg.get("oidc.frontendBaseUrl")));
        src.setAuthorizationEndpoint(toNull(cfg.get("oidc.authorizationEndpoint")));
        src.setTokenEndpoint(toNull(cfg.get("oidc.tokenEndpoint")));
        src.setUserinfoEndpoint(toNull(cfg.get("oidc.userinfoEndpoint")));
        src.setScopes(toNull(cfg.get("oidc.scopes")));
        src.setForcePromptLogin(bool(cfg.get("oidc.forcePromptLogin")));
        src.setUsernameClaim(toNull(cfg.get("oidc.usernameClaim")));
        src.setNameClaim(toNull(cfg.get("oidc.nameClaim")));
        src.setUseRawUsername(bool(cfg.get("oidc.useRawUsername")));
        src.setAutoCreateUser(bool(cfg.get("oidc.autoCreateUser")));
        src.setDefaultRole(toNull(cfg.get("oidc.defaultRole")));
        src.setDefaultDepartment(toNull(cfg.get("oidc.defaultDepartment")));
        src.setFetchDepartmentInfo(bool(cfg.get("oidc.fetchDepartmentInfo")));
        src.setDepartmentClaim(toNull(cfg.get("oidc.departmentClaim")));
        providerService.createFromMigration("default", name, src);
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
