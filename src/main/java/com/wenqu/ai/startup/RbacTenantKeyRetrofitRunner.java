package com.wenqu.ai.startup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色域表租户化改造（批次 F1，存量库一次性）：五张角色域表的主键补上 tenant_id。
 * <p>必须跑在 {@code RbacSeedRunner}（每租户种子）之前——否则种子给 huazhang 租户插
 * 'superadmin' 会撞旧 PK(code) 直接炸（真机踩过：Duplicate entry 'superadmin' for key PRIMARY）。
 * <p>幂等：PK 已含 tenant_id 则整表跳过。步骤：补列兜底（SchemaMigrator @Order(0) 已做）→
 * 回填（角色行归 default；子表行跟随其角色的租户）→ DROP/ADD PRIMARY KEY。
 * 一次性机制：全租户升级稳定后整类删除（启动迁移用完即删口径）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(1)
public class RbacTenantKeyRetrofitRunner implements ApplicationRunner {

    /** 需要租户化的角色域表：表 → PK 列序 */
    private static final Map<String, String[]> TENANT_TABLES = new LinkedHashMap<>();

    static {
        TENANT_TABLES.put("c_ai_role", new String[]{"tenant_id", "code"});
        TENANT_TABLES.put("c_ai_role_menu", new String[]{"tenant_id", "role_code", "menu_id"});
        TENANT_TABLES.put("c_ai_role_api", new String[]{"tenant_id", "role_code", "api_id"});
        TENANT_TABLES.put("c_ai_role_permission", new String[]{"tenant_id", "role_code", "permission_code"});
        TENANT_TABLES.put("c_ai_role_data_scope", new String[]{"tenant_id", "role_code"});
    }

    private final JdbcTemplate jdbc;

    public RbacTenantKeyRetrofitRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, String[]> e : TENANT_TABLES.entrySet()) {
            String table = e.getKey();
            try {
                if (pkHasTenantId(table)) continue;
                List<String> cols = columns(table);
                if (cols == null) continue;
                if (!cols.contains("tenant_id")) {
                    jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `tenant_id` VARCHAR(32) NOT NULL DEFAULT 'default' "
                            + "COMMENT '租户ID（批次 F：角色按租户隔离）'");
                }
                if (!table.equals("c_ai_role")) {
                    jdbc.update("UPDATE `" + table + "` t JOIN `c_ai_role` r ON t.role_code = r.code "
                            + "SET t.tenant_id = r.tenant_id WHERE t.tenant_id = 'default' AND r.tenant_id <> 'default'");
                }
                String pkCols = String.join(", ", java.util.Arrays.stream(e.getValue()).map(c -> "`" + c + "`").toList());
                jdbc.execute("ALTER TABLE `" + table + "` DROP PRIMARY KEY, ADD PRIMARY KEY (" + pkCols + ")");
                log.info("[RbacPerm] F1 租户化：{} 主键改为 {}", table, pkCols);
            } catch (Exception ex) {
                log.warn("[RbacPerm] F1 租户化 {} 失败（不阻塞启动；多租户角色判定在该表上可能不完整）: {}",
                        table, ex.getMessage());
            }
        }
    }

    private boolean pkHasTenantId(String table) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = 'PRIMARY'",
                    table);
            return rows.stream().anyMatch(r -> "tenant_id".equals(String.valueOf(r.get("COLUMN_NAME"))));
        } catch (Exception e) {
            return true; // 查不动就当已改造，别把启动卡死
        }
    }

    private List<String> columns(String table) {
        try {
            return jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class, table);
        } catch (Exception e) {
            return null;
        }
    }
}
