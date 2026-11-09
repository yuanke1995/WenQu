package com.wenqu.ai.agent.chatflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.context.HistoryCompactionService;
import com.wenqu.ai.agentx.turn.TurnDonePayload;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.WorkflowRun;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能体绑定工作流后的问答路径（chatflow 语义）。
 *
 * <p><b>为什么整条走工作流、不与常规链路混合</b>：绑定了工作流的智能体，行为由工作流定义——
 * 智能体自带的人设 / 知识库范围 / 工具开关都不参与（那些是"预设型智能体"的维度）。两套逻辑同时生效
 * 会让回答不可预测。
 *
 * <p>与 {@code TurnState} 无关：这一条路根本没有生成流，所以它只吃通道（{@link ChatRunRuntime}）
 * 与会话落库，本轮状态袋一眼都不看。长期记忆提取用的模型由调用方传入（本轮的个人默认模型已在
 * 问答侧取过——同一个用户的同一行不查第二次）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ChatflowBridge {

    private final ChatRunRuntime runtime;
    private final SessionService sessionService;
    private final ObjectProvider<WorkflowService> workflowServiceProvider;
    private final ImageUrlSigner imageUrlSigner;
    private final QaLogService qaLogService;
    private final HistoryCompactionService historyCompaction;

    public ChatflowBridge(ChatRunRuntime runtime,
                          SessionService sessionService,
                          ObjectProvider<WorkflowService> workflowServiceProvider,
                          ImageUrlSigner imageUrlSigner,
                          QaLogService qaLogService,
                          HistoryCompactionService historyCompaction) {
        this.runtime = runtime;
        this.sessionService = sessionService;
        this.workflowServiceProvider = workflowServiceProvider;
        this.imageUrlSigner = imageUrlSigner;
        this.qaLogService = qaLogService;
        this.historyCompaction = historyCompaction;
    }

    // ==================== 智能体绑定工作流（chatflow 语义） ====================

    /**
     * 智能体绑定工作流后的问答路径：会话每轮把用户问题送进图，end 节点的出参即回答。
     * <p>
     * 与常规链路的分工：绑定了工作流的智能体，<b>行为由工作流定义</b>——智能体自带的人设 /
     * 知识库范围 / 工具开关都不参与（那些是"预设型智能体"的维度）。两套逻辑同时生效会让回答
     * 不可预测，故这里整条走工作流，不做混合。
     * <p>
     * 流式：llm 节点的 token 经 {@code WorkflowRunCtx#tokenSink} 透传到这里，逐块转 SSE token 事件；
     * 最终回答以 end 节点出参为准（done.finalContent 覆盖流式累积——多 llm 节点或模板聚合时
     * 出参可能不等于任一节点的原文，这是设计使然，不是不一致）。
     */
    public void runWorkflowChat(String sessionId, String question, String userId, String memoryModel, SseEmitter emitter,
                                 long startTime, List<Map<String, String>> degradations,
                                 Set<String> degradedCodes, Agent agent, boolean guestMode, boolean branchTurn,
                                 boolean regenerate,
                                 String replaceMessageId, String editVariantGroup, Agent delegatedAgent,
                                 String attachmentsJson, String mentionsJson, int wfDepth) {
        runtime.sendSseEvent(emitter, "plan", JSON.toJSONString(List.of("执行工作流")), sessionId);
        // 起表基准早帧：主链路那一帧在引擎路径上，工作流分支走不到那里。不补的表现为「秒表按本地
        // 按下回车起表、答完换成服务端墙钟时掉一段」（客户端钟还偏着时掉的更多）。只给时长不给绝对时刻。
        long openWallMs = Math.max(0L, System.currentTimeMillis() - startTime);
        runtime.sendSseEvent(emitter, "turn_start",
                JSON.toJSONString(Map.of("wallMs", openWallMs, "prepMs", openWallMs)), sessionId);
        // 本轮身份（把手在 chat() 入口已登记，工作流分支同样走那一条轮）：问答两侧都挂它，
        // 逐气泡「过程」才取得到本轮的账本事件
        final String workflowTurnId = runtime.turnIdOf(sessionId);
        java.util.concurrent.ScheduledFuture<?> heartbeat = runtime.scheduleKeepalive(emitter, "工作流");
        try {
            String workflowUserMsgId = null;
            if (!regenerate) {
                // 附件/@ 引用随用户消息一并落库：工作流路径虽不把它们送进图（已登记 workflowInputIgnored
                // 降级），但用户确实上传/引用了，气泡里凭空消失就是「刷新一次当没发生过」
                workflowUserMsgId = sessionService.appendMessage(MessageWrite.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(question)
                        .attachments(attachmentsJson)
                        .mentions(mentionsJson)
                        .turnId(workflowTurnId)
                        .build());
                // 编辑重发：新用户消息挂上被替换旧分支的版本组键（与主链路同口径）
                if (workflowUserMsgId != null && editVariantGroup != null && !editVariantGroup.isBlank()) {
                    sessionService.setMessageVariant(workflowUserMsgId, editVariantGroup);
                }
            }
            Map<String, Object> inputs = buildWorkflowInputs(sessionId, question, degradations, degradedCodes);
            StringBuilder streamed = new StringBuilder();
            // 图片机制（与主链路同口径）：retrieval 节点完成 [图片N] 编号后回调累计原始 URL 列表——
            // 签名后发 SSE image 事件（时序先于 LLM token，前端按 images[N-1] 渲染回答里的 [图片N]），
            // 原始列表留给落库（历史接口存原始 URL、读时动态签名）。回调抛错不打断工作流（fail-soft 与 tokenSink 同口径）
            List<String> workflowImages = new ArrayList<>();
            WorkflowService.Principal p = WorkflowService.Principal.current();   // 流水线线程已 loadIdentity(userId)
            // 准备段=用户消息落库到图开始跑之间的那一截（与主链路 prepMs 同义）；整轮墙钟在下面收口时取。
            // 工作流也按服务端墙钟计时：done 与落库 tokens 用同一个数，前端不再拿本地钟对账。
            long prepMs = System.currentTimeMillis() - startTime;
            WorkflowRun run = workflowServiceProvider.getObject()
                    // wfDepth+1：本轮已处于 wfDepth 层嵌套（0=顶层），本次工作流运行落在下一层——
                    // 运行入口按防环上限拒绝超深（子智能体节点→绑定工作流的智能体→再调工作流的递归）
                    .runForAgent(agent.getWorkflowId(), inputs, p, token -> {
                        streamed.append(token);
                        runtime.sendSseEvent(emitter, "token", token, sessionId);
                    }, imgs -> {
                        if (imgs == null || imgs.isEmpty()) return;
                        synchronized (workflowImages) {
                            workflowImages.clear();
                            workflowImages.addAll(imgs);
                        }
                        List<String> signed = imgs.stream().map(imageUrlSigner::signUrl).toList();
                        runtime.sendSseEvent(emitter, "image", JSON.toJSONString(signed), sessionId);
                    }, wfDepth + 1);
            if (!"success".equals(run.getStatus())) {
                String reason = run.getError() == null || run.getError().isBlank()
                        ? "工作流运行未成功（状态 " + run.getStatus() + "）" : run.getError();
                log.warn("[WORKFLOW-CHAT] 工作流运行失败: session={} run={} : {}", sessionId, run.getId(), reason);
                runtime.sendSseEvent(emitter, "error", "工作流执行失败：" + reason, sessionId);
                runtime.completeEmitter(emitter);
                return;
            }
            String answer = workflowAnswerOf(run);
            Map<String, Object> tokens = workflowTokensOf(run);
            long turnWallMs = System.currentTimeMillis() - startTime;
            // 与主链路同键：历史行读 tokens.turnWallMs/turnPrepMs，现场 done 读同名的载荷键，
            // 两处取的是同一对变量——答完看到的数字与刷新后看到的必须一模一样。
            tokens.put("turnWallMs", turnWallMs);
            tokens.put("turnPrepMs", prepMs);
            // 重新生成：先软删被替换的旧回答并登记分支版本组（与主链路口径一致，多版本可切换）
            String replaceGroup = null;
            if (replaceMessageId != null && !replaceMessageId.isBlank()) {
                replaceGroup = sessionService.beginAnswerReplace(replaceMessageId);
            }
            // 工作流轮只有最终回答、没有推理过程：单 answer 块与主链路同形，前端不必为它单开一套渲染
            String blocksJson = answerBlocksJson(workflowTurnId, answer);
            String messageId = sessionService.appendMessage(MessageWrite.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(answer)
                    .images(workflowImages.isEmpty() ? null : List.copyOf(workflowImages))
                    .tokens(JSON.toJSONString(tokens))
                    .agentId(agent.getId())
                    .agentName(agent.getName())
                    .turnId(workflowTurnId)
                    .blocks(blocksJson)
                    .build());
            if (replaceGroup != null && messageId != null) {
                sessionService.setMessageVariant(messageId, replaceGroup);
            }
            sessionService.persistDegradations(messageId, degradations);   // 工作流轮同样能刷新后看到降级条
            qaLogService.logAsync(sessionId, question, answer, List.of(), false, false,
                    System.currentTimeMillis() - startTime, false,
                    messageId, agent.getId());
            // 工作流运行信息：前端可展示"本次回答由工作流 v{n} 产出，耗时 xx"
            Map<String, Object> wfInfo = new LinkedHashMap<>();
            wfInfo.put("workflowId", run.getWorkflowId());
            wfInfo.put("version", run.getVersion());
            wfInfo.put("runId", run.getId());
            wfInfo.put("status", run.getStatus());
            wfInfo.put("durationMs", run.getDurationMs());
            // 分支独有键走 extras；核心键（含计时与 blocks）由 TurnDonePayload 一处构造，
            // 与主链路同形——工作流没有推理过程，thinking/toolCalls/related 如实给空。
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("workflowRun", wfInfo);
            // §4 轮级委派归属（与主链路同口径）：工作流智能体被 @ 委派时气泡归属按委派值展示
            if (delegatedAgent != null) {
                extras.put("delegatedAgentId", delegatedAgent.getId());
                extras.put("delegatedAgentName", delegatedAgent.getName() == null ? "" : delegatedAgent.getName());
            }
            String donePayload = TurnDonePayload.toJson(TurnDonePayload.Input.builder()
                    .finalContent(answer)
                    .messageId(messageId)
                    .userMessageId(workflowUserMsgId)
                    .turnId(workflowTurnId)
                    // 本轮作答方归属：不再有会话锁定，工作流轮同样按实际作答的智能体下发
                    .agentId(agent.getId())
                    .agentName(agent.getName() == null ? "" : agent.getName())
                    // 工作流节点各有各的模型，本轮没有一个「整轮模型」可报；前端对空 model 有兜底
                    .model(null)
                    .sources(List.of())
                    // 与主链路一致地动态签名（前端 onDone 用 finalImages 覆盖流式期间已签名的 images；
                    // 落库存的是原始 URL，此处仅对下发副本签名）
                    .finalImages(workflowImages.stream().map(imageUrlSigner::signUrl).toList())
                    .degradations(degradations)
                    .elapsedMs(turnWallMs)
                    .humanWaitMs(0L)
                    .turnWallMs(turnWallMs)
                    .prepMs(prepMs)
                    .tokens(tokens)
                    .toolCalls(List.of())
                    .blocks(blocksJson == null ? null : JSON.parseObject(blocksJson))
                    .artifacts(List.of())
                    .related(List.of())
                    .thinking(null)
                    .extras(extras)
                    .build());
            runtime.sendSseEvent(emitter, "done", donePayload, sessionId);
            runtime.completeEmitter(emitter);
            // 长期记忆由框架提取：MemoryFlushManager 在上下文压缩时按 MemoryConfig
            // 的触发条件自动 flush。工作流这条路径**不提取长期记忆**——它绕过 agent 运行时，
            // 框架的 flush 钩子挂不上。
            log.info("[WORKFLOW-CHAT] 工作流回答完成: session={} run={} v={} 耗时 {}ms",
                    sessionId, run.getId(), run.getVersion(), run.getDurationMs());
        } catch (com.wenqu.ai.common.BizException e) {
            log.warn("[WORKFLOW-CHAT] 工作流执行被拒绝: session={} : {}", sessionId, e.getMessage());
            runtime.sendSseEvent(emitter, "error", e.getMessage(), sessionId);
            runtime.completeEmitter(emitter);
        } catch (Exception e) {
            log.error("[WORKFLOW-CHAT] 工作流执行异常: session={} : {}", sessionId, e.toString());
            runtime.sendSseEvent(emitter, "error", "工作流执行异常：" + e.getMessage(), sessionId);
            runtime.completeEmitter(emitter);
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * 工作流开始节点的入参：固定提供 {@code question}（本轮问题）与 {@code history}（近期多轮文本）。
     * 只有 start 节点<b>声明过</b>的键才会被真正引用（引擎只校验声明项，多给的键不影响执行），
     * 因此多轮能力是"工作流声明了 history 才生效"，不必为它单独开关。
     */
    private Map<String, Object> buildWorkflowInputs(String sessionId, String question,
                                                    List<Map<String, String>> degradations,
                                                    Set<String> degradedCodes) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("question", question == null ? "" : question);
        // 工作流 inputs 的短历史用固定 5 轮（代码常量，非用户设置）：工作流节点自带上下文语义，
        // 不做预算驱动全量带入，避免把主链路的压缩策略耦合进工作流
        List<Map<String, Object>> recent = sessionService.getRecentHistory(sessionId, 5);
        if (recent == null) {
            runtime.addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
            recent = List.of();
        }
        inputs.put("history", historyCompaction.buildHistoryText(recent));
        return inputs;
    }

    /**
     * 结束节点出参 → 回答文本：优先取 {@code answer} 键；没有该键但只有唯一出参时取它；
     * 多出参且无 answer → fail-loud（不明确指定就把某个出参当回答，是猜）。
     */
    private String workflowAnswerOf(WorkflowRun run) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        try {
            outputs = JSON.parseObject(run.getOutputs() == null ? "{}" : run.getOutputs());
        } catch (Exception ignored) {
        }
        if (outputs == null || outputs.isEmpty()) {
            throw new com.wenqu.ai.common.BizException("工作流的结束节点没有产出任何出参，无法作为回答");
        }
        Object answer = outputs.get("answer");
        if (answer == null && outputs.size() == 1) answer = outputs.values().iterator().next();
        if (answer == null) {
            throw new com.wenqu.ai.common.BizException("工作流的结束节点未声明 answer 出参（当前出参："
                    + String.join("、", outputs.keySet()) + "）——请在结束节点里把回答映射到 answer");
        }
        String text = String.valueOf(answer);
        return text.isBlank() ? "（工作流未产出回答内容）" : text;
    }

    /** 本次运行的 token 汇总（各节点 trace 相加；拿不到算 0，不臆造数字） */
    private Map<String, Object> workflowTokensOf(WorkflowRun run) {
        int prompt = 0, completion = 0;
        try {
            JSONArray arr = JSON.parseArray(run.getNodeTraces() == null ? "[]" : run.getNodeTraces());
            if (arr != null) {
                for (Object o : arr) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object p = m.get("promptTokens");
                    Object c = m.get("completionTokens");
                    if (p instanceof Number n) prompt += n.intValue();
                    if (c instanceof Number n) completion += n.intValue();
                }
            }
        } catch (Exception ignored) {
        }
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("output", completion);
        tokens.put("total", prompt + completion);
        return tokens;
    }

    /** 工作流轮只有最终回答，没有推理过程可交错：单 answer 块即可与主链路同形。 */
    private static String answerBlocksJson(String turnId, String answer) {
        if (answer == null || answer.isBlank()) return null;
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", "b1");
        block.put("kind", "text");
        block.put("channel", "answer");
        block.put("text", answer);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        if (turnId != null && !turnId.isBlank()) root.put("turnId", turnId);
        root.put("blocks", List.of(block));
        return JSON.toJSONString(root);
    }

}
