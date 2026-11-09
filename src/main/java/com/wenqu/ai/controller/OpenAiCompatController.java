package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.service.ChatService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.CollectingSseEmitter;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容端点（对外能力第二批·管道层）：{@code POST /api/ai/v1/chat/completions}。
 * <p>
 * 外部项目用任意 OpenAI SDK 把 base_url 指到问渠、填上 API Key，就能以
 * {@code client.chat.completions.create(model="智能体id", messages=[...])} 的语义调用问渠智能体
 * ——这是「智能体作为能力出口嵌其他项目」的正式门面（与 /v1/workflows 同一 v1 命名空间、
 * 同一 API Key 通道）。
 * <p>
 * 语义映射：
 * <ul>
 *   <li>{@code model} = 智能体 id（必填；按该调用身份的智能体可见性判定，不可见一律 404 不泄露存在性）；</li>
 *   <li>{@code messages}：历史 user/assistant 逐条落临时会话后跑本轮（与网页问答同一管线，
 *       检索/工具/产物全部生效）；{@code system} 消息按「调用方补充指令」拼进本轮题面
 *       （UntrustedContent 消毒——它是外部输入，不是系统指令）；</li>
 *   <li>{@code stream}：true = OpenAI chunk 增量 + usage 尾帧 + [DONE]；false = 一次性 JSON
 *       （usage 映射 done 载荷 tokens）；</li>
 *   <li>无状态：临时会话跑完即删（评测线同一先例），多轮靠客户端重发完整 messages——OpenAI 原生用法。</li>
 * </ul>
 * 计量归属：请求经 API Key 身份装载（UserContextInterceptor/SecurityConfig 同一链），
 * 台账按 {@code api_key_id} 落 c_ai_usage_log，ApiKey 管理页按 Key 汇总近 30 天用量。
 * 鉴权：JWT 同样可调（便于调试），Key 通道走 SecurityConfig 第 1 层白名单放行。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "OpenAI 兼容端点", description = "外部项目以 OpenAI SDK 语义调用问渠智能体")
public class OpenAiCompatController {

    private final ChatService chatService;
    private final SessionService sessionService;
    private final AgentService agentService;
    private final RateLimitService rateLimitService;

    /** 临时会话的等待上限（整轮；含工具循环，对齐评测单题口径 300s） */
    private static final long TURN_TIMEOUT_MS = 300_000L;
    /** 单条 message 内容上限（与平台单条提问 20 万字上限同一量级，防止单请求吃爆内存） */
    private static final int MESSAGE_MAX_CHARS = 200_000;
    private static final int MESSAGES_MAX_COUNT = 64;
    /** Function calling：单请求 tools 上限与 schema 总字节上限 */
    private static final int TOOLS_MAX_COUNT = 32;
    private static final int TOOLS_MAX_SCHEMA_BYTES = 64 * 1024;

    /** 智能体 id 形态：平台生成的标识串（UUID/雪花），防把任意字符串当 id 打库 */
    private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Operation(summary = "Chat Completions（OpenAI 兼容）")
    @PostMapping("/api/ai/v1/chat/completions")
    public org.springframework.http.ResponseEntity<?> chatCompletions(@RequestBody JSONObject body) {
        long start = System.currentTimeMillis();
        String agentId = body.getString("model");
        if (agentId == null || agentId.isBlank() || !AGENT_ID.matcher(agentId).matches()) {
            return bad("invalid_request_error", "model 必须是智能体 id（在问渠智能体页查看）");
        }
        JSONArray messages = body.getJSONArray("messages");
        if (messages == null || messages.isEmpty()) {
            return bad("invalid_request_error", "messages 不能为空");
        }
        if (messages.size() > MESSAGES_MAX_COUNT) {
            return bad("invalid_request_error", "messages 过多（上限 " + MESSAGES_MAX_COUNT + " 条）");
        }
        // Function calling：只接受只读工具，副作用工具 fail-closed 不暴露；
        // 未知工具静默忽略（不在声明集合里就不可能在响应里出现 tool_calls）
        Set<String> declaredTools = new LinkedHashSet<>();
        JSONArray tools = body.getJSONArray("tools");
        if (tools != null && !tools.isEmpty()) {
            if (tools.size() > TOOLS_MAX_COUNT) {
                return bad("invalid_request_error", "tools 过多（上限 " + TOOLS_MAX_COUNT + " 个）");
            }
            int schemaBytes = 0;
            for (int i = 0; i < tools.size(); i++) {
                JSONObject t = tools.getJSONObject(i);
                JSONObject fn = t == null ? null : t.getJSONObject("function");
                String name = fn == null ? null : fn.getString("name");
                if (name == null || name.isBlank() || name.length() > 64) continue;
                schemaBytes += JSON.toJSONString(t).length();
                if (sideEffectTool(name)) continue;   // 有副作用/需审批：HTTP 无交互通道，不暴露
                declaredTools.add(name);
            }
            if (schemaBytes > TOOLS_MAX_SCHEMA_BYTES) {
                return bad("invalid_request_error", "tools 的 schema 总量超过 64KB");
            }
        }
        boolean stream = Boolean.TRUE.equals(body.getBoolean("stream"));

        // 调用方身份（JWT 或 X-Api-Key 已由拦截器/安全链装载）；按 Key 限频独立分桶
        String uid = RequestUser.uid();
        String apiKeyId = apiKeyIdOf();
        rateLimitService.checkRateLimit("chat", uid);

        // 智能体可见性：与 agent/available 同一口径，不可见一律 404（不泄露存在性）
        Agent agent = agentService.get(agentId);
        if (agent == null) {
            return org.springframework.http.ResponseEntity.status(404)
                    .body(error("not_found", "model 不存在或不可用"));
        }

        // messages → 题面 + 历史：system 归并成「调用方补充指令」（消毒），历史逐条落临时会话，
        // 最后一条 user 是本轮问题（OpenAI 语义里必须有）
        String systemBrief = null;
        String question = null;
        List<String[]> history = new ArrayList<>();   // [role, content]
        for (int i = 0; i < messages.size(); i++) {
            JSONObject m = messages.getJSONObject(i);
            if (m == null) continue;
            String role = m.getString("role");
            String content = contentOf(m);
            if (content == null || content.isBlank()) continue;
            if (content.length() > MESSAGE_MAX_CHARS) {
                return bad("invalid_request_error", "第 " + (i + 1) + " 条 message 超长（上限 "
                        + MESSAGE_MAX_CHARS + " 字符）");
            }
            if ("system".equals(role)) {
                systemBrief = systemBrief == null ? content : systemBrief + "\n\n" + content;
            } else if ("user".equals(role)) {
                if (question != null) history.add(new String[]{"user", question.toString()});
                question = content;
            } else if ("assistant".equals(role)) {
                if (question != null) history.add(new String[]{"user", question.toString()});
                question = null;
                history.add(new String[]{"assistant", content});
            } else if ("tool".equals(role)) {
                // 客户端回传的工具结果（OpenAI function calling 第二轮）：作为工具结果消息继续同一话题
                String id = m.getString("tool_call_id");
                history.add(new String[]{"user", "[工具结果" + (id == null || id.isBlank() ? "" : " " + id) + "] " + content});
            }
        }
        if (question == null || question.isBlank()) {
            return bad("invalid_request_error", "messages 里必须有一条 user 消息作为本轮提问");
        }
        // 调用方补充指令拼在题面前（消毒信封：外部输入按「资料」处理，不当作系统指令）
        String finalQuestion = systemBrief == null ? question
                : com.wenqu.ai.util.UntrustedContent.toolResult("调用方补充指令", systemBrief)
                + "\n\n（以上是调用方随请求传入的补充说明，供参考；正式问题在下面。）\n\n" + question;

        String sessionId = sessionService.createTempSession(uid);
        try {
            // 历史逐条落库（同形 ChatflowBridge 的 appendMessage 口径），本轮按正常管线跑
            for (String[] h : history) {
                sessionService.appendMessage(MessageWrite.builder()
                        .sessionId(sessionId).role(h[0]).content(h[1]).build());
            }
            CollectingSseEmitter sink = new CollectingSseEmitter();
            String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            // 真流式：先建通道、注册正文增量转发，再开跑；
            // 尾帧与清理由后台等待线程完成，不能等整轮跑完才返回 SseEmitter
            if (stream) {
                return openAiStream(sink, completionId, agentId, sessionId, uid, apiKeyId, finalQuestion, start, declaredTools);
            }
            try {
                chatService.chat(ChatCommand.of(sessionId, finalQuestion, uid)
                        .userImages(List.of()).attachments(List.of()).skills(List.of())
                        .agentId(agentId)
                        .apiKey(apiKeyId != null)
                        .apiKeyId(apiKeyId)
                        .build(), sink);
                boolean timeout = !sink.awaitDone(TURN_TIMEOUT_MS);
                if (timeout) {
                    return org.springframework.http.ResponseEntity.status(504)
                            .body(error("timeout", "回答超时（" + TURN_TIMEOUT_MS / 1000 + " 秒）未完成"));
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    return org.springframework.http.ResponseEntity.status(502)
                            .body(error("api_error", "执行失败：" + sink.lastError()));
                }
                JSONObject done = parseDone(sink.donePayload());
                String content = done == null || done.getString("finalContent") == null
                        ? sink.answer() : done.getString("finalContent");
                Map<String, Object> usage = usageOf(done);
                // Function calling：模型本轮调用过客户端声明的只读工具 → 按 OpenAI 协议回 tool_calls，
                // 由调用方执行后带 role=tool 再发下一轮（工具结果在下一轮 history 里恢复）
                List<Map<String, Object>> declaredCalls = declaredToolCalls(done, declaredTools);
                if (!declaredCalls.isEmpty()) {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("id", completionId);
                    resp.put("object", "chat.completion");
                    resp.put("created", System.currentTimeMillis() / 1000);
                    resp.put("model", agentId);
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("role", "assistant");
                    message.put("content", null);
                    message.put("tool_calls", declaredCalls);
                    Map<String, Object> choice = new LinkedHashMap<>();
                    choice.put("message", message);
                    choice.put("finish_reason", "tool_calls");
                    choice.put("index", 0);
                    resp.put("choices", List.of(choice));
                    resp.put("usage", usage);
                    return org.springframework.http.ResponseEntity.ok(resp);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", completionId);
                out.put("object", "chat.completion");
                // 非流式在 usage 里带上请求级信息由 SDK 自取（流式的 usage 在尾块，见 streamResponse）
                out.put("created", System.currentTimeMillis() / 1000);
                out.put("model", agentId);
                List<Map<String, Object>> choices = new ArrayList<>();
                Map<String, Object> choice = new LinkedHashMap<>();
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", content == null ? "" : content);
                choice.put("message", message);
                choice.put("finish_reason", "stop");
                choice.put("index", 0);
                choices.add(choice);
                out.put("choices", choices);
                out.put("usage", usage);
                return org.springframework.http.ResponseEntity.ok(out);
            } finally {
                // 收集型通道不触发 onCompletion，必须主动清登记（评测线同一坑账）
                ChatRunRuntime.forgetSseChannel(sink);
            }
        } catch (BizException e) {
            return bad("invalid_request_error", e.getMessage());
        } catch (Exception e) {
            log.error("[OPENAI-COMPAT] 调用失败: agent={} uid={} : {}", agentId, uid, e.getMessage(), e);
            return org.springframework.http.ResponseEntity.status(500)
                    .body(error("api_error", "调用失败：" + e.getMessage()));
        } finally {
            // 临时会话跑完即删（评测线同一先例）：软删留审计痕迹，历史接口不可见。
            // 流式分支的会话由后台收尾线程删除（此处不能提前删，否则整轮落库时会话已不在）
            if (!stream) {
                try {
                    sessionService.deleteSession(uid, sessionId);
                } catch (Exception ignored) {
                }
            }
            log.info("[OPENAI-COMPAT] 完成: agent={} uid={} key={} stream={} 耗时={}ms",
                    agentId, uid, apiKeyId == null ? "-" : apiKeyId, stream, System.currentTimeMillis() - start);
        }
    }

    /**
     * 真增量流式：先返回 SseEmitter，正文 token 到达即转发，
     * 整轮结束后补 usage 尾帧与 [DONE]。临时会话在后台收尾时删除，外层 finally 不再重复删。
     */
    private org.springframework.http.ResponseEntity<?> openAiStream(CollectingSseEmitter sink, String completionId,
                                                                    String agentId, String sessionId, String uid,
                                                                    String apiKeyId, String finalQuestion, long start,
                                                                    Set<String> declaredTools) {
        SseEmitter out = new SseEmitter(TURN_TIMEOUT_MS);
        try {
            chunk(out, completionId, agentId, delta("role", "assistant"));
        } catch (Exception e) {
            out.completeWithError(e);
            return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
        }
        sink.forwardTokens(text -> {
            if (text == null || text.isEmpty()) return;
            try {
                chunk(out, completionId, agentId, delta("content", text));
            } catch (Exception ignored) {
                // 客户端断开：本轮继续跑完并落审计（收集型通道不参与断线中止语义）
            }
        });
        try {
            chatService.chat(ChatCommand.of(sessionId, finalQuestion, uid)
                    .userImages(List.of()).attachments(List.of()).skills(List.of())
                    .agentId(agentId)
                    .apiKey(apiKeyId != null)
                    .apiKeyId(apiKeyId)
                    .build(), sink);
        } catch (BizException e) {
            sendStreamError(out, completionId, agentId, "invalid_request_error", e.getMessage());
            finishStream(sink, uid, sessionId, agentId, apiKeyId, start, null);
            return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
        } catch (Exception e) {
            sendStreamError(out, completionId, agentId, "api_error", "调用失败：" + e.getMessage());
            finishStream(sink, uid, sessionId, agentId, apiKeyId, start, null);
            return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                boolean timeout = !sink.awaitDone(TURN_TIMEOUT_MS);
                if (timeout) {
                    sendStreamError(out, completionId, agentId, "timeout",
                            "回答超时（" + TURN_TIMEOUT_MS / 1000 + " 秒）未完成");
                } else if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    sendStreamError(out, completionId, agentId, "api_error", "执行失败：" + sink.lastError());
                } else {
                    JSONObject done = parseDone(sink.donePayload());
                    List<Map<String, Object>> calls = declaredToolCalls(done, declaredTools);
                    if (!calls.isEmpty()) {
                        sendToolCallsChunk(out, completionId, agentId, calls);
                    } else {
                        chunk(out, completionId, agentId, Map.of());
                    }
                    sendUsageChunk(out, completionId, agentId, usageOf(done));
                    out.send(SseEmitter.event().data("[DONE]"));
                    out.complete();
                }
            } catch (Exception e) {
                try {
                    out.completeWithError(e);
                } catch (Exception ignored) {
                }
            } finally {
                finishStream(sink, uid, sessionId, agentId, apiKeyId, start, null);
            }
        });
        return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
    }

    /** 流式收尾：注销通道登记 + 删临时会话 + 留一条完成日志（与同步路径同一口径） */
    private void finishStream(CollectingSseEmitter sink, String uid, String sessionId, String agentId,
                              String apiKeyId, long start, String ignored) {
        ChatRunRuntime.forgetSseChannel(sink);
        try {
            sessionService.deleteSession(uid, sessionId);
        } catch (Exception ignoredEx) {
        }
        log.info("[OPENAI-COMPAT] 流式完成: agent={} uid={} key={} 耗时={}ms",
                agentId, uid, apiKeyId == null ? "-" : apiKeyId, System.currentTimeMillis() - start);
    }

    /** 流式错误帧：OpenAI 客户端按 error 字段分流，不再补 [DONE] */
    private void sendStreamError(SseEmitter out, String id, String model, String type, String message) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            chunk.put("choices", List.of());
            chunk.put("error", error(type, message).get("error"));
            out.send(SseEmitter.event().data(JSON.toJSONString(chunk)));
            out.complete();
        } catch (Exception e) {
            try {
                out.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    /** usage 尾帧（OpenAI 流式约定：choices 为空、usage 单独一帧） */
    private void sendUsageChunk(SseEmitter out, String id, String model, Map<String, Object> usage) throws Exception {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);
        chunk.put("choices", List.of());
        chunk.put("usage", usage);
        out.send(SseEmitter.event().data(JSON.toJSONString(chunk)));
    }

    /** 流式 tool_calls 帧：delta 带 tool_calls，finish_reason=tool_calls */
    private void sendToolCallsChunk(SseEmitter out, String id, String model,
                                    List<Map<String, Object>> calls) throws Exception {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);
        Map<String, Object> delta = new LinkedHashMap<>();
        List<Map<String, Object>> indexed = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Map<String, Object> one = new LinkedHashMap<>(calls.get(i));
            one.put("index", i);
            indexed.add(one);
        }
        delta.put("tool_calls", indexed);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("delta", delta);
        choice.put("finish_reason", "tool_calls");
        choice.put("index", 0);
        chunk.put("choices", List.of(choice));
        out.send(SseEmitter.event().data(JSON.toJSONString(chunk)));
    }

    /**
     * 本轮工具调用 → OpenAI tool_calls：只回客户端声明过的只读工具。
     * <p>框架已按智能体配置执行过这些工具（只读，重复执行无害）；副作用工具在入参解析时就被剔除，
     * 所以这里不会出现「客户端再执行一次写操作」的双重副作用。
     */
    private List<Map<String, Object>> declaredToolCalls(JSONObject done, Set<String> declared) {
        if (done == null || declared == null || declared.isEmpty()) return List.of();
        JSONArray calls = done.getJSONArray("toolCalls");
        if (calls == null || calls.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            JSONObject c = calls.getJSONObject(i);
            String name = c == null ? null : c.getString("name");
            if (name == null || !declared.contains(name)) continue;
            String args = c.getString("args");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", name);
            fn.put("arguments", args == null || args.isBlank() ? "{}" : args);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            call.put("type", "function");
            call.put("function", fn);
            out.add(call);
        }
        return out;
    }

    /** 有副作用/需审批/会改变平台状态的工具：HTTP 无交互通道，function calling 一律不暴露 */
    private static boolean sideEffectTool(String name) {
        String n = name == null ? "" : name.trim();
        return n.equals("run_code") || n.equals("run_code_script") || n.equals("write_file")
                || n.equals("edit_file") || n.equals("presentArtifact") || n.equals("writeTodo")
                || n.equals("askUser") || n.startsWith("goal_") || n.startsWith("plan_")
                || n.startsWith("agent_") || n.startsWith("task_") || n.startsWith("wen_qu")
                || n.startsWith("wf_") || n.startsWith("localBrowser");
    }

    // ==================== Responses API ====================

    @Operation(summary = "Responses API（OpenAI 兼容）")
    @PostMapping("/api/ai/v1/responses")
    public org.springframework.http.ResponseEntity<?> responses(@RequestBody JSONObject body) {
        long start = System.currentTimeMillis();
        String agentId = body.getString("model");
        if (agentId == null || agentId.isBlank() || !AGENT_ID.matcher(agentId).matches()) {
            return bad("invalid_request_error", "model 必须是智能体 id（在问渠智能体页查看）");
        }
        Object rawInput = body.get("input");
        if (rawInput == null) return bad("invalid_request_error", "input 不能为空");

        String uid = RequestUser.uid();
        String apiKeyId = apiKeyIdOf();
        rateLimitService.checkRateLimit("chat", uid);
        Agent agent = agentService.get(agentId);
        if (agent == null) {
            return org.springframework.http.ResponseEntity.status(404)
                    .body(error("not_found", "model 不存在或不可用"));
        }

        String instructions = body.getString("instructions");
        String question = null;
        List<String[]> history = new ArrayList<>();
        if (rawInput instanceof String s) {
            question = s;
        } else if (rawInput instanceof JSONArray arr) {
            if (arr.size() > MESSAGES_MAX_COUNT) {
                return bad("invalid_request_error", "input 过多（上限 " + MESSAGES_MAX_COUNT + " 条）");
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject m = arr.getJSONObject(i);
                if (m == null) continue;
                String role = m.getString("role");
                String content = contentOf(m);
                if (content == null || content.isBlank()) continue;
                if ("system".equals(role) || "developer".equals(role)) {
                    instructions = instructions == null ? content : instructions + "\n\n" + content;
                } else if ("user".equals(role)) {
                    if (question != null) history.add(new String[]{"user", question});
                    question = content;
                } else if ("assistant".equals(role)) {
                    if (question != null) history.add(new String[]{"user", question});
                    question = null;
                    history.add(new String[]{"assistant", content});
                }
            }
        }
        if (question == null || question.isBlank()) {
            return bad("invalid_request_error", "input 里必须有一条 user 消息作为本轮提问");
        }
        if (question.length() > MESSAGE_MAX_CHARS) {
            return bad("invalid_request_error", "单条输入超长（上限 " + MESSAGE_MAX_CHARS + " 字符）");
        }
        String finalQuestion = instructions == null ? question
                : com.wenqu.ai.util.UntrustedContent.toolResult("调用方补充指令", instructions)
                + "\n\n（以上是调用方随请求传入的补充说明，供参考；正式问题在下面。）\n\n" + question;
        boolean stream = Boolean.TRUE.equals(body.getBoolean("stream"));

        String sessionId = sessionService.createTempSession(uid);
        try {
            for (String[] h : history) {
                sessionService.appendMessage(MessageWrite.builder()
                        .sessionId(sessionId).role(h[0]).content(h[1]).build());
            }
            String responseId = "resp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            CollectingSseEmitter sink = new CollectingSseEmitter();
            if (stream) {
                return openResponsesStream(sink, responseId, agentId, sessionId, uid, apiKeyId, finalQuestion, start);
            }
            chatService.chat(ChatCommand.of(sessionId, finalQuestion, uid)
                    .userImages(List.of()).attachments(List.of()).skills(List.of())
                    .agentId(agentId).apiKey(apiKeyId != null).apiKeyId(apiKeyId).build(), sink);
            boolean timeout = !sink.awaitDone(TURN_TIMEOUT_MS);
            if (timeout) {
                return org.springframework.http.ResponseEntity.status(504)
                        .body(error("timeout", "回答超时（" + TURN_TIMEOUT_MS / 1000 + " 秒）未完成"));
            }
            if (sink.lastError() != null && !sink.lastError().isBlank()) {
                return org.springframework.http.ResponseEntity.status(502)
                        .body(error("api_error", "执行失败：" + sink.lastError()));
            }
            JSONObject done = parseDone(sink.donePayload());
            String content = done == null || done.getString("finalContent") == null
                    ? sink.answer() : done.getString("finalContent");
            return org.springframework.http.ResponseEntity.ok()
                    .body(responseObject(responseId, agentId, content, usageOf(done), "completed"));
        } catch (BizException e) {
            return bad("invalid_request_error", e.getMessage());
        } catch (Exception e) {
            log.error("[OPENAI-COMPAT] responses 失败: agent={} uid={} : {}", agentId, uid, e.getMessage(), e);
            return org.springframework.http.ResponseEntity.status(500)
                    .body(error("api_error", "调用失败：" + e.getMessage()));
        } finally {
            if (!stream) {
                try {
                    sessionService.deleteSession(uid, sessionId);
                } catch (Exception ignored) {
                }
            }
            log.info("[OPENAI-COMPAT] responses 完成: agent={} uid={} stream={} 耗时={}ms",
                    agentId, uid, stream, System.currentTimeMillis() - start);
        }
    }

    /** Responses 对象（非流式与流式 completed 事件共用同一形状） */
    private Map<String, Object> responseObject(String id, String model, String text,
                                               Map<String, Object> usage, String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("object", "response");
        out.put("created_at", System.currentTimeMillis() / 1000);
        out.put("model", model);
        out.put("status", status);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "output_text");
        content.put("text", text == null ? "" : text);
        content.put("annotations", List.of());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "message");
        message.put("id", id + "-msg");
        message.put("role", "assistant");
        message.put("status", status);
        message.put("content", List.of(content));
        out.put("output", List.of(message));
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("input_tokens", usage == null ? 0 : usage.getOrDefault("prompt_tokens", 0));
        u.put("output_tokens", usage == null ? 0 : usage.getOrDefault("completion_tokens", 0));
        u.put("total_tokens", usage == null ? 0 : usage.getOrDefault("total_tokens", 0));
        out.put("usage", u);
        return out;
    }

    /** Responses 真增量流：response.created → output_text.delta → response.completed */
    private org.springframework.http.ResponseEntity<?> openResponsesStream(CollectingSseEmitter sink,
                                                                           String responseId, String agentId,
                                                                           String sessionId, String uid,
                                                                           String apiKeyId, String finalQuestion,
                                                                           long start) {
        SseEmitter out = new SseEmitter(TURN_TIMEOUT_MS);
        try {
            sendResponseEvent(out, "response.created", Map.of(
                    "type", "response.created",
                    "response", responseObject(responseId, agentId, "", null, "in_progress")));
        } catch (Exception e) {
            out.completeWithError(e);
            return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
        }
        sink.forwardTokens(text -> {
            if (text == null || text.isEmpty()) return;
            try {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("type", "response.output_text.delta");
                d.put("item_id", responseId + "-msg");
                d.put("output_index", 0);
                d.put("content_index", 0);
                d.put("delta", text);
                sendResponseEvent(out, "response.output_text.delta", d);
            } catch (Exception ignored) {
            }
        });
        try {
            chatService.chat(ChatCommand.of(sessionId, finalQuestion, uid)
                    .userImages(List.of()).attachments(List.of()).skills(List.of())
                    .agentId(agentId).apiKey(apiKeyId != null).apiKeyId(apiKeyId).build(), sink);
        } catch (Exception e) {
            sendResponseEventSilently(out, "response.failed", Map.of(
                    "type", "response.failed",
                    "response", responseObject(responseId, agentId, "", null, "failed")));
            finishStream(sink, uid, sessionId, agentId, apiKeyId, start, null);
            return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                boolean timeout = !sink.awaitDone(TURN_TIMEOUT_MS);
                if (timeout || (sink.lastError() != null && !sink.lastError().isBlank())) {
                    sendResponseEvent(out, "response.failed", Map.of(
                            "type", "response.failed",
                            "response", responseObject(responseId, agentId, "", null,
                                    timeout ? "failed" : "failed")));
                } else {
                    JSONObject done = parseDone(sink.donePayload());
                    String text = done == null || done.getString("finalContent") == null
                            ? sink.answer() : done.getString("finalContent");
                    sendResponseEvent(out, "response.completed", Map.of(
                            "type", "response.completed",
                            "response", responseObject(responseId, agentId, text, usageOf(done), "completed")));
                    out.complete();
                }
            } catch (Exception e) {
                try {
                    out.completeWithError(e);
                } catch (Exception ignored) {
                }
            } finally {
                finishStream(sink, uid, sessionId, agentId, apiKeyId, start, null);
            }
        });
        return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").body(out);
    }

    private void sendResponseEvent(SseEmitter out, String event, Map<String, Object> data) throws Exception {
        out.send(SseEmitter.event().name(event).data(JSON.toJSONString(data)));
    }

    private void sendResponseEventSilently(SseEmitter out, String event, Map<String, Object> data) {
        try {
            sendResponseEvent(out, event, data);
            out.complete();
        } catch (Exception e) {
            try {
                out.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    private Map<String, Object> delta(String key, String value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(key, value);
        return d;
    }

    private void chunk(SseEmitter emitter, String id, String model, Map<String, Object> delta) throws Exception {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);
        List<Map<String, Object>> choices = new ArrayList<>();
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("delta", delta);
        choice.put("index", 0);
        choices.add(choice);
        chunk.put("choices", choices);
        emitter.send(SseEmitter.event().data(JSON.toJSONString(chunk)));
    }

    /** content 支持 OpenAI 的字符串与 parts 两种形态；取 parts 里的 text 拼接 */
    private String contentOf(JSONObject m) {
        Object c = m.get("content");
        if (c instanceof String s) return s;
        if (c instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject part = arr.getJSONObject(i);
                if (part != null && part.getString("text") != null) sb.append(part.getString("text"));
            }
            return sb.toString();
        }
        return null;
    }

    private String apiKeyIdOf() {
        Object v = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null
                ? null : org.springframework.web.context.request.RequestContextHolder
                .currentRequestAttributes().getAttribute(
                        com.wenqu.ai.config.SecurityConfig.ATTR_API_KEY_ID,
                        org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return v == null ? null : String.valueOf(v);
    }

    /** done 载荷解析（坑账：CollectingSseEmitter 的 done content 是「字符串套 JSON」双重序列化） */
    private JSONObject parseDone(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            Object o = JSON.parse(payload);
            if (o instanceof JSONObject j) return j;
            if (o instanceof String s && !s.isBlank()) return JSON.parseObject(s);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** usage 映射（tokens 求和取 total；拿不到如实给 0，不臆造） */
    private Map<String, Object> usageOf(JSONObject done) {
        long prompt = 0, completion = 0, total = 0;
        if (done != null) {
            JSONObject tk = done.getJSONObject("tokens");
            if (tk != null) {
                prompt = tk.getLongValue("prompt");
                completion = tk.getLongValue("output");
                total = tk.getLongValue("total");
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        usage.put("total_tokens", total);
        return usage;
    }

    /** 4xx 形状（非流式路径）：SDK 按状态码走异常分支并读 error 体 */
    private org.springframework.http.ResponseEntity<?> bad(String type, String message) {
        return org.springframework.http.ResponseEntity.status(400).body(error(type, message));
    }

    /** OpenAI 错误形状：{"error":{"message","type"}} */
    private Map<String, Object> error(String type, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", err);
        return out;
    }
}
