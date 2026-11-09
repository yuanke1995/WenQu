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

/** 任务结算后的复查顺序：迟到待办 → 新任务 → 增量 drain + 镜像 → 退出。 */
class TurnFollowLoopSettledTest {

    private final TurnFollowLoop loop = new TurnFollowLoop();

    @Test
    @DisplayName("有迟到待办：先放卡，继续跟，不再看新任务")
    void latePendingContinues() {
        FakeHooks hooks = new FakeHooks();
        hooks.latePending = Map.of("req-1", event());

        TurnFollowLoop.SettledResult result = loop.afterSettled(hooks, task("completed"));

        assertThat(result.breakLoop()).isFalse();
        assertThat(hooks.announces).isEqualTo(1);
        assertThat(hooks.drains).isEqualTo(1);
        assertThat(hooks.retrieves).isEqualTo(1);
        assertThat(hooks.pauses).containsExactly(150L);
    }

    @Test
    @DisplayName("没有迟到待办但有接续的新任务：切到新任务继续")
    void newerRunningTaskSwitches() {
        FakeHooks hooks = new FakeHooks();
        AgentSession.Task newer = task("running");
        hooks.newer = newer;

        TurnFollowLoop.SettledResult result = loop.afterSettled(hooks, task("completed"));

        assertThat(result.breakLoop()).isFalse();
        assertThat(result.current()).isEqualTo(newer);
        assertThat(hooks.drains).isEqualTo(1);
        assertThat(hooks.pauses).containsExactly(150L);
    }

    @Test
    @DisplayName("新任务也结算且镜像仍有卡：补读后继续，不让卡悬着")
    void mirrorPendingContinues() {
        FakeHooks hooks = new FakeHooks();
        hooks.newer = task("completed");
        hooks.mirrorPending = true;

        TurnFollowLoop.SettledResult result = loop.afterSettled(hooks, task("completed"));

        assertThat(result.breakLoop()).isFalse();
        assertThat(hooks.drains).isEqualTo(2);
        assertThat(hooks.pauses).containsExactly(150L, 200L);
    }

    @Test
    @DisplayName("没有新任务、镜像也空：退出循环")
    void cleanSettledBreaks() {
        FakeHooks hooks = new FakeHooks();

        TurnFollowLoop.SettledResult result = loop.afterSettled(hooks, task("completed"));

        assertThat(result.breakLoop()).isTrue();
        assertThat(hooks.drains).isEqualTo(2);
        assertThat(hooks.pauses).containsExactly(150L);
    }

    private static AgentSession.Task task(String status) {
        return new AgentSession.Task("cmd-1", "turn-1", "run-1", status, null);
    }

    private static SessionEvent event() {
        return new SessionEvent(1, "e1", 1L, 0L, "interaction/requested", null, "turn-1", false, "{}");
    }

    private static final class FakeHooks implements TurnFollowLoop.SettledHooks {
        Map<String, SessionEvent> latePending = new LinkedHashMap<>();
        AgentSession.Task newer;
        boolean mirrorPending;
        int drains;
        int retrieves;
        int announces;
        final List<Long> pauses = new ArrayList<>();

        @Override
        public void pause(long millis) {
            pauses.add(millis);
        }

        @Override
        public void drain() {
            drains++;
        }

        @Override
        public void emitRetrieved() {
            retrieves++;
        }

        @Override
        public Map<String, SessionEvent> pending() {
            return latePending;
        }

        @Override
        public void announcePending(Map<String, SessionEvent> pending) {
            announces++;
        }

        @Override
        public AgentSession.Task latestTask(AgentSession.Task known) {
            return newer;
        }

        @Override
        public boolean mirrorHasPending() {
            return mirrorPending;
        }
    }
}
