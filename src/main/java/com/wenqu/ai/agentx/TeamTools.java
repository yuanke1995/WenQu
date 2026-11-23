package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.team.AgentTeamService;
import com.wenqu.ai.service.SessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

/**
 * 智能体团队工具（会话级开关，不进系统设置；只在团队会话轮挂载）。
 *
 * <p>三把工具都从当轮槽位现取 root/session，实例按会话常驻时不捕获上一轮的 channel。
 *
 * @author yuanke
 */
public class TeamTools {

    private final AgentTeamService teamService;
    private final SessionService sessionService;
    private final AgentxTurnSlot slot;

    public TeamTools(AgentTeamService teamService, SessionService sessionService, AgentxTurnSlot slot) {
        this.teamService = teamService;
        this.sessionService = sessionService;
        this.slot = slot;
    }

    private String sessionId() {
        AgentxTurnSlot.Turn t = slot.current();
        return t == null ? "" : t.hooks().sessionId();
    }

    private String rootId() {
        String s = sessionId();
        if (s == null || s.isBlank()) return s;
        return teamService.rootOf(s);
    }

    private String uid() {
        AgentxTurnSlot.Turn t = slot.current();
        return t == null ? "" : t.hooks().userId();
    }

    @Tool(description = "列出本团队的全部成员（名字、会话 id、在线状态、当前任务）。只在团队模式可用。")
    public String team_list() {
        String root = rootId();
        if (root == null || root.isBlank()) return "当前会话不是团队会话。";
        List<AgentTeamService.Member> members = teamService.listMembers(root, uid());
        if (members.isEmpty()) return "团队目前没有成员。用 agent_spawn 派出一位助手即成为成员。";
        StringBuilder sb = new StringBuilder("团队成员（lead 为根，其余为可继续成员）：\n");
        for (AgentTeamService.Member m : members) {
            sb.append("- ").append(m.name() == null ? m.sessionId() : m.name())
                    .append(" [").append(m.state() == null ? "OFFLINE" : m.state()).append(']')
                    .append(" session=").append(m.sessionId()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "给团队里的另一位成员发消息（成员在跑时下一模型步读到，离线时落到它的收件箱）。agent 用 list 中的名字或 session id。")
    public String team_send(
            @ToolParam(description = "目标成员名或 session id（lead 表示根）") String agent,
            @ToolParam(description = "要发给它的消息内容") String message) {
        if (agent == null || agent.isBlank()) return "错误：agent 不能为空。";
        if (message == null || message.isBlank()) return "错误：message 不能为空。";
        String root = rootId();
        boolean ok = teamService.sendMessage(uid(), root, sessionId(), agent, message);
        AgentxTurnSlot.Turn t = slot.current();
        if (t != null && ok) {
            t.channel().emit(com.wenqu.ai.engine.RunEventType.TEAM_MESSAGE,
                    com.alibaba.fastjson2.JSON.toJSONString(
                            java.util.Map.of("from", sessionId(), "to", agent, "text", message)));
        }
        return ok ? ("消息已送给 " + agent + "。") : ("没能送达 " + agent + "：它不是本团队成员。");
    }

}
