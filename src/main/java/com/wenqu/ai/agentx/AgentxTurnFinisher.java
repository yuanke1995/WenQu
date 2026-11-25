package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.RelatedPrompt;
import com.wenqu.ai.agent.chat.SteerReceipts;
import com.wenqu.ai.agent.chat.TurnCloseout;
import com.wenqu.ai.agentx.turn.TurnDonePayload;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnPersister;
import com.wenqu.ai.agent.chat.TurnRecorder;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agentx.turn.TurnTranscriptRecorder;
import com.wenqu.ai.agent.context.CitationIntegrityService;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.ProjectTurnChangesService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.webhook.AgentWebhookService;
import com.wenqu.ai.util.TurnBlocks;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一轮的收尾（从 AgentscopeChatEngine 抽出）：落库、下发 done 帧、进问答台账、目标轮终拍、webhook。
 *
 * <p>一段线性流水线，按阶段拆成小方法，用一个可变 {@link Draft} 承载阶段间共享的状态：
 * 定稿正文/尾标/是否落库 → 引用自检 → 用量与耗时 → 落库 → 下发 done。
 * 全程只读定稿快照一次，落库行与 done 帧逐字段同一份，不存在二次读池的窗口。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnFinisher {

    private final ProjectTurnChangesService turnChangeService;
    private final CitationIntegrityService citations;
    private final AgentxTurnUsage turnUsage;
    private final GoalService goalService;
    private final AgentxAgentBinding agentBinding;
    private final FrameworkSessions sessions;
    private final AgentxTurnPersister turnPersister;

    private static final int RELATED_PROSE_MIN_CHARS = 80;
    private static final int RELATED_CHIPS_TOLERANCE = 2;



    public void finish(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                       ModelRegistryService.ModelRoute route, String modelRef, int contextWindow, String answer,
                       AgentxTurnRecording recording, String userMessageId,
                       boolean stopped, boolean timedOut, boolean budget, boolean stalled, boolean awaiting,
                       long startedAtMs, long humanWaitMs, long prepMs,
                       AgentSession session, int historyBefore, String turnId,
                       Map<String, Object> planRecord, String recoveredNote) {
        AgentxTurnDraft c = new AgentxTurnDraft(cmd, channel, pool, route, modelRef, contextWindow, answer, recording, userMessageId,
                stopped, timedOut, budget, stalled, awaiting, startedAtMs, humanWaitMs, prepMs, session,
                historyBefore, turnId, planRecord, recoveredNote);
        settleBody(c);
        runCitations(c);
        computeTokens(c);
        turnPersister.persistAndEmit(c);
    }

    /** 阶段一：定稿正文（相关推荐并回、工具当正文剥离、半程轮落库判据、重启接续空转交代）。 */
    private void settleBody(AgentxTurnDraft c) {
        // 每轮文件改动：轮末对比（轮前快照在这里等齐）。非本机工作区/失败=不出卡。
        c.turnChanges = c.cmd.isApiKey() ? null : turnChangeService.settle(c.cmd.getSessionId(), c.turnId);
        int relatedMaxChips = RelatedPrompt.COUNT + RELATED_CHIPS_TOLERANCE;
        List<String> relatedProse = c.recording.related.prose(RELATED_PROSE_MIN_CHARS, relatedMaxChips);
        if (!relatedProse.isEmpty()) {
            String merged = String.join("\n\n", relatedProse);
            c.body = c.body.isBlank() ? merged : c.body.stripTrailing() + "\n\n" + merged;
            c.pool.registrar().degrade("relatedHoldsAnswer",
                    "模型把正文写进了推荐块（" + relatedProse.size() + " 段），已并回回答");
            log.warn("[FAIL-LOUD] related 内含正文 {} 段（{} 字），已并回回答: session={}",
                    relatedProse.size(), merged.length(), c.cmd.getSessionId());
        }
        c.relatedChips = c.recording.related.chips(RELATED_PROSE_MIN_CHARS, relatedMaxChips);
        String leakedCall = c.recording.related.dsml();
        if (!leakedCall.isEmpty()) {
            c.pool.registrar().degrade("toolCallAsText",
                    "模型把一步操作写成了文本，系统没能执行它，这一步的内容可能缺失");
            log.warn("[FAIL-LOUD] 模型把工具调用写成正文 {} 字（已剥离、未执行）: session={}",
                    leakedCall.length(), c.cmd.getSessionId());
        }
        boolean realAnswer = !c.body.isBlank();
        boolean hasProcess = !c.recording.toolCalls.isEmpty() || c.recording.thinking.length() > 0;
        boolean supersededRound = c.planRecord != null && "superseded".equals(c.planRecord.get("status"));
        TurnCloseout.Body closeout = TurnCloseout.settleBody(c.body, hasProcess, supersededRound,
                c.stopped, c.timedOut, c.budget, c.stalled, c.awaiting);
        c.body = closeout.text();
        c.noticeText = closeout.noticeText();
        c.persist = closeout.persist();
        c.realAnswer = realAnswer;
        if (c.recoveredNote != null && !c.persist) {
            c.body = c.recoveredNote;
            c.noticeText = c.recoveredNote;
            c.persist = true;
        }
        c.finalImages = c.pool.rawImages();
    }

    /** 阶段二：引用完整性自检（悬空角标剔除、未标注 fail-loud、语义自检）。半程/停止轮跳过。 */
    private void runCitations(AgentxTurnDraft c) {
        if (!TurnCloseout.citationsApplicable(c.stopped, c.timedOut, c.budget, c.stalled, c.realAnswer)) return;
        var outcome = citations.check(c.body, new CitationIntegrityService.Ctx(
                c.cmd.getSessionId(), c.cmd.getUserId(), c.modelRef,
                c.pool.sources(), c.finalImages,
                c.pool.degradations(), c.pool.degradedCodes(), new HashMap<>(), c.startedAtMs));
        c.body = outcome.text();
        c.pool.applyCitationOutcome(outcome);
    }

    /** 阶段三：用量与耗时（终值一次算齐、暗截断对账、usage 帧）。 */
    private void computeTokens(AgentxTurnDraft c) {
        c.wallMs = System.currentTimeMillis() - c.startedAtMs;
        c.elapsedMs = Math.max(0L, c.wallMs - c.humanWaitMs);
        TurnHandle handle = TurnRegistry.handle(c.cmd.getSessionId());
        c.framedTurnId = c.turnId != null && c.turnId.startsWith("goal:")
                ? c.turnId : (handle == null ? c.turnId : handle.turnId());
        Map<String, Object> tokens = turnUsage.usageOf(c.session, c.historyBefore, c.pool, c.contextWindow);
        AgentxEmitUtil.closeThinkingFrame(c.channel, c.recording);
        tokens.put("thinkingChars", c.recording.thinking.length());
        tokens.put("turnElapsedMs", c.elapsedMs);
        String profile = sessions.turnSlot(c.cmd).profile();
        if (profile != null) tokens.put("profile", profile);
        if (c.humanWaitMs > 0L) tokens.put("humanWaitMs", c.humanWaitMs);
        tokens.put("turnWallMs", c.wallMs + c.prepMs);
        tokens.put("turnPrepMs", c.prepMs);
        applyOutputLostCheck(c, tokens);
        c.tokens = tokens;
        if (!tokens.isEmpty()) c.channel.emit(RunEventType.USAGE, JSON.toJSONString(tokens));
    }

    /** 暗截断对账：生成量与正文字数不符就补一句「内容疑似丢失」。 */
    private void applyOutputLostCheck(AgentxTurnDraft c, Map<String, Object> tokens) {
        long finalOutput = tokens.get("finalOutput") instanceof Number n ? n.longValue() : 0;
        int toolArgChars = (int) Math.min(Integer.MAX_VALUE, c.recording.toolArgRawChars());
        int accounted = (int) Math.min(Integer.MAX_VALUE,
                (long) c.body.length() + c.recording.thinking.length() + toolArgChars);
        if (!c.stopped && !c.timedOut && !c.budget && !c.stalled && !tokens.isEmpty()
                && TurnRecorder.looksLikeOutputLost(finalOutput, accounted, true)) {
            c.pool.registrar().degrade("outputLost", "模型本轮生成 " + finalOutput
                    + " tokens，仅 " + accounted + " 字进入回答，内容疑似丢失");
            c.body = c.body + TurnRecorder.OUTPUT_LOST_SUFFIX;
            log.warn("[FAIL-LOUD] 生成量与正文字数不符（框架引擎）: session={}, 最终轮输出={} tokens, 已交代={} 字"
                            + "（思考 {} 字/工具入参 {} 字）", c.cmd.getSessionId(), finalOutput, accounted,
                    c.recording.thinking.length(), toolArgChars);
        }
    }

    /** 阶段四：处理压缩标记/状态行/产物/显示块，再落库，返回助手消息 id。 */

    /** 落库前的准备：压缩摘要、检索状态行、产物结算、终态原因入块、显示投影、替换分支手术。 */

    /** 定稿快照：落库/done 读同一份，此后不再触池。 */

    /** 真正落库：消息行与问答台账收成一次 TurnPersister 调用。 */

    /** 阶段五：下发 done 帧、收据、webhook、收台。 */

    /** done 载荷：全部读定稿快照，与刚落库的消息行逐字段同一份。 */

    /** 成员轮直播收台：轮结束的必经之路，不收直播台会永远 done=false。 */

    /** 本轮压过上下文 → 摘要落库（分隔线「查看摘要」按会话读）。 */
}
