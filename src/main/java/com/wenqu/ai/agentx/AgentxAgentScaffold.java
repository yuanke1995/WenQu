package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ConfigService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/**
 * 建实例的脚手架（从 AgentscopeChatEngine 的 buildAgent 抽出）：状态目录、RuntimeContext、
 * 框架工具族装配与目标工具注册。
 *
 * <p>状态目录用相对路径（按工作目录 user.dir 解析，等价于种子默认值 user.dir/user-data/agentscope）；
 * 配置被清空或缺失时退回默认，避免 Path.of("") 抛 InvalidPathException 把整轮打挂。
 *
 * <p>框架把工具派到自己的弹性线程：身份与本轮检索开关覆盖必须在发起线程取快照、在工具内重放，
 * 不重放就是检索恒空且不报错。降级出口按轮走——实例复用后 channel 是建实例那一轮的，必须从槽位现取。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxAgentScaffold {

    private final ConfigService configService;
    private final WenquAgentxTools tools;
    private final FrameworkSessions sessions;
    private final GoalService goalService;
    private final SessionService sessionService;

    /** 脚手架：状态目录、运行时上下文、toolkit、分组名单。 */
    public record Scaffold(Path stateDir, RuntimeContext ctx, Toolkit toolkit,
                           java.util.Set<String> groupedNames) {
    }

    public Scaffold build(ChatCommand cmd, ExecutionPolicy policy, AgentxTurnSlot slot,
                          boolean sandboxEnabled, java.util.function.BooleanSupplier teamActive,
                          com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway) {
        Path stateDir = stateDir();
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId())
                .sessionId(cmd.getSessionId())
                .build();
        Toolkit toolkit = new Toolkit();
        boolean interactive = !cmd.isGuestMode() && !cmd.isApiKey() && AgentxTextUtil.interactiveTurn(cmd);
        boolean sandboxReady = sandboxEnabled && sessions.sandboxAttached(sessions.key(cmd));
        List<Object> holders = tools.frameworkTools(cmd, policy, slot, (code, message) -> {
            log.info("[agentx] 降级 {}: {}", code, message);
            AgentxTurnSlot.Turn t = slot.current();
            if (t != null) t.channel().emit(RunEventType.WARN, message);
        }, interactive, sandboxReady, sandboxEnabled);
        // 目标模式的控制工具常驻注册：实例按会话常驻，目标可能在建实例之后才建立，
        // 不能等到目标建立再往 Toolkit 里加工具（那需要重建实例）。非目标轮调用会落空返回。
        holders.add(new GoalTools(goalService, slot));
        // 团队任务依赖 shim：框架 TeamTool.createTask 不暴露 blockedBy，模型经这一把声明 DAG 依赖。
        if (teamActive.getAsBoolean() && teamGateway != null) {
            tools.mountRawHolders(holders, policy, interactive, slot,
                    new com.wenqu.ai.agentx.team.TeamTaskDependTool(teamGateway, sessionService, slot));
        }
        java.util.Set<String> groupedNames = tools.registerWithGroups(toolkit, holders);
        // 工具表随实例跨轮稳定，登记到槽位：脚本子调用要把框架申报只读的那几把也纳进名册
        slot.noteToolkit(toolkit);
        // 这一轮建了组的名单按会话记下来：每轮的激活集只在这份名单里增删
        sessions.noteGroupedSurface(sessions.key(cmd), groupedNames);
        return new Scaffold(stateDir, ctx, toolkit, groupedNames);
    }

    /** 状态目录：相对路径按 user.dir 解析；空/缺失退回默认。 */
    private Path stateDir() {
        String raw = configService.get("chat.agentscopeStateDir");
        if (raw == null || raw.isBlank()) raw = "user-data/agentscope";
        return Path.of(raw);
    }
}
