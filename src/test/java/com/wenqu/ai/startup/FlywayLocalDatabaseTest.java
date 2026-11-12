package com.wenqu.ai.startup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 本地 MySQL 集成测试：空库执行 Flyway V1+V2+V3+V4，验证基线可用、各版改动生效、二次 migrate 为 0。
 *
 * <p>不依赖 Docker；本机 3306 不可达时自动跳过。CI 里仍由 {@link FlywayFreshDatabaseTest}
 * （Testcontainers）覆盖。
 */
class FlywayLocalDatabaseTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void freshDatabaseRunsV1ToV4ThenStops() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_it_" + System.currentTimeMillis();

        try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
             Statement st = root.createStatement()) {
            st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        try {
            String url = BASE_URL.replace("/?", "/" + db + "?");
            Flyway flyway = Flyway.configure()
                    .dataSource(url, USER, PASSWORD)
                    .locations("classpath:db/migration")
                    .load();

            MigrateResult first = flyway.migrate();
            assertThat(first.migrationsExecuted).as("空库应执行 V1 + V2 + V3 + V4").isEqualTo(4);

            assertThat(tables(url)).contains(
                    "c_ai_session", "c_ai_message", "c_ai_session_event",
                    "c_ai_goal", "c_ai_usage_log", "c_ai_steer_item", "flyway_schema_history");
            assertThat(columns(url, "c_ai_agent"))
                    .as("V2 已删除 chatflow 列")
                    .doesNotContain("workflow_id");
            assertThat(columns(url, "c_ai_menu"))
                    .as("V3 已加企业面标记列")
                    .contains("enterprise_only");
            // V4：插话待发队列（tenant_id 缺了运行期会炸，见多租户拦截器）
            assertThat(columns(url, "c_ai_steer_item"))
                    .as("V4 已建插话待发队列（含多租户列与收据列）")
                    .contains("tenant_id", "session_id", "user_id", "status", "receipt_id", "images");

            MigrateResult second = flyway.migrate();
            assertThat(second.migrationsExecuted).as("二次启动不应重复执行").isZero();
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static boolean canConnect() {
        try (Connection ignored = DriverManager.getConnection(BASE_URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<String> tables(String url) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("show tables")) {
            while (rs.next()) out.add(rs.getString(1).toLowerCase());
        }
        return out;
    }

    private static Set<String> columns(String url, String table) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select column_name from information_schema.columns "
                             + "where table_schema = database() and table_name = '" + table + "'")) {
            while (rs.next()) out.add(rs.getString(1).toLowerCase());
        }
        return out;
    }
}
