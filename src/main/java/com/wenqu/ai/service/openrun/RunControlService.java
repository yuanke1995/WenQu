package com.wenqu.ai.service.openrun;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.hitl.HitlDecisionService;
import com.wenqu.ai.agentx.FrameworkSessions;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.OpenRun;
import com.wenqu.ai.model.ToolApproval;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.util.PreviewTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * v1 run 的控制面：审批 / 产物 / 插话 / 停止（A1 第二批）。
 *
 * <p><b>全部复用现有实现，不复制第二条引擎</b>：审批照 {@code ChatController} 的三段顺序
 * （框架待办优先 → 旧人在回路回退 → CONTINUED 收尾），插话/停止直接调 {@code ChatRunRuntime}，
 * 产物走 {@code ArtifactService.listBySession} + {@code ImageUrlSigner}。跨副本不用另造总线
 * 分支：{@code FrameworkSessions.respond*} 内部已有 {@code routeAnswerElsewhere}，是带 5s 回执
 * 的审批专用通道，比 stop 那条 fire-and-forget 总线更贴合。</p>
 *
 * <p><b>runId 不是 turnId</b>：v1 的 runId 是对外独立句柄，框架轮次另有 turnId，两者不经映射。
 * 所以这里一律「mustOwn(runId) → sessionId」再向下寻卡/寻活轮/查产物；审批卡还要额外校验
 * 归属会话与产生时刻，避免复用会话时裁到上一轮的卡。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunControlService {

    /** 统一的 decision 枚举；原生三个端点各用布尔组合，v1 收敛成一个字段 */
    private static final Set<String> DECISIONS = Set.of(
            "approve", "reject", "revise", "supersede", "answer", "ignore");
    private static final int STEER_MAX_CHARS = 800;

    private final OpenRunService openRunService;
    private final HitlDecisionService hitl;
    private final FrameworkSessions frameworkSessions;
    private final ChatRunRuntime chatRunRuntime;
    private final ArtifactService artifactService;
    private final ImageUrlSigner imageUrlSigner;
    private final RunEventReplay replay;

    // ==================== 审批 ====================

    /**
     * 提交一张审批卡。请求体：{@code approvalId}（来自 approval.required 事件）、
     * {@code decision}、按类型可选的 {@code reason / scope / plan / answers[]}。
     */
    public Map<String, Object> approve(String uid, String runId, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        OpenRun row = openRunService.mustOwn(uid, runId);
        String approvalId = str(b.get("approvalId"));
        if (approvalId.isBlank()) {
            throw new BizException(400, "approvalId 必填（见 approval.required 事件的 approvalId）");
        }
        String decision = str(b.get("decision"));
        if (!DECISIONS.contains(decision)) {
            throw new BizException(400, "decision 只支持 approve / reject / revise / supersede / answer / ignore");
        }
        ToolApproval card = hitl.getApproval(approvalId, uid);
        if (card == null) throw new BizException(404, "审批卡不存在或已失效");
        assertCardBelongsToRun(card, row);

        String tool = card.getToolName() == null ? "" : card.getToolName();
        boolean plan = "planApproval".equals(tool);
        boolean ask = "askUser".equals(tool);
        if (ask && !Set.of("answer", "ignore").contains(decision)) {
            throw new BizException(400, "提问卡只支持 decision=answer（带 answers[]）或 ignore");
        }
        if (plan && !Set.of("approve", "reject", "revise", "supersede").contains(decision)) {
            throw new BizException(400, "计划卡只支持 approve / reject / revise / supersede");
        }
        if (!plan && !ask && !Set.of("approve", "reject").contains(decision)) {
            throw new BizException(400, "工具审批卡只支持 approve / reject");
        }

        boolean ok;
        if (ask) {
            List<String> answers = answersFromBody(b);
            if ("ignore".equals(decision)) {
                ok = hitl.resolveAskIgnore(approvalId, uid);
            } else {
                // 与 ChatController.answerAgentAsk 同口径：框架先收，旧通道回退，CONTINUED 卡收尾
                ok = frameworkRespond(() -> frameworkSessions.respondAsk(approvalId, frameworkAskText(answers)),
                        "ask", approvalId)
                        || hitl.resolveAsk(approvalId, answers, uid)
                        || hitl.closeContinuedAsk(approvalId, uid, answers);
            }
        } else if (plan) {
            boolean approved = "approve".equals(decision);
            boolean revise = "revise".equals(decision);
            boolean supersede = "supersede".equals(decision);
            String planText = str(b.get("plan"));
            String supersedeReason = planText.isBlank() ? "用户带着意见继续对话，本版计划作废" : planText;
            String reason = revise ? planText
                    : (supersede ? supersedeReason : (approved ? null : "用户拒绝了这版计划"));
            String outcome = approved ? "approve"
                    : (supersede ? "supersede" : (revise ? "revise" : "reject"));
            ok = frameworkRespond(() -> frameworkSessions.respondPlanApproval(approvalId, approved, reason, outcome),
                    "plan", approvalId)
                    || hitl.resolvePlanApproval(approvalId, approved, planText, revise, supersede, uid);
        } else {
            boolean approved = "approve".equals(decision);
            String reason = str(b.get("reason"));
            String scope = str(b.get("scope"));
            ok = frameworkRespond(() -> frameworkSessions.respond(approvalId, approved,
                    approved ? null : reason, scope), "approval", approvalId)
                    || hitl.resolveApproval(approvalId, approved, uid);
        }
        if (!ok) {
            // 行仍 PENDING 说明只是这次没投递进去（上一步还在执行），别报「已失效」让用户以为白点了
            ToolApproval again = hitl.getApproval(approvalId, uid);
            if (again != null && "PENDING".equals(again.getStatus())) {
                throw new BizException(409, "这一步还在执行上一步，卡片仍有效，请稍后重试");
            }
            throw new BizException(409, "审批卡已处理或已失效（可能已超时）");
        }
        log.info("[OPEN-RUN] 审批 run={} card={} kind={} decision={}", runId, approvalId,
                plan ? "plan_approval" : (ask ? "ask_user" : "tool_approval"), decision);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("approvalId", approvalId);
        out.put("kind", plan ? "plan_approval" : (ask ? "ask_user" : "tool_approval"));
        out.put("decision", decision);
        out.put("accepted", true);
        if (!ask && !plan && "approve".equals(decision) && "session".equals(str(b.get("scope")))) {
            out.put("autoResolved", frameworkSessions.resolveSiblingsAfterSessionGrant(approvalId));
        }
        return out;
    }

    /**
     * 框架待办优先，但「框架里没有这张卡」不是错误，而是回落信号。
     * <p>框架在「没有 pendingOwner / 重启后接不回实例 / 卡片已终态」时，内部有时把
     * {@code "No pending interaction: …"} 记成 WARN 并返回 false，有时直接把
     * {@code IllegalArgumentException} 抛到外层（卡片已裁决时就是后者）；会话忙则是
     * {@code IllegalStateException}。原生端点的调用方都登记过卡，所以碰不到，但 v1 端点面对
     * 残留卡、跨副本句柄丢失、重复提交都会踩到——不收口的话请求直接变成 400，旧通道
     * {@code HitlDecisionService} 明明能按 DB 行收尾却永远走不到。
     * 只捕获这两种「没有待办 / 会话忙」信号，其余异常照抛。</p>
     */
    private static boolean frameworkRespond(BooleanSupplier call, String kind, String cardId) {
        try {
            return call.getAsBoolean();
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.debug("[OPEN-RUN] 框架无 {} 待办，回落旧审批通道: card={} : {}", kind, cardId, e.getMessage());
            return false;
        }
    }

    /** 卡片必须属于这次 run 的会话，且不能在 run 创建之前（复用会话时别裁到上一轮的卡） */
    private static void assertCardBelongsToRun(ToolApproval card, OpenRun row) {
        if (card.getSessionId() == null || !card.getSessionId().equals(row.getSessionId())) {
            throw new BizException(404, "审批卡不属于这次运行");
        }
        LocalDateTime born = card.getCreatedAt();
        LocalDateTime runAt = row.getCreateTime();
        // 2 秒容差：两处都是 LocalDateTime.now()，跨秒边界不该把本轮的卡误判成历史卡
        if (born != null && runAt != null && born.isBefore(runAt.minusSeconds(2))) {
            throw new BizException(404, "审批卡不属于这次运行");
        }
    }

    private static List<String> answersFromBody(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        Object raw = body.get("answers");
        if (raw instanceof List<?> list) {
            for (Object x : list) if (x != null) out.add(String.valueOf(x));
        }
        return out;
    }

    /** 与 ChatController.frameworkAskText 同口径：单题原样、多题 JSON 数组、空答不代编 */
    private static String frameworkAskText(List<String> answers) {
        if (answers.isEmpty()) return "（用户未回答这一题（点了提交但没有选择答案））";
        List<String> one = answers.stream().map(String::trim)
                .map(x -> x.isEmpty() ? "（用户未回答这一题（点了提交但没有选择答案））" : x).toList();
        return one.size() == 1 ? one.get(0) : JSON.toJSONString(one);
    }

    // ==================== 产物 ====================

    /** 只回本次 run 之后、归属本会话的可下载产物；签名 URL 当场换发（1 小时） */
    public Map<String, Object> artifacts(String uid, String runId) {
        OpenRun row = openRunService.mustOwn(uid, runId);
        List<Artifact> list = artifactService.listBySession(uid, row.getSessionId(), row.getCreateTime());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Artifact a : list) {
            if (a.getObjectKey() == null || a.getObjectKey().isBlank()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getDisplayName() == null || a.getDisplayName().isBlank()
                    ? a.getFilename() : a.getDisplayName());
            m.put("filename", a.getFilename());
            m.put("kind", PreviewTypes.artifactKindOf(a.getExt()));
            m.put("preview", PreviewTypes.kindOf(a.getExt()));
            m.put("ext", a.getExt());
            m.put("size", a.getSize());
            m.put("createdAt", a.getCreateTime() == null ? null : a.getCreateTime().toString());
            m.put("downloadUrl", imageUrlSigner.signUrl("/ai/" + a.getObjectKey()));
            m.put("expiresInSeconds", ConfigDefaults.IMAGES_AUTH_EXPIRE_SECONDS);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("count", items.size());
        out.put("artifacts", items);
        return out;
    }

    // ==================== 插话 / 停止 ====================

    /** 运行中插话；v1 只接纯文本（图片/附件走内容形态，不在 run 契约里） */
    public Map<String, Object> steer(String uid, String runId, String text) {
        OpenRun row = openRunService.mustOwn(uid, runId);
        if (text == null || text.isBlank()) throw new BizException(400, "text 必填");
        if (text.length() > STEER_MAX_CHARS) {
            throw new BizException(400, "text 最长 " + STEER_MAX_CHARS + " 字");
        }
        // 收集型通道不登记运行总线，steerActor 会回落 TurnRegistry.handle——ChatService 对它同样注册了把手
        String messageId = chatRunRuntime.steerTurn(row.getSessionId(), uid, text,
                Collections.emptyList(), Collections.emptyList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("accepted", messageId != null);
        out.put("messageId", messageId == null ? "" : messageId);
        return out;
    }

    /**
     * 停止：幂等（无在跑轮返回 stopped=false）。原生 stopTurn 不发 SSE 帧，这里打一个短标记，
     * 让投影层在随后到达的 done 上把终态收成 stopped 而不是 succeeded。
     */
    public Map<String, Object> stop(String uid, String runId) {
        OpenRun row = openRunService.mustOwn(uid, runId);
        boolean stopped = chatRunRuntime.stopTurn(row.getSessionId(), uid);
        if (stopped) replay.markStopped(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("stopped", stopped);
        // 即时状态：终态要等引擎的 done 帧，由 events / GET /runs/{id} 反映
        out.put("status", openRunService.mustOwn(uid, runId).getStatus());
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
