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

import java.util.List;
import java.util.Map;

/**
 * 目标模式 REST（与 tool-approval / ask-user / plan-approval 三族逐字同形）。
 *
 * <p>四张卡不许互相顶班：计划卡说「打算怎么做」，清单说「现在做到哪」，提问卡问「这一步你拍不拍板」，
 * 目标卡定「这件事什么算完、还剩多少预算」——所以它是独立的一族端点，不挂靠 ask-user。
 */
@RestController
@RequestMapping("/api/ai/chat")
public class GoalController {

    private final GoalService goalService;

    public GoalController(GoalService goalService) {
        this.goalService = goalService;
    }

    @Operation(summary = "设为目标", description = "把一件事设为目标：{sessionId,objective,criteria:[{kind,text,verifierId,verifierParams}],"
            + "budget:{turns,tokens,toolCalls,minutes},action:\"start\"}。创建后进「待确认」，用户在目标卡上逐条"
            + "勾选验收标准并确认才开始（确认只能由用户点，平台没有自动放行这条路）。预算只往下收：请求值超过"
            + "平台默认/个人上限时按上限执行。")
    @PostMapping("/goal")
    public ResultJson<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        String uid = RequestUser.uid();
        String sessionId = str(body.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            return ResultJson.error("缺少 sessionId");
        }
        if (!goalService.enabled()) {
            return ResultJson.error("目标模式未开启（平台开关 chat.goalMode.enabled 为关）");
        }
        try {
            Goal goal = goalService.start(sessionId, uid, str(body.get("agentId")),
                    str(body.get("objective")),
                    listOf(body.get("criteria")),
                    mapOf(body.get("budget")),
                    null);
            return ResultJson.ok(goalService.view(goal, null), "目标已创建，等待确认验收标准");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "目标动作", description = "confirmCriteria=逐条确认验收标准并开始；pause=暂停；resume=继续"
            + "（预算仍满则拒绝）；extendBudget=加预算；verifyNow=现在验收；finish=预算内主动收尾；cancel=取消。"
            + "markManual=人工核验勾选（{key,outcome:PASSED|FAILED}）。")
    @PostMapping("/goal/{goalId}")
    public ResultJson<Map<String, Object>> act(
            @Parameter(description = "目标 ID") @PathVariable("goalId") String goalId,
            @RequestBody Map<String, Object> body) {
        String uid = RequestUser.uid();
        String action = str(body.get("action"));
        try {
            Goal goal = switch (action == null ? "" : action) {
                case "confirmCriteria" -> goalService.confirmCriteria(goalId, uid,
                        listOf(body.get("criteria")), null);
                case "pause" -> goalService.pause(goalId, uid, null);
                case "resume" -> goalService.resume(goalId, uid, null);
                case "extendBudget" -> goalService.extendBudget(goalId, uid,
                        intOf(body.get("extraTurns")), longOf(body.get("extraTokens")), null);
                case "verifyNow" -> {
                    goalService.verifyNow(goalId, uid, null);
                    yield goalService.get(goalId);
                }
                case "markManual" -> goalService.markManual(goalId, uid,
                        str(body.get("key")), str(body.get("outcome")), null);
                case "finish" -> goalService.finishIncomplete(goalId, uid, null);
                case "cancel" -> goalService.cancel(goalId, uid, null);
                default -> throw new IllegalArgumentException("未知动作：" + action);
            };
            return ResultJson.ok(goalService.view(goal, null));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "待确认/进行中的目标", description = "按会话取当前目标（目标卡回显与恢复用）。"
            + "重启/换副本时框架实例不在本进程：返回 live:false + DB 那份，框架侧同步等下一轮开跑前。")
    @GetMapping("/goal/pending")
    public ResultJson<Map<String, Object>> pending(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(goalService.pending(sessionId, RequestUser.uid()));
    }

    @Operation(summary = "会话当前目标", description = "右栏「目标」面板回显：当前/最近一条目标（读 DB 不读框架）。")
    @GetMapping("/session/{sessionId}/goal")
    public ResultJson<Map<String, Object>> sessionGoal(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        Goal goal = goalService.latestGoal(sessionId);
        if (goal == null) {
            return ResultJson.ok(Map.of("goal", null));
        }
        return ResultJson.ok(goalService.view(goal, null));
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
