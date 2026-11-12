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
 * S1-1 起点：在真 MySQL 空库上验证 Flyway V1 基线。
 * <p>覆盖 S0-1 尚未实测的「空库首次执行」路径：V1 能建出核心表，第二次 migrate 不再执行。
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
        assertThat(first.migrationsExecuted).isEqualTo(1);

        Set<String> tables = tables();
        assertThat(tables).contains(
                "c_ai_session", "c_ai_message", "c_ai_session_event",
                "c_ai_goal", "c_ai_usage_log", "flyway_schema_history");

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
}
