package com.wenqu.ai.controller;

import com.wenqu.ai.config.SecurityConfig;
import com.wenqu.ai.openapi.OpenApi;
import com.wenqu.ai.openapi.OpenApiScopes;
import com.wenqu.ai.openapi.RequestIdFilter;
import com.wenqu.ai.service.ApiKeyService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.openrun.OpenRunService;
import com.wenqu.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * A 期 run 原语（第一批）：建轮 / 状态 / 事件流。
 *
 * <p>路径必须落在 {@code /api/ai/v1/} 才会被 OpenApiRegistry 登记、走 Key 通道与 OpenAI 形状错误体。
 * 响应直接回对象，不走 {@code ResultJson} 平台包络——与其余 v1 端点一致。</p>
 */
@RestController
@RequiredArgsConstructor
public class OpenRunController {

    private final OpenRunService openRunService;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;

    @OpenApi(scope = OpenApiScopes.RUNS_WRITE, summary = "创建一次智能体运行（run）")
    @PostMapping("/api/ai/v1/agents/{agentId}/runs")
    public Map<String, Object> create(@PathVariable("agentId") String agentId,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        String uid = RequestUser.uid();
        String keyId = (String) request.getAttribute(SecurityConfig.ATTR_API_KEY_ID);
        apiKeyService.requireScope(keyId, OpenApiScopes.RUNS_WRITE);
        rateLimitService.checkRateLimit("chat", uid);
        Map<String, Object> b = body == null ? Map.of() : body;
        String question = b.get("question") == null ? null : String.valueOf(b.get("question"));
        String sessionId = b.get("sessionId") == null ? null : String.valueOf(b.get("sessionId"));
        String requestId = RequestIdFilter.current();
        return openRunService.create(uid, keyId, agentId, question, sessionId,
                request.getHeader("Idempotency-Key"), requestId);
    }

    @OpenApi(scope = OpenApiScopes.RUNS_READ, summary = "查询运行状态与输出（与 run.done 同口径）")
    @GetMapping("/api/ai/v1/runs/{id}")
    public Map<String, Object> detail(@PathVariable("id") String id, HttpServletRequest request) {
        String keyId = (String) request.getAttribute(SecurityConfig.ATTR_API_KEY_ID);
        apiKeyService.requireScope(keyId, OpenApiScopes.RUNS_READ);
        return openRunService.detail(openRunService.mustOwn(RequestUser.uid(), id), RequestIdFilter.current());
    }

    @OpenApi(scope = OpenApiScopes.RUNS_READ, summary = "SSE 事件流（Last-Event-ID 续传，seq 连续）")
    @GetMapping(value = "/api/ai/v1/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request) {
        String uid = RequestUser.uid();
        String keyId = (String) request.getAttribute(SecurityConfig.ATTR_API_KEY_ID);
        apiKeyService.requireScope(keyId, OpenApiScopes.RUNS_READ);
        openRunService.mustOwn(uid, id);
        long cursor = parseSeq(lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        openRunService.attach(id, cursor, emitter);
        return emitter;
    }

    private static long parseSeq(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            long v = Long.parseLong(raw.trim());
            return Math.max(0L, v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
