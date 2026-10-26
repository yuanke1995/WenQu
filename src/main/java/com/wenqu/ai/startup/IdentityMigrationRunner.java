package com.wenqu.ai.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一身份模型桥接迁移（幂等，可重复执行，不阻塞启动）：
 * <p>
 * 把遗留的「OIDC 专用存储」一次性迁入统一身份模型，为 Phase 1 批次2 的服务层重构铺数据底座：
 * <ol>
 *   <li><b>认证源</b>：{@code c_ai_oidc_provider} 每行 → {@code c_ai_identity_provider}(type='oidc')，
 *       协议参数收纳进 {@code config} JSON（字段名对齐 OidcProvider 模型，client_secret 已是 RSA 密文原样搬）。</li>
 *   <li><b>用户-外部身份</b>：{@code c_ai_user.oidc_sub}/{@code oidc_provider} → {@code c_ai_user_identity}
 *       （provider_id=oidc_provider, external_id=oidc_sub）。</li>
 *   <li><b>多角色</b>：{@code c_ai_user.role} 单值 → {@code c_ai_user_role}(granted_by='manual')。</li>
 * </ol>
 * <p>
 * <b>故意不删旧列/旧表</b>：服务层（OidcService/OrgService/UserController/User 模型）仍读旧列，
 * 此刻 DROP 会导致编译失败、应用起不来。旧列与旧表的下线放到服务层改造完成后的迁移批次。
 * 本 Runner 仅做「一次性复制」——无兼容双写，运行期仍以旧表为准直到服务层切换。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(2)
public class IdentityMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IdentityMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[IdentityMigration] 统一身份桥接迁移失败（不阻塞启动）：{}", e.getMessage());
        }
    }

    void migrate() {
        if (!tableExists("c_ai_identity_provider")
                || !tableExists("c_ai_oidc_provider")
                || !columnExists("c_ai_user", "oidc_sub")
                || !columnExists("c_ai_user", "oidc_provider")
                || !columnExists("c_ai_user", "role")) {
            log.warn("[IdentityMigration] 前置表/列不存在（SchemaMigrator 可能未执行），跳过桥接迁移");
            return;
        }
        seedIdentityProviders();
        seedUserIdentities();
        seedUserRoles();
    }

    /** c_ai_oidc_provider → c_ai_identity_provider（仅当新表为空时；幂等） */
    private void seedIdentityProviders() {
        try {
            Long idpCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM c_ai_identity_provider", Long.class);
            if (idpCount != null && idpCount > 0) {
                log.info("[IdentityMigration] c_ai_identity_provider 已存在数据，跳过认证源桥接");
                return;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM c_ai_oidc_provider");
            if (rows.isEmpty()) {
                log.info("[IdentityMigration] c_ai_oidc_provider 无数据，跳过认证源桥接（全新安装将由管理页直接建）");
                return;
            }
            int n = 0;
            for (Map<String, Object> r : rows) {
                Map<String, Object> cfg = new HashMap<>();
                cfg.put("issuerUrl", str(r, "issuer_url"));
                cfg.put("clientId", str(r, "client_id"));
                cfg.put("clientSecret", str(r, "client_secret"));
                cfg.put("redirectUri", str(r, "redirect_uri"));
                cfg.put("frontendBaseUrl", str(r, "frontend_base_url"));
                cfg.put("authorizationEndpoint", str(r, "authorization_endpoint"));
                cfg.put("tokenEndpoint", str(r, "token_endpoint"));
                cfg.put("userinfoEndpoint", str(r, "userinfo_endpoint"));
                cfg.put("scopes", str(r, "scopes"));
                cfg.put("forcePromptLogin", num(r, "force_prompt_login"));
                cfg.put("usernameClaim", str(r, "username_claim"));
                cfg.put("nameClaim", str(r, "name_claim"));
                cfg.put("useRawUsername", num(r, "use_raw_username"));
                cfg.put("autoCreateUser", num(r, "auto_create_user"));
                cfg.put("defaultRole", str(r, "default_role"));
                cfg.put("defaultDepartment", str(r, "default_department"));
                cfg.put("fetchDepartmentInfo", num(r, "fetch_department_info"));
                cfg.put("departmentClaim", str(r, "department_claim"));
                String configJson = objectMapper.writeValueAsString(cfg);
                jdbcTemplate.update(
                        "INSERT INTO c_ai_identity_provider (id, type, name, enabled, config, default_role, "
                                + "default_department, auto_create_user, sort_order, create_time, update_time) VALUES ("
                                + "?, 'oidc', ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                        str(r, "id"),
                        str(r, "name"),
                        num(r, "enabled") == null ? 1 : num(r, "enabled"),
                        configJson,
                        str(r, "default_role"),
                        str(r, "default_department"),
                        num(r, "auto_create_user") == null ? 1 : num(r, "auto_create_user"),
                        num(r, "sort_order") == null ? 0 : num(r, "sort_order"));
                n++;
            }
            log.info("[IdentityMigration] 已桥接 {} 个 OIDC 认证源到 c_ai_identity_provider(type=oidc)", n);
        } catch (Exception e) {
            log.warn("[IdentityMigration] 桥接认证源失败：{}", e.getMessage());
        }
    }

    /** c_ai_user.oidc_sub/oidc_provider → c_ai_user_identity（按行幂等） */
    private void seedUserIdentities() {
        try {
            List<Map<String, Object>> users = jdbcTemplate.queryForList(
                    "SELECT uid, oidc_sub, oidc_provider FROM c_ai_user");
            int n = 0;
            for (Map<String, Object> u : users) {
                String uid = str(u, "uid");
                String sub = str(u, "oidc_sub");
                String provider = str(u, "oidc_provider");
                if (uid == null || sub == null || provider == null) continue;
                try {
                    jdbcTemplate.update(
                            "INSERT INTO c_ai_user_identity (id, uid, provider_id, external_id, create_time, update_time) "
                                    + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                            UUID.randomUUID().toString(), uid, provider, sub);
                    n++;
                } catch (Exception dup) {
                    // 已存在 (provider_id, external_id) 唯一冲突：跳过，幂等
                }
            }
            if (n > 0) log.info("[IdentityMigration] 已桥接 {} 条用户-外部身份绑定到 c_ai_user_identity", n);
        } catch (Exception e) {
            log.warn("[IdentityMigration] 桥接用户身份失败：{}", e.getMessage());
        }
    }

    /** c_ai_user.role 单值 → c_ai_user_role（按行幂等） */
    private void seedUserRoles() {
        try {
            List<Map<String, Object>> users = jdbcTemplate.queryForList(
                    "SELECT uid, role FROM c_ai_user");
            int n = 0;
            for (Map<String, Object> u : users) {
                String uid = str(u, "uid");
                String role = str(u, "role");
                if (uid == null || role == null) continue;
                try {
                    jdbcTemplate.update(
                            "INSERT INTO c_ai_user_role (uid, role_code, granted_by, create_time) VALUES (?, ?, 'manual', NOW())",
                            uid, role);
                    n++;
                } catch (Exception dup) {
                    // 已存在 (uid, role_code) 唯一冲突：跳过，幂等
                }
            }
            if (n > 0) log.info("[IdentityMigration] 已桥接 {} 条用户-角色关系到 c_ai_user_role", n);
        } catch (Exception e) {
            log.warn("[IdentityMigration] 桥接用户角色失败：{}", e.getMessage());
        }
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        String s = v.toString().trim();
        return s.isEmpty() ? null : Integer.parseInt(s);
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
}
