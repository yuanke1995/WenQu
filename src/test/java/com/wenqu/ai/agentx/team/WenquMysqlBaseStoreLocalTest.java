package com.wenqu.ai.agentx.team;

import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberInfo;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamMessage;
import io.agentscope.harness.agent.team.TeamTask;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 框架团队底座的持久化验证：{@link WenquMysqlBaseStore} + 框架自带 {@link LocalTeamClient}
 * 在真实 MySQL 上跑「建团队 → 名册 → 建任务(blockedBy) → 阻塞认领 → 完成解锁 → 领后认领 → 邮箱」。
 *
 * <p>证明：任务板 / 依赖 / 认领 CAS / 邮箱 / 名册全部由框架实现，问渠只提供 BaseStore。
 * 本机 MySQL 不可达时跳过（与 TeamTaskServiceLocalTest 同款）。
 */
class WenquMysqlBaseStoreLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void frameworkTeamClientOnMysql() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_team_store_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");
        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(dbUrl, USER, PASSWORD));
            LocalTeamClient client = new LocalTeamClient(new WenquMysqlBaseStore(jdbc));

            String ns = "default";
            String team = "root-1";
            client.createTeam(new TeamCreateSpec(team, ns, "audit", "lead-agent", "",
                    List.of(new TeamMemberSpec("reviewer", "agent-x", "", "byo")))).block();

            List<TeamMemberInfo> members = client.listMembers(ns, team).block();
            assertThat(members).extracting(TeamMemberInfo::memberName)
                    .containsExactlyInAnyOrder("lead", "reviewer");

            TeamTask t1 = client.createTask(ns, team, "梳理结构", "先看目录", List.of(), "").block();
            assertThat(t1).isNotNull();
            assertThat(t1.state()).isEqualTo(TeamTask.PENDING);

            TeamTask t2 = client.createTask(ns, team, "审计兼容层", "依赖 t1",
                    List.of(t1.taskId()), "").block();
            assertThat(t2.blockedBy()).containsExactly(t1.taskId());

            // 被阻塞：认领应失败
            assertThatThrownBy(() -> client.claimTask(ns, team, t2.taskId(), "reviewer", 0).block())
                    .isInstanceOf(TeamConflictException.class);

            // t1 未被阻塞：认领 → 完成
            TeamTask claimed1 = client.claimTask(ns, team, t1.taskId(), "reviewer", 0).block();
            assertThat(claimed1.state()).isEqualTo(TeamTask.IN_PROGRESS);
            client.completeTask(ns, team, t1.taskId(), "done").block();

            // 前置完成后 t2 解锁可认领
            TeamTask claimed2 = client.claimTask(ns, team, t2.taskId(), "reviewer", 0).block();
            assertThat(claimed2.state()).isEqualTo(TeamTask.IN_PROGRESS);
            assertThat(claimed2.owner()).isEqualTo("reviewer");

            // 邮箱：写一条消息并能读回（TeamWakeups 无 hook 时 sendMessage 仍应成功入库）
            TeamMessage msg = client.sendMessage(ns, team, "lead", "reviewer", "请先审计兼容层").block();
            assertThat(msg).isNotNull();
            List<TeamMessage> msgs = client.listMessages(ns, team, 20).block();
            assertThat(msgs).extracting(TeamMessage::content).contains("请先审计兼容层");
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            } catch (Exception ignored) {
                // 清理失败不影响断言
            }
        }
    }

    private static boolean canConnect() {
        try (Connection c = DriverManager.getConnection(BASE_URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
