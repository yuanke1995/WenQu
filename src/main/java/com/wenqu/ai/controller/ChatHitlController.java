package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ChatService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 人在回路（HITL）的裁决入口：工具审批、提问作答、计划批准，以及工具大输出外溢原文读回。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "人在回路", description = "工具审批、askUser 提问、执行计划批准与工具外溢原文")
public class ChatHitlController {

private final ChatService chatService;
private final com.wenqu.ai.agent.hitl.HitlDecisionService hitl;
private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;
private final com.wenqu.ai.service.ToolSpillService toolSpillService;

    @Operation(summary = "工具执行审批", description = "裁决智能体的工具执行请求（approval_required 事件下发）：仅本轮用户本人可批，"
            + "拒绝/超时后模型会收到未执行错误并继续回答；审批挂起为内存态，刷新页面即失效。"
            + "body 传 {approved:true, scope:'session'} 为「本次会话都允许」：同一工具在本次会话内不再弹卡"
            + "（按工具名放行，不过期到别的工具/别的会话）")
    @Permission("chat:approve")
    @PostMapping("/tool-approval/{approvalId}")
    public ResultJson resolveToolApproval(
            @Parameter(description = "审批请求 ID（approval_required 事件下发）") @PathVariable("approvalId") String approvalId,
            @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String scope = body.get("scope") == null ? null : String.valueOf(body.get("scope"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        // 框架引擎的待办先走它自己：AgentSession.respond 会找回待办所属的原任务与工具调用并安排
        // 接续执行；不在这套会话里才交回人在回路的旧通道（两条通道不双写同一个审批号）
        boolean ok = frameworkSessions.respond(approvalId, approved,
                approved ? null : reason, scope)
                || hitl.resolveApproval(approvalId, approved, com.wenqu.ai.util.RequestUser.uid());
        // 会话级放行还要把同根会话下同工具已挂着的卡一并批掉：那些调用已经在等审批，
        // 只写下放行 (下一次生效) 救不了它们；返回 ids 让前端把卡一起收掉。
        java.util.List<String> autoResolved = (ok && approved && "session".equals(scope))
                ? frameworkSessions.resolveSiblingsAfterSessionGrant(approvalId)
                : java.util.List.of();
        if (!ok) {
            // 行还是 PENDING 说明只是这一次没投递进去（同一会话上一张卡刚批完、子轮正在执行），
            // 卡片仍有效，别报成「已失效」让用户以为白点了
            var rec = hitl.getApproval(approvalId, com.wenqu.ai.util.RequestUser.uid());
            if (rec != null && "PENDING".equals(rec.getStatus())) {
                return ResultJson.error("这一步还在执行上一步，请稍后再点一次（卡片仍有效）");
            }
            return ResultJson.error("审批请求不存在或已失效（可能已超时）");
        }
        if (!autoResolved.isEmpty()) {
            return ResultJson.ok(java.util.Map.of(
                    "msg", approved ? "已批准" : "已拒绝",
                    "autoResolved", autoResolved));
        }
        return ResultJson.ok(approved ? "已批准" : "已拒绝");
    }

    @Operation(summary = "工具审批记录（恢复）", description = "按审批 ID 取本人的审批记录（tool.approval 通知点击后重建审批卡用）；" +
            "不存在/非本人返回 null。内存态可能已失效（进程重启/超时），此时记录状态为终态，卡片提示已处理。")
    @Permission("chat:approve")
    @GetMapping("/tool-approval/pending")
    public ResultJson listPendingToolApprovals(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(java.util.Map.of("items",
                markFrameworkLive(hitl.listPendingApprovals(sessionId, com.wenqu.ai.util.RequestUser.uid()), frameworkSessions)));
    }

    /**
     * 框架引擎的待办活在 {@code AgentSession} 上，而恢复端点的 {@code live} 是按旧通道的内存表判的
     * ——不改的话框架卡一律被标成「卡片还在但没人能唤醒」，用户取回一张答不了的卡。
     * <p>只改明确属于框架会话的那些 id，其余原样返回（两条通道不双写同一个号，判归属也一样）。
     * <p>静态且把 {@code frameworkSessions} 作参数传入，是因为免登录分享侧的待答恢复
     * （{@code ShareController} 的 {@code /ask-user/pending}）要认同一件事；不复制这份循环。
     */
    static java.util.List<java.util.Map<String, Object>> markFrameworkLive(
            java.util.List<java.util.Map<String, Object>> items,
            com.wenqu.ai.agentx.FrameworkSessions frameworkSessions) {
        if (items == null) return java.util.List.of();
        for (java.util.Map<String, Object> item : items) {
            Object id = item.get("approvalId") != null ? item.get("approvalId")
                    : (item.get("askId") != null ? item.get("askId") : item.get("planApprovalId"));
            if (id != null && frameworkSessions.isLive(String.valueOf(id))) {
                item.put("live", true);
            }
        }
        return items;
    }

    @Permission("chat:approve")
    @GetMapping("/tool-approval/{approvalId}")
    public ResultJson getToolApproval(
            @Parameter(description = "审批请求 ID") @PathVariable("approvalId") String approvalId) {
        return ResultJson.ok(hitl.getApproval(approvalId, com.wenqu.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "回答智能体提问", description = "回答智能体的结构化提问（ask_user 事件下发，人在回路）：仅本轮用户本人可答，"
            + "答案作为工具结果回给模型继续本轮回答。提问卡持久化在 c_ai_tool_approval（PENDING→终态），"
            + "等待期间客户端断开不中止本轮，故刷新页面/换设备后仍可经 /ask-user/pending 取回卡片作答。"
            + "body 传 {answers:[...]} 为作答（与问题下标对齐）；传 {ignore:true} 为忽略（不作答）。"
            + "超时与忽略都不替用户选答案。返回 false 表示这次作答没能送达（已收尾，或唤醒句柄随进程重启消失）。"
            + "前台等待已结束的提问卡（CONTINUED）仍可答：本端点只收尾那一行并回 {continued:true}，"
            + "由客户端把整批答案作为一条用户消息送进会话。")
    @Permission("chat:approve")
    @PostMapping("/ask-user/{askId}")
    public ResultJson answerAgentAsk(
            @Parameter(description = "提问 ID（ask_user 事件下发）") @PathVariable("askId") String askId,
            @RequestBody Map<String, Object> body) {
        boolean ignore = Boolean.TRUE.equals(body.get("ignore"));
        String uid = com.wenqu.ai.util.RequestUser.uid();
        // 一卡多问：answers 为与问题下标对齐的答案数组（一题一项，多选的那几项已由前端并成一项里的
        // 一个串）。两处出口共用这一份解析，不再各抄一遍取数
        java.util.List<String> answers = answersFromBody(body);
        // 框架引擎的提问卡走框架待办（挂起→答复→由框架接续原任务），不属于它才回落旧通道；
        // 忽略＝把「用户没答」原样送回模型，绝不替用户编一个答案
        if (!ignore) {
            if (frameworkSessions.respondAsk(askId, frameworkAskText(answers))) {
                return ResultJson.ok("已提交");
            }
        }
        boolean ok;
        if (ignore) {
            ok = hitl.resolveAskIgnore(askId, uid);
        } else {
            ok = hitl.resolveAsk(askId, answers, uid);
        }
        if (ok) return ResultJson.ok(ignore ? "已忽略，智能体将自行判断" : "已提交");
        // 前台等待已结束的那张卡（CONTINUED）：那次工具调用早已结算，答案不再回灌它——
        // 这里只收尾这一行，由客户端把整批答案作为一条用户消息 steer 进会话（DSH 的 answer_to_pending_question）
        if (!ignore && hitl.closeContinuedAsk(askId, uid, answers)) {
            return ResultJson.ok(java.util.Map.of("continued", true, "msg", "已提交，答案将作为用户消息送达"));
        }
        return ResultJson.error("提问已结束，这次作答没能送达智能体");
    }

    /**
     * 作答文本的单一真源在 {@link com.wenqu.ai.agent.hitl.AskAnswerText}——游客分享页
     * （{@code ShareController}）与开放运行端（{@code RunControlService}）送的是同一份
     * 文本，复写在这里的三份实现必然漂移。这两个静态方法保留成薄委托，只为不动它们的调用点。
     */
    static java.util.List<String> answersFromBody(java.util.Map<String, Object> body) {
        return com.wenqu.ai.agent.hitl.AskAnswerText.answersFromBody(body);
    }

    /** 框架提问卡送回答给模型的文本，见 {@link com.wenqu.ai.agent.hitl.AskAnswerText#toModelText}。 */
    static String frameworkAskText(java.util.List<String> answers) {
        return com.wenqu.ai.agent.hitl.AskAnswerText.toModelText(answers);
    }

    @Operation(summary = "待答提问（恢复）", description = "按会话列本人仍挂在 PENDING 的 askUser 提问卡，"
            + "供前端在会话加载/切换与点开 tool.ask 通知时重建卡片。返回 items：{askId,questions,timeoutMs,"
            + "remainingMs,createdAt,expired,live}；live=false 表示唤醒句柄已不在（进程重启），作答送不到模型。")
    @Permission("chat:approve")
    @GetMapping("/ask-user/pending")
    public ResultJson listPendingAsks(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(java.util.Map.of("items",
                markFrameworkLive(hitl.listPendingAsks(sessionId, com.wenqu.ai.util.RequestUser.uid()), frameworkSessions)));
    }

    @Operation(summary = "工具完整输出（外溢原文读回）", description = "读回一条被外溢存储的工具原文（沙盒输出、读到的整份文件等）。"
            + "工具卡片超过展示上限时不再「截断即丢」：完整原文落盘，卡片留 spillId，这里按需读回。"
            + "三种看法：不带参数=从头一次给一段（512K 字符，响应里 next 是下一段偏移）；带 offset=按字符续读；"
            + "带 line=按<b>行</b>窗口读（自动给命中行留上文，翻日志与检索跳读用它，一次最多 500 行）。"
            + "整份找词用 /tool-output/{spillId}/search。"
            + "归属只认发起人本人（目录按 uid 哈希 + meta 二次校验），非本人/已过期清理/未开启外溢一律报错而不是回空壳。")
    @Permission("chat:tool-output")
    @GetMapping("/tool-output/{spillId}")
    public ResultJson getToolOutput(
            @Parameter(description = "外溢标识（工具卡片 spillId / argsSpillId）") @PathVariable("spillId") String spillId,
            @Parameter(description = "从第几个字符开始读（默认 0）") @RequestParam(value = "offset", defaultValue = "0") Integer offset,
            @Parameter(description = "本次最多读多少字符（默认与上限同为 512K）") @RequestParam(value = "limit", required = false) Integer limit,
            @Parameter(description = "按行读：从第几行开始（1-based，给定时走行窗口）") @RequestParam(value = "line", required = false) Integer line,
            @Parameter(description = "按行读：最多给几行（默认 200，上限 500）") @RequestParam(value = "lines", required = false) Integer lines) {
        String uid = com.wenqu.ai.util.RequestUser.uid();
        java.util.Map<String, Object> out = line != null && line > 0
                ? toolSpillService.readLines(spillId, uid, line, lines == null ? 0 : lines)
                : toolSpillService.read(spillId, uid, offset == null || offset < 0 ? 0 : offset,
                        limit == null ? 0 : limit);
        if (out == null) return ResultJson.error("这份原文不存在、已被清理（超保留期或超每人配额），或不属于你");
        return ResultJson.ok(out);
    }

    @Operation(summary = "工具完整输出·服务端检索", description = "在盘上那份<b>整份</b>留存原文里按行找关键字，返回命中行号与字符偏移"
            + "（上限 200 条命中，超出用 limited=true 交代还有多少）。前端只筛已读回那一段时，"
            + "「那句报错在 300 万字的日志里」永远筛不到——这条补齐「搜到 → 跳到那一段」。归属判据与读回同一条。")
    @Permission("chat:tool-output")
    @GetMapping("/tool-output/{spillId}/search")
    public ResultJson searchToolOutput(
            @Parameter(description = "外溢标识") @PathVariable("spillId") String spillId,
            @Parameter(description = "关键字（整行包含匹配，忽略大小写）") @RequestParam("q") String q) {
        java.util.Map<String, Object> out = toolSpillService.search(spillId, com.wenqu.ai.util.RequestUser.uid(), q);
        if (out == null) return ResultJson.error("这份原文不存在、已被清理，或不属于你（关键字不能为空）");
        return ResultJson.ok(out);
    }

    @Operation(summary = "会话事件账本（过程回放）", description = "按会话读 c_ai_session_event：这一轮怎么跑出来的——工具步的起止与耗时、"
            + "人在回路三张卡、产物、子智能体编排、停止与失败，按写入顺序分轮列出（仅本人，默认最近 5 轮、上限 200 条）。"
            + "带 turn=<消息的 turnId> 时只返回那一轮（对话页逐气泡「过程」的取数）。"
            + "账本只服务事后回看与审计，不参与模型输入；正文与逐字增量不在这里（那些随消息落库）。")
    @Permission("session:read")
    @GetMapping("/session/{sessionId}/events")
    public ResultJson listSessionEvents(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @Parameter(description = "最多返回几轮（默认 5，上限 20；带 turn 时无效）") @RequestParam(value = "turns", required = false) Integer turns,
            @Parameter(description = "只看这一轮（消息行的 turnId；留空=按会话分轮列出）") @RequestParam(value = "turn", required = false) String turn) {
        return ResultJson.ok(chatService.listSessionEvents(sessionId, com.wenqu.ai.util.RequestUser.uid(), turns, turn));
    }

    @Operation(summary = "深度执行计划裁决", description = "批准/退回重出/继续对话取代/拒绝 deep_plan_approval 事件下发的执行计划（人在回路）："
            + "批准可带编辑后的计划全文（body {approved:true,plan:\"...\"}，plan 空串=按模型原稿执行）；"
            + "退回重出（body {revise:true,plan:\"修改意见\"}）＝用户不满意本版，同一轮内模型按意见产出下一版，"
            + "可反复直到批准/拒绝/超时；继续对话（body {supersede:true,plan:\"修改意见\"}）＝用户带着修改意见继续对话，本版作废"
            + "（意见全文同时记进待办终态；本轮随即收口，前端随后把意见作为用户消息发出，新一轮按意见重做计划）；拒绝（approved:false）或超时=本轮终止。"
            + "仅本轮用户本人可裁决；记录持久化在 c_ai_tool_approval（tool_name=planApproval，"
            + "PENDING→REVISED/APPROVED/REJECTED/SUPERSEDED/TIMEOUT/STOPPED/INTERRUPTED），等待期间断开不中止本轮，"
            + "刷新/换设备后仍可经 /deep/plan-approval/pending 取回卡片（含版本链）继续裁决。")
    @Permission("chat:approve")
    @PostMapping("/deep/plan-approval/{planApprovalId}")
    public ResultJson resolvePlanApproval(
            @Parameter(description = "计划批准 ID（deep_plan_approval 事件下发）") @PathVariable("planApprovalId") String planApprovalId,
            @RequestBody Map<String, Object> body) {
        PlanVerdict verdict = PlanVerdict.of(body);
        // 框架引擎的计划退出确认走框架待办：批准=approve，退回重出/继续对话/拒绝都按拒绝带上理由，
        // 让模型在原任务里看到用户的意见（revise 与 supersede 的界面差异仍由前端承担，不在此分叉）。
        if (frameworkSessions.respondPlanApproval(planApprovalId, verdict.approved(),
                verdict.frameworkReason(), verdict.frameworkOutcome())) {
            // 界面终态（supersede/reject）由框架引擎那侧的循环取走并补发 deep_plan_superseded /
            // deep_plan_cancelled 与带标记的 done——不在这里直接收轮：控制流归框架持有
            return ResultJson.ok(verdict.approved() ? "已批准" : "已退回");
        }
        boolean ok = hitl.resolvePlanApproval(planApprovalId, verdict.approved(), verdict.plan(),
                verdict.revise(), verdict.supersede(), com.wenqu.ai.util.RequestUser.uid());
        if (!ok) return ResultJson.error("计划已处理或已失效（可能已超时）");
        return ResultJson.ok(verdict.userMessage());
    }

    /**
     * 一次计划裁决的四种去向，以及每种去向对框架待办与界面各自说的话。
     * <p>把「取哪个字段、送什么理由、回什么文案」这三件事按去向收进一个不可变值，
     * 而不是在控制器里叠四层三元表达式——那是 NPath 复杂度的主要来源，也最容易在
     * 新增一种去向时漏配其中一处。
     *
     * @param approved          是否批准（revise / supersede / 拒绝都为 false）
     * @param revise            退回重出：同一轮内模型按意见产出下一版
     * @param supersede         继续对话取代：本版作废，意见随新一轮发出
     * @param plan              编辑后的计划全文（null = 按模型原稿）
     * @param frameworkReason   交给框架待办的理由
     * @param frameworkOutcome  approve / supersede / revise / reject
     * @param userMessage       回给界面的提示
     */
    private record PlanVerdict(boolean approved, boolean revise, boolean supersede, String plan,
                               String frameworkReason, String frameworkOutcome, String userMessage) {

        private static final String SUPERSEDE_FALLBACK_REASON = "用户带着意见继续对话，本版计划作废";

        static PlanVerdict of(Map<String, Object> body) {
            boolean approved = Boolean.TRUE.equals(body.get("approved"));
            boolean revise = Boolean.TRUE.equals(body.get("revise"));
            boolean supersede = Boolean.TRUE.equals(body.get("supersede"));
            Object raw = body.get("plan");
            String plan = raw == null ? null : String.valueOf(raw);
            String reason = frameworkReason(approved, revise, supersede, plan);
            String outcome = frameworkOutcome(approved, revise, supersede);
            String message = userMessage(approved, revise, supersede);
            return new PlanVerdict(approved, revise, supersede, plan, reason, outcome, message);
        }

        /**
         * 继续对话也带上意见全文：那一轮随即被收口，理由主要落在待办记录的 answer 上——
         * 审计要看得见用户到底说了什么，而不是只剩一句「用户带着意见继续对话」。
         */
        private static String frameworkReason(boolean approved, boolean revise, boolean supersede, String plan) {
            if (revise) return plan;
            if (supersede) return plan == null || plan.isBlank() ? SUPERSEDE_FALLBACK_REASON : plan;
            // 批准不带理由；拒绝给一句固定文案
            return approved ? null : "用户拒绝了这版计划";
        }

        private static String frameworkOutcome(boolean approved, boolean revise, boolean supersede) {
            if (approved) return "approve";
            if (supersede) return "supersede";
            if (revise) return "revise";
            return "reject";
        }

        private static String userMessage(boolean approved, boolean revise, boolean supersede) {
            if (supersede) return "本版计划已作废（继续对话见新计划）";
            if (revise) return "已按你的意见修改计划";
            if (approved) return "已批准，按计划执行";
            return "已取消本轮";
        }
    }

    @Operation(summary = "待批准计划（恢复）", description = "按会话列本人仍挂在 PENDING 的执行计划批准卡，"
            + "供前端在会话加载/切换与点开 tool.approval 通知时重建卡片。返回 items："
            + "{planApprovalId,plan,timeoutMs,remainingMs,createdAt,expired,live}；live=false 表示唤醒句柄已不在"
            + "（进程重启），批准送不到模型。")
    @Permission("chat:approve")
    @GetMapping("/deep/plan-approval/pending")
    public ResultJson listPendingPlans(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(java.util.Map.of("items",
                markFrameworkLive(hitl.listPendingPlans(sessionId, com.wenqu.ai.util.RequestUser.uid()), frameworkSessions)));
    }
}
