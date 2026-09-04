package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.FormAgentService;
import com.wisesoft.ai.service.RateLimitService;
import com.wisesoft.ai.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 智能表单 Agent 控制器：对话式辅助动态表单的设计/填报/审核（SSE 流式）。
 *
 * <p>事件格式与 /api/ai/chat 对齐：token（文本增量）、done（结束）、error（错误）。
 * P0-b 无状态单轮，不入会话库。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "智能表单", description = "表单 Agent SSE 流式对话")
public class FormAgentController {

    private final FormAgentService formAgentService;
    private final RateLimitService rateLimitService;

    @Operation(summary = "智能表单助手 SSE 流式对话",
            description = "发送表单设计/填报/审核相关问题，通过 SSE 流式返回。Agent 可自动调用工具查看表单清单、表单结构并校验填报内容。事件类型：token（文本增量）、done（结束）、error（错误）")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @PostMapping("/form-agent/chat")
    public SseEmitter chat(@RequestBody @Valid FormAgentChatRequest request, HttpServletRequest httpRequest) {
        String userId = UserContext.resolve(httpRequest);
        rateLimitService.checkRateLimit("formAgent", UserContext.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);

        long timeout = 180_000L; // 表单操作含多次工具调用，放宽到 3 分钟
        SseEmitter emitter = new SseEmitter(timeout);
        formAgentService.chat(request.getQuestion(), emitter);
        return emitter;
    }

    @Operation(summary = "智能表单助手可用状态", description = "返回表单 Agent 是否启用/已配置（前端用于展示入口）")
    @PostMapping("/form-agent/status")
    public ResultJson<?> status(HttpServletRequest httpRequest) {
        return ResultJson.ok(java.util.Map.of(
                "enabled", formAgentService.isEnabled()));
    }

    /** 客户端真实 IP（与 ChatController 逻辑一致） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @Data
    public static class FormAgentChatRequest {
        @NotBlank(message = "请输入问题")
        @Size(max = 8000, message = "问题过长（最多 8000 字）")
        private String question;
    }
}
