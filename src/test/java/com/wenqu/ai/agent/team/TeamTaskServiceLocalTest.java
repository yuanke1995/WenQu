package com.wenqu.ai.agent.team;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.mapper.TeamTaskEdgeMapper;
import com.wenqu.ai.mapper.TeamTaskEventMapper;
import com.wenqu.ai.mapper.TeamTaskMapper;
import com.wenqu.ai.service.ChatRunBus;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 团队任务 DAG 本地集成测试：真实 MySQL + Flyway，覆盖 建任务 → 加依赖 → 认领 → 完成解锁。
 * <p>不可达本机 MySQL 时跳过（与 SessionWriteLocalTest 同款）。
 */
class TeamTaskServiceLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void dagCreateDependClaimCompleteUnlocksDownstream() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_team_it_" + System.currentTimeMillis();
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
            TeamTaskService svc = new TeamTaskService(
                    template.getMapper(TeamTaskMapper.class),
                    template.getMapper(TeamTaskEdgeMapper.class),
                    template.getMapper(TeamTaskEventMapper.class),
                    mock(ChatRunBus.class));

            String rootId = "root-1";
            var t1 = svc.create(rootId, "梳理结构", "先看目录");
            var t2 = svc.create(rootId, "审计兼容层", "依赖 t1");
            assertThat(t1.state()).isEqualTo(TeamTaskService.READY);
            assertThat(t2.state()).isEqualTo(TeamTaskService.READY);

            // 加依赖 t1 → t2：t2 退回 PENDING 且 blocked=1
            assertThat(svc.depend(rootId, t1.id(), t2.id())).isNull();
            Map<String, Object> g = svc.graph(rootId);
            var nodes = (java.util.List<?>) g.get("nodes");
            assertThat(nodes).hasSize(2);
            var t2v = svc.graph(rootId);
            boolean t2Pending = ((java.util.List<?>) t2v.get("nodes")).stream()
                    .map(o -> (TeamTaskService.TaskView) o)
                    .anyMatch(v -> v.id().equals(t2.id()) && TeamTaskService.PENDING.equals(v.state())
                            && v.blockedCount() == 1);
            assertThat(t2Pending).as("加依赖后后继应为 PENDING 且 blocked=1").isTrue();

            // 自环/成环被拒
            assertThat(svc.depend(rootId, t2.id(), t1.id())).isEqualTo("依赖会成环");
            assertThat(svc.depend(rootId, t1.id(), t1.id())).isEqualTo("不能给自己加依赖");

            // PENDING 不可认领
            assertThat(svc.claim(rootId, t2.id(), "sessA", "reviewer")).contains("不可认领");

            // 认领并完成 t1 → t2 解锁为 READY
            assertThat(svc.claim(rootId, t1.id(), "sessA", "reviewer")).isNull();
            assertThat(svc.complete(rootId, t1.id(), "done", false)).isNull();
            boolean t2Ready = ((java.util.List<?>) svc.graph(rootId).get("nodes")).stream()
                    .map(o -> (TeamTaskService.TaskView) o)
                    .anyMatch(v -> v.id().equals(t2.id()) && TeamTaskService.READY.equals(v.state())
                            && v.blockedCount() == 0);
            assertThat(t2Ready).as("前置完成后后继应解锁为 READY").isTrue();

            // 事件时间线至少包含 created/claimed/completed/ready
            var events = svc.events(rootId, t2.id());
            assertThat(events.stream().map(e -> String.valueOf(e.get("kind"))).toList())
                    .contains("created", "depended", "ready");
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource ds) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(TeamTaskMapper.class);
        configuration.addMapper(TeamTaskEdgeMapper.class);
        configuration.addMapper(TeamTaskEventMapper.class);
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
