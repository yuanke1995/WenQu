package com.wenqu.ai.agentx.team;

import com.wenqu.ai.agentx.AgentxTurnSlot;
import com.wenqu.ai.service.SessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 团队任务依赖工具（唯一为框架缺口补的一把薄 shim）。
 *
 * <p>框架 {@code TeamTool.createTask} 不暴露 {@code blockedBy}、{@code TeamClient} 也没有独立的
 * addDependency，所以模型无法经框架工具声明“B 依赖 A”。这把工具把 {@code TeamTaskGateway.addDependency}
 * 暴露给模型：在框架 BaseStore 的任务条目上做一次 CAS 追加，不新造任务板引擎。
 *
 * @author yuanke
 */
public class TeamTaskDependTool {

    private final WenquTeamGateway gateway;
    private final SessionService sessionService;
    private final AgentxTurnSlot slot;

    public TeamTaskDependTool(WenquTeamGateway gateway, SessionService sessionService, AgentxTurnSlot slot) {
        this.gateway = gateway;
        this.sessionService = sessionService;
        this.slot = slot;
    }

    private String rootId() {
        AgentxTurnSlot.Turn t = slot.current();
        String sid = t == null ? "" : t.hooks().sessionId();
        if (sid == null || sid.isBlank()) return sid;
        var s = sessionService.sessionById(sid);
        if (s != null && s.getRootSessionId() != null && !s.getRootSessionId().isBlank()) {
            return s.getRootSessionId();
        }
        return sid;
    }

    @Tool(description = "给团队任务板上的两个任务加依赖：前置任务完成后，后继任务才可认领。用任务标题或 id。")
    public String team_task_depend(
            @ToolParam(description = "前置任务标题（或 id）") String from,
            @ToolParam(description = "后继任务标题（或 id）") String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return "错误：需要前置任务和后继任务。";
        }
        String root = rootId();
        if (root == null || root.isBlank()) return "当前会话不是团队会话。";
        String err = gateway.addDependency(root, from, to);
        return err == null ? ("已登记依赖：「" + to + "」需等「" + from + "」完成") : ("依赖未加上：" + err);
    }
}
