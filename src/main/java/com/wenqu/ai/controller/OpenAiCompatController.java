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
import java.util.List;
import java.util.Map;
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
    /** 单条 message 内容上限（与 chat.maxQuestionChars 的量级口径一致，防止单请求吃爆内存） */
    private static final int MESSAGE_MAX_CHARS = 32_000;
    private static final int MESSAGES_MAX_COUNT = 64;

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
                if (stream) {
                    // ResponseEntity<SseEmitter> 由 Spring 的 ResponseBodyEmitterReturnValueHandler 原生支持
                    return org.springframework.http.ResponseEntity.ok()
                            .header("X-Accel-Buffering", "no")
                            .body(streamResponse(completionId, agentId, content, usage));
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
            // 临时会话跑完即删（评测线同一先例）：软删留审计痕迹，历史接口不可见
            try {
                sessionService.deleteSession(uid, sessionId);
            } catch (Exception ignored) {
            }
            log.info("[OPENAI-COMPAT] 完成: agent={} uid={} key={} stream={} 耗时={}ms",
                    agentId, uid, apiKeyId == null ? "-" : apiKeyId, stream, System.currentTimeMillis() - start);
        }
    }

    /** OpenAI chunk 流：先 role 帧、正文增量、再 usage 尾帧，最后 [DONE]（SseEmitter 直接写） */
    private SseEmitter streamResponse(String completionId, String agentId, String content,
                                      Map<String, Object> usage) {
        SseEmitter emitter = new SseEmitter(TURN_TIMEOUT_MS);
        try {
            chunk(emitter, completionId, agentId, delta("role", "assistant"));
            if (content != null && !content.isEmpty()) {
                // 单块下发：问渠的回答在此前已整段生成（收集型通道），没有逐 token 增量可借——
                // 真增量流式需要把引擎 token 通道接进来，属后续增量（v1 语义等价、一次性到达）
                chunk(emitter, completionId, agentId, delta("content", content));
            }
            chunk(emitter, completionId, agentId, Map.of());
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
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
