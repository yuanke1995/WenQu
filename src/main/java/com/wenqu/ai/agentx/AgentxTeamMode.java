package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;

/**
 * 建实例期的团队模式（从 AgentscopeChatEngine 的 buildAgent 抽出）。
 *
 * <p>框架原生团队模式：自动挂 TeamsMiddleware（团队提示/看板摘要/唤醒桥）并注册角色裁剪的
 * team 工具。根=lead，成员=worker；任务板/邮箱/名册全部由框架 TeamClient 承担。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxTeamMode {

    public void apply(HarnessAgent.Builder agentBuilder, ChatCommand cmd,
                      com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway) {
        if (teamGateway == null) return;
        String root = cmd.getTeamRootSessionId() != null && !cmd.getTeamRootSessionId().isBlank()
                ? cmd.getTeamRootSessionId() : cmd.getSessionId();
        boolean isLead = cmd.getMemberSessionId() == null || cmd.getMemberSessionId().isBlank();
        String role = isLead ? com.wenqu.ai.agentx.team.WenquTeamGateway.LEAD : cmd.getMemberName();
        teamGateway.ensureTeam(root, cmd.getAgentId());
        agentBuilder.teamsMode(teamGateway.client(), teamGateway.contextFor(root, role, isLead), cmd.getSessionId());
    }
}
