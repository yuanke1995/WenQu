package com.wenqu.ai.agentx.team;

import com.wenqu.ai.agent.team.TeamTaskEventService;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamTask;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验证 {@link WenquTeamClient} 的任务事件旁听：框架 {@code LocalTeamClient} 的
 * create/claim/complete 与 {@link WenquTeamGateway#addDependency} 各在 V39 账本上留下一条，
 * 供面板/DAG 画布重建「创建 → 认领 → 完成 / 登记依赖」时间线。
 *
 * <p>与 {@code WenquMysqlBaseStoreLocalTest} 同款：本机 MySQL 不可达时跳过。
 *
 * @author yuanke
 */
class WenquTeamClientEventTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void taskEventsAreProjected() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_team_evt_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");
        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(dbUrl, USER, PASSWORD));
            WenquMysqlBaseStore store = new WenquMysqlBaseStore(jdbc);
            TeamTaskEventService events = new TeamTaskEventService(jdbc);
            WenquTeamClient client = new WenquTeamClient(new LocalTeamClient(store), events, null);

            String ns = "default";
            String team = "root-evt";
            client.createTeam(new TeamCreateSpec(team, ns, "审计", "lead", "",
                    List.of(new TeamMemberSpec("reviewer", "agent-x", "", "byo")))).block();

            TeamTask t1 = client.createTask(ns, team, "梳理结构", "", List.of(), "").block();
            TeamTask t2 = client.createTask(ns, team, "审计兼容层", "", List.of(t1.taskId()), "").block();
            client.claimTask(ns, team, t1.taskId(), "reviewer", 0).block();
            client.completeTask(ns, team, t1.taskId(), "done").block();

            List<Map<String, Object>> tl = events.list(team, t1.taskId());
            assertThat(tl).extracting(m -> m.get("kind"))
                    .containsExactly("created", "claimed", "completed");
            assertThat(tl.get(2).get("detail")).isEqualTo("done");
            assertThat(tl.get(0).get("at")).asString().isNotBlank();

            // 依赖 shim 也留痕
            TeamTask t3 = client.createTask(ns, team, "汇总", "", List.of(), "").block();
            WenquTeamGateway gateway = new WenquTeamGateway(store, null, events, null, null, null);
            assertThat(gateway.addDependency(team, t1.taskId(), t3.taskId())).isNull();
            assertThat(events.list(team, t3.taskId())).extracting(m -> m.get("kind"))
                    .contains("created", "depended");
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

    /** 框架 TeamTool.spawnMember → 我们的 MemberController（不触发框架的 “requires control-plane” 抛错）。 */
    @Test
    void spawnMemberRoutesToMemberController() {
        TeamClient throwing = (TeamClient) java.lang.reflect.Proxy.newProxyInstance(
                TeamClient.class.getClassLoader(), new Class<?>[]{TeamClient.class},
                (proxy, method, args1) -> {
                    throw new UnsupportedOperationException("delegate should not be called: " + method.getName());
                });
        final String[] got = new String[3];
        WenquTeamClient.MemberController controller = new WenquTeamClient.MemberController() {
            @Override
            public void spawn(String root, String memberName, String agentRef) {
                got[0] = root;
                got[1] = memberName;
                got[2] = agentRef;
            }

            @Override
            public void shutdown(String root, String memberName) {
            }

            @Override
            public void wake(String root, String memberName, String message) {
            }
        };
        WenquTeamClient client = new WenquTeamClient(throwing, null, null, controller);
        client.spawnMember("default", "root-1", "reviewer", "agent-x", "role").block();
        assertThat(got).containsExactly("root-1", "reviewer", "agent-x");
    }

    /** 发消息后要主动叫起成员（框架 TeamWakeups 对新建成员无 hook，这是兜底路径）。 */
    @Test
    void sendMessageWakesMember() {
        io.agentscope.harness.agent.team.TeamMessage msg =
                new io.agentscope.harness.agent.team.TeamMessage("lead", "reviewer", "干活了", 1L);
        TeamClient delegate = (TeamClient) java.lang.reflect.Proxy.newProxyInstance(
                TeamClient.class.getClassLoader(), new Class<?>[]{TeamClient.class},
                (proxy, method, args1) -> {
                    if ("sendMessage".equals(method.getName())) {
                        return reactor.core.publisher.Mono.just(msg);
                    }
                    throw new UnsupportedOperationException("unexpected: " + method.getName());
                });
        final String[] woken = new String[2];
        WenquTeamClient.MemberController controller = new WenquTeamClient.MemberController() {
            public void spawn(String root, String memberName, String agentRef) { }
            public void shutdown(String root, String memberName) { }
            public void wake(String root, String memberName, String message) { woken[0] = root; woken[1] = memberName; }
        };
        WenquTeamClient client = new WenquTeamClient(delegate, null, null, controller);
        client.sendMessage("default", "root-1", "lead", "reviewer", "干活了").block();
        assertThat(woken).containsExactly("root-1", "reviewer");
    }
}
