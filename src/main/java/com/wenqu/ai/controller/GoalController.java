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

/**
 * 目标模式 REST。
 *
 * <p>形状对齐 DSH 的 goal：建目标 / 改目标 / 停止 / 继续 / 删除，没有「确认验收标准」这一族。
 * 目标一旦建立就自己往下跑，助手自报完成即停；用户的动作只剩三个。
 */
@RestController
@RequestMapping("/api/ai/chat")
public class GoalController {

    private final GoalService goalService;

    public GoalController(GoalService goalService) {
        this.goalService = goalService;
    }

    @Operation(summary = "创建目标", description = "把一件事交给目标模式：{sessionId,objective}。"
            + "建行即开始推进，不弹确认卡、不要验收标准；助手完成后自动停下。"
            + "同会话已有未结束目标时拒绝（先改它、继续它或删除它）。")
    @PostMapping("/goal")
    public ResultJson<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        String uid = RequestUser.uid();
        String sessionId = str(body.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            return ResultJson.error("缺少 sessionId");
        }
        try {
            Goal goal = goalService.start(sessionId, uid, str(body.get("agentId")),
                    str(body.get("objective")), null);
            return ResultJson.ok(goalService.view(goal), "目标已开始");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "目标动作", description = "pause=停止（可再继续）；resume=继续；"
            + "edit=改目标原文（{objective}）；clear=删除（界面撤下，历史保留）。"
            + "确认验收标准/现在验收/人工勾选一律退役——目标模式没有这些步骤。")
    @PostMapping("/goal/{goalId}")
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
                case "clear", "cancel", "delete" -> goalService.clear(goalId, uid, null);
                default -> throw new IllegalArgumentException("未知动作：" + action);
            };
            return ResultJson.ok(goalService.view(goal));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "当前目标", description = "按会话取当前目标（目标条回显与恢复用）。"
            + "重启/换副本后状态以库里为准。")
    @GetMapping("/goal/pending")
    public ResultJson<Map<String, Object>> pending(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(goalService.pending(sessionId, RequestUser.uid()));
    }

    @Operation(summary = "会话当前/最近目标", description = "右栏「目标」面板回显：当前/最近一条目标（读 DB）。"
            + "已删除（CANCELLED）的目标不再回显——删了就不该在面板里又冒出来。")
    @GetMapping("/session/{sessionId}/goal")
    public ResultJson<Map<String, Object>> sessionGoal(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        Goal goal = goalService.latestGoal(sessionId);
        if (goal == null || GoalService.CANCELLED.equals(goal.getStatus())) {
            // 无目标是常态，Map.of 不接受 null 值会抛 NPE
            return ResultJson.ok(Collections.singletonMap("goal", null));
        }
        return ResultJson.ok(goalService.view(goal));
    }

    @Operation(summary = "目标过程账本", description = "按 goal_id 聚合的事件（过程面板深链：一个目标覆盖多轮）。")
    @GetMapping("/session/{sessionId}/goal/events")
    public ResultJson<List<Map<String, Object>>> goalEvents(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @Parameter(description = "目标 ID") @RequestParam("goalId") String goalId) {
        return ResultJson.ok(goalService.goalEvents(sessionId, RequestUser.uid(), goalId));
    }

    // ==================== 内部：请求体取数（与 answersFromBody 同形的纯函数） ====================

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
