package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.plan.PlanCopy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.TurnNotifyService;
import io.agentscope.core.session.SessionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.firstNonNull;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.str;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.toolInputOf;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.toolNameOf;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.nativeGuardKind;

/**
 * 框架待办（一等公民）→ 问渠各类确认卡（从 AgentscopeChatEngine 抽出）。
 *
 * <p>一轮里框架挂起的待办按 kind 分流成三种卡：计划退出确认（plan_* 走问渠计划卡）、
 * 智能体提问（askUser）、工具审批（confirmation）。每张卡都要先落库再发帧（同一 approvalId
 * 两个跟随者各宣布一次时，数据库幂等、emit 侧靠返回值去重），并给屏幕外的人发一条通知。
 *
 * <p>{@code workspaceRootsOf} 仍留在引擎（approvalReason 用它判越界写），以 Function 注入。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxPendingAnnouncer {

    private final FrameworkSessions sessions;
    private final AgentxAskCard askCardBuilder;
    private final ConfigService configService;
    private final ToolDisplayNames displayNames;
    private final TurnNotifyService turnNotify;
    private final Function<ChatCommand, List<String>> workspaceRoots;

    public void announce(Map<String, SessionEvent> pending, ChatCommand cmd, RunChannel channel,
                         Set<String> announced, String turnId, AgentxTurnRecording recording) {
        if (pending.isEmpty()) return;
        String sessionKey = sessions.key(cmd);
        sessions.notePending(sessionKey, pending);
        for (Map.Entry<String, SessionEvent> entry : pending.entrySet()) {
            if (!announced.add(entry.getKey())) continue;
            announceOne(entry, cmd, channel, sessionKey, turnId, recording);
        }
    }

    /** 按 kind 分流到计划卡 / 提问卡 / 审批卡三种出口。 */
    private void announceOne(Map.Entry<String, SessionEvent> entry, ChatCommand cmd, RunChannel channel,
                             String sessionKey, String turnId, AgentxTurnRecording recording) {
        Map<String, Object> data = entry.getValue().data() == null ? Map.of() : entry.getValue().data();
        String tool = toolNameOf(firstNonNull(data.get("call"), firstNonNull(data.get("toolUse"),
                firstNonNull(data.get("message"), data))));
        String args = str(firstNonNull(toolInputOf(data.get("call")),
                firstNonNull(toolInputOf(data.get("toolUse")), toolInputOf(data))));
        // 两种待办按 kind 分流（工具挂起=external_execution，权限确认=confirmation）。
        // 未知 kind 也走提问卡——题面至少能呈现，绝不静默吞掉
        String kind = str(data.get("kind"));
        if ("confirmation".equals(kind) && tool.startsWith("plan_")) {
            announcePlanCard(entry, cmd, channel, sessionKey, turnId, recording, data, tool);
            return;
        }
        // 提问工具被框架记成「确认」是不该发生的（AskUserTool 已声明权限放行）：真出现时留一条日志
        if ("confirmation".equals(kind) && AskUserTool.NAME.equals(tool)) {
            log.warn("[AGENTX] 提问工具被记成确认待办，按审批卡处理: session={} request={}",
                    cmd.getSessionId(), entry.getKey());
        }
        if (!"confirmation".equals(kind)) {
            announceAskCard(entry, cmd, channel, sessionKey, turnId, data, tool);
            return;
        }
        announceApprovalCard(entry, cmd, channel, sessionKey, turnId, data, tool, args);
    }

    /** 计划退出确认 → 问渠计划卡（deep_plan_approval），不是普通工具审批卡。 */
    private void announcePlanCard(Map.Entry<String, SessionEvent> entry, ChatCommand cmd, RunChannel channel,
                                  String sessionKey, String turnId, AgentxTurnRecording recording,
                                  Map<String, Object> data, String tool) {
        Map<String, Object> planCard = new LinkedHashMap<>();
        String rawPlan = AgentxTextUtil.planTextOf(toolInputOf(data.get("call")),
                recording == null ? null : recording.planMarkdown);
        if (AgentxTextUtil.NO_PLAN_BODY.equals(rawPlan)) {
            // 「模型没调 plan_write」是排查用的内部原因，只进日志
            log.warn("[PLAN] 本轮 plan_exit 没带计划正文（未取得 plan_write 的 content）: session={} tool={}",
                    cmd.getSessionId(), tool);
        }
        // 用户口径的保底：正文里出现沙盒/运行目录路径就收成文件名
        String planText = PlanCopy.stripInternalPaths(rawPlan, planInternalRoots());
        planCard.put("planApprovalId", entry.getKey());
        planCard.put("plan", planText);
        planCard.put("timeoutMs", configService.getInt("chat.planTimeoutMs", 3600000));
        // 血缘随 deep_plan_approval 一起下发：断线接流/换设备时没有原始 userMsg，只能靠帧自身带
        putPlanBase(planCard, cmd);
        if (recording != null) {
            // 产出这一版的意见就是用户上一次「退回重出」提交的正文；首次计划没有意见
            String produceFeedback = sessions.peekPlanFeedback(sessionKey);
            recording.planVersion(planText, produceFeedback, entry.getKey());
            // 新一版已挂出待确认：退回重出状态到此结束
            sessions.markPlanPending(sessionKey);
        }
        channel.emit(RunEventType.DEEP_PLAN_APPROVAL, JSON.toJSONString(planCard));
        // 落同一张 c_ai_tool_approval（tool_name=planApproval）：不落刷新后取不回来，血缘也存进 request_args
        Map<String, Object> pendingArgs = new LinkedHashMap<>();
        pendingArgs.put("plan", planText);
        putPlanBase(pendingArgs, cmd);
        sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), "planApproval",
                JSON.toJSONString(pendingArgs));
        // 计划卡等人拍板是有时限的：屏幕外的人看不见那张卡，只能靠这一句把人叫回来
        turnNotify.onPlanPending(cmd, entry.getKey());
    }

    /** 计划血缘（planBaseId 及其实施进度）只在请求显式带了 planBaseId 时写。 */
    private void putPlanBase(Map<String, Object> target, ChatCommand cmd) {
        if (cmd.getPlanBaseId() == null || cmd.getPlanBaseId().isBlank()) return;
        target.put("planBaseId", cmd.getPlanBaseId());
        target.put("planBaseVersion", cmd.getPlanBaseVersion());
        target.put("planBaseDone", cmd.getPlanBaseDone());
        target.put("planBaseTotal", cmd.getPlanBaseTotal());
        target.put("planBaseFailed", cmd.getPlanBaseFailed());
    }

    /** 智能体提问 → askUser 卡（载荷与手写侧 askArgsJson 同形，恢复端点可直接复用）。 */
    private void announceAskCard(Map.Entry<String, SessionEvent> entry, ChatCommand cmd, RunChannel channel,
                                 String sessionKey, String turnId, Map<String, Object> data, String tool) {
        Object call = firstNonNull(data.get("call"), data);
        String askPayload = askCardBuilder.askCard(entry.getKey(), toolInputOf(call), tool);
        channel.emit(RunEventType.ASK_USER, askPayload);
        sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), "askUser", askPayload);
        // 通知正文带第一题的题面：手机上「在等你回答」不说明在等什么，人得先点进来才知道
        turnNotify.onAskPending(cmd, entry.getKey(), firstAskText(toolInputOf(call)));
    }

    /** 工具审批 → 审批卡：展示名与工具卡同源；先落库、只有真插入才发帧去重。 */
    private void announceApprovalCard(Map.Entry<String, SessionEvent> entry, ChatCommand cmd, RunChannel channel,
                                      String sessionKey, String turnId, Map<String, Object> data,
                                      String tool, String args) {
        Map<String, Object> approvalCard = new LinkedHashMap<>();
        approvalCard.put("approvalId", entry.getKey());
        approvalCard.put("tool", tool);
        approvalCard.put("args", args);
        approvalCard.put("timeoutMs", configService.getInt("chat.approvalTimeoutMs", 600000));
        String reason = approvalReason(cmd, tool, firstNonNull(toolInputOf(data.get("call")),
                firstNonNull(toolInputOf(data.get("toolUse")), toolInputOf(data))));
        if (!reason.isEmpty()) approvalCard.put("reason", reason);
        displayNames.putDisplayFields(approvalCard, cmd.getUserId(), tool);
        // 工具表没收过这个名字（MCP 动态工具常见）：补一句说明，但绝不拿工具名冒充展示名
        if (!approvalCard.containsKey("hint")) {
            approvalCard.put("hint", "执行前请确认这一步");
        }
        channel.emit(RunEventType.APPROVAL_REQUIRED, JSON.toJSONString(approvalCard));
        // 先落库、只有真的插入了才发帧：同一 approvalId 两个跟随者各宣布一次时靠返回值去重
        boolean inserted = sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), tool, args, reason);
        if (!inserted) {
            log.info("[AGENTX] 审批卡已存在，跳过重复下发: session={} approval={}",
                    cmd.getSessionId(), entry.getKey());
            return;
        }
        turnNotify.onApprovalPending(cmd, entry.getKey(), str(approvalCard.get("label")));
    }

    /**
     * 审批卡上的「为什么问」：与执行期守卫同一份判据——本机文件/命令工具由污点护栏接管，
     * 其余工具按本轮审批档要求确认。空串 = 没有可说清的理由，卡片沿用默认提示。
     */
    @SuppressWarnings("unchecked")
    private String approvalReason(ChatCommand cmd, String tool, Object inputObj) {
        AgentxTurnSlot.Turn t = sessions.turnSlot(cmd).current();
        if (t == null) return "";
        AgentxNativeToolGuard.Kind kind = nativeGuardKind(tool);
        if (kind != null) {
            Map<String, Object> input = inputObj instanceof Map ? (Map<String, Object>) inputObj : null;
            String note = AgentxNativeToolGuard.forcedAskNote(kind, input, t.policy(), t.taint(),
                    workspaceRoots.apply(cmd));
            if (note != null) return note;
        }
        var surface = sessions.turnSlot(cmd).surface();
        if (surface != null && surface.mcpToolNames().contains(tool)) {
            return "MCP 外部工具：将在你登记的 MCP 服务里执行这一步。";
        }
        return "当前要求执行前确认：这一步会改动外部状态。";
    }

    /** 计划正文里算「内部路径」的根，补在 PlanCopy 内置名单之外（沙盒虚拟根 + 框架状态目录）。 */
    private List<String> planInternalRoots() {
        List<String> roots = new ArrayList<>();
        String prefix = configService.get("sandbox.virtualPathPrefix");
        if (prefix != null && !prefix.isBlank()) roots.add(prefix.trim());
        String stateDir = configService.get("chat.agentscopeStateDir");
        if (stateDir != null && !stateDir.isBlank()) {
            roots.add(stateDir.trim());
            try {
                roots.add(Path.of(stateDir.trim()).toAbsolutePath().normalize().toString());
            } catch (Exception ignored) {
                // 配置成了非法路径：内置名单照样兜底，不为这一条把整轮打挂
            }
        }
        return roots;
    }

    /** 提问卡第一题的题面（站内通知正文用）。 */
    static String firstAskText(Object input) {
        if (!(input instanceof Map<?, ?> in)) return input == null ? "" : String.valueOf(input).strip();
        Object qs = in.get("questions");
        if (qs instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> q) return str(q.get("question"));
            return String.valueOf(first).strip();
        }
        return str(in.get("question"));
    }
}
