package com.wenqu.ai.agent.hitl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.mapper.ToolApprovalMapper;
import com.wenqu.ai.model.ToolApproval;
import com.wenqu.ai.service.ConfigService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 提问卡超时＝pending（DSH 口径）：前台等待结束后那一行落 LATE 而不是终态，
 * 卡片仍能取回、仍能作答；作答只收尾这一行，答案由客户端作为新发言送进会话。
 */
class AskContinuedAnswerLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    @DisplayName("超时转 CONTINUED 后仍可取回与作答；空答案批＝跳过，越权拒绝")
    void lateAskStaysAnswerable() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_ask_continued_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");
        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            SqlSessionTemplate template = new SqlSessionTemplate(
                    sqlSessionFactory(new DriverManagerDataSource(dbUrl, USER, PASSWORD)));
            ToolApprovalMapper mapper = template.getMapper(ToolApprovalMapper.class);
            HitlDecisionService service = new HitlDecisionService(
                    mock(ConfigService.class), mapper, mock(com.wenqu.ai.agent.tool.ToolRegistry.class));

            insertAsk(mapper, "ask-1");
            insertAsk(mapper, "ask-2");

            Map<String, Object> before = service.listPendingAsks("s1", "u1").get(0);
            assertThat(before.get("continued")).isEqualTo(false);
            assertThat((Long) before.get("remainingMs")).isPositive();

            assertThat(service.markAskContinued("ask-1", "（前台等待已结束…）")).isTrue();
            assertThat(service.markAskContinued("ask-1", "再来一次")).as("已转 CONTINUED，幂等").isFalse();

            List<Map<String, Object>> after = service.listPendingAsks("s1", "u1");
            Map<String, Object> continued = after.stream().filter(x -> "ask-1".equals(x.get("askId"))).findFirst().orElseThrow();
            assertThat(continued.get("continued")).isEqualTo(true);
            assertThat(continued.get("expired")).as("仍可答，不是过期卡").isEqualTo(false);
            assertThat(continued.get("live")).as("答了有人接：作为新发言送达").isEqualTo(true);
            assertThat(continued.get("remainingMs")).isEqualTo(0L);
            assertThat(statusOf(dbUrl, "ask-1")).isEqualTo("CONTINUED");
            assertThat(resolvedAtOf(dbUrl, "ask-1")).as("还没裁决").isNull();

            assertThat(service.closeContinuedAsk("ask-1", "other", List.of("弹窗子表"))).as("越权拒绝").isFalse();
            assertThat(service.closeContinuedAsk("ask-1", "u1", List.of("弹窗子表"))).isTrue();
            assertThat(statusOf(dbUrl, "ask-1")).isEqualTo("ANSWERED");
            assertThat(service.listPendingAsks("s1", "u1"))
                    .extracting(x -> x.get("askId")).containsExactly("ask-2");

            service.markAskContinued("ask-2", "（前台等待已结束…）");
            // DSH 没有「忽略」这一档：跳过就是空答案的那一题，仍是一批作答
            assertThat(service.closeContinuedAsk("ask-2", "u1", List.of(""))).isTrue();
            assertThat(statusOf(dbUrl, "ask-2")).isEqualTo("ANSWERED");
            assertThat(service.listPendingAsks("s1", "u1")).isEmpty();
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static void insertAsk(ToolApprovalMapper mapper, String id) {
        ToolApproval row = new ToolApproval();
        row.setId(id);
        row.setSessionId("s1");
        row.setUserId("u1");
        row.setTurnId("t1");
        row.setToolName("askUser");
        row.setStatus("PENDING");
        row.setRequestArgs("{\"questions\":[{\"question\":\"用哪种录入方式？\",\"options\":[\"弹窗子表\",\"表内子表\"]}]}");
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
    }

    private static String statusOf(String dbUrl, String id) throws Exception {
        return columnOf(dbUrl, id, "status");
    }

    private static String resolvedAtOf(String dbUrl, String id) throws Exception {
        return columnOf(dbUrl, id, "resolved_at");
    }

    private static String columnOf(String dbUrl, String id, String column) throws Exception {
        try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select " + column + " from c_ai_tool_approval where id='" + id + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource ds) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ToolApprovalMapper.class);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(configuration);
        return bean.getObject();
    }

    private static boolean canConnect() {
        try (Connection ignored = DriverManager.getConnection(BASE_URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
