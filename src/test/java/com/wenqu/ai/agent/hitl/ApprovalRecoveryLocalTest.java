package com.wenqu.ai.agent.hitl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.mapper.ToolApprovalMapper;
import com.wenqu.ai.model.ToolApproval;
import com.wenqu.ai.service.ConfigService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
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
 * 本地 MySQL 审批恢复集成测试：模拟进程重启后从 c_ai_tool_approval 恢复 PENDING 卡。
 *
 * <p>覆盖 S1-1 的“审批恢复”路径：恢复列表可见、live=false、本人可裁决落库、越权拒绝。
 */
class ApprovalRecoveryLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void pendingApprovalIsRecoveredAndResolved() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_approval_it_" + System.currentTimeMillis();
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
            ToolApprovalMapper mapper = template.getMapper(ToolApprovalMapper.class);

            ToolApproval row = new ToolApproval();
            row.setId("ap-1");
            row.setSessionId("s1");
            row.setUserId("u1");
            row.setTurnId("t1");
            row.setToolName("execute");
            row.setStatus("PENDING");
            row.setRequestArgs("{\"cmd\":\"ls\"}");
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);

            HitlDecisionService service = new HitlDecisionService(
                    mock(ConfigService.class), mapper, mock(com.wenqu.ai.agent.tool.ToolRegistry.class));

            assertThat(service.getApproval("ap-1", "u1")).isNotNull();
            assertThat(service.getApproval("ap-1", "other")).as("非本人不可见").isNull();

            List<Map<String, Object>> pending = service.listPendingApprovals("s1", "u1");
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).get("approvalId")).isEqualTo("ap-1");
            assertThat(pending.get(0).get("live")).as("重启后句柄不在").isEqualTo(false);
            assertThat((Long) pending.get(0).get("remainingMs")).isPositive();

            assertThat(service.resolveApproval("ap-1", false, "other")).as("越权拒绝").isFalse();
            assertThat(service.resolveApproval("ap-1", true, "u1")).isTrue();
            assertThat(service.listPendingApprovals("s1", "u1")).isEmpty();

            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select status, resolved_at from c_ai_tool_approval where id='ap-1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("APPROVED");
                assertThat(rs.getTimestamp("resolved_at")).isNotNull();
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
