package com.wenqu.ai.agentx;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重复调用闸的计数口径：连续第 3 次相同才拒，换个调用或换一轮重开。
 */
class AgentxRepeatGuardTest {

    private static ToolUseBlock call(String name, Map<String, Object> input) {
        return new ToolUseBlock("id-" + name, name, input);
    }

    private static Map<String, Object> args(String command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        return m;
    }

    @Test
    void 同一工具同一参数连续第3次起被拒() {
        AgentxRepeatGuard guard = new AgentxRepeatGuard();
        guard.beginTurn(null);
        ToolUseBlock ls = call("execute", args("ls -la /tmp"));

        assertThat(guard.denyThis(ls)).isFalse();   // 第 1 次：首发
        assertThat(guard.denyThis(ls)).isFalse();   // 第 2 次：可能是有意的重试
        assertThat(guard.denyThis(ls)).isTrue();    // 第 3 次：空转
        assertThat(guard.denyThis(ls)).isTrue();    // 之后继续拒
    }

    @Test
    void 中间插进别的调用就重新计数() {
        AgentxRepeatGuard guard = new AgentxRepeatGuard();
        guard.beginTurn(null);
        ToolUseBlock a = call("execute", args("ls -la /tmp"));
        ToolUseBlock b = call("execute", args("ls -la /tmp/a.png"));

        assertThat(guard.denyThis(a)).isFalse();
        assertThat(guard.denyThis(a)).isFalse();
        assertThat(guard.denyThis(b)).isFalse();
        assertThat(guard.denyThis(a)).isFalse();    // b 之后这一段重新从 1 数
        assertThat(guard.denyThis(a)).isFalse();
        assertThat(guard.denyThis(a)).isTrue();
    }

    @Test
    void 工具名不同不算重复() {
        AgentxRepeatGuard guard = new AgentxRepeatGuard();
        guard.beginTurn(null);
        ToolUseBlock a = call("execute", args("ls"));
        ToolUseBlock b = call("list_files", args("ls"));

        assertThat(guard.denyThis(a)).isFalse();
        assertThat(guard.denyThis(b)).isFalse();
        assertThat(guard.denyThis(a)).isFalse();
        assertThat(guard.denyThis(b)).isFalse();
    }

    @Test
    void 参数键序不同视为同一次调用() {
        AgentxRepeatGuard guard = new AgentxRepeatGuard();
        guard.beginTurn(null);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("command", "ls");
        first.put("timeoutMs", 1000);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("timeoutMs", 1000);
        reversed.put("command", "ls");

        assertThat(guard.denyThis(call("execute", first))).isFalse();
        assertThat(guard.denyThis(call("execute", reversed))).isFalse();
        assertThat(guard.denyThis(call("execute", first))).isTrue();
    }

    @Test
    void 新的一轮把计数归零() {
        AgentxRepeatGuard guard = new AgentxRepeatGuard();
        guard.beginTurn(null);
        ToolUseBlock ls = call("execute", args("ls"));

        guard.denyThis(ls);
        guard.denyThis(ls);
        assertThat(guard.denyThis(ls)).isTrue();

        guard.beginTurn(null);
        assertThat(guard.denyThis(ls)).isFalse();
        assertThat(guard.denyThis(ls)).isFalse();
        assertThat(guard.denyThis(ls)).isTrue();
    }
}
