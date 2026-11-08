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
            + "建行即入队，由调度器起轮；助手提交的证据通过核验后才自动停下。"
            + "同会话已有未结束目标时拒绝（先改它、继续它或删除它）。")
    @Permission("chat:goal")
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

    @Operation(summary = "目标动作", description = "pause=停止（可再继续）；resume=继续；edit=改目标原文（{objective}，未完成也可改，保存后自动继续）；"
            + "cancel=取消（结束并留痕，终态卡可回看）；delete=删除记录（仅终态，界面不再回显）。"
            + "clear 为旧客户端的兼容写法：活目标按取消、终态按删除处理。"
            + "完成由模型 goal_submit 提交证据、平台核验；不需要用户逐条勾选验收标准。")
    @Permission("chat:goal")
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
                case "cancel" -> goalService.cancel(goalId, uid, null);
                case "delete", "clearRecord" -> goalService.deleteRecord(goalId, uid, null);
                case "clear" -> goalService.clear(goalId, uid, null);
                default -> throw new IllegalArgumentException("未知动作：" + action);
            };
            return ResultJson.ok(goalService.view(goal));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "当前目标", description = "按会话取当前目标（目标条回显与恢复用）。"
            + "重启/换副本后状态以库里为准。")
    @Permission("chat:goal")
    @GetMapping("/goal/pending")
    public ResultJson<Map<String, Object>> pending(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(goalService.pending(sessionId, RequestUser.uid()));
    }

    @Operation(summary = "会话当前/最近目标", description = "会话目标回显：当前/最近一条目标（读 DB）。"
            + "取消的目标仍回显（终态卡与过程回看要用）；删除记录（endReason=cleared）后才不再出现。")
    @Permission("chat:goal")
    @GetMapping("/session/{sessionId}/goal")
    public ResultJson<Map<String, Object>> sessionGoal(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        Goal goal = goalService.latestGoal(sessionId);
        // 无目标是常态，Map.of 不接受 null 值会抛 NPE
        if (goal == null || clearedRecord(goal)) {
            return ResultJson.ok(Collections.singletonMap("goal", null));
        }
        return ResultJson.ok(goalService.view(goal));
    }

    /** 删除记录后不再回显：终态 CANCELLED 且 endReason=cleared（旧版 clear 的存量行也是这一档）。 */
    private static boolean clearedRecord(Goal goal) {
        return GoalService.CANCELLED.equals(goal.getStatus()) && "cleared".equals(goal.getEndReason());
    }

    @Operation(summary = "目标过程账本", description = "按 goal_id 聚合的事件（过程面板深链：一个目标覆盖多轮）；"
            + "afterId 用于断线增量读取。")
    @Permission("chat:goal")
    @GetMapping("/session/{sessionId}/goal/events")
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
