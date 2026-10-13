package com.wenqu.ai.agent.chatflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.context.HistoryCompactionService;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.WorkflowRun;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.UserMemoryService;
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
 * 智能体绑定工作流后的问答路径（M4，chatflow 语义；P6 刀 4 自 {@code RagService} 抽出）。
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
    private final UserMemoryService userMemoryService;
    private final HistoryCompactionService historyCompaction;

    public ChatflowBridge(ChatRunRuntime runtime,
                          SessionService sessionService,
                          ObjectProvider<WorkflowService> workflowServiceProvider,
                          ImageUrlSigner imageUrlSigner,
                          QaLogService qaLogService,
                          UserMemoryService userMemoryService,
                          HistoryCompactionService historyCompaction) {
        this.runtime = runtime;
        this.sessionService = sessionService;
        this.workflowServiceProvider = workflowServiceProvider;
        this.imageUrlSigner = imageUrlSigner;
        this.qaLogService = qaLogService;
        this.userMemoryService = userMemoryService;
        this.historyCompaction = historyCompaction;
    }

    // ==================== M4：智能体绑定工作流（chatflow 语义） ====================

    /**
     * 智能体绑定工作流后的问答路径（M4）：会话每轮把用户问题送进图，end 节点的出参即回答。
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
                                 String replaceMessageId, String editVariantGroup, Agent delegatedAgent) {
        runtime.sendSseEvent(emitter, "plan", JSON.toJSONString(List.of("执行工作流")), sessionId);
        runtime.sendSseEvent(emitter, "stage", "正在执行工作流…", sessionId);
        // 本轮身份（把手在 chat() 入口已登记，工作流分支同样走那一条轮）：问答两侧都挂它，
        // 逐气泡「过程」才取得到本轮的账本事件
        final String workflowTurnId = runtime.turnIdOf(sessionId);
        java.util.concurrent.ScheduledFuture<?> heartbeat = runtime.scheduleKeepalive(emitter, "工作流");
        try {
            String workflowUserMsgId = null;
            if (!regenerate) {
                workflowUserMsgId = sessionService.appendMessageWithTurn(sessionId, "user", question,
                        null, workflowTurnId);
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
            WorkflowRun run = workflowServiceProvider.getObject()
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
                    });
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
            // 重新生成：先软删被替换的旧回答并登记分支版本组（与主链路口径一致，多版本可切换）
            String replaceGroup = null;
            if (replaceMessageId != null && !replaceMessageId.isBlank()) {
                replaceGroup = sessionService.beginAnswerReplace(replaceMessageId);
            }
            String messageId = sessionService.appendMessage(sessionId, "assistant", answer,
                    workflowImages.isEmpty() ? null : List.copyOf(workflowImages), null, null, null, null, null,
                    null, JSON.toJSONString(tokens), null, null,
                    agent.getId(), agent.getName(),
                    // model / related / mentions / historyRefs / plan：工作流分支不带这些维度（此前也无落库点）
                    null, null, null, null, null, workflowTurnId);
            if (replaceGroup != null && messageId != null) {
                sessionService.setMessageVariant(messageId, replaceGroup);
            }
            sessionService.persistDegradations(messageId, degradations);   // 工作流轮同样能刷新后看到降级条
            qaLogService.logAsync(sessionId, question, answer, List.of(), false,
                    System.currentTimeMillis() - startTime, question, null, false,
                    messageId, agent.getId());
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", List.of());
            donePayload.put("related", List.of());
            donePayload.put("messageId", messageId);
            donePayload.put("turnId", workflowTurnId);   // 本轮身份：与消息行同源，当轮气泡即可按轮回看过程
            // 本轮用户消息 ID（编辑重发后前端给新用户消息挂分支切换器；regenerate 时为 null 不下发）
            if (workflowUserMsgId != null && !workflowUserMsgId.isBlank()) {
                donePayload.put("userMessageId", workflowUserMsgId);
            }
            donePayload.put("finalContent", answer);
            // 与主链路一致地动态签名（前端 onDone 用 finalImages 覆盖流式期间已签名的 images；
            // 落库存的是原始 URL，此处仅对下发副本签名）
            donePayload.put("finalImages", workflowImages.stream().map(imageUrlSigner::signUrl).toList());
            donePayload.put("degradations", degradations);
            donePayload.put("artifacts", List.of());
            donePayload.put("toolCalls", List.of());
            donePayload.put("tokens", tokens);
            // 工作流运行信息：前端可展示"本次回答由工作流 v{n} 产出，耗时 xx"
            Map<String, Object> wfInfo = new LinkedHashMap<>();
            wfInfo.put("workflowId", run.getWorkflowId());
            wfInfo.put("version", run.getVersion());
            wfInfo.put("runId", run.getId());
            wfInfo.put("status", run.getStatus());
            wfInfo.put("durationMs", run.getDurationMs());
            donePayload.put("workflowRun", wfInfo);
            // 本轮作答方归属（P5）：不再有会话锁定，工作流轮同样按实际作答的智能体下发
            if (agent != null) {
                donePayload.put("agentId", agent.getId());
                donePayload.put("agentName", agent.getName() == null ? "" : agent.getName());
            }
            // §4 轮级委派归属（与主链路同口径）：工作流智能体被 @ 委派时气泡归属按委派值展示
            if (delegatedAgent != null) {
                donePayload.put("delegatedAgentId", delegatedAgent.getId());
                donePayload.put("delegatedAgentName", delegatedAgent.getName() == null ? "" : delegatedAgent.getName());
            }
            // 没赶上的插话同样交回前端（本分支没有流状态，把手还在就直接取）
            runtime.attachLeftoverSteers(donePayload, null, sessionId);
            runtime.sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), sessionId);
            runtime.completeEmitter(emitter);
            // 记忆提取：工作流节点用的是自己的模型，这里按「用户个人默认聊天模型」提取长期记忆
            // （没有配置默认模型就不提取——记忆提取必须有模型，不传空引用去让下游猜/报内部错）
            if (memoryModel != null && !memoryModel.isBlank()) {
                userMemoryService.maybeExtract(userId, sessionId, question, answer, guestMode, memoryModel);
            } else {
                log.debug("[WORKFLOW-CHAT] 未配置个人默认模型，跳过长期记忆提取: uid={}", userId);
            }
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

}
