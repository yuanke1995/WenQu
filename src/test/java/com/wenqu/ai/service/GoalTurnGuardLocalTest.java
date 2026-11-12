package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.agent.goal.GoalContinuation;
import com.wenqu.ai.agent.goal.GoalPromptBuilder;
import com.wenqu.ai.agent.goal.GoalSettings;
import com.wenqu.ai.agent.goal.GoalVerifier;
import com.wenqu.ai.agentx.FrameworkSessions;
import com.wenqu.ai.mapper.GoalEvidenceMapper;
import com.wenqu.ai.mapper.GoalMapper;
import com.wenqu.ai.mapper.GoalRoundMapper;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.model.GoalRound;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标轮「叫停」与「起轮让位」的真实 MySQL 集成测试。
 *
 * <p>锁住三条线上事故的回归面：
 * <ol>
 *   <li>取消后目标轮还在跑、还能提交完成申请、还能交付产物；</li>
 *   <li>用户插话（让位）后旧轮不停；</li>
 *   <li>用户轮占着会话时，目标轮仍然建轮次行白涨轮号。</li>
 * </ol>
 */
class GoalTurnGuardLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void cancelGuardAndSessionGate() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_goal_guard_it_" + System.currentTimeMillis();
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
            GoalRoundMapper roundMapper = template.getMapper(GoalRoundMapper.class);

            GoalSettings settings = mock(GoalSettings.class);
            when(settings.heartbeatStaleSeconds()).thenReturn(90);
            when(settings.staleGraceSeconds()).thenReturn(90);
            when(settings.nextRoundDelaySeconds()).thenReturn(2);
            GoalService service = new GoalService(
                    goalMapper, roundMapper,
                    template.getMapper(GoalEvidenceMapper.class),
                    mock(SessionEventService.class), mock(ChatRunBus.class),
                    mock(FrameworkSessions.class), mock(SessionService.class),
                    mock(ArtifactService.class), settings,
                    mock(GoalVerifier.class), mock(GoalPromptBuilder.class),
                    mock(TurnNotifyService.class),
                    mock(SteerQueueService.class));

            // ---- 1. 进行中的轮不算「被叫停」；取消之后同一轮必须判为叫停 ----
            String goalId = seedGoal(goalMapper, "s-guard", GoalService.RUNNING, "EXECUTING");
            String turnId = GoalService.GOAL_TURN_ID_PREFIX + goalId + ":1";
            seedRunningRound(roundMapper, goalId, turnId);
            assertThat(service.goalTurnAborted(turnId)).isFalse();
            assertThat(service.goalTurnAborted("plain-user-turn")).isFalse();

            // 1b. 正常收尾窗口：轮次已 SETTLED、目标已排下一轮/已完成——这不是叫停，
            //     收尾时的产物交付必须照常（线上两轮产物就是在这里被误丢的）。
            GoalRound settled = roundMapper.byTurnId(turnId);
            settled.setStatus("SETTLED");
            roundMapper.updateById(settled);
            Goal nextRound = goalMapper.selectById(goalId);
            nextRound.setPhase(GoalService.QUEUED);
            goalMapper.updateById(nextRound);
            service.clearTurnAbort(turnId);
            assertThat(service.goalTurnAborted(turnId)).isFalse();

            Goal achieved = goalMapper.selectById(goalId);
            achieved.setStatus(GoalService.ACHIEVED);
            achieved.setPhase("DONE");
            goalMapper.updateById(achieved);
            service.clearTurnAbort(turnId);
            assertThat(service.goalTurnAborted(turnId)).isFalse();

            // 再把这一轮恢复成在跑，验证取消判定
            Goal back = goalMapper.selectById(goalId);
            back.setStatus(GoalService.RUNNING);
            back.setPhase("EXECUTING");
            goalMapper.updateById(back);
            GoalRound again = roundMapper.byTurnId(turnId);
            again.setStatus(GoalRound.RUNNING);
            roundMapper.updateById(again);

            service.cancel(goalId, "u-guard", null);
            assertThat(service.goalTurnAborted(turnId)).isTrue();

            // ---- 2. 取消之后：完成申请与目标进展都写不进去 ----
            assertThat(service.recordProposal(turnId, "完成了", "[]", "[]", false, null))
                    .contains("未记录");
            assertThat(roundMapper.byTurnId(turnId).getProposalJson()).isNull();
            assertThat(service.recordNote(turnId, "[\"a\"]", "[]", "[]", "DELIVER", "写完了", "[]"))
                    .contains("目标已结束");

            // ---- 3. 用户插话让位：目标转暂停，且这一轮立刻判为叫停 ----
            String goal2 = seedGoal(goalMapper, "s-preempt", GoalService.RUNNING, "EXECUTING");
            String turn2 = GoalService.GOAL_TURN_ID_PREFIX + goal2 + ":1";
            seedRunningRound(roundMapper, goal2, turn2);
            assertThat(service.preemptForUserTurn("s-preempt", "u-guard")).isTrue();
            assertThat(goalMapper.selectById(goal2).getStatus()).isEqualTo(GoalService.PAUSED);
            assertThat(goalMapper.selectById(goal2).getEndReason()).isEqualTo("preempted");
            assertThat(service.goalTurnAborted(turn2)).isTrue();

            // ---- 4. 会话被占：目标轮让位，不建轮次行、不涨轮号 ----
            String waitId = seedGoal(goalMapper, "s-busy", GoalService.RUNNING, "QUEUED");
            // 起轮驱动绑成空实现：这一分支应当在「抢锁失败」就返回，根本走不到提交
            service.bindTurnDriver((g, input, n) -> {
                throw new AssertionError("会话被占时不应提交目标轮");
            });
            service.bindSessionGate(new GoalService.SessionGate() {
                @Override
                public boolean acquire(String sessionId) {
                    return false;
                }

                @Override
                public void release(String sessionId) {
                }
            });
            service.runClaimed(goalMapper.selectById(waitId), "owner-test");
            assertThat(roundMapper.maxN(waitId)).isZero();
            Goal deferred = goalMapper.selectById(waitId);
            assertThat(deferred.getTurnCounter()).isZero();
            assertThat(deferred.getNextRunAt()).isAfter(LocalDateTime.now());
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static String seedGoal(GoalMapper mapper, String sessionId, String status, String phase) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Goal g = new Goal();
        g.setId(id);
        g.setSessionId(sessionId);
        g.setUserId("u-guard");
        g.setObjective("整理材料");
        g.setStatus(status);
        g.setPhase(phase);
        g.setObjectiveVersion(0);
        g.setTurnCounter(0);
        g.setUsedTurns(0);
        g.setUsedTokens(0L);
        g.setUsedToolCalls(0);
        g.setUsedMs(0L);
        g.setAttempt(0);
        g.setNoProgressRounds(0);
        g.setRecoveryCount(0);
        g.setBudgetRounds(40);
        g.setBudgetTokens(2_000_000L);
        g.setBudgetMs(7_200_000L);
        g.setBudgetToolCalls(400);
        g.setRowVersion(0L);
        g.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        g.setCreatedAt(LocalDateTime.now());
        g.setStartedAt(LocalDateTime.now());
        g.setContinuationJson(GoalContinuation.of("整理材料").toJson());
        g.setLeaseOwner("owner-test");
        g.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        mapper.insert(g);
        return id;
    }

    private static void seedRunningRound(GoalRoundMapper mapper, String goalId, String turnId) {
        GoalRound r = new GoalRound();
        r.setGoalId(goalId);
        r.setN(1);
        r.setStatus(GoalRound.RUNNING);
        r.setTurnId(turnId);
        r.setStartedAt(LocalDateTime.now());
        r.setUsedTokens(0L);
        r.setUsedMs(0L);
        r.setUsedToolCalls(0);
        r.setCreatedAt(LocalDateTime.now());
        mapper.insert(r);
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
