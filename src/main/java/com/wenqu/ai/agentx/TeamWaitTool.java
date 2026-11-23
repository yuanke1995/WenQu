package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 团队消息等待（框架原生挂起）：成员调用后本轮挂起，等另一成员 `team_send` 的消息送达时
 * 由 {@code FrameworkSessions.respondRaw} 以文本答案恢复，模型在下一步看到那条消息继续工作。
 *
 * <p>与 askUser 同款挂起路径（见 {@link AskUserTool}）：异常必须是框架的 ToolSuspendException，
 * 不能靠阻塞工具线程（Spring AI 会把异常包掉，挂起信号到不了框架）。
 *
 * @author yuanke
 */
public final class TeamWaitTool extends ToolBase {

    public static final String NAME = "team_wait";

    public TeamWaitTool() {
        super(NAME, "等待团队里其他成员发来的下一条消息。挂起当前轮，消息到达后自动继续；"
                + "没有其他成员要给你任务时不要调用。",
                Map.of("type", "object",
                        "properties", Map.of("reason", Map.of("type", "string",
                                "description", "简短说明在等什么（如：等 reviewer 的审计结论）")),
                        "required", java.util.List.of("reason")),
                false, false, false, null, false, false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.error(new ToolSuspendException(JSON.toJSONString(
                java.util.Map.of("kind", "team_wait", "reason",
                        String.valueOf(input.getOrDefault("reason", "等待团队消息"))))));
    }

    /** 等待不改变状态、也没有外部副作用，权限直接放行（与 AskUserTool 同一理由）。 */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("等待团队消息不改变状态"));
    }
}
