package com.wenqu.ai.agentx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态契约：从 {@code AgentscopeChatEngine} 拆出跟随循环后，{@link AgentxFollowCtx} 的字段
 * 必须在跑循环之前真正被赋值，而不是留一个同名局部变量。
 *
 * <p>线上事故：{@code AgentxLoopRunner} 里写了 {@code TurnFollowLoop loop = new TurnFollowLoop()}、
 * {@code AgentxTurnMirror mirror = ...}、{@code TurnInteractionCoordinator interactions = ...}，
 * 但后面经 {@code c.loop}/{@code c.mirror}/{@code c.interactions} 使用——ctx 上这几个字段恒为 null，
 * 每一轮问答都在 {@code c.loop.seed(...)} 处 NPE，前端只看到「服务内部出现异常」。
 * {@code AgentxTurnSettler} 里 {@code pendingAtClose}/{@code superseded}/{@code planOutcomeRaw}/
 * {@code answer}/{@code failure} 也有同一处影子局部。mvn verify 不跑真实问答，故 CI 未拦截。
 *
 * <p>本测试只钉住「ctx 字段被赋值」这一条可被机器判定的硬约束，防止同类回归。
 */
class AgentxFollowCtxWiringContractTest {

    private static final Path LOOP_RUNNER = Path.of("src", "main", "java", "com", "wenqu", "ai",
            "agentx", "AgentxLoopRunner.java");
    private static final Path TURN_SETTER = Path.of("src", "main", "java", "com", "wenqu", "ai",
            "agentx", "AgentxTurnSettler.java");

    @Test
    void loopRunnerAssignsEveryCtxFieldItLaterReads() throws Exception {
        String source = Files.readString(LOOP_RUNNER);
        assertThat(source)
                .contains("c.loop = new TurnFollowLoop()")
                .contains("c.mirror = new AgentxTurnMirror()")
                .contains("c.interactions = new TurnInteractionCoordinator(")
                .contains("c.machineBudgetMs =")
                .contains("c.turnModel =")
                .contains("c.abruptBefore =")
                .contains("c.modelErrBefore =")
                .contains("c.orphanRecovered =");
        // 事故原貌：同名局部建了对象却没用上 ctx 字段
        assertThat(source)
                .doesNotContain("TurnFollowLoop loop = new TurnFollowLoop()")
                .doesNotContain("AgentxTurnMirror mirror = new AgentxTurnMirror()");
    }

    @Test
    void turnSettlerAssignsEveryCtxFieldItLaterReads() throws Exception {
        String source = Files.readString(TURN_SETTER);
        assertThat(source)
                .contains("c.pendingAtClose = c.session.pending()")
                .contains("c.superseded = planOutcome ==")
                .contains("c.planOutcomeRaw = sessions.takePlanOutcome(")
                .contains("c.answer = c.recording.answerText()")
                .contains("c.failure = AgentxHistoryText.runFailure(")
                .contains("c.streamedBody =")
                .contains("c.cutByMaxIters =")
                .contains("c.awaiting =")
                .contains("c.machineMs =")
                .contains("c.wallMs =");
        // 事故原貌：同名局部声明，后面的读取却走 c.*
        assertThat(source)
                .doesNotContain("String answer = c.recording.answerText()")
                .doesNotContain("String failure = AgentxHistoryText.runFailure(");
    }
}
