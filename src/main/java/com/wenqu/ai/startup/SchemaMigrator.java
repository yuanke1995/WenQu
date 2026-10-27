package com.wenqu.ai.startup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存量库 schema 演进：启动时解析 classpath 的 schema.sql（CREATE TABLE 定义），
 * 与 information_schema 比对，缺失的**列**与**索引**自动 ALTER 补上（幂等：先查再补）。
 * <p>
 * 解决"存量表加列/加索引需手动 ALTER"的运维痛点——schema.sql 变更后老库启动即自动升级，
 * 避免新装库与存量库结构漂移。补列/补索引失败仅告警不阻塞启动（保证只读/降级场景可用）。
 * <p>
 * 边界：只做"增量补齐"，不改列类型、不删除或重建同名索引（避免误伤线上数据）；
 * 同名索引若定义与 schema.sql 不一致只告警，需运维手工处理。
 * 大表补索引可能耗时较久，可用 ai-app.schema-auto-index=false 关闭索引自动补齐。
 */
@Slf4j
@Component
// @Order(0) —— 必须**最早**跑，不是"较早"：本类存在的唯一目的就是让后续启动任务能操作新列/新表。
// 历史事故（2026-10-08）：本类曾是 @Order(100) 而 RbacSeedRunner 是 @Order(1)，种子先跑 →
// 存量库缺 render_as 列时 seed() 第一条 selectList 就 SQLSyntaxErrorException，
// 6 个内置 Tab 菜单一条没种、user 角色补绑整段没跑，表现为「智能体页没有可用的功能 Tab」。
// 任何给已有表加列的改动都会踩这个坑，故取 0：ApplicationRunner 之间不存在需要更早的先决条件。
@Order(0)
public class SchemaMigrator implements ApplicationRunner {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+`([^`]+)`\\s*\\(([\\s\\S]*?)\\)\\s*ENGINE", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_LINE = Pattern.compile(
            "^\\s*`([^`]+)`\\s+(.+?)\\s*,?$");
    /** 索引定义：[UNIQUE] KEY `名` (`列`[ DESC][, `列`...]) */
    private static final Pattern INDEX_LINE = Pattern.compile(
            "^\\s*(UNIQUE\\s+)?KEY\\s+`([^`]+)`\\s*\\(([^)]+)\\)\\s*,?$", Pattern.CASE_INSENSITIVE);
    /** 非列定义行前缀（PRIMARY KEY / KEY / UNIQUE / 外键 / 全文索引等，列名均以反引号开头故不会误判） */
    private static final List<String> NON_COLUMN_PREFIX =
            List.of("PRIMARY", "KEY", "UNIQUE", "CONSTRAINT", "INDEX", "FULLTEXT", "FOREIGN", "--");

    private final JdbcTemplate jdbcTemplate;

    /** 索引自动补齐开关：大表 ALTER ADD INDEX 可能耗时，需要窗口期执行时可关闭改由运维手工执行 */
    @Value("${ai-app.schema-auto-index:true}")
    private boolean autoIndex;

    public SchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[SchemaMigrator] schema 自动演进失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    /** 解析 schema.sql → 逐表比对补列、补索引 */
    void migrate() {
        Map<String, TableDef> tables = parseSchema();
        if (tables.isEmpty()) {
            log.warn("[SchemaMigrator] 未解析到任何建表定义（classpath schema.sql 不可读？）");
            return;
        }
        int addedCols = 0, addedIdx = 0;
        for (Map.Entry<String, TableDef> e : tables.entrySet()) {
            String table = e.getKey();
            List<String> existingCols = queryColumns(table);
            if (existingCols == null) {
                // 整表缺失（schema.sql 新增表的存量库）：先按原始语句建表（CREATE TABLE IF NOT EXISTS 幂等），
                // 再走常规补列/补索引流程。建表失败才告警跳过——否则新增表在存量库永远不生效。
                log.info("[SchemaMigrator] 检测到新表 {}，执行建表", table);
                existingCols = createTable(table, e.getValue().ddl()) ? queryColumns(table) : null;
            }
            if (existingCols == null) {
                log.warn("[SchemaMigrator] 跳过表 {} 的列补齐：查询现列失败（表创建失败或 information_schema 不可读）", table);
                continue;
            }
            for (Map.Entry<String, String> col : e.getValue().columns().entrySet()) {
                if (!existingCols.contains(col.getKey())) {
                    if (addColumn(table, col.getKey(), col.getValue())) addedCols++;
                }
            }
            if (autoIndex) {
                List<String> existingIdx = queryIndexNames(table);
                if (existingIdx == null) {
                    log.warn("[SchemaMigrator] 跳过表 {} 的索引补齐：查询现索引失败", table);
                    continue;
                }
                for (IndexDef idx : e.getValue().indexes()) {
                    if (!existingIdx.contains(idx.name())) {
                        if (addIndex(table, idx)) addedIdx++;
                    }
                }
            }
        }
        int tenantFixed = normalizeTenantColumns();
        int scopeRenamed = migrateQuotaScope();
        log.info("[SchemaMigrator] schema 演进检查完成：{} 张表，补列 {} 个，补索引 {} 个{}{}{}",
                tables.size(), addedCols, addedIdx,
                autoIndex ? "" : "（索引自动补齐已关闭）",
                tenantFixed > 0 ? "，租户列归一 " + tenantFixed + " 张表" : "",
                scopeRenamed > 0 ? "，配额作用域改名 " + scopeRenamed + " 条" : "");
    }

    /**
     * 租户列归一（Phase 4 刀二）：把所有 {@code tenant_id} 统一成 {@code VARCHAR(32) NOT NULL DEFAULT 'default'}，
     * 并把历史 NULL 行回填成默认租户。
     *
     * <p><b>为什么这是本类「只补不改」边界唯一的例外</b>：Phase 1/P3 那批表留的是可空占位列
     * （注释写着「Phase4 启用，当前 NULL」）。租户行级过滤一上线，读侧谓词就是 {@code tenant_id = 'default'}，
     * 而 SQL 里 {@code NULL = 'default'} 不为真 —— 那些行会<b>在界面上集体消失</b>。真机实测到两处：
     * 审计页 27 条变 0 条、内容安全规则读成空集＝<b>闸门静默失效</b>。这里改的不是类型，是「数据看不看得见」。
     *
     * <p>顺序是先回填再改列：MySQL 严格模式下带着 NULL 去 {@code MODIFY ... NOT NULL} 会直接报错。
     * 表名取自 information_schema（不是外部输入），拼接安全。大表上这条 ALTER 会重建表——
     * 生产首次升级请在窗口期执行（手册「多租户」里已写明）。
     *
     * @return 实际改写定义的表数（0＝都已归一，正常静默）
     */
    private int normalizeTenantColumns() {
        int fixed = 0;
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                    "select TABLE_NAME as t, IS_NULLABLE as n, COLUMN_DEFAULT as d, CHARACTER_MAXIMUM_LENGTH as len "
                            + "from information_schema.COLUMNS "
                            + "where TABLE_SCHEMA = DATABASE() and COLUMN_NAME = 'tenant_id'");
            for (Map<String, Object> c : cols) {
                // 平台全局表（租户注册表、开通申请等）不归一：它们本来就不参与行级过滤，
                // 把列改成 NOT NULL DEFAULT 'default' 等于给「没有归属」这件事编一个假值
                if (com.wenqu.ai.config.WenquTenantLineHandler.isGlobalTable(String.valueOf(c.get("t")))) continue;
                String table = String.valueOf(c.get("t"));
                try {
                    Integer nulls = jdbcTemplate.queryForObject(
                            "select count(*) from `" + table + "` where `tenant_id` is null", Integer.class);
                    if (nulls != null && nulls > 0) {
                        jdbcTemplate.update("update `" + table + "` set `tenant_id` = 'default' where `tenant_id` is null");
                        log.info("[SchemaMigrator] 租户回填：{}.tenant_id 的 {} 行 NULL 归入默认租户", table, nulls);
                    }
                    boolean nullable = !"NO".equalsIgnoreCase(String.valueOf(c.get("n")));
                    Object def = c.get("d");
                    boolean wrongDefault = def == null || !"default".equals(String.valueOf(def));
                    boolean wrongWidth = !"32".equals(String.valueOf(c.get("len")));
                    if (nullable || wrongDefault || wrongWidth) {
                        jdbcTemplate.update("alter table `" + table + "` modify column `tenant_id` "
                                + "VARCHAR(32) NOT NULL DEFAULT 'default' "
                                + "COMMENT '租户ID（Phase 4 多租户：本行归属的租户）'");
                        fixed++;
                    }
                } catch (Exception ex) {
                    log.warn("[SchemaMigrator] 表 {} 的租户列归一失败（该表的行可能对部分成员不可见，需手工处理）: {}",
                            table, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 查询租户列清单失败（跳过归一）: {}", ex.getMessage());
        }
        return fixed;
    }

    /**
     * 配额作用域 {@code platform} → {@code tenant}（Phase 4 刀二）。
     * <p>配额行本身按租户归属，"全平台"这个说法在多租户下是错的——一条规则管不到别的租户。
     * 值改了就要连带存量行一起改，否则老行读成未知作用域、{@code matchesScope} 一律不匹配，
     * 表现为「设过预算的人突然不限额了」这种最坏的静默失效。
     */
    private int migrateQuotaScope() {
        try {
            return jdbcTemplate.update("update c_ai_quota set scope = 'tenant' where scope = 'platform'");
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 配额作用域改名失败（platform 档将不再匹配任何调用，请手工处理）: {}",
                    ex.getMessage());
            return 0;
        }
    }

    /** 查表现有列；表不存在返回 null */
    private List<String> queryColumns(String table) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class, table);
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 查询表 {} 列失败: {}", table, ex.getMessage());
            return null;
        }
    }

    /** 查表现有索引名；查询失败返回 null */
    private List<String> queryIndexNames(String table) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class, table);
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 查询表 {} 索引失败: {}", table, ex.getMessage());
            return null;
        }
    }

    /** 整表缺失时建表（schema.sql 原始语句，幂等）；成功返回 true */
    private boolean createTable(String table, String ddl) {
        if (ddl == null || ddl.isBlank()) {
            log.warn("[SchemaMigrator] 表 {} 缺少建表语句，无法自动创建", table);
            return false;
        }
        try {
            jdbcTemplate.execute(ddl);
            log.info("[SchemaMigrator] 自动建表完成: {}", table);
            return true;
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 建表失败 {}: {}", table, ex.getMessage());
            return false;
        }
    }

    private boolean addColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
            log.info("[SchemaMigrator] 自动补列完成: {}.{} = {}", table, column, definition);
            return true;
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 补列失败 {}.{}: {}", table, column, ex.getMessage());
            return false;
        }
    }

    private boolean addIndex(String table, IndexDef idx) {
        long start = System.currentTimeMillis();
        try {
            String sql = "ALTER TABLE `" + table + "` ADD " + (idx.unique() ? "UNIQUE " : "")
                    + "INDEX `" + idx.name() + "` (" + idx.columns() + ")";
            jdbcTemplate.execute(sql);
            log.info("[SchemaMigrator] 自动补索引完成: {}.{} ({}) 耗时 {}ms",
                    table, idx.name(), idx.columns(), System.currentTimeMillis() - start);
            return true;
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 补索引失败 {}.{}: {}", table, idx.name(), ex.getMessage());
            return false;
        }
    }

    /**
     * 解析 schema.sql 的 CREATE TABLE 块（classpath 读取，兼容 jar 部署）。
     * 同时保留原始建表语句：新增表（存量库整表缺失）需先建表，之后才能继续补列/补索引。
     */
    Map<String, TableDef> parseSchema() {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        String content = readSchemaFile();
        if (content == null) return tables;
        Matcher mt = CREATE_TABLE.matcher(content);
        while (mt.find()) {
            String table = mt.group(1);
            String body = mt.group(2);
            Map<String, String> cols = new LinkedHashMap<>();
            List<IndexDef> indexes = new ArrayList<>();
            for (String line : body.split("\n")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                Matcher im = INDEX_LINE.matcher(l);
                if (im.matches()) {
                    indexes.add(new IndexDef(im.group(2), im.group(3).trim(), im.group(1) != null));
                    continue;
                }
                if (NON_COLUMN_PREFIX.stream().anyMatch(p -> l.toUpperCase().startsWith(p))) continue;
                Matcher cm = COLUMN_LINE.matcher(l);
                if (cm.matches()) {
                    cols.put(cm.group(1), cm.group(2).trim());
                }
            }
            tables.put(table, new TableDef(cols, indexes, mt.group()));
        }
        return tables;
    }

    private String readSchemaFile() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
            if (in == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[SchemaMigrator] schema.sql 读取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 建表定义：列（列名→定义）+ 索引（PRIMARY KEY 不参与自动补齐）+ 原始建表语句（整表缺失时执行） */
    record TableDef(Map<String, String> columns, List<IndexDef> indexes, String ddl) {
    }

    /** 索引定义：名称 + 列表达式（原样透传，含 DESC 等修饰）+ 是否唯一 */
    record IndexDef(String name, String columns, boolean unique) {
    }
}
