package com.wenqu.ai.agentx;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 提问工具不走「执行前确认」：默认 passthrough 会被权限引擎判成确认，提问就变成一张恢复不了的审批卡。 */
class AskUserToolPermissionTest {

    @Test
    @DisplayName("askUser 在任何权限模式下都直接放行，不要求确认")
    void askToolNeverRequiresConfirmation() {
        AskUserTool tool = new AskUserTool();
        for (PermissionMode mode : PermissionMode.values()) {
            var ctx = PermissionContextState.builder().mode(mode).build();
            PermissionBehavior behavior = tool.checkPermissions(Map.of("question", "选哪种录入方式？"), ctx)
                    .block()
                    .getBehavior();
            assertThat(behavior).as("mode=%s", mode).isEqualTo(PermissionBehavior.ALLOW);
        }
    }
}
