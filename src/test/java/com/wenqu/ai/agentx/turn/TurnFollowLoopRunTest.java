package com.wenqu.ai.agentx.turn;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.session.AgentSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 跟随循环骨架的分支顺序：状态迁移与退出判定。 */
class TurnFollowLoopRunTest {

    @Test
    @DisplayName("启动后收到停止：置 stopped、发中断，结果保留当前任务")
    void launchThenStop() {
        FakeHooks hooks = new FakeHooks();
        hooks.stopRequested = true;
        AgentSession.Task current = task("running");

        TurnFollowLoop.LoopResult result = new TurnFollowLoop().run(ctx(current, hooks, 1000), hooks);

        assertThat(hooks.interrupts).isEqualTo(1);
        assertThat(result.current()).isEqualTo(current);
    }

    @Test
    @org.junit.jupiter.api.Timeout(3)
    @DisplayName("连续无进展：置 stalled、发中断与警示")
    void stallStops() {
        FakeHooks hooks = new FakeHooks();
        hooks.progressStalled = true;

        new TurnFollowLoop().run(ctx(task("running"), hooks, 1000), hooks);

        assertThat(hooks.interrupts).isEqualTo(1);
        assertThat(hooks.warnStalls).isEqualTo(1);
        assertThat(hooks.warns).isEmpty();
    }

    @Test
    @DisplayName("恰好答完的那轮：无进展标记不算空转，按正常轮收")
    void settledTurnIsNotStall() {
        FakeHooks hooks = new FakeHooks();
        hooks.progressStalled = true;

        new TurnFollowLoop().run(ctx(task("completed"), hooks, 1000), hooks);

        assertThat(hooks.warnStalls).isZero();
        assertThat(hooks.interrupts).isZero();
        assertThat(hooks.pauses).containsExactly(150L);
    }

    @Test
    @DisplayName("计划被取代：只中断，不置 stopped/stalled")
    void planSupersededInterrupts() {
        FakeHooks hooks = new FakeHooks();
        hooks.planSuperseded = true;

        new TurnFollowLoop().run(ctx(task("running"), hooks, 1000), hooks);

        assertThat(hooks.interrupts).isEqualTo(1);
        assertThat(hooks.warnStalls).isZero();
        assertThat(hooks.warns).isEmpty();
    }

    @Test
    @DisplayName("预算已尽：循环一次都不进")
    void budgetExhaustedSkipsLoop() {
        FakeHooks hooks = new FakeHooks();
        hooks.machineMs = 100;

        new TurnFollowLoop().run(ctx(task("running"), hooks, 100), hooks);

        assertThat(hooks.drains).isZero();
        assertThat(hooks.pauses).isEmpty();
    }

    @Test
    @DisplayName("待办等待：先登记等待并让出一拍，随后可停止")
    void pendingWaitPauses() {
        FakeHooks hooks = new FakeHooks();
        hooks.hasPending = true;
        hooks.pending = Map.of("req-1", event());
        hooks.onPause = () -> {
            hooks.hasPending = false;
            hooks.stopRequested = true;
        };

        new TurnFollowLoop().run(ctx(task("running"), hooks, 1000), hooks);

        assertThat(hooks.begins).isEqualTo(1);
        assertThat(hooks.pauses).containsExactly(300L);
        assertThat(hooks.interrupts).isEqualTo(1);
    }

    @Test
    @DisplayName("提问超时：按未作答继续，随后可停止，不置 timedOut")
    void askTimeoutContinues() {
        FakeHooks hooks = new FakeHooks();
        hooks.hasPending = true;
        hooks.pending = Map.of("req-1", event());
        hooks.answerTimedOutAsk = true;
        hooks.onAnswerTimeout = () -> {
            hooks.hasPending = false;
            hooks.stopRequested = true;
        };

        new TurnFollowLoop().run(ctx(task("running"), hooks, 0), hooks);

        assertThat(hooks.begins).isEqualTo(1);
        assertThat(hooks.ends).isEqualTo(1);
        assertThat(hooks.warns).anyMatch(s -> s.contains("未作答"));
        assertThat(hooks.interrupts).isEqualTo(1);
    }

    @Test
    @DisplayName("确认/审批超时：发超时警示并收轮")
    void approvalTimeoutStops() {
        FakeHooks hooks = new FakeHooks();
        hooks.hasPending = true;
        hooks.pending = Map.of("req-1", event());

        new TurnFollowLoop().run(ctx(task("running"), hooks, 0), hooks);

        assertThat(hooks.warns).anyMatch(s -> s.contains("等待超时"));
        assertThat(hooks.interrupts).isZero();
        assertThat(hooks.ends).isZero();
    }

    @Test
    @DisplayName("任务已结算：走 afterSettled 的补读，干净则退出")
    void settledBreaksAfterRecheck() {
        FakeHooks hooks = new FakeHooks();

        new TurnFollowLoop().run(ctx(task("completed"), hooks, 1000), hooks);

        assertThat(hooks.drains).isEqualTo(3);   // 主循环 1 次 + afterSettled 补读 2 次
        assertThat(hooks.retrieves).isEqualTo(2);
        assertThat(hooks.pauses).containsExactly(150L);
    }

    private static TurnFollowLoop.RunContext ctx(AgentSession.Task current, FakeHooks hooks, long askBudget) {
        TurnInteractionCoordinator interactions = new TurnInteractionCoordinator(
                hooks::now,
                kind -> askBudget);
        return new TurnFollowLoop.RunContext(current, 100, 10_000, 10_000, interactions);
    }

    private static AgentSession.Task task(String status) {
        return new AgentSession.Task("cmd-1", "turn-1", "run-1", status, null);
    }

    private static SessionEvent event() {
        return new SessionEvent(1, "e1", 1L, 0L, "interaction/requested", null, "turn-1", false, "{}");
    }

    private static final class FakeHooks implements TurnFollowLoop.Hooks {
        long now;
        long machineMs;
        boolean stopRequested;
        boolean planSuperseded;
        boolean progressStalled;
        boolean takeWrapUp;
        boolean hasPending;
        boolean answerTimedOutAsk;
        boolean activeRunsEmpty = true;
        Map<String, SessionEvent> pending = new LinkedHashMap<>();
        AgentSession.Task latestTask;
        Runnable onPause = () -> {
        };
        Runnable onAnswerTimeout = () -> {
        };
        int interrupts;
        int warnStalls;
        int begins;
        int ends;
        int drains;
        int retrieves;
        final List<String> warns = new ArrayList<>();
        final List<Long> pauses = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public long machineMs() {
            return machineMs;
        }

        @Override
        public void pause(long millis) {
            pauses.add(millis);
            if (pauses.size() > 200) throw new IllegalStateException("loop guard: 空转不止血，pauses=" + pauses.size());
            now += millis;
            onPause.run();
        }

        @Override
        public long drain(long cursor, long emitFrom) {
            drains++;
            return cursor + 1;
        }

        @Override
        public boolean takePendingChanged() {
            return false;
        }

        @Override
        public boolean takeTaskChanged() {
            return false;
        }

        @Override
        public boolean takeMessageChanged() {
            return false;
        }

        @Override
        public void emitLiveUsage(long[] usageStamp, boolean messageChanged) {
        }

        @Override
        public int emitRetrieved(int announcedRefs) {
            retrieves++;
            return announcedRefs;
        }

        @Override
        public boolean planSuperseded() {
            return planSuperseded;
        }

        @Override
        public void interrupt() {
            interrupts++;
        }

        @Override
        public void announcePending(Map<String, SessionEvent> pending) {
        }

        @Override
        public boolean stopRequested() {
            return stopRequested;
        }

        @Override
        public void steerWrapUp() {
        }

        @Override
        public boolean takeWrapUp() {
            return takeWrapUp;
        }

        @Override
        public boolean progressStalled() {
            return progressStalled;
        }

        @Override
        public void warn(String text) {
            warns.add(text);
        }

        @Override
        public void warnStall() {
            warnStalls++;
        }

        @Override
        public boolean hasPending() {
            return hasPending;
        }

        @Override
        public Map<String, SessionEvent> pendingSnapshot() {
            return pending;
        }

        @Override
        public Map<String, SessionEvent> pending() {
            return new LinkedHashMap<>();
        }

        @Override
        public boolean answerTimedOutAsk(Map<String, SessionEvent> pending) {
            onAnswerTimeout.run();
            return answerTimedOutAsk;
        }

        @Override
        public void beginHumanWait(Map<String, SessionEvent> pending) {
            begins++;
        }

        @Override
        public void endHumanWait() {
            ends++;
        }

        @Override
        public AgentSession.Task latestTask(AgentSession.Task known) {
            return latestTask;
        }

        @Override
        public boolean activeRunsEmpty() {
            return activeRunsEmpty;
        }
    }
}
