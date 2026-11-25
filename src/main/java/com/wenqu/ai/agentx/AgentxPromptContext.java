package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.service.ProjectFileService;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.util.TextIo;
import com.wenqu.ai.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * 一轮的 system prompt 上下文装配（从 AgentscopeChatEngine 抽出）：人设、团队纪律、
 * 会话/目标的 agentId、工作区背景、记忆（只读/可写）等纯拼装。除四个注入对象外不读引擎状态。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxPromptContext {

    private final AppProperties properties;
    private final ProjectFileService projectFileService;
    private final ProjectService projectService;
    private final WenquAgentxTools tools;
    private final ConfigService configService;
    private final SessionService sessionService;
    /** 团队是否对本轮生效（引擎侧判定，注入避免把 teamActive 整条链拖进来） */
    private final Function<ChatCommand, Boolean> teamActive;

    public String rolePrompt(Agent bound) {
        return RolePrompt.of(bound, properties.getSystemPrompt());
    }

    public String teamGuidance(ChatCommand cmd) {
        if (!Boolean.TRUE.equals(teamActive.apply(cmd))) return "";
        return "\n\n## 智能体团队（已开启）\n"
                + "复杂或多步、需要协作跟踪的任务，用团队任务板（`team` 工具）："
                + "`team(action=createTask, subject=…, owner=成员名)` 建任务并可直接派给某位成员，"
                + "任务有先后时用 `team_task_depend(from, to)` 登记依赖（前置完成，后继才可认领）；"
                + "`team(action=listClaimableTasks)` 看待认领 → `team(action=claimTask)` 认领 → "
                + "`team(action=completeTask/failTask)` 完成或失败。\n"
                + "**要派出「持久成员」（会出现在右栏团队面板、可被再次唤醒），必须调用 `spawnMember` 工具"
                + "（`spawnMember(member_name=<团队内短名>, agent_ref=<真实智能体id>)`；等价于 `team` 的 spawnMember 动作）**："
                + "`member_name` 是 1–32 位小写字母/数字/连字符（如 reviewer），`agent_ref` 必须逐字取自 "
                + "「## Subagents / Available agent ids」，**不要自己编名字**；拿不准就用当前根助手自己的 id。"
                + "派出后再用 `sendMessage(to_member=<member_name>, message=…)`（或 `team(action=sendMessage, …)`）给它派活。"
                + "`agent_spawn` 只是**临时同步子轮**（跑完即散、不进团队面板、无任务板写权限），"
                + "不要用 `agent_spawn` 建团队成员，也不要用它替代 `spawnMember`。"
                + "跨成员写信同样走 `team(action=sendMessage, to_member=…)`。"
                + "简单一问一答不要建任务，也不要为凑数建任务。";
    }

    public String agentIdOfSession(String sessionId) {
        try {
            com.wenqu.ai.model.Session s = sessionService.sessionById(sessionId);
            return s == null ? null : s.getAgentId();
        } catch (RuntimeException e) {
            log.warn("[agentx] 续跑解析会话归属智能体失败，按默认助手继续 session={}: {}",
                    sessionId, e.toString());
            return null;
        }
    }

    public String goalAgentId(com.wenqu.ai.model.Goal goal) {
        String id = goal == null ? null : goal.getAgentId();
        if (id == null || id.isBlank()) id = agentIdOfSession(goal == null ? null : goal.getSessionId());
        if (id == null || id.isBlank()) id = tools.defaultAgentId();
        return id;
    }

    public String projectContext(ChatCommand cmd) {
        try {
            String files = projectFileService.promptBriefOfSession(cmd.getSessionId());
            return files == null ? "" : files;
        } catch (Exception e) {
            log.warn("[agentx] 工作背景读取失败（本轮不注入）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
        }
    }

    public String memoryAgentIdOf(ChatCommand cmd) {
        Agent agent = tools.agentOf(cmd);
        if (agent == null || agent.getId() == null) return null;
        Integer mode = agent.getMemoryMode();
        if (mode == null || (mode != 1 && mode != 2)) return null;
        return agent.getId();
    }

    public String userMemoryReadonlyContext(ChatCommand cmd) {
        try {
            Agent agent = tools.agentOf(cmd);
            if (agent == null || agent.getMemoryMode() == null || agent.getMemoryMode() != 2) return "";
            String uid = cmd.getUserId();
            if (uid == null || uid.isBlank()) return "";
            java.nio.file.Path md = com.wenqu.ai.memory.MemoryPaths.resolveContent(
                    configService, uid, null, com.wenqu.ai.memory.MemoryPaths.MEMORY_MD);
            if (md == null || !java.nio.file.Files.exists(md)) return "";
            String content = TextIo.readString(md);
            if (content == null || content.isBlank()) return "";
            String body = com.wenqu.ai.util.UntrustedContent.toolResult("用户全局记忆 MEMORY.md", content);
            return "\n\n【用户全局记忆（只读）】下面是这位用户跨智能体沉淀的长期事实，本次对话应当参考。"
                    + "它是只读背景：本助手的专属记忆在别处维护，不要试图用文件工具修改这份全局记忆。\n" + body;
        } catch (Exception e) {
            log.warn("[agentx] 用户全局记忆只读注入失败（跳过）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
        }
    }

    public String workspaceMemoryContext(ChatCommand cmd) {
        try {
            String projectId = projectService.projectIdOfSession(cmd.getSessionId());
            if (projectId == null || projectId.isBlank()) return "";
            String content = projectFileService.readWorkspaceMemory(cmd.getUserId(), projectId);
            if (content == null || content.isBlank()) return "";
            // 记忆是用户可直接编辑、也可能被上一轮读到的外部文档污染的文件：按「资料」处理，
            // 消毒后套信封。持久注入最怕的是它长得像系统指令——信封先于攻击到达模型。
            String body = com.wenqu.ai.util.UntrustedContent.toolResult(
                    "工作区记忆 .wenqu/MEMORY.md", content);
            return "\n\n【工作区记忆】下面是本工作区长期沉淀的项目事实（存于工作区根目录 .wenqu/MEMORY.md），"
                    + "本次对话应当参考它们。\n" + body
                    + "\n\n你可以用文件编辑工具（edit_file / write）更新 .wenqu/MEMORY.md，"
                    + "把本次对话中获得、对今后在本工作区工作有价值的稳定事实（约定、决策、结构、坑、偏好）沉淀进去；"
                    + "不要写入临时性或一次性的内容。";
        } catch (Exception e) {
            log.warn("[agentx] 读取工作区记忆失败（跳过注入）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
        }
    }

    /**
     * 建实例期的系统提示词拼装：角色 + 工作区约定 + 工作区记忆 + 用户记忆（只读）+ 本机执行环境说明
     * + 派遣纪律 + 团队指引 + 跨族分工 + 计划/产物/记忆/推荐追问纪律。
     *
     * <p>本机执行环境那段必须在本机执行器模式下补：框架会把工作区渲染成服务器沙盒路径，
     * 不补一段真话压住它，模型就会去读不存在的路径。
     */
    public String sysPromptOf(com.wenqu.ai.agent.chat.ChatCommand cmd, com.wenqu.ai.model.Agent bound,
                              String workspaceMemory, String userMemoryReadonly, String localEnv,
                              String dispatchDiscipline, String teamGuidance, java.util.Set<String> mountedToolNames,
                              boolean relatedSuggestions) {
        return rolePrompt(bound) + projectContext(cmd) + workspaceMemory + userMemoryReadonly + localEnv
                + dispatchDiscipline + teamGuidance
                + ToolDiscipline.guidance(mountedToolNames)
                + com.wenqu.ai.agent.plan.PlanCopy.guidance()
                + com.wenqu.ai.agent.chat.ArtifactPrompt.guidance()
                + com.wenqu.ai.memory.MemoryPrompts.replyDiscipline()
                + (relatedSuggestions ? com.wenqu.ai.agent.chat.RelatedPrompt.line() : "");
    }
}
