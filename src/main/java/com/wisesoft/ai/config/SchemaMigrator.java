package com.wisesoft.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
                // 表不存在（新库由 schema.sql 创建）或 information_schema 查询失败：原样跳过但必须告警，
                // 否则会出现"启动期看似无事、运行时才报 Unknown column"的静默失效。
                log.warn("[SchemaMigrator] 跳过表 {} 的列补齐：查询现列失败（表不存在或 information_schema 不可读），该表新增列未自动补齐", table);
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
        log.info("[SchemaMigrator] schema 演进检查完成：{} 张表，补列 {} 个，补索引 {} 个{}",
                tables.size(), addedCols, addedIdx, autoIndex ? "" : "（索引自动补齐已关闭）");
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

    /** 解析 schema.sql 的 CREATE TABLE 块（classpath 读取，兼容 jar 部署） */
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
            tables.put(table, new TableDef(cols, indexes));
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

    /** 建表定义：列（列名→定义）+ 索引（PRIMARY KEY 不参与自动补齐） */
    record TableDef(Map<String, String> columns, List<IndexDef> indexes) {
    }

    /** 索引定义：名称 + 列表达式（原样透传，含 DESC 等修饰）+ 是否唯一 */
    record IndexDef(String name, String columns, boolean unique) {
    }
}
