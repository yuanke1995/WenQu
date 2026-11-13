package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 原生工具护栏：污点后破坏性命令/越界写必须 ask；未标脏时不能把每把工具都变成卡。 */
class AgentxNativeToolGuardTest {

    private static final List<String> ROOTS = List.of("/Users/me/work");

    @Test
    @DisplayName("标脏后 execute 的删除命令 -> ask；普通命令 -> allow")
    void taintedExecAsk() {
        AgentxTurnSlot slot = slot(taint(true));
        AgentxNativeToolGuard guard = guard(slot, AgentxNativeToolGuard.Kind.EXEC,
                ExecutionPolicy.of(PermissionPreset.STANDARD, null, "/Users/me/work", null));

        assertThat(decision(guard, Map.of("command", "rm -rf build")))
                .isEqualTo(PermissionBehavior.ASK);
        assertThat(decision(guard, Map.of("command", "ls -la")))
                .isEqualTo(PermissionBehavior.ALLOW);
        // 回归：heredoc 脚本里的 > 与引号文本不是重定向，标脏后也不该弹卡
        assertThat(decision(guard, Map.of("command",
                "cd /Users/me/work && python3 - <<'EOF'\n"
                        + "print(s.rstrip().endswith('</html>'))\n"
                        + "EOF")))
                .isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @DisplayName("标脏后完全权限档写工作区外 -> ask；工作区内 -> allow")
    void taintedOutsideWriteAsk() {
        AgentxTurnSlot slot = slot(taint(true));
        AgentxNativeToolGuard guard = guard(slot, AgentxNativeToolGuard.Kind.WRITE,
                ExecutionPolicy.of(PermissionPreset.AUTONOMOUS, null, "/Users/me/work", null));

        assertThat(decision(guard, Map.of("path", "/etc/hosts", "content", "x")))
                .isEqualTo(PermissionBehavior.ASK);
        assertThat(decision(guard, Map.of("path", "/Users/me/work/a.md", "content", "x")))
                .isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @DisplayName("没标脏时原生工具保持可执行（不是每把都弹卡）")
    void untaintedAllows() {
        AgentxTurnSlot slot = slot(taint(false));
        AgentxNativeToolGuard guard = guard(slot, AgentxNativeToolGuard.Kind.EXEC,
                ExecutionPolicy.of(PermissionPreset.STANDARD, null, "/Users/me/work", null));
        assertThat(decision(guard, Map.of("command", "rm -rf build")))
                .isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @DisplayName("审批卡理由与守卫同源：文件重定向算覆盖写，普通命令没有理由")
    void forcedAskNoteExplainsRedirect() {
        ExecutionPolicy p = ExecutionPolicy.of(PermissionPreset.STANDARD, "auto", "/Users/me/work", null);
        AgentxTaint t = taint(true);
        String note = AgentxNativeToolGuard.forcedAskNote(AgentxNativeToolGuard.Kind.EXEC,
                Map.of("command", "python3 -m http.server 8765 >/tmp/wq_preview.log 2>&1"), p, t, ROOTS);
        assertThat(note).isNotNull().contains("本轮已读取过外部内容").contains("覆盖");
        assertThat(AgentxNativeToolGuard.forcedAskNote(AgentxNativeToolGuard.Kind.EXEC,
                Map.of("command", "ls -la"), p, t, ROOTS)).isNull();
        // 没标脏就没有强制确认，理由也不该冒出来
        assertThat(AgentxNativeToolGuard.forcedAskNote(AgentxNativeToolGuard.Kind.EXEC,
                Map.of("command", "rm -rf build"), p, taint(false), ROOTS)).isNull();
    }

    @Test
    @DisplayName("选过「本次会话都允许」的工具不再弹卡；别的工具照问")
    void sessionAllowedToolSkipsAsk() {
        AgentxTurnSlot slot = slot(taint(true));
        AgentxNativeToolGuard exec = guard(slot, AgentxNativeToolGuard.Kind.EXEC,
                ExecutionPolicy.of(PermissionPreset.STANDARD, null, "/Users/me/work", null));
        assertThat(decision(exec, Map.of("command", "rm -rf build")))
                .isEqualTo(PermissionBehavior.ASK);

        slot.allowToolForSession("execute");
        assertThat(decision(exec, Map.of("command", "rm -rf build")))
                .isEqualTo(PermissionBehavior.ALLOW);

        // 按工具名放行：别把另一把工具也一起放过去
        AgentxNativeToolGuard write = guard(slot, AgentxNativeToolGuard.Kind.WRITE,
                ExecutionPolicy.of(PermissionPreset.AUTONOMOUS, null, "/Users/me/work", null));
        assertThat(write.getName()).isEqualTo("write_file");
        assertThat(decision(write, Map.of("path", "/etc/hosts", "content", "x")))
                .isEqualTo(PermissionBehavior.ASK);
    }

    private static PermissionBehavior decision(AgentxNativeToolGuard guard, Map<String, Object> input) {
        var ctx = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
        return guard.checkPermissions(input, ctx).block().getBehavior();
    }

    private static AgentxTaint taint(boolean marked) {
        AgentxTaint t = new AgentxTaint();
        if (marked) t.mark("read_file");
        return t;
    }

    private static AgentxTurnSlot slot(AgentxTaint taint) {
        AgentxTurnSlot slot = new AgentxTurnSlot();
        slot.begin(new AgentxTurnSlot.Turn("t1", null, null, null,
                ExecutionPolicy.of(PermissionPreset.STANDARD, "auto", "/Users/me/work", null), null, taint));
        return slot;
    }

    private static AgentxNativeToolGuard guard(AgentxTurnSlot slot, AgentxNativeToolGuard.Kind kind,
                                               ExecutionPolicy policy) {
        // policy 以轮上的那份为准（guard 读 slot.current().policy()）
        slot.begin(new AgentxTurnSlot.Turn("t1", null, null, null, policy, null,
                slot.current().taint()));
        // 名字按 kind 取真实工具名：会话级放行是按名字判的，拿 execute 冒充 write_file 就测不到
        return new AgentxNativeToolGuard(new FakeTool(nameOf(kind)), kind, slot, ROOTS);
    }

    private static String nameOf(AgentxNativeToolGuard.Kind kind) {
        return switch (kind) {
            case EXEC -> "execute";
            case WRITE -> "write_file";
            case EDIT -> "edit_file";
            case DATA -> "read_file";
        };
    }

    /** 只用于权限判定：callAsync 不会被这些用例触发。 */
    private static final class FakeTool extends ToolBase {
        FakeTool(String name) {
            super(name, "", Map.of("type", "object", "properties", Map.of()),
                    false, false, false, null, false, false);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("ok"));
        }
    }
}
