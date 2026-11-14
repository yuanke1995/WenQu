package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionEventMapper;
import com.wenqu.ai.model.SessionEvent;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 本地 MySQL 事件账本集成测试：真实 MyBatis-Plus Mapper 写入 c_ai_session_event。
 *
 * <p>覆盖 S1-1 的“事件账本”路径：自增主键即写入顺序、字段完整落库（账本恒开，无开关分支）。
 */
class SessionEventLedgerLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void eventIsPersistedWithOrderedId() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_event_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");

        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();
            SqlSessionFactory factory = sqlSessionFactory(dbUrl);

            ConfigService config = mock(ConfigService.class);

            try (SqlSession session = factory.openSession(true)) {
                SessionEventMapper mapper = session.getMapper(SessionEventMapper.class);
                SessionEventService service = new SessionEventService(
                        mapper, mock(MessageMapper.class), config);

                Long id = service.append("s1", "u1", "t1", "tool_status", "{\"name\":\"search\"}");
                assertThat(id).as("自增主键回填").isNotNull();

                SessionEvent saved = mapper.selectById(id);
                assertThat(saved.getSessionId()).isEqualTo("s1");
                assertThat(saved.getUserId()).isEqualTo("u1");
                assertThat(saved.getTurnId()).isEqualTo("t1");
                assertThat(saved.getType()).isEqualTo("tool_status");
                assertThat(saved.getPayload()).contains("search");
                assertThat(saved.getCreatedAt()).isNotNull();
            }
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static SqlSessionFactory sqlSessionFactory(String url) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, USER, PASSWORD);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(SessionEventMapper.class);
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
