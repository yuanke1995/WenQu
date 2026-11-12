package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.mapper.ArtifactMapper;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.mapper.SessionShareMapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.Session;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 本地 MySQL 会话写入集成测试：真实 SessionMapper/MessageMapper + Spring 事务。
 *
 * <p>覆盖 S1-1 的“会话写入”路径：占位会话自动补建、消息序号分配、会话 message_count/首问标题同步。
 */
class SessionWriteLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void writesMessagesAndMaintainsSessionCounters() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_session_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");

        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            DriverManagerDataSource ds = new DriverManagerDataSource(dbUrl, USER, PASSWORD);
            SqlSessionFactory factory = sqlSessionFactory(ds);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

            SessionService service = new SessionService(
                    mock(StringRedisTemplate.class), mock(AppProperties.class), new ObjectMapper(),
                    template.getMapper(SessionMapper.class), template.getMapper(MessageMapper.class),
                    mock(SessionShareMapper.class), mock(ArtifactMapper.class), tx,
                    mock(ModelRegistryService.class), mock(ProjectService.class),
                    mock(ExecutorRegistry.class));

            Session seed = new Session();
            seed.setId("s1");
            seed.setUserId("u1");
            seed.setMessageCount(0);
            template.getMapper(SessionMapper.class).insert(seed);

            String userMsgId = service.appendMessage(MessageWrite.builder()
                    .sessionId("s1").role("user").content("你好，介绍一下").turnId("t1").build());
            String assistantMsgId = service.appendMessage(MessageWrite.builder()
                    .sessionId("s1").role("assistant").content("这是回答").turnId("t1").build());

            assertThat(userMsgId).isNotBlank();
            assertThat(assistantMsgId).isNotBlank();
            assertThat(userMsgId).isNotEqualTo(assistantMsgId);

            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "select role, content, sequence from c_ai_message where session_id='s1' order by sequence")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("role")).isEqualTo("user");
                    assertThat(rs.getString("content")).isEqualTo("你好，介绍一下");
                    assertThat(rs.getInt("sequence")).isEqualTo(1);
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("role")).isEqualTo("assistant");
                    assertThat(rs.getInt("sequence")).isEqualTo(2);
                }
                try (ResultSet rs = st.executeQuery(
                        "select message_count, title from c_ai_session where id='s1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("message_count")).isEqualTo(2);
                    assertThat(rs.getString("title")).isEqualTo("你好，介绍一下");
                }
            }

            assertThatThrownBy(() -> service.ensureOwnedOrCreate("s1", "u2"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无权访问");
            service.ensureOwnedOrCreate("s-owned-new", "u2");
            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select user_id from c_ai_session where id='s-owned-new'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("user_id")).isEqualTo("u2");
            }

            service.deleteSession("u1", "s1");
            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "select deleted from c_ai_session where id='s1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("deleted")).as("会话软删").isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery(
                        "select count(*) from c_ai_message where session_id='s1' and deleted=0")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("消息同步软删").isZero();
                }
            }
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    @Test
    void deleteRollsBackSessionWhenMessageWriteFails() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_session_rb_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");

        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            DriverManagerDataSource ds = new DriverManagerDataSource(dbUrl, USER, PASSWORD);
            SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(ds));
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            SessionService service = new SessionService(
                    mock(StringRedisTemplate.class), mock(AppProperties.class), new ObjectMapper(),
                    template.getMapper(SessionMapper.class), template.getMapper(MessageMapper.class),
                    mock(SessionShareMapper.class), mock(ArtifactMapper.class), tx,
                    mock(ModelRegistryService.class), mock(ProjectService.class),
                    mock(ExecutorRegistry.class));

            Session seed = new Session();
            seed.setId("s-rb");
            seed.setUserId("u-rb");
            seed.setMessageCount(0);
            template.getMapper(SessionMapper.class).insert(seed);
            service.appendMessage(MessageWrite.builder()
                    .sessionId("s-rb").role("user").content("会被回滚的消息").turnId("t-rb").build());

            // 让同一事务里的第二步（消息软删）失败：会话软删必须跟着回滚
            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement()) {
                st.execute("RENAME TABLE c_ai_message TO c_ai_message_gone");
            }
            assertThatThrownBy(() -> service.deleteSession("u-rb", "s-rb"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("删除会话失败");

            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select deleted from c_ai_session where id='s-rb'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("deleted")).as("消息写失败时会话软删必须回滚").isZero();
            }
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource ds) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(SessionMapper.class);
        configuration.addMapper(MessageMapper.class);
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
