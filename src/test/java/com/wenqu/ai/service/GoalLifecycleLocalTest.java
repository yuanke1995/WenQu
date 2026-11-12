package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.agent.goal.GoalContinuation;
import com.wenqu.ai.agent.goal.GoalPromptBuilder;
import com.wenqu.ai.agent.goal.GoalSettings;
import com.wenqu.ai.agent.goal.GoalVerifier;
import com.wenqu.ai.agentx.FrameworkSessions;
import com.wenqu.ai.mapper.GoalEvidenceMapper;
import com.wenqu.ai.mapper.GoalMapper;
import com.wenqu.ai.mapper.GoalRoundMapper;
import com.wenqu.ai.model.Goal;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 目标生命周期真实 MySQL 集成测试：pause → resume → edit → cancel → deleteRecord
 * 每一步都落真表再读回，锁住「状态机与列更新」不回退（S1-4）。
 */
class GoalLifecycleLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void lifecycleTransitionsPersist() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_goal_it_" + System.currentTimeMillis();
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
            GoalMapper goalMapper = template.getMapper(GoalMapper.class);

            GoalSettings settings = mock(GoalSettings.class);
            when(settings.heartbeatStaleSeconds()).thenReturn(90);
            when(settings.staleGraceSeconds()).thenReturn(90);
            when(settings.nextRoundDelaySeconds()).thenReturn(2);
            SessionService sessionService = mock(SessionService.class);
            GoalService service = new GoalService(
                    goalMapper, template.getMapper(GoalRoundMapper.class),
                    template.getMapper(GoalEvidenceMapper.class),
                    mock(SessionEventService.class), mock(ChatRunBus.class),
                    mock(FrameworkSessions.class), sessionService,
                    mock(ArtifactService.class), settings,
                    mock(GoalVerifier.class), mock(GoalPromptBuilder.class),
                    mock(TurnNotifyService.class),
                    mock(SteerQueueService.class));

            String goalId = UUID.randomUUID().toString().replace("-", "");
            Goal seed = new Goal();
            seed.setId(goalId);
            seed.setSessionId("s-life");
            seed.setUserId("u-life");
            seed.setObjective("整理材料");
            seed.setStatus(GoalService.RUNNING);
            seed.setPhase("QUEUED");
            seed.setObjectiveVersion(0);
            seed.setTurnCounter(0);
            seed.setUsedTurns(0);
            seed.setUsedTokens(0L);
            seed.setUsedToolCalls(0);
            seed.setUsedMs(0L);
            seed.setAttempt(0);
            seed.setNoProgressRounds(0);
            seed.setRecoveryCount(0);
            seed.setBudgetRounds(40);
            seed.setBudgetTokens(2_000_000L);
            seed.setBudgetMs(7_200_000L);
            seed.setBudgetToolCalls(400);
            seed.setRowVersion(0L);
            seed.setNextRunAt(LocalDateTime.now());
            seed.setCreatedAt(LocalDateTime.now());
            seed.setStartedAt(LocalDateTime.now());
            seed.setContinuationJson(GoalContinuation.of("整理材料").toJson());
            goalMapper.insert(seed);

            // 他人会话：归属门禁必须挡住，且不能落下目标行
            doThrow(new BizException(403, "无权访问该会话"))
                    .when(sessionService).ensureOwnedOrCreate("s-foreign", "u-life");
            assertThatThrownBy(() -> service.start("s-foreign", "u-life", null, "越权目标", null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无权访问");
            assertThat(goalMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Goal>()
                    .eq("session_id", "s-foreign"))).isZero();

            // 新会话直接 /deep：会话行必须由发起人兜底建好，不能留给后台目标轮落成 system
            service.start("s-new-deep", "u-life", null, "新会话直接开始", null);
            verify(sessionService).ensureOwnedOrCreate("s-new-deep", "u-life");
            assertThat(goalMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Goal>()
                    .eq("session_id", "s-new-deep")).getUserId()).isEqualTo("u-life");

            service.pause(goalId, "u-life", null);
            assertThat(goalMapper.selectById(goalId).getStatus()).isEqualTo(GoalService.PAUSED);
            assertThat(goalMapper.selectById(goalId).getEndReason()).isEqualTo("stopped");

            service.resume(goalId, "u-life", null);
            Goal resumed = goalMapper.selectById(goalId);
            assertThat(resumed.getStatus()).isEqualTo(GoalService.RUNNING);
            assertThat(resumed.getNextRunAt()).isNotNull();

            service.edit(goalId, "u-life", "整理材料并出报告", null);
            Goal edited = goalMapper.selectById(goalId);
            assertThat(edited.getObjective()).isEqualTo("整理材料并出报告");
            assertThat(edited.getObjectiveVersion()).isEqualTo(1);

            service.cancel(goalId, "u-life", null);
            assertThat(goalMapper.selectById(goalId).getStatus()).isEqualTo(GoalService.CANCELLED);

            service.deleteRecord(goalId, "u-life", null);
            Goal cleared = goalMapper.selectById(goalId);
            assertThat(cleared.getEndReason()).isEqualTo("cleared");
            try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select status, end_reason from c_ai_goal where id='" + goalId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo(GoalService.CANCELLED);
                assertThat(rs.getString("end_reason")).isEqualTo("cleared");
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
        configuration.addMapper(GoalMapper.class);
        configuration.addMapper(GoalRoundMapper.class);
        configuration.addMapper(GoalEvidenceMapper.class);
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
