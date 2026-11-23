package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.team.AgentTeamService;
import com.wenqu.ai.agent.team.TeamTaskService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

/**
 * 团队任务板工具（挂载在团队会话轮；名字用 team_task_* 前缀，避开框架保留的 task_list/task_output/task_cancel）。
 *
 * <p><b>一律用「任务标题」而不是内部 UUID 作为入参</b>：模型和工具卡面向人，UUID 既难读也容易把工具卡
 * 塞满乱码；标题在同一任务板内唯一，内部解析成 id 再落库。
 *
 * @author yuanke
 */
public class TeamTaskTools {

    private final TeamTaskService taskService;
    private final AgentTeamService teamService;
    private final AgentxTurnSlot slot;

    public TeamTaskTools(TeamTaskService taskService, AgentTeamService teamService, AgentxTurnSlot slot) {
        this.taskService = taskService;
        this.teamService = teamService;
        this.slot = slot;
    }

    private String sessionId() {
        AgentxTurnSlot.Turn t = slot.current();
        return t == null ? "" : t.hooks().sessionId();
    }

    private String root() {
        String s = sessionId();
        return s == null || s.isBlank() ? s : teamService.rootOf(s);
    }

    @Tool(description = "在团队任务板上建一个任务（给标题，可选说明）。复杂协作先拆任务，再决定依赖与认领。")
    public String team_task_create(
            @ToolParam(description = "任务标题（简短、可验收）") String subject,
            @ToolParam(description = "任务说明（可空）") String description) {
        var v = taskService.create(root(), subject, description);
        return v == null ? "任务创建失败（标题不能为空）"
                : "已建任务「" + v.subject() + "」，当前状态 " + v.state();
    }

    @Tool(description = "给两个任务加依赖：前置任务完成后，后继任务才可认领。用任务标题指定。")
    public String team_task_depend(
            @ToolParam(description = "前置任务标题") String from,
            @ToolParam(description = "后继任务标题") String to) {
        String fromId = taskService.resolveTaskId(root(), from);
        String toId = taskService.resolveTaskId(root(), to);
        if (fromId == null) return "找不到前置任务「" + from + "」，请先用 team_task_list 看任务标题。";
        if (toId == null) return "找不到后继任务「" + to + "」，请先用 team_task_list 看任务标题。";
        String err = taskService.depend(root(), fromId, toId);
        return err == null ? "已登记依赖：「" + to + "」需等「" + from + "」完成" : "依赖未加上：" + err;
    }

    @Tool(description = "认领一个已就绪(READY)的任务（用任务标题）。")
    public String team_task_claim(@ToolParam(description = "任务标题") String task) {
        String id = taskService.resolveTaskId(root(), task);
        if (id == null) return "找不到任务「" + task + "」，请先用 team_task_list 看任务标题。";
        String err = taskService.claim(root(), id, sessionId(), teamService.shortName(sessionId()));
        return err == null ? "已认领任务「" + task + "」" : "认领失败：" + err;
    }

    @Tool(description = "完成或失败一个任务（用任务标题）；完成会自动解锁下游，失败不会。")
    public String team_task_complete(
            @ToolParam(description = "任务标题") String task,
            @ToolParam(description = "结果摘要") String result,
            @ToolParam(description = "true=失败，false=完成") boolean failed) {
        String id = taskService.resolveTaskId(root(), task);
        if (id == null) return "找不到任务「" + task + "」，请先用 team_task_list 看任务标题。";
        String err = taskService.complete(root(), id, result, failed);
        return err == null ? "任务「" + task + "」已记为" + (failed ? "失败" : "完成")
                : "更新失败：" + err;
    }

    @Tool(description = "查看团队任务板的当前状态（每个任务的标题、状态、认领人、未完成前置数）。")
    public String team_task_list() {
        Map<String, Object> graph = taskService.graph(root());
        List<?> nodes = (List<?>) graph.get("nodes");
        if (nodes == null || nodes.isEmpty()) return "任务板还是空的。";
        StringBuilder sb = new StringBuilder("团队任务板：\n");
        for (Object o : nodes) {
            TeamTaskService.TaskView v = (TeamTaskService.TaskView) o;
            sb.append("- 「").append(v.subject()).append("」 [").append(label(v.state())).append(']');
            if (v.ownerName() != null && !v.ownerName().isBlank()) sb.append(" 认领人:").append(v.ownerName());
            if (v.blockedCount() > 0) sb.append(" 待前置:").append(v.blockedCount());
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 状态词转人话（工具卡/回复里不出现英文状态码）。 */
    private static String label(String state) {
        return switch (state == null ? "" : state) {
            case "PENDING" -> "待前置";
            case "READY" -> "可认领";
            case "IN_PROGRESS" -> "执行中";
            case "COMPLETED" -> "已完成";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            default -> state;
        };
    }
}
