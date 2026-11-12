package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 深度执行 REST（内部仍是目标实体）。
 *
 * <p>对外只有一个入口：/deep。建目标 / 改目标 / 停止 / 继续 / 删除，没有「确认验收标准」这一族。
 * 目标一旦建立就自己往下跑，助手自报完成即停；计划批准是过程内的人在回路。
 */
@RestController
@RequestMapping("/api/ai/chat")
public class DeepController {

    private final GoalService goalService;
    private final com.wenqu.ai.service.AgentService agentService;

    public DeepController(GoalService goalService, com.wenqu.ai.service.AgentService agentService) {
        this.goalService = goalService;
        this.agentService = agentService;
    }

    @Operation(summary = "开始深度执行", description = "把一件事交给目标模式：{sessionId,objective}。"
            + "建行即入队，由调度器起轮；助手提交的证据通过核验后才自动停下。"
            + "同会话已有未结束目标时拒绝（先改它、继续它或删除它）。")
    @Permission("chat:goal")
    @PostMapping("/deep")
    public ResultJson<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        String uid = RequestUser.uid();
        String sessionId = str(body.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            return ResultJson.error("缺少 sessionId");
        }
        try {
            // 作答方与当轮问答同一口径：调用方带了就用它，没带回落平台默认智能体。
            // 目标行落空会让续跑轮掉回平台默认权限档（智能体 auto 不生效），所以这里必须补。
            String agentId = str(body.get("agentId"));
            if (agentId == null || agentId.isBlank()) {
                var def = agentService.defaultAgent();
                agentId = def == null ? null : def.getId();
            }
            Goal goal = goalService.start(sessionId, uid, agentId,
                    str(body.get("objective")), null);
            return ResultJson.ok(goalService.view(goal), "目标已开始");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "深度执行动作", description = "pause=停止（可再继续）；resume=继续；edit=改目标原文（{objective}，未完成也可改，保存后自动继续）；"
            + "cancel=取消（结束并留痕，终态卡可回看）；delete=删除记录（仅终态，界面不再回显）。"
            + "完成由模型 goal_submit 提交证据、平台核验；不需要用户逐条勾选验收标准。")
    @Permission("chat:goal")
    @PostMapping("/deep/{goalId}")
    public ResultJson<Map<String, Object>> act(
            @Parameter(description = "目标 ID") @PathVariable("goalId") String goalId,
            @RequestBody Map<String, Object> body) {
        String uid = RequestUser.uid();
        String action = str(body.get("action"));
        try {
            Goal goal = switch (action == null ? "" : action) {
                case "pause" -> goalService.pause(goalId, uid, null);
                case "resume" -> goalService.resume(goalId, uid, null);
                case "edit", "update" -> goalService.edit(goalId, uid, str(body.get("objective")), null);
                case "cancel" -> goalService.cancel(goalId, uid, null);
                case "delete" -> goalService.deleteRecord(goalId, uid, null);
                default -> throw new IllegalArgumentException("未知动作：" + action);
            };
            return ResultJson.ok(goalService.view(goal));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "当前深度执行", description = "按会话取当前目标（目标条回显与恢复用）。"
            + "重启/换副本后状态以库里为准。")
    @Permission("chat:goal")
    @GetMapping("/deep/pending")
    public ResultJson<Map<String, Object>> pending(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(goalService.pending(sessionId, RequestUser.uid()));
    }

    @Operation(summary = "会话当前/最近深度执行", description = "会话目标回显：当前/最近一条目标（读 DB）。"
            + "取消的目标仍回显（终态卡与过程回看要用）；删除记录（endReason=cleared）后才不再出现。")
    @Permission("chat:goal")
    @GetMapping("/session/{sessionId}/deep")
    public ResultJson<Map<String, Object>> sessionGoal(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        Goal goal = goalService.latestGoal(sessionId);
        // 无目标是常态，Map.of 不接受 null 值会抛 NPE
        if (goal == null || clearedRecord(goal)) {
            return ResultJson.ok(Collections.singletonMap("goal", null));
        }
        return ResultJson.ok(goalService.view(goal));
    }

    /** 删除记录后不再回显：终态 CANCELLED 且 endReason=cleared（delete 写这一档）。 */
    private static boolean clearedRecord(Goal goal) {
        return GoalService.CANCELLED.equals(goal.getStatus()) && "cleared".equals(goal.getEndReason());
    }

    @Operation(summary = "深度执行过程账本", description = "按 goal_id 聚合的事件（过程面板深链：一个目标覆盖多轮）；"
            + "afterId 用于断线增量读取。")
    @Permission("chat:goal")
    @GetMapping("/session/{sessionId}/deep/events")
    public ResultJson<List<Map<String, Object>>> goalEvents(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @Parameter(description = "目标 ID") @RequestParam("goalId") String goalId,
            @Parameter(description = "增量游标：只返回 id 大于它的账本行（断线重连用）")
            @RequestParam(value = "afterId", required = false, defaultValue = "0") long afterId) {
        return ResultJson.ok(goalService.goalEvents(sessionId, RequestUser.uid(), goalId, afterId));
    }

    // ==================== 内部：请求体取数（与 answersFromBody 同形的纯函数） ====================

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
