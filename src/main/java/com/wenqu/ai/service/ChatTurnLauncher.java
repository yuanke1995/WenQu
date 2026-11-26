package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigKeys;
import com.wenqu.ai.controller.ChatRequestValidator;
import com.wenqu.ai.controller.ChatTurnAdmission;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 开一轮问答的编排（HTTP 无关）。
 * <p>把「准入 → 会话归一 → 同步段校验 → 组装命令 → 交给引擎」这一段从控制器里移出来：
 * 控制器只该做 HTTP 适配（取参、返响应），而这段流程有十几步、依赖六个服务，
 * 留在控制器里会让该类为了一个端点耦合二十多个类型。
 * <p>失败语义保持原样：同步段校验抛 {@link BizException}（用户拿到明确的 4xx），
 * 且在抛出前补一笔「被拒提问」留痕——用户那句问是当场打出去的，不留痕会被读成「软件吞了我一句话」。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTurnLauncher {

    private final ChatService chatService;
    private final SessionService sessionService;
    private final PlanLineageService planLineage;
    private final ChatTurnAdmission admission;
    private final ChatRequestValidator validator;

    /**
     * 发起一轮问答，返回挂载好的 SSE 通道。
     *
     * @param receivedAtMs 墙钟起表点，必须由调用方在进方法最早处取（见下面注释）
     */
    public SseEmitter launch(ChatRequest request, HttpServletRequest httpRequest, long receivedAtMs) {
        String userId = RequestUser.uid();
        String question = admission.admit(request.getQuestion(), httpRequest);
        List<String> images = request.getImages();
        List<ChatRequest.Attachment> attachments =
                validator.normalizeAttachments(request.getAttachments(), userId);
        Map<String, String> variables = ChatCommand.normalizeVariables(request.getVariables());
        String sessionId = admission.resolveSessionId(request.getSessionId(), userId);

        // 同步段的几道校验统一在这里跑：它们都是「一句话还没发出去就被挡下」，
        // 失败时补一笔留痕再抛 400——用户那句提问是当场打出去的，不留痕就会被读成「软件吞了我一句话」。
        PreparedTurn prepared;
        try {
            prepared = prepareTurn(request, httpRequest, userId, sessionId);
        } catch (BizException e) {
            chatService.recordRejectedQuestion(sessionId, userId, request, e.getMessage());
            throw e;
        }

        // 不设容器级 SSE 超时：那是一条从请求开始一路走到头的墙钟，且 async 启动后不可续期——
        // 人在回路的提问卡/审批等待会被算进去，用户在第二张卡上选完答案整轮已被掐断。
        // 整轮截断改由 ChatRunRuntime 的存活看门狗按「机器耗时」判定（chat.sseTimeoutMs，人工等待不计入）。
        SseEmitter emitter = new SseEmitter(0L);
        chatService.chat(buildCommand(request, prepared, httpRequest, userId, sessionId,
                receivedAtMs, question, images, attachments, variables), emitter);
        return emitter;
    }

    /** 同步段校验的产物：后续组装 ChatCommand 时要用的那几个字段。 */
    private record PreparedTurn(com.wenqu.ai.service.PlanLineageService.Resolved planBase,
                                List<ChatRequest.Mention> mentions,
                                List<ChatRequest.HistoryRef> historyRefs,
                                String editVariantGroup,
                                String effectiveAgentId) {
    }

    /**
     * 发起问答前的同步段校验（fail-loud）：模型/智能体可用性、@ 引用、历史引用、编辑分支手术。
     * 全部在发起之前完成而不是挪进流水线——流水线是异步线程，异常只能变成 SSE error，
     * 用户拿不到明确的 400 语义。
     */
    private PreparedTurn prepareTurn(ChatRequest request, HttpServletRequest httpRequest,
                                     String userId, String sessionId) {
        validator.rejectApiKeyForWorkspaceSession(httpRequest, sessionId);
        // 重新计划血缘：只对计划模式生效。归属/状态不满足时返回 null——这张旧卡可能来自被清理的会话，
        // 重新计划本身仍要能跑，只是不带上一版上下文与血缘标。
        var planBase = request.isPlanMode()
                ? planLineage.resolve(sessionId, userId, request.getPlanBaseId())
                : null;
        // 模型与智能体准入（归属/停用校验），返回本轮最终生效的智能体 ID
        String effectiveAgentId = admission.admitModelAndAgent(request, userId, sessionId);
        return new PreparedTurn(planBase,
                validator.validateMentions(request.getMentions(), httpRequest),
                validator.validateHistoryRefs(sessionId, request.getHistoryRefs()),
                prepareEditBranch(request, sessionId),
                effectiveAgentId);
    }

    /** 编辑重发（消息分支）：校验被编辑消息并完成分支手术；返回贯穿本轮的变体组键。 */
    private String prepareEditBranch(ChatRequest request, String sessionId) {
        String editId = request.getEditMessageId();
        if (editId == null || editId.isBlank()) return null;
        String trimmed = editId.trim();
        if (!trimmed.matches("[0-9a-fA-F]{32}")) {
            throw new BizException("被编辑消息标识无效");
        }
        return sessionService.prepareEditBranch(sessionId, trimmed);
    }

    /** 组装上报给引擎的一轮命令（只做字段拼装，不含任何校验）。 */
    private ChatCommand buildCommand(ChatRequest request, PreparedTurn prepared, HttpServletRequest httpRequest,
                                     String userId, String sessionId, long receivedAtMs, String question,
                                     List<String> images, List<ChatRequest.Attachment> attachments,
                                     Map<String, String> variables) {
        // 团队成员会话的普通问答：在成员页/面板发消息时，这一轮必须仍按「团队成员轮」跑
        // （挂框架 team 工具、成员权限封顶、团队上下文），否则成员页发出的指令会丢团队能力。
        Session teamSess = sessionService.sessionById(sessionId);
        boolean teamMemberTurn = teamSess != null
                && SessionService.KIND_TEAM.equals(teamSess.getKind());
        var planBase = prepared.planBase();
        // regenerate：重新生成/自动重试的重发——该问题的用户消息已随上一轮请求即时落库，跳过重复落库
        return ChatCommand.builder()
                .sessionId(sessionId).question(question).userId(userId)
                .receivedAtMs(receivedAtMs)
                .apiKey(httpRequest.getAttribute(com.wenqu.ai.config.SecurityConfig.ATTR_API_KEY_ID) != null)
                .userImages(images).attachments(attachments).skills(request.getSkills())
                .mentions(prepared.mentions()).historyRefs(prepared.historyRefs())
                .deepThink(request.isDeepThink()).agentId(prepared.effectiveAgentId())
                .modelOverride(request.getModel())
                .teamMode(teamMemberTurn)
                .memberSessionId(teamMemberTurn ? sessionId : null)
                .memberName(teamMemberTurn ? teamSess.getMemberName() : null)
                .teamRootSessionId(teamMemberTurn ? teamSess.getRootSessionId() : null)
                .regenerate(request.isRegenerate()).replaceMessageId(request.getReplaceMessageId())
                .reasoningLevel(request.getReasoningLevel())
                .editVariantGroup(prepared.editVariantGroup()).planMode(request.isPlanMode())
                .planBaseId(planBase == null ? null : planBase.planBaseId())
                .planBaseVersion(planBase == null ? 0 : planBase.version())
                .planBaseDone(planBase == null ? 0 : planBase.done())
                .planBaseTotal(planBase == null ? 0 : planBase.total())
                .planBaseFailed(planBase == null ? 0 : planBase.failed())
                .planBaseContext(planBase == null ? "" : planBase.promptNote())
                .fromSteer(request.isFromSteer())
                .variables(variables)
                .build();
    }
}
