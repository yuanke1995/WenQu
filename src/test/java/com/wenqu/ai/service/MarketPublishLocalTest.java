package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.mapper.MarketInstallLogMapper;
import com.wenqu.ai.mapper.MarketInstallMapper;
import com.wenqu.ai.mapper.MarketItemMapper;
import com.wenqu.ai.mapper.MarketVersionMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.MarketItem;
import com.wenqu.ai.skill.UserSkillStore;
import com.wenqu.ai.skill.WenquSkillRepository;
import com.wenqu.ai.util.RequestUser;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 市场上架真实 MySQL 集成测试：条目与版本行同一事务；版本写失败时条目必须回滚（S1-4）。
 */
class MarketPublishLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void publishWritesItemAndVersionAtomically() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_market_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");
        Path payloadDir = Files.createTempDirectory("wenqu-market-it-");
        RequestUser.set("admin", "d", List.of("admin"));

        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            DriverManagerDataSource ds = new DriverManagerDataSource(dbUrl, USER, PASSWORD);
            SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(ds));
            MarketItemMapper itemMapper = template.getMapper(MarketItemMapper.class);
            MarketVersionMapper versionMapper = template.getMapper(MarketVersionMapper.class);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            RoleService roleService = mock(RoleService.class);
            when(roleService.isAdminAny(anyList())).thenReturn(true);

            MarketService service = newMarketService(payloadDir, itemMapper, versionMapper, template,
                    roleService, new DataSourceTransactionManager(ds));

            Map<String, Object> ok = tx.execute(s -> service.publishMcp("admin", body("原子测试目录")));
            assertThat(ok.get("name")).isEqualTo("原子测试目录");
            assertThat(count(dbUrl, "select count(*) from c_ai_market_item where item_type='mcp' and item_key='原子测试目录'"))
                    .isEqualTo(1);
            assertThat(count(dbUrl, "select count(*) from c_ai_market_version")).isEqualTo(1);

            MarketVersionMapper failing = spy(versionMapper);
            doThrow(new RuntimeException("版本行写失败")).when(failing).insert(any(com.wenqu.ai.model.MarketVersion.class));
            MarketService broken = newMarketService(payloadDir, itemMapper, failing, template,
                    roleService, new DataSourceTransactionManager(ds));
            assertThatThrownBy(() -> tx.execute(s -> broken.publishMcp("admin", body("回滚测试目录"))))
                    .hasMessageContaining("版本行写失败");
            assertThat(count(dbUrl, "select count(*) from c_ai_market_item where item_key='回滚测试目录'"))
                    .as("版本写失败时条目必须回滚").isZero();
            assertThat(count(dbUrl, "select count(*) from c_ai_market_version where release_note='首次登记'"))
                    .as("只保留成功那次版本行").isEqualTo(1);
        } finally {
            RequestUser.clear();
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static MarketService newMarketService(Path payloadDir, MarketItemMapper itemMapper,
                                                  MarketVersionMapper versionMapper, SqlSessionTemplate template,
                                                  RoleService roleService,
                                                  DataSourceTransactionManager txManager) {
        return new MarketService(payloadDir.toString(), itemMapper,
                template.getMapper(MarketInstallMapper.class), versionMapper,
                template.getMapper(MarketInstallLogMapper.class),
                mock(UserSkillStore.class), mock(WenquSkillRepository.class),
                mock(UserMapper.class), roleService, mock(AgentService.class),
                mock(WorkflowService.class), mock(com.wenqu.ai.mapper.KnowledgeBaseMapper.class),
                mock(com.wenqu.ai.mapper.UserMcpMapper.class), mock(McpClientService.class),
                mock(ResourceVisibilityService.class), mock(UserRoleService.class),
                mock(AuditService.class), txManager);
    }

    private static Map<String, Object> body(String name) {
        return Map.of("name", name, "url", "https://example.com/mcp",
                "mcpType", "http", "summary", "集成测试", "version", "1.0.0");
    }

    private static long count(String dbUrl, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource ds) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(MarketItemMapper.class);
        configuration.addMapper(MarketVersionMapper.class);
        configuration.addMapper(MarketInstallMapper.class);
        configuration.addMapper(MarketInstallLogMapper.class);
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
