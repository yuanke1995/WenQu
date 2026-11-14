package com.wenqu.ai.startup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真 MySQL 空库上验证迁移脚本：V1 建出核心表、V2 删除 chatflow 列、V3 给菜单加企业面标记、
 * V4 建插话待发队列表（含多租户列）、V5 给队列加附件列、V6 补定时任务运行配置、
 * V10 清理重排平台层全局键（下放知识库；V7~V9 为历史一次性迁移，编号顺延），第二次 migrate 不再执行。
 * Docker 不可用时自动跳过；有 Docker 的 CI/开发机直接跑。
 */
@Tag("container")
@Testcontainers(disabledWithoutDocker = true)
class FlywayFreshDatabaseTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("wenqu_it")
            .withUsername("it")
            .withPassword("it");

    @Test
    void baselineRunsOnceAndCreatesCoreTables() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult first = flyway.migrate();
        assertThat(first.migrationsExecuted).as("空库应执行 V1~V10").isEqualTo(10);

        Set<String> tables = tables();
        assertThat(tables).contains(
                "c_ai_session", "c_ai_message", "c_ai_session_event",
                "c_ai_goal", "c_ai_usage_log", "c_ai_steer_item", "flyway_schema_history");
        assertThat(columns("c_ai_agent")).as("V2 已删除 chatflow 列").doesNotContain("workflow_id");
        assertThat(columns("c_ai_menu")).as("V3 已加企业面标记列").contains("enterprise_only");
        // V4：插话待发队列。tenant_id 缺了会被多租户拦截器在运行期打成 Unknown column，必须一起钉住
        assertThat(columns("c_ai_steer_item"))
                .as("V4 已建插话待发队列（含多租户列与收据列）、V5 已加附件列")
                .contains("tenant_id", "session_id", "user_id", "status", "receipt_id", "images", "attachments");
        // V6：定时任务任务级运行配置 + 执行记录可观测字段
        assertThat(columns("c_ai_scheduled_job"))
                .as("V6 已加通知偏好/重试/超时/到期")
                .contains("notify_on", "max_retries", "retry_interval_sec", "timeout_sec", "end_at");
        assertThat(columns("c_ai_scheduled_run"))
                .as("V6 已加尝试次数与耗时")
                .contains("attempt", "duration_ms");

        MigrateResult second = flyway.migrate();
        assertThat(second.migrationsExecuted).as("二次启动不应重复执行基线").isZero();
    }

    private static Set<String> tables() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select table_name from information_schema.tables where table_schema = database()")) {
            while (rs.next()) out.add(rs.getString(1).toLowerCase());
        }
        return out;
    }

    private static Set<String> columns(String table) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select column_name from information_schema.columns "
                             + "where table_schema = database() and table_name = '" + table + "'")) {
            while (rs.next()) out.add(rs.getString(1).toLowerCase());
        }
        return out;
    }
}
